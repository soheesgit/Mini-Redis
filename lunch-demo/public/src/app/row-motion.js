const SLIDE = { duration: 450, easing: 'cubic-bezier(0.2, 0.8, 0.2, 1)' };
const GLOW = { duration: 1100, easing: 'ease-out' };

/**
 * 줄마다 목록 안에서의 위치와 순서를 기억한다. offsetTop은 페이지 스크롤과 진행 중인 transform에 영향받지 않고,
 * 목록의 offsetTop을 빼서 글꼴 로딩이나 알림 문구처럼 목록 바깥에서 생긴 밀림은 무시한다.
 * @param {Element} root
 * @returns {Map<Element, { top: number, index: number }>}
 */
const rowTops = (root) => new Map(Array.from(root.querySelectorAll('.menu-row'), (row, index) => {
  const item = /** @type {HTMLElement} */ (row);
  return [row, { top: item.offsetTop - (/** @type {HTMLElement | null} */ (item.parentElement)?.offsetTop ?? 0), index }];
}));

/**
 * patch 뒤의 줄을 움직여 보여 준다. 둘 다 Web Animations API(element.animate)로 해서 style 속성을 바꾸지 않는다.
 * 그래서 MutationObserver에 기록이 남지 않고, 다음 커밋의 '바뀐 노드' 수에 섞이지 않는다.
 * 1. 미끄러지기(FLIP): 지난 커밋 때 위치(First)와 지금 위치(Last)의 차이만큼 되돌려 놓고(Invert) 제자리로 움직인다(Play).
 *    MOVE로 옮긴 줄, 삭제로 당겨진 줄이 순간이동하지 않고 미끄러진다.
 * 2. 강조: patch가 건드린 줄 블록 전체를 잠깐 대표 색으로 빛낸다.
 * @param {readonly MutationRecord[]} records
 * @param {Element} root
 * @param {Map<Element, { top: number, index: number }>} previousTops
 */
function animateRows(records, root, previousTops) {
  const rowOf = (/** @type {Node} */ node) => (node.nodeType === 1 ? /** @type {Element} */ (node) : node.parentElement)?.closest('.menu-row');
  /** @type {Set<Element>} */
  const touched = new Set();
  for (const record of records) {
    // 삭제는 줄이 이미 사라졌으므로 강조할 곳이 없다. 추가·이동은 들어온 줄, 텍스트·속성 변경은 그 줄을 강조한다.
    const nodes = record.type === 'childList' ? [...record.addedNodes] : [record.target];
    for (const node of nodes) {
      const row = rowOf(node);
      if (row) touched.add(row);
    }
  }
  const view = root.ownerDocument.defaultView;
  const reduceMotion = view?.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  // 빛나는 색은 CSS의 대표 색 변수에서 읽어 디자인과 어긋나지 않게 한다.
  const token = (/** @type {string} */ name) => (view ? view.getComputedStyle(root).getPropertyValue(name).trim() : '');
  const glow = [
    { backgroundColor: token('--brand-soft'), boxShadow: `0 0 0 2px ${token('--brand')}` },
    { backgroundColor: token('--card'), boxShadow: `0 0 0 2px transparent` },
  ];
  for (const [row, { top, index }] of rowTops(root)) {
    // shortcut: animate가 없는 환경(jsdom 등)에서는 움직이지 않는다.
    if (typeof row.animate !== 'function') return;
    const before = previousTops.get(row);
    // 순서가 바뀐 줄만 미끄러진다(MOVE로 옮겨졌거나, 위 줄이 삭제돼 당겨진 줄).
    if (!reduceMotion && before !== undefined && before.index !== index && before.top !== top) {
      // 위로 올라가는 줄이 내려가는 줄 위에 보이도록 겹침 순서를 올린다.
      const z = before.top > top ? '1' : '0';
      row.animate([{ transform: `translateY(${before.top - top}px)`, zIndex: z }, { transform: 'translateY(0)', zIndex: z }], SLIDE);
    }
    if (touched.has(row)) {
      row.animate(glow, reduceMotion ? { ...GLOW, duration: 200 } : GLOW);
    }
  }
}

export function attachRowMotion(target, appContainer) {
  let tops = new Map();
  const view = appContainer.ownerDocument.defaultView;
  const remeasure = () => { tops = rowTops(appContainer); };
  view?.addEventListener('resize', remeasure);
  const observer = new MutationObserver(() => {});
  observer.observe(appContainer, { childList: true, attributes: true, characterData: true, subtree: true });
  target.onCommit = (info) => {
    const records = observer.takeRecords();
    if (info.renders > 1) animateRows(records, appContainer, tops);
    remeasure();
  };
  return () => {
    target.onCommit = null;
    observer.disconnect();
    view?.removeEventListener('resize', remeasure);
  };
}
