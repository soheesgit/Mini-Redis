/** @typedef {import('./contracts.js').Tree} Tree */
/** @typedef {import('./contracts.js').NodePath} NodePath */
/** @typedef {import('./contracts.js').Patch} Patch */

/**
 * 두 속성/이벤트 객체에서 설정할 항목과 삭제할 이름을 분리한다.
 * 속성 문자열은 값으로, 이벤트 함수는 참조로 비교한다.
 * @template T
 * @param {Readonly<Record<string, T>>} previous
 * @param {Readonly<Record<string, T>>} next
 * @returns {{ set: Record<string, T>, remove: string[] }}
 */
function diffRecords(previous, next) {
  // hasOwn으로 상속된 프로퍼티와 실제 데이터 필드를 구분한다.
  // fromEntries는 __proto__ 같은 이름도 자체 데이터 필드로 만든다.
  const set = Object.fromEntries(Object.entries(next).filter(([name, value]) => (
    !Object.hasOwn(previous, name) || previous[name] !== value
  )));
  const remove = Object.keys(previous).filter((name) => !Object.hasOwn(next, name));
  return { set, remove };
}

/**
 * 이전/다음 VNode를 같은 위치끼리 비교하여 순서가 있는 Patch 목록을 만든다.
 * DOM에 접근하지 않고 입력을 변경하지 않는다. node 값은 불변 VNode를 참조한다.
 *
 * 형제 전부에 서로 다른 key가 있으면 위치 대신 key로 짝짓고, 순서만 바뀐 노드는 MOVE로 옮긴다.
 * 한계: 재귀 호출이므로 매우 깊은 트리는 JavaScript 호출 스택 한도를 넘을 수 있다.
 * @type {import('./contracts.js').Diff}
 */
export function diff(previous, next) {
  /** @type {Patch[]} */
  const patches = [];

  /**
   * @param {Tree} before
   * @param {Tree} after
   * @param {NodePath} path
   * @returns {void}
   */
  function compare(before, after, path) {
    // 불변 입력에서 같은 참조는 같은 트리다. null끼리 비교하는 경우도 포함한다.
    if (before === after) return;

    if (before === null) {
      if (after !== null) patches.push({ type: 'INSERT', path, node: after });
      return;
    }
    if (after === null) {
      patches.push({ type: 'REMOVE', path });
      return;
    }

    // 노드 종류가 바뀌면 해당 서브트리를 통째로 교체하므로 자식은 비교하지 않는다.
    if (before.kind !== after.kind) {
      patches.push({ type: 'REPLACE', path, node: after });
      return;
    }
    if (before.kind === 'text' && after.kind === 'text') {
      if (before.value !== after.value) patches.push({ type: 'TEXT', path, value: after.value });
      return;
    }

    if (before.kind === 'element' && after.kind === 'element') {
      // 같은 Element 종류라도 div → p처럼 태그가 바뀌면 교체한다.
      if (before.tag !== after.tag) {
        patches.push({ type: 'REPLACE', path, node: after });
        return;
      }

      const attrs = diffRecords(before.props.attrs, after.props.attrs);
      const events = diffRecords(before.props.events, after.props.events);
      if (Object.keys(attrs.set).length || attrs.remove.length
        || Object.keys(events.set).length || events.remove.length) {
        patches.push({
          type: 'PROPS',
          path,
          changes: {
            setAttrs: attrs.set,
            removeAttrs: attrs.remove,
            setEvents: events.set,
            removeEvents: events.remove,
          },
        });
      }

      // key 목록이면 위치가 아니라 key로 짝짓는다.
      if (isKeyed(before.children) && isKeyed(after.children)) {
        compareKeyed(before.children, after.children, path);
        return;
      }

      // 공통 구간의 비교는 부모의 자식 수를 바꾸지 않으므로 기존 인덱스를 유지한다.
      const commonLength = Math.min(before.children.length, after.children.length);
      for (let index = 0; index < commonLength; index += 1) {
        // 경로 배열을 새로 만들어 형제·하위 노드의 경로가 서로 섞이지 않게 한다.
        compare(before.children[index] ?? null, after.children[index] ?? null, [...path, index]);
      }

      // 뒤에서 삭제하면 아직 삭제하지 않은 앞쪽 형제들의 인덱스가 바뀌지 않는다.
      for (let index = before.children.length - 1; index >= after.children.length; index -= 1) {
        patches.push({ type: 'REMOVE', path: [...path, index] });
      }

      // 앞에서 추가하면 각 INSERT의 인덱스가 그 시점의 자식 수와 일치한다.
      for (let index = before.children.length; index < after.children.length; index += 1) {
        compare(null, after.children[index] ?? null, [...path, index]);
      }
    }
  }

  /**
   * key 목록 비교. 위치 비교는 맨 앞에 하나만 끼워도 뒤의 모든 노드가 "글자가 바뀜"으로 보이지만,
   * key 비교는 같은 key를 같은 노드로 보고 지우지 않고 옮긴다. 예: [a,b,c] → [x,a,b,c]는 INSERT 1건.
   * 이 층의 구조 변경(REMOVE·MOVE·INSERT)은 자식 내부 비교가 끝난 뒤에 내보내므로,
   * 자식 내부 Patch의 경로는 아직 순서가 바뀌지 않은 이전 위치 그대로 맞는다.
   * @param {ReadonlyArray<import('./contracts.js').VNode>} oldChildren
   * @param {ReadonlyArray<import('./contracts.js').VNode>} newChildren
   * @param {NodePath} path 부모 경로
   */
  function compareKeyed(oldChildren, newChildren, path) {
    const newByKey = new Map(newChildren.map((child) => [keyOf(child), child]));
    const oldKeys = new Set(oldChildren.map(keyOf));

    // 1. 같은 key끼리 내용 비교. 이 층은 아직 그대로라 경로는 이전 위치다.
    oldChildren.forEach((child, index) => {
      const match = newByKey.get(keyOf(child));
      if (match !== undefined) compare(child, match, [...path, index]);
    });

    // 2. 사라진 key는 뒤에서부터 지워 앞쪽 인덱스가 밀리지 않게 한다.
    for (let index = oldChildren.length - 1; index >= 0; index -= 1) {
      if (!newByKey.has(keyOf(/** @type {import('./contracts.js').VNode} */ (oldChildren[index])))) {
        patches.push({ type: 'REMOVE', path: [...path, index] });
      }
    }

    // 3. 남은 노드의 순서 맞추기. 가장 긴 증가 부분수열(LIS)에 든 노드는 서로 순서가 맞으니 두고 나머지만 옮긴다.
    const current = oldChildren.map(keyOf).filter((key) => newByKey.has(key));
    const target = newChildren.map(keyOf).filter((key) => oldKeys.has(key));
    const currentIndex = new Map(current.map((key, index) => [key, index]));
    const stay = longestIncreasing(target.map((key) => /** @type {number} */ (currentIndex.get(key))));
    // 뒤에서부터 정하면 target[i + 1]은 이미 제자리이므로 그 앞에 끼우면 된다.
    for (let index = target.length - 1; index >= 0; index -= 1) {
      if (stay.has(index)) continue;
      const key = /** @type {string} */ (target[index]);
      const anchor = target[index + 1];
      const from = current.indexOf(key);
      const to = anchor === undefined ? current.length : current.indexOf(anchor);
      patches.push({ type: 'MOVE', path, from, to });
      // Patch가 DOM에 하는 일을 current에도 똑같이 해서 다음 MOVE의 인덱스를 맞춘다.
      current.splice(from, 1);
      current.splice(anchor === undefined ? current.length : current.indexOf(anchor), 0, key);
    }

    // 4. 새 key는 새 인덱스 오름차순으로 넣는다. 앞쪽은 이미 최종 순서다.
    newChildren.forEach((child, index) => {
      if (!oldKeys.has(keyOf(child))) patches.push({ type: 'INSERT', path: [...path, index], node: child });
    });
  }

  compare(previous, next, []);
  return patches;
}

/** @param {import('./contracts.js').VNode} node */
function keyOf(node) {
  return node.kind === 'element' ? node.key : undefined;
}

/**
 * 형제 전부에 서로 다른 key가 있을 때만 key로 짝짓는다. 하나라도 빠지거나 겹치면 위치 비교로 돌아간다.
 * @param {ReadonlyArray<import('./contracts.js').VNode>} children
 */
function isKeyed(children) {
  const keys = children.map(keyOf);
  return keys.length > 0 && keys.every((key) => key !== undefined) && new Set(keys).size === keys.length;
}

/**
 * 가장 긴 증가 부분수열의 "인덱스" 집합. O(n log n). Vue 3의 key 재조정과 같은 방법이다.
 * 예: [a,b,c,d] → [b,c,d,a]이면 seq = [1,2,3,0] → {0,1,2}. b·c·d는 두고 a 하나만 맨 끝으로 옮긴다.
 * @param {readonly number[]} seq
 * @returns {Set<number>}
 */
export function longestIncreasing(seq) {
  /** @type {number[]} tails[len] = 길이 len+1인 증가 수열 중 끝값이 가장 작은 것의 끝 인덱스 */
  const tails = [];
  /** @type {number[]} prev[i] = 수열에서 i 바로 앞 원소의 인덱스 (거꾸로 따라가 복원) */
  const prev = [];
  seq.forEach((value, index) => {
    let low = 0;
    let high = tails.length;
    while (low < high) {
      const mid = (low + high) >> 1;
      if (/** @type {number} */ (seq[/** @type {number} */ (tails[mid])]) < value) low = mid + 1;
      else high = mid;
    }
    prev[index] = low > 0 ? /** @type {number} */ (tails[low - 1]) : -1;
    tails[low] = index;
  });
  const result = new Set();
  for (let index = tails.at(-1) ?? -1; index >= 0; index = /** @type {number} */ (prev[index])) result.add(index);
  return result;
}
