/**
 * 예를 들어 버튼 노드마다 click을 처리하는 함수 참조를 기록한다.
 * WeakMap의 기록은 다른 곳에서 참조하지 않는 노드의 메모리 회수를 막지 않는다.
 * @type {WeakMap<Element, Map<string, EventListener>>}
 */
const listenersByElement = new WeakMap();

/**
 * 렌더와 Patch가 등록한 리스너를 노드별로 관리한다.
 * @param {Element} element
 * @param {string} name
 * @param {EventListener | null} listener null이면 등록된 리스너를 해제한다.
 */
export function setDOMEvent(element, name, listener) {
  const listeners = listenersByElement.get(element);
  const previous = listeners?.get(name);
  // 같은 버튼에 같은 함수를 다시 전달하면 기존 등록을 그대로 쓴다.
  if (previous === listener) return;

  // 같은 이름의 리스너를 바꾸려면 등록할 때 사용한 함수 참조가 필요하다.
  if (previous) {
    element.removeEventListener(name, previous);
    listeners?.delete(name);
  }
  if (listener === null) return;

  // 새 함수를 등록하고, 다음 PROPS 명령이 이 함수를 찾아 해제할 수 있게 기록한다.
  element.addEventListener(name, listener);
  const current = listeners ?? new Map();
  current.set(name, listener);
  listenersByElement.set(element, current);
}
