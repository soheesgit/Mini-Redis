import { h } from '../core/vnode.js';
import { setDOMEvent } from './events.js';

/**
 * VNode를 읽어 새 DOM 트리를 만든다. 반환한 루트는 화면에 자동 삽입하지 않는다.
 * @type {import('../core/contracts.js').CreateDOM}
 */
export function createDOM(vnode, ownerDocument) {
  // 전역 document 대신 전달받은 HTML Document에서 모든 노드를 생성한다.
  if (!ownerDocument || ownerDocument.nodeType !== 9 || ownerDocument.contentType !== 'text/html') {
    throw new TypeError('Rendering requires an HTML Document.');
  }
  // 텍스트는 HTML로 해석하지 않는다. 공백·빈 문자열도 Text 노드로 만든다.
  if (vnode?.kind === 'text' && typeof vnode.value === 'string') {
    return ownerDocument.createTextNode(vnode.value);
  }
  // 렌더링 단계에는 정규화된 VNode만 들어와야 한다.
  // 원시 자식 값은 앞서 h에서 텍스트 VNode로 변환해야 한다.
  if (vnode?.kind !== 'element' || !Array.isArray(vnode.children)
    || vnode.children.some((child) => !child || typeof child !== 'object')) {
    throw new TypeError('Rendering requires an ElementVNode or TextVNode.');
  }

  // 생성 함수와 같은 태그·props 계약으로 검사하며 입력은 변경하지 않는다.
  const elementVNode = h(vnode.tag, vnode.props, vnode.children);
  const element = ownerDocument.createElement(elementVNode.tag);
  // class·style·data-*와 boolean 속성의 문자열 값을 그대로 설정한다.
  for (const [name, value] of Object.entries(elementVNode.props.attrs)) {
    element.setAttribute(name, value);
  }
  // 나중에 Patch에서 교체하거나 해제할 수 있도록 등록한 함수도 기록한다.
  for (const [name, listener] of Object.entries(elementVNode.props.events)) {
    setDOMEvent(element, name, listener);
  }
  // 자식도 같은 Document에서 재귀 생성하고 원래 순서대로 연결한다.
  for (const child of elementVNode.children) {
    element.appendChild(createDOM(child, ownerDocument));
  }
  // 호출자가 원하는 컨테이너에 삽입할 수 있도록 분리된 루트를 반환한다.
  return element;
}

/**
 * 저장된 트리로 컨테이너의 내용을 복원한다. 새 루트는 삽입 전에 완성한다.
 * @param {Element} container
 * @param {import('../core/contracts.js').Tree} tree
 */
export function replaceDOMTree(container, tree) {
  if (tree === null) {
    container.replaceChildren();
  } else {
    container.replaceChildren(createDOM(tree, container.ownerDocument));
  }
}
