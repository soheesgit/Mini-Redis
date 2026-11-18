/**
 * 브라우저가 유지하는 입력값을 제출할 때 읽고, 추가에 성공하면 입력을 비운다.
 * @param {string} fieldName
 * @param {(value: string) => void} submit
 * @returns {EventListener}
 */
export function submitTextField(fieldName, submit) {
  return (event) => {
    event.preventDefault();
    const form = /** @type {HTMLFormElement} */ (event.currentTarget);
    const field = /** @type {HTMLInputElement} */ (form.elements.namedItem(fieldName));
    const value = field.value.trim();
    if (value === '') return;

    submit(value);
    form.reset();
  };
}

/**
 * Effect에서 문서 제목을 변경하고 cleanup에서 이전 제목을 복원한다.
 * @param {Element} container
 * @param {string} title
 */
export function syncDocumentTitle(container, title) {
  const document = container.ownerDocument;
  const previous = document.title;
  document.title = title;
  return () => { document.title = previous; };
}
