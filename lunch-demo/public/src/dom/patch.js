import { createDOM } from './render.js';
import { setDOMEvent } from './events.js';
import { assertSafeAttribute } from '../core/security.js';

/** @typedef {import('../core/contracts.js').NodePath} NodePath */
/** @typedef {import('../core/contracts.js').Patch} Patch */
/** @typedef {import('../core/contracts.js').PropsDelta} PropsDelta */

/**
 * 자식 탐색·삽입이나 속성 변경에 쓸 수 있는 HTML 요소인지 확인한다.
 * @param {Node} node
 * @returns {Element}
 */
function requireElement(node) {
  const element = /** @type {Element} */ (node);
  if (element?.nodeType !== 1 || element.namespaceURI !== 'http://www.w3.org/1999/xhtml'
    || ['template', 'svg', 'math'].includes(element.localName)) {
    throw new TypeError('Expected an HTML element supported by the engine.');
  }
  return element;
}

/** @param {Node} node @returns {Element | Text} */
function requireSupportedNode(node) {
  return node.nodeType === 3 ? /** @type {Text} */ (node) : requireElement(node);
}

/** @param {Element} container @returns {Element | Text | null} */
function getRoot(container) {
  // <div><ul>...</ul></div>에서 div가 컨테이너라면 ul이 루트다.
  // 경로 []는 이 ul을 가리킨다. 컨테이너가 비어 있으면 루트는 null이다.
  if (container.childNodes.length > 1) {
    throw new RangeError('Container must own at most one root node.');
  }
  return container.firstChild ? requireSupportedNode(container.firstChild) : null;
}

/**
 * 경로의 숫자 하나를 읽을 때마다 현재 노드에서 한 단계 아래로 내려간다.
 * ul에서 [0, 0]을 찾으면 첫 번째 li에 들어가 그 첫 번째 Text 노드를 반환한다.
 * @param {Element | Text | null} root
 * @param {NodePath} path
 */
function resolveNode(root, path) {
  if (root === null) throw new RangeError('Patch path requires an existing root.');

  let node = root;
  for (const index of path) {
    // childNodes는 공백 Text도 한 자리로 센다. Diff의 VNode 순서와 같은 기준이다.
    const child = requireElement(node).childNodes[index];
    if (!child) throw new RangeError('Patch path is outside the tree.');
    node = requireSupportedNode(child);
  }
  return node;
}

/** @param {unknown} value */
function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/**
 * 같은 요소를 유지하면서 changes에 적힌 속성과 이벤트만 바꾼다.
 * setAttrs의 title은 값을 설정하고, removeAttrs의 title은 속성을 지운다.
 * 이벤트도 같은 방식으로 설정하거나 해제한다.
 * @param {Element} element
 * @param {PropsDelta} changes
 */
function applyProps(element, changes) {
  if (!changes || !isRecord(changes.setAttrs) || !isRecord(changes.setEvents)
    || !Array.isArray(changes.removeAttrs) || !Array.isArray(changes.removeEvents)) {
    throw new TypeError('PROPS requires attribute and event changes.');
  }

  const { setAttrs, removeAttrs, setEvents, removeEvents } = changes;
  const attributes = Object.entries(setAttrs);
  const events = Object.entries(setEvents);
  // 설정과 삭제가 충돌하거나 값의 형태가 잘못된 경우 DOM을 변경하기 전에 거절한다.
  for (const [name, value] of attributes) {
    if (!name || typeof value !== 'string') {
      throw new TypeError('Attributes must be strings. Use events for listeners.');
    }
    assertSafeAttribute(name, value);
  }
  for (const name of removeAttrs) {
    if (typeof name !== 'string' || !name || Object.hasOwn(setAttrs, name)) {
      throw new TypeError('Invalid attribute removal.');
    }
  }
  for (const [name, listener] of events) {
    if (!name || name !== name.toLowerCase() || typeof listener !== 'function') {
      throw new TypeError('Events must map lowercase names to listener functions.');
    }
  }
  for (const name of removeEvents) {
    if (typeof name !== 'string' || !name || Object.hasOwn(setEvents, name)) {
      throw new TypeError('Invalid event removal.');
    }
  }

  // HTML에서 CLASS와 class는 같은 속성이다. 삭제를 먼저 해 새 값을 남긴다.
  for (const name of removeAttrs) element.removeAttribute(name);
  for (const [name, value] of attributes) {
    // 빈 문자열도 속성 값이다. disabled 같은 속성은 존재 여부로 판별한다.
    try {
      element.setAttribute(name, value);
    } catch (error) {
      if (error && typeof error === 'object' && 'name' in error && error.name === 'InvalidCharacterError') {
        throw new TypeError(`Invalid attribute name: ${name}`, { cause: error });
      }
      throw error;
    }
  }
  for (const name of removeEvents) setDOMEvent(element, name, null);
  for (const [name, listener] of events) setDOMEvent(element, name, listener);
}

/**
 * 목록에서 받은 명령 하나를 실행한다. 경로는 대상을, type은 작업을 정한다.
 * @param {Element} container
 * @param {Element | Text | null} root
 * @param {Patch} patch
 */
function applyPatch(container, root, patch) {
  if (!patch || !Array.isArray(patch.path)) throw new TypeError('Patch requires a path array.');
  const { path } = patch;
  for (const index of path) {
    if (!Number.isInteger(index) || index < 0) {
      throw new RangeError('Patch indices must be nonnegative integers.');
    }
  }

  if (patch.type === 'INSERT') {
    const index = path.at(-1);
    if (index === undefined) {
      // 경로 []에 새 루트를 넣으려면 바깥 컨테이너가 비어 있어야 한다.
      if (root !== null) throw new RangeError('Root insertion requires an empty container.');
      container.appendChild(createDOM(patch.node, container.ownerDocument));
    } else {
      // INSERT [1, 0]이라면 [1]에서 부모를 찾고 그 부모의 0번 자식 앞에 넣는다.
      // 인덱스가 자식 수와 같으면 기준 자식(anchor)이 null이므로 맨 뒤에 붙는다.
      const parent = requireElement(resolveNode(root, path.slice(0, -1)));
      if (index > parent.childNodes.length) throw new RangeError('Invalid insertion index.');
      const anchor = parent.childNodes[index] ?? null;
      if (anchor) requireSupportedNode(anchor);
      parent.insertBefore(createDOM(patch.node, parent.ownerDocument), anchor);
    }
    return;
  }

  if (patch.type === 'MOVE') {
    // 지우고 새로 만들지 않고 이미 있는 노드를 옮긴다. insertBefore는 원래 자리에서 빼서 끼운다.
    const parent = requireElement(resolveNode(root, path));
    const { from, to } = patch;
    const length = parent.childNodes.length;
    if (!Number.isInteger(from) || !Number.isInteger(to)) throw new TypeError('MOVE requires integer indices.');
    if (from < 0 || from >= length || to < 0 || to > length) throw new RangeError('Invalid move index.');
    const node = requireSupportedNode(/** @type {Node} */ (parent.childNodes[from]));
    parent.insertBefore(node, parent.childNodes[to] ?? null);
    return;
  }

  // 나머지 명령은 경로 전체가 이미 존재하는 대상 노드를 가리킨다.
  const target = resolveNode(root, path);
  switch (patch.type) {
    case 'REMOVE':
      target.remove();
      break;
    case 'REPLACE':
      // 새 VNode를 실제 DOM으로 만든 뒤 기존 노드가 있던 자리에 넣는다.
      target.replaceWith(createDOM(patch.node, target.ownerDocument));
      break;
    case 'TEXT':
      if (target.nodeType !== 3 || typeof patch.value !== 'string') {
        throw new TypeError('TEXT requires a Text node and a string value.');
      }
      // 예를 들어 "A"를 "A!"로 바꿔도 부모 li와 Text 노드의 참조는 유지된다.
      /** @type {Text} */ (target).data = patch.value;
      break;
    case 'PROPS':
      applyProps(requireElement(target), patch.changes);
      break;
    default:
      throw new TypeError('Unknown patch type.');
  }
}

/**
 * Patch를 순서대로 적용해 현재 루트를 돌려준다.
 * ul의 자식 [A, B, C]를 [A!]로 바꾸는 예를 따라가면 다음과 같다.
 * 1. TEXT [0, 0]으로 A의 글자를 A!로 바꾼다. [A!, B, C]
 * 2. REMOVE [2]로 C를 지운다. [A!, B]
 * 3. REMOVE [1]로 B를 지운다. [A!]
 * 명령마다 현재 childNodes에서 경로를 찾는다. 실패하면 그때까지의 변경은 남는다.
 * @type {import('../core/contracts.js').ApplyPatches}
 */
export function applyPatches(container, patches) {
  requireElement(container);
  if (!Array.isArray(patches)) throw new TypeError('Expected an array of patches.');

  let root = getRoot(container);
  for (const patch of patches) {
    applyPatch(container, root, patch);
    // 루트가 교체되거나 삭제됐을 수 있으므로 다음 명령 전에 다시 읽는다.
    root = getRoot(container);
  }
  return root;
}
