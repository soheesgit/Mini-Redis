import { diff } from '../core/diff.js';
import { applyPatches } from '../dom/patch.js';
import { replaceDOMTree } from '../dom/render.js';

/** @typedef {import('../core/contracts.js').VNode} VNode */
/** @template T @typedef {(previous: T) => T} StateUpdater */
/** @template T @typedef {(action: T | StateUpdater<T>) => void} StateSetter */
/** @template T @typedef {{ kind: 'state', value: T, setState: StateSetter<T> }} StateSlot */
/** @typedef {() => void} Cleanup */
/** @typedef {() => void | Cleanup} Effect */
/** @typedef {{ kind: 'effect', dependencies: readonly unknown[] | undefined, cleanup: Cleanup | undefined }} EffectSlot */
/** @typedef {{ kind: 'memo', dependencies: readonly unknown[], value: unknown }} MemoSlot */
/** @typedef {{ kind: 'state' } | EffectSlot | MemoSlot} HookSlot */
/** 상태를 바꾸고 되돌리는 함수를 반환한다. 값이 그대로면 null을 반환해 렌더를 건너뛰게 한다. */
/** @typedef {() => ((() => void) | null)} StateChange */
/** @typedef {{ props: object, vnode: VNode }} MemoEntry */
/** @typedef {Map<Function, Map<string, MemoEntry>>} MemoCache */
/**
 * @typedef {object} RenderContext
 * @property {HookSlot[]} hooks
 * @property {number} nextIndex
 * @property {(changeState: StateChange) => void} update
 * @property {(() => void)[]} pendingEffects
 * @property {[number, MemoSlot][]} pendingMemos
 * @property {MemoCache} memoCache 지난 커밋의 memo 기록장 (읽기 전용)
 * @property {MemoCache} nextMemoCache 이번 렌더에서 쓴 memo 기록. 커밋에 성공하면 기록장이 된다
 * @property {Map<Function, number>} memoCalls key 없는 memo 자식이 몇 번째로 불렸는지
 * @property {number} memoHits memo로 건너뛴 자식 수
 */

const MAX_EFFECT_UPDATE_PASSES = 50;

// 루트 함수를 실행하는 동안만 슬롯을 찾을 문맥을 제공한다. 상태값은 각 인스턴스에 있다.
/** @type {RenderContext | null} */
let activeRender = null;

// child()로 자식을 실행하는 깊이. 0보다 크면 지금 실행 중인 함수는 자식이므로 Hook을 막는다.
let childDepth = 0;

/** @param {string} hook */
function requireRender(hook) {
  if (childDepth > 0) {
    throw new Error(`${hook}는 루트 컴포넌트에서만 쓸 수 있어요. 자식에는 상태 대신 props로 값과 핸들러를 넘겨 주세요.`);
  }
  if (activeRender === null) {
    throw new Error(`${hook} requires a rendering root component.`);
  }
  return activeRender;
}

/**
 * 슬롯을 만들 때 setter도 함께 만들어 이후 렌더링에서 같은 함수를 반환한다.
 * @template T
 * @param {T} initialValue
 * @param {RenderContext} context
 * @param {number} index
 * @returns {StateSlot<T>}
 */
function createStateSlot(initialValue, { hooks, update }, index) {
  /** @type {StateSlot<T>} */
  const slot = {
    kind: 'state',
    value: initialValue,
    setState(action) {
      if (activeRender !== null || childDepth > 0) {
        throw new Error('Cannot set state during render.');
      }
      if (hooks[index] !== slot) {
        throw new Error('Cannot set state on a discarded hook.');
      }

      update(() => {
        // 슬롯의 최신 값으로 갱신하고, DOM 반영 실패 시 이전 값을 복원할 함수를 반환한다.
        const previous = slot.value;
        const next = typeof action === 'function'
          ? (/** @type {StateUpdater<T>} */ (action))(previous)
          : action;
        // 값이 그대로면 다시 그릴 이유가 없다. 처리 시점의 최신 값과 비교하므로 batch 안의 순서도 지킨다.
        if (Object.is(previous, next)) return null;
        slot.value = next;
        return () => { slot.value = previous; };
      });
    },
  };
  return slot;
}

/**
 * 호출 순서에 해당하는 슬롯에서 상태값과 setter를 읽는다.
 * @template T
 * @param {T} initialValue
 * @returns {[T, StateSetter<T>]}
 */
export function useState(initialValue) {
  const context = requireRender('useState');
  const index = context.nextIndex++;
  const hook = context.hooks[index];
  if (hook !== undefined && hook.kind !== 'state') {
    throw new Error('Hook order changed between renders.');
  }

  // 렌더마다 같은 순서로 호출하는 규칙에 따라 같은 위치에서 같은 타입의 슬롯을 읽는다.
  const slot = /** @type {StateSlot<T> | undefined} */ (hook) ?? createStateSlot(initialValue, context, index);
  context.hooks[index] = slot;
  return [slot.value, slot.setState];
}

/**
 * 의존성을 생략하면 매번 실행한다. 배열은 길이와 각 값이 모두 같은지 비교한다.
 * @param {readonly unknown[] | undefined} previous
 * @param {readonly unknown[] | undefined} next
 */
function sameDependencies(previous, next) {
  return previous !== undefined && next !== undefined && previous.length === next.length
    && previous.every((value, index) => Object.is(value, next[index]));
}

/**
 * 루트 렌더 중에는 실행할 Effect만 예약한다. 실제 호출은 DOM 반영이 끝난 뒤에 한다.
 * @param {Effect} effect
 * @param {readonly unknown[]} [dependencies]
 */
export function useEffect(effect, dependencies) {
  const context = requireRender('useEffect');
  const index = context.nextIndex++;
  const hook = context.hooks[index];
  if (hook !== undefined && hook.kind !== 'effect') {
    throw new Error('Hook order changed between renders.');
  }

  /** @type {EffectSlot} */
  const slot = hook ?? { kind: 'effect', dependencies: undefined, cleanup: undefined };
  context.hooks[index] = slot;
  if (sameDependencies(slot.dependencies, dependencies)) return;

  const nextDependencies = dependencies === undefined ? undefined : [...dependencies];
  context.pendingEffects.push(() => {
    // cleanup을 먼저 비워 두면 정리 중 오류가 나도 같은 함수를 다시 호출하지 않는다.
    const cleanup = slot.cleanup;
    slot.cleanup = undefined;
    slot.dependencies = undefined;
    cleanup?.();

    const result = effect();
    slot.cleanup = typeof result === 'function' ? result : undefined;
    slot.dependencies = nextDependencies;
  });
}

/**
 * 의존성이 그대로면 이전에 반영한 계산 결과의 참조를 재사용한다.
 * @template T
 * @param {() => T} calculate
 * @param {readonly unknown[]} dependencies
 * @returns {T}
 */
export function useMemo(calculate, dependencies) {
  const context = requireRender('useMemo');
  if (!Array.isArray(dependencies)) {
    throw new TypeError('useMemo dependencies must be an array.');
  }

  const index = context.nextIndex++;
  const hook = context.hooks[index];
  if (hook !== undefined) {
    if (hook.kind !== 'memo') throw new Error('Hook order changed between renders.');
    if (sameDependencies(hook.dependencies, dependencies)) {
      return /** @type {T} */ (hook.value);
    }
  }

  // 렌더 중 계산한 값은 바로 반환하고, 캐시 교체는 DOM 반영 성공까지 미룬다.
  const nextDependencies = [...dependencies];
  const value = calculate();
  context.pendingMemos.push([index, { kind: 'memo', dependencies: nextDependencies, value }]);
  return value;
}

/**
 * 자식 컴포넌트를 실행한다. 자식은 props만 받아 VNode를 돌려주는 순수 함수여야 하므로
 * 실행하는 동안 Hook 호출을 막는다. 상태는 루트에 두고 props로 내려 준다(Lifting State Up).
 * shortcut: TodoForm(props)처럼 직접 호출하면 루트 몸체와 구분할 수 없어 막지 못한다. 자식은 child()로 부른다.
 * @template P
 * @param {(props: P) => VNode} render
 * @param {P} props
 * @returns {VNode}
 */
export function child(render, props) {
  const context = activeRender;
  if (context === null || !MEMOS.has(render)) return runChild(render, props);

  // 1. 누구의 지난번인지 찾기: props.key가 있으면 key로, 없으면 이 렌더에서 몇 번째로 불렸는지로 찾는다.
  const key = /** @type {{ key?: unknown }} */ (props)?.key;
  let id = `key:${String(key)}`;
  if (key === undefined || key === null) {
    // key 없는 호출끼리만 순서를 센다. key 있는 호출이 섞여도 번호가 밀리지 않는다.
    const count = context.memoCalls.get(render) ?? 0;
    context.memoCalls.set(render, count + 1);
    id = `#${count}`;
  }
  const previous = context.memoCache.get(render)?.get(id);

  // 2. 재료(props)가 같으면 함수를 부르지 않고 지난번 VNode 객체를 그대로 쓴다. diff는 첫 줄(같은 객체)에서 끝난다.
  let vnode;
  if (previous !== undefined && shallowEqual(previous.props, /** @type {object} */ (props))) {
    vnode = previous.vnode;
    context.memoHits += 1;
  } else {
    vnode = runChild(render, props);
  }

  // 3. 이번 렌더에서 쓴 것만 새 기록장에 옮긴다. 화면에서 빠진 자식의 기록은 자연히 버려진다.
  // shortcut: memo 자식이 건너뛰어지면 그 안에 있던 memo 손자의 기록은 옮겨지지 않아 다음에 한 번 다시 계산한다. 중첩 memo가 많아지면 손자 기록도 함께 옮긴다.
  let entries = context.nextMemoCache.get(render);
  if (entries === undefined) context.nextMemoCache.set(render, (entries = new Map()));
  entries.set(id, { props: /** @type {object} */ (props), vnode });
  return vnode;
}

/**
 * @template P
 * @param {(props: P) => VNode} render
 * @param {P} props
 */
function runChild(render, props) {
  childDepth += 1;
  try {
    return render(props);
  } finally {
    childDepth -= 1;
  }
}

/** memo로 감싼 자식 함수 표시. 함수에 필드를 붙이지 않아 원래 함수 모양을 바꾸지 않는다. */
const MEMOS = new WeakSet();

/**
 * 자식 컴포넌트를 memo로 감싼다. child()로 부를 때 props가 얕은 비교로 같으면 다시 실행하지 않는다.
 * 핸들러처럼 함수나 객체를 props로 넘기면 useMemo로 같은 참조를 유지해야 효과가 있다.
 * @template P
 * @param {(props: P) => VNode} render
 * @returns {(props: P) => VNode}
 */
export function memo(render) {
  /** @param {P} props */
  const memoized = (props) => {
    // 루트 몸체에서 child() 없이 직접 부르면 기록장도 hook 차단도 거치지 않으므로 막는다.
    if (activeRender !== null && childDepth === 0) {
      throw new Error('memo 자식은 child(Component, props)로 불러 주세요.');
    }
    return render(props);
  };
  MEMOS.add(memoized);
  return memoized;
}

/**
 * props 값을 하나씩 Object.is로 비교한다(얕은 비교). 내용까지 파고들지 않으므로 빠르다.
 * @param {object} previous
 * @param {object} next
 */
function shallowEqual(previous, next) {
  const before = /** @type {Record<string, unknown>} */ (previous);
  const after = /** @type {Record<string, unknown>} */ (next);
  const keys = Object.keys(before);
  return keys.length === Object.keys(after).length
    && keys.every((name) => Object.hasOwn(after, name) && Object.is(before[name], after[name]));
}

/**
 * 한 콜백이 실패해도 같은 커밋이나 해제에 속한 나머지 콜백을 실행한 뒤 오류를 전달한다.
 * @param {readonly (() => void)[]} callbacks
 */
function runEffectCallbacks(callbacks) {
  const errors = [];
  for (const callback of callbacks) {
    try {
      callback();
    } catch (error) {
      errors.push(error);
    }
  }
  if (errors.length === 1) throw errors[0];
  if (errors.length > 1) throw new AggregateError(errors, 'Multiple effect callbacks failed.');
}

/**
 * 한 슬롯을 여러 번 변경해도 역순으로 복원하면 배치 시작 전 값으로 돌아간다.
 * @param {readonly StateChange[]} changes
 */
function applyStateChanges(changes) {
  /** @type {(() => void)[]} */
  const rollbacks = [];
  const rollback = () => {
    for (const restore of rollbacks.reverse()) restore();
  };

  try {
    for (const change of changes) {
      const restore = change();
      if (restore !== null) rollbacks.push(restore);
    }
  } catch (error) {
    rollback();
    throw error;
  }
  // 묶은 요청이 모두 같은 값이었다면 렌더하지 않는다.
  return rollbacks.length > 0 ? rollback : null;
}

/**
 * 루트 렌더 함수를 감싸고 렌더링 사이에 유지할 데이터를 보관한다.
 * @template Props
 */
export class FunctionComponent {
  /**
   * 인스턴스마다 슬롯 배열을 따로 두어 렌더 함수가 다시 실행돼도 Hook 값을 유지할 수 있다.
   * @type {HookSlot[]}
   */
  hooks = [];

  #render;
  #props;

  /** @type {Element | null} */
  #container = null;

  #isCommitting = false;

  /** @type {StateChange[] | null} */
  #queuedEffectUpdates = null;

  /** @type {StateChange[] | null} */
  #batchedUpdates = null;

  /** 마지막 커밋이 DOM에 적용한 Patch 목록. onCommit에 전달한다. */
  /** @type {import('../core/contracts.js').PatchList} */
  #lastPatches = [];

  #renders = 0;

  /** 마지막으로 커밋에 성공한 렌더의 memo 기록장. 렌더나 Patch가 실패하면 그대로 둔다. */
  /** @type {MemoCache} */
  #memoCache = new Map();

  #memoHits = 0;

  /** 마지막 커밋의 diff·patch 소요 시간(ms). onCommit으로 개발 도구 패널에 알린다. */
  #lastTimings = { diff: 0, patch: 0 };

  /**
   * DOM 반영과 그 커밋의 Effect가 끝날 때마다 불린다. Hooks 인스펙터 같은 개발 도구가 쓰며, 여기서 난 오류는 콘솔에만 남긴다.
   * @type {((info: { patches: import('../core/contracts.js').PatchList, hooks: readonly HookSlot[], renders: number, memoHits: number, timings: { diff: number, patch: number } }) => void) | null}
   */
  onCommit = null;

  /**
   * 마지막으로 DOM에 반영한 VNode다. 최초 마운트 전에는 null이며 이후 Diff의 비교 기준이 된다.
   * @type {VNode | null}
   */
  #previousVNode = null;

  /**
   * @param {(props: Props) => VNode} render
   * @param {Props} props
   */
  constructor(render, props) {
    this.#render = render;
    this.#props = props;
  }

  get previousVNode() {
    return this.#previousVNode;
  }

  /**
   * 루트 함수가 props로 만든 트리를 빈 컨테이너에 처음 렌더링한다.
   * @param {Element} container
   */
  mount(container) {
    // 이미 이전 VNode가 저장되어 있다면 처음 렌더링을 수행한 것으로 판단하고 오류를 발생
    if (this.#previousVNode !== null) {
      throw new Error('Component is already mounted.');
    }

    this.#renderAndCommit(container);
  }

  /** 마운트한 컨테이너에 루트 함수의 새 VNode를 반영한다. */
  update() {
    this.#update();
  }

  /**
   * 동기 콜백이 호출한 setter를 모아 하나의 갱신으로 반영한다.
   * @template T
   * @param {() => T} callback
   * @returns {T}
   */
  batch(callback) {
    if (this.#container === null) {
      throw new Error('Component must be mounted before batching.');
    }
    if (this.#isCommitting && this.#queuedEffectUpdates === null) {
      throw new Error('Component is already processing a lifecycle operation.');
    }
    if (this.#batchedUpdates !== null) return callback();

    /** @type {StateChange[]} */
    const changes = [];
    this.#batchedUpdates = changes;
    let result;
    try {
      result = callback();
    } finally {
      // Promise를 반환하더라도 여기서 수집을 끝낸다. 이후 setter는 새 구간에서 실행된다.
      this.#batchedUpdates = null;
    }

    if (changes.length > 0) {
      // Effect 중이라면 합친 갱신 하나를 기존 Effect 큐가 처리한다.
      try {
        this.#update(() => applyStateChanges(changes));
      } catch (error) {
        // 갱신 오류로 반환할 수 없게 된 콜백 Promise의 거부를 처리한다.
        void Promise.resolve(result).catch(() => {});
        throw error;
      }
    }
    return result;
  }

  /** DOM 루트를 제거하고 등록된 cleanup을 호출한다. 이후 새로 마운트할 수 있다. */
  unmount() {
    if (this.#isCommitting || this.#batchedUpdates !== null) {
      throw new Error('Component is already processing a lifecycle operation.');
    }
    const container = this.#container;
    if (container === null) return;

    this.#isCommitting = true;
    try {
      applyPatches(container, diff(this.#previousVNode, null));
      const cleanups = [];
      for (const slot of this.hooks) {
        if (slot.kind === 'effect' && slot.cleanup) cleanups.push(slot.cleanup);
      }

      // 먼저 마운트 정보를 비워 cleanup이 실패해도 해제된 상태와 한 번만 정리하는 규칙을 유지한다.
      this.#container = null;
      this.#previousVNode = null;
      this.hooks.length = 0;
      this.#memoCache = new Map();
      runEffectCallbacks(cleanups);
    } finally {
      this.#isCommitting = false;
    }
  }

  /** @param {StateChange} [changeState] */
  #update(changeState) {
    if (this.#container === null) {
      throw new Error('Component must be mounted before updating.');
    }

    if (this.#batchedUpdates !== null) {
      if (changeState === undefined) throw new Error('Cannot update while collecting a batch.');
      // 슬롯 값은 갱신 시점까지 유지해 updater가 바로 앞 요청의 결과를 읽도록 한다.
      this.#batchedUpdates.push(changeState);
      return;
    }

    if (changeState !== undefined && this.#queuedEffectUpdates !== null) {
      // Effect가 예약한 함수형 updater는 큐에서 꺼낼 때 최신 상태로 계산한다.
      this.#queuedEffectUpdates.push(changeState);
      return;
    }

    this.#renderAndCommit(this.#container, changeState);
  }

  #renderVNode() {
    const previousRender = activeRender;
    const hookCount = this.hooks.length;
    // 인스턴스의 슬롯 배열은 그대로 쓰고, 호출 위치만 매 렌더링마다 0부터 센다.
    /** @type {RenderContext} */
    const context = {
      hooks: this.hooks,
      nextIndex: 0,
      update: (changeState) => this.#update(changeState),
      pendingEffects: [],
      pendingMemos: [],
      memoCache: this.#memoCache,
      nextMemoCache: new Map(),
      memoCalls: new Map(),
      memoHits: 0,
    };
    activeRender = context;
    try {
      // 루트 함수의 this에 인스턴스가 바인딩되지 않도록 꺼내서 호출한다.
      const render = this.#render;
      const vnode = render(this.#props);
      // Diff에서 null은 빈 트리를 뜻한다. 루트 함수는 Element 또는 Text VNode를 반환해야 한다.
      if (vnode === null || vnode === undefined) {
        throw new TypeError('Component must return a VNode.');
      }
      if (this.#previousVNode !== null && context.nextIndex !== hookCount) {
        throw new Error('Hook order changed between renders.');
      }
      return { vnode, pendingEffects: context.pendingEffects, pendingMemos: context.pendingMemos, nextMemoCache: context.nextMemoCache, memoHits: context.memoHits };
    } finally {
      activeRender = previousRender;
    }
  }

  /**
   * @param {Element} container
   * @param {StateChange} [changeState]
   */
  #renderAndCommit(container, changeState) {
    if (this.#isCommitting) {
      throw new Error('Component is already processing a lifecycle operation.');
    }

    /** @type {StateChange[]} */
    const queuedUpdates = [];
    let nextChange = changeState;
    let nextUpdateIndex = 0;
    let schedulingPasses = 0;
    this.#isCommitting = true;
    try {
      do {
        const rollbackState = nextChange?.();
        if (nextChange !== undefined && rollbackState === null) {
          // 바뀐 값이 없으면 렌더·diff·patch를 하지 않고 다음 예약으로 넘어간다.
          nextChange = queuedUpdates[nextUpdateIndex++];
          continue;
        }
        const pendingEffects = this.#commitDOM(container, rollbackState);
        this.#renders += 1;
        const queuedCount = queuedUpdates.length;

        // DOM 반영 뒤에만 예약을 받는다. 모든 cleanup과 의존성 저장이 끝나면 큐를 이어서 처리한다.
        this.#queuedEffectUpdates = queuedUpdates;
        try {
          runEffectCallbacks(pendingEffects);
        } finally {
          this.#queuedEffectUpdates = null;
          // Effect까지 끝난 hooks를 알린다. 개발 도구의 오류가 이미 끝난 커밋을 깨뜨리지 않게 막는다.
          try {
            this.onCommit?.({ patches: this.#lastPatches, hooks: this.hooks, renders: this.#renders, memoHits: this.#memoHits, timings: this.#lastTimings });
          } catch (error) {
            console.error(error);
          }
        }

        // Effect가 계속 갱신을 예약하면 동기 루프를 끊고 마지막으로 반영된 상태를 유지한다.
        if (queuedUpdates.length > queuedCount) {
          schedulingPasses += 1;
          if (schedulingPasses > MAX_EFFECT_UPDATE_PASSES) {
            throw new Error('Too many consecutive effect updates.');
          }
        }
        nextChange = queuedUpdates[nextUpdateIndex++];
      } while (nextChange !== undefined);
    } finally {
      this.#queuedEffectUpdates = null;
      this.#isCommitting = false;
    }
  }

  /**
   * @param {Element} container
   * @param {(() => void) | null} [rollbackState]
   */
  #commitDOM(container, rollbackState) {
    const previous = this.#previousVNode;
    const hookCount = this.hooks.length;
    let applyingPatches = false;
    try {
      const { vnode, pendingEffects, pendingMemos, nextMemoCache, memoHits } = this.#renderVNode();
      // 첫 마운트는 null과 비교해 루트를 삽입하고, 갱신은 마지막으로 적용한 트리와 비교한다.
      const startedAt = performance.now();
      const patches = diff(previous, vnode);
      const diffedAt = performance.now();
      applyingPatches = true;
      applyPatches(container, patches);
      this.#lastTimings = { diff: diffedAt - startedAt, patch: performance.now() - diffedAt };
      this.#lastPatches = patches;
      // 커밋에 성공한 렌더의 기록만 다음 비교 기준으로 삼는다. 실패하면 이전 기록장을 그대로 쓴다.
      this.#memoCache = nextMemoCache;
      this.#memoHits = memoHits;
      for (const [index, slot] of pendingMemos) {
        this.hooks[index] = slot;
      }
      this.#previousVNode = vnode;
      this.#container = container;
      return pendingEffects;
    } catch (error) {
      // 렌더링 중 새로 만든 슬롯과 상태 변경을 되돌린다. 예약된 Effect는 실행되지 않는다.
      this.hooks.length = hookCount;
      rollbackState?.();
      if (applyingPatches && previous !== null) {
        // 앞선 Patch가 이미 DOM을 바꿨을 수 있으므로 마지막 성공 트리로 화면을 복원한다.
        replaceDOMTree(container, previous);
      }
      throw error;
    }
  }
}
