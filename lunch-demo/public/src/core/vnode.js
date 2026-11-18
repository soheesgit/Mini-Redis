import { assertSafeAttribute, assertSafeTag } from './security.js';

/** @typedef {import('./contracts.js').VNode} VNode */

/**
 * 문자열이나 숫자를 텍스트 VNode로 만든다. 실제 DOM에는 접근하지 않는다.
 * @type {import('./contracts.js').CreateTextVNode}
 */
export function createTextVNode(value) {
  if (typeof value !== 'string' && typeof value !== 'number') {
    throw new TypeError('Text must be a string or number.');
  }
  // 숫자도 문자열로 통일해 DOM의 텍스트 값과 같은 형태로 저장한다.
  return { kind: 'text', value: String(value) };
}

/**
 * 태그·속성·자식을 받아 요소 VNode를 만든다.
 * 자식의 표현을 정규화하고 입력 객체와 배열을 직접 수정하지 않는다.
 * @type {import('./contracts.js').H}
 */
export function h(tag, props, children = []) {
  // 현재 계약은 소문자 HTML 태그만 지원한다.
  // SVG·MathML은 별도 네임스페이스, template은 별도 content 처리가 필요하다.
  if (typeof tag !== 'string' || !/^[a-z][a-z0-9._-]*$/.test(tag)
    || ['template', 'svg', 'math'].includes(tag)) {
    throw new TypeError('Only lowercase HTML tags without template are supported.');
  }
  assertSafeTag(tag);

  // props가 없는 경우에도 attrs와 events를 갖도록 구조를 통일한다.
  const source = props === null ? { attrs: {}, events: {} } : props;
  if (!source || typeof source !== 'object'
    || !source.attrs || typeof source.attrs !== 'object' || Array.isArray(source.attrs)
    || !source.events || typeof source.events !== 'object' || Array.isArray(source.events)) {
    throw new TypeError('Props must contain attrs and events records.');
  }
  // 속성은 문자열로 보존한다. onclick 같은 인라인 코드는 events와 구분한다.
  for (const [name, value] of Object.entries(source.attrs)) {
    if (!name || typeof value !== 'string') {
      throw new TypeError('Attributes must be strings; use events for listeners.');
    }
    assertSafeAttribute(name, value);
  }
  // key는 형제 목록 안에서 이 노드의 정체성이다. 속성이 아니므로 DOM에는 쓰지 않는다.
  const { key } = /** @type {{ key?: unknown }} */ (source);
  if (key !== undefined && typeof key !== 'string' && typeof key !== 'number') {
    throw new TypeError('Key must be a string or number.');
  }
  // 이벤트에는 문자열 코드가 아닌 함수 참조를 저장한다. 이름은 click처럼 쓴다.
  for (const [name, listener] of Object.entries(source.events)) {
    if (!name || name !== name.toLowerCase() || typeof listener !== 'function') {
      throw new TypeError('Events must map lowercase names to listener functions.');
    }
  }
  if (!Array.isArray(children)) {
    throw new TypeError('Children must be an array.');
  }

  /** @type {VNode[]} */
  const normalized = [];
  for (const child of children) {
    // 조건부 자식에 사용되는 값은 화면에 표시할 노드를 만들지 않는다.
    if (child === null || child === undefined || typeof child === 'boolean') {
      continue;
    }
    if (typeof child === 'string' || typeof child === 'number') {
      // 0, 빈 문자열, 공백 문자열도 유효한 텍스트이므로 그대로 포함한다.
      normalized.push(createTextVNode(child));
    } else if (typeof child === 'object' && (
      (child.kind === 'text' && typeof child.value === 'string')
      || (child.kind === 'element' && typeof child.tag === 'string'
        && child.props && Array.isArray(child.children))
    )) {
      // 이미 생성된 VNode는 불변 값으로 공유하고, 바깥 배열만 새로 만든다.
      normalized.push(child);
    } else {
      throw new TypeError('Children must be VNodes or supported primitive values.');
    }
  }

  // 속성 객체는 복사해 호출자가 원본 props를 바꿔도 결과에 전파되지 않게 한다.
  // 이벤트 함수와 기존 자식 VNode는 불변 값으로 취급하며 참조를 유지한다.
  /** @type {import('./contracts.js').ElementVNode} */
  const vnode = {
    kind: 'element',
    tag,
    props: { attrs: { ...source.attrs }, events: { ...source.events } },
    children: normalized,
  };
  return key === undefined ? vnode : { ...vnode, key: String(key) };
}
