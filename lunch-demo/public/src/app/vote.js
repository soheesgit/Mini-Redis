import { FunctionComponent, child, memo, useEffect, useMemo, useState } from '../component/function-component.js';
import { h } from '../core/vnode.js';
import { syncDocumentTitle } from '../dom/todo-controls.js';
import { attachRowMotion } from './row-motion.js';

/** @typedef {{ id: number, name: string, votes: number }} Menu */
/** @typedef {{ text: string, error: boolean } | null} Notice */

export const MAX_NAME_LENGTH = 20;

/** 표가 같으면 먼저 등록한 메뉴가 위로 간다. */
const byVotes = (/** @type {Menu} */ a, /** @type {Menu} */ b) => b.votes - a.votes || a.id - b.id;
/** 클릭한 버튼의 data-id에서 메뉴 id를 읽는다. 줄마다 새 함수를 만들지 않으려고 id를 함수가 아닌 속성에 둔다. */
const idOf = (/** @type {Event} */ event) => Number(/** @type {HTMLElement} */ (event.currentTarget).dataset.id);

/** @param {string} name 앞뒤 공백을 지운 이름 */
function nameProblem(name) {
  if (name === '') return '메뉴 이름을 입력해 주세요.';
  if (name.length > MAX_NAME_LENGTH) return `메뉴 이름은 ${MAX_NAME_LENGTH}자까지 쓸 수 있어요.`;
  return null;
}

/**
 * 메뉴 한 줄. memo라서 순위·표·이름이 그대로면 다시 실행하지 않고, key로 순위가 바뀌어도 같은 DOM을 옮겨 쓴다.
 * 핸들러는 모든 줄이 같은 함수를 쓴다. 줄마다 새 화살표 함수를 만들면 다시 그릴 때마다 이벤트 PROPS patch가 생긴다.
 * @type {(props: { key: number, rank: number, name: string, votes: number, onVote: EventListener, onRemove: EventListener }) => import('../core/contracts.js').VNode}
 */
const MenuRow = memo(({ key: id, rank, name, votes, onVote, onRemove }) => h('li', { attrs: { class: rank === 1 ? 'menu-row first' : 'menu-row' }, events: {}, key: id }, [
  h('span', { attrs: { class: 'menu-rank' }, events: {} }, [`${rank}위 `]),
  h('b', { attrs: { class: 'menu-name' }, events: {} }, [`${name} `]),
  h('span', { attrs: { class: 'menu-votes' }, events: {} }, [`${votes}표`]),
  h('button', { attrs: { type: 'button', class: 'menu-vote', 'data-id': String(id), 'aria-label': `${name}에 투표` }, events: { click: onVote } }, ['투표']),
  h('button', { attrs: { type: 'button', class: 'menu-remove', 'data-id': String(id), 'aria-label': `${name} 삭제` }, events: { click: onRemove } }, ['×']),
]));

/** 루트 컴포넌트가 서버에서 읽은 메뉴를 Hook 상태에 반영한다. */
function VoteApp({ setTitle, batch, initialMenus, request }) {
  const [menus, setMenus] = useState(initialMenus);
  const [notice, setNotice] = useState(/** @type {Notice} */ (null));
  const ranking = useMemo(() => [...menus].sort(byVotes), [menus]);
  const leaderName = ranking[0]?.name ?? null;
  useEffect(() => setTitle(leaderName === null ? '오늘 점심 뭐 먹지?' : `1위 ${leaderName} · 점심 투표`), [leaderName, setTitle]);

  const handlers = useMemo(() => {
    async function sync(action, input, message = null) {
      try {
        const result = await request(action, input);
        batch(() => {
          setMenus(result.menus);
          setNotice(message ? { text: message, error: false } : null);
        });
        return true;
      } catch (error) {
        setNotice({ text: error.message, error: true });
        return false;
      }
    }
    return {
      vote: (event) => sync('vote', { id: idOf(event) }),
      remove: (event) => sync('remove', { id: idOf(event) }),
      async add(event) {
        event.preventDefault();
        const form = event.currentTarget;
        const field = form.elements.namedItem('menu');
        const name = field.value.trim();
        const problem = nameProblem(name);
        if (problem) {
          setNotice({ text: problem, error: true });
          return;
        }
        if (await sync('add', { name }, `'${name}'을(를) 추가했어요.`)) form.reset();
      },
      reset: () => sync('reset'),
    };
  }, [batch, request]);

  const total = menus.reduce((sum, menu) => sum + menu.votes, 0);
  return h('section', { attrs: { class: 'vote', 'aria-labelledby': 'vote-title' }, events: {} }, [
    h('header', { attrs: { class: 'vote-head' }, events: {} }, [
      h('h1', { attrs: { id: 'vote-title' }, events: {} }, ['오늘 점심 뭐 먹지?']),
      h('button', { attrs: { type: 'button', class: 'ghost' }, events: { click: handlers.reset } }, ['초기화']),
    ]),
    h('form', { attrs: { class: 'add-form', novalidate: '' }, events: { submit: handlers.add } }, [
      h('input', { attrs: { name: 'menu', type: 'text', placeholder: '메뉴 이름 (예: 비빔밥)', maxlength: String(MAX_NAME_LENGTH + 10), autocomplete: 'off', 'aria-label': '추가할 메뉴 이름', 'aria-describedby': 'vote-notice' }, events: {} }),
      h('button', { attrs: { type: 'submit', class: 'primary' }, events: {} }, ['추가']),
    ]),
    h('p', { attrs: { id: 'vote-notice', class: notice?.error ? 'notice error' : 'notice', role: 'status' }, events: {} }, [notice?.text ?? '']),
    ranking.length === 0
      ? h('p', { attrs: { class: 'menu-empty' }, events: {} }, ['메뉴를 추가해 주세요.'])
      : h('ul', { attrs: { class: 'menu-list', role: 'list', 'aria-label': '득표 순위' }, events: {} },
        ranking.map((menu, index) => child(MenuRow, { key: menu.id, rank: index + 1, name: menu.name, votes: menu.votes, onVote: handlers.vote, onRemove: handlers.remove }))),
    h('p', { attrs: { class: 'vote-total' }, events: {} }, [`메뉴 ${menus.length}개 · 총 ${total}표`]),
  ]);
}

/**
 * 서버에 연결된 점심 투표 앱을 띄운다.
 * @param {HTMLElement} appContainer
 */
export function startVote(appContainer, { initialMenus, request }) {
  /** @type {FunctionComponent<any>} */
  const component = new FunctionComponent(VoteApp, {
    initialMenus, request,
    setTitle: (/** @type {string} */ title) => syncDocumentTitle(appContainer, title),
    batch: (/** @type {() => void} */ callback) => component.batch(callback),
  });
  const detachMotion = attachRowMotion(component, appContainer);
  try {
    component.mount(appContainer);
  } catch (error) {
    detachMotion();
    throw error;
  }
  return () => {
    try {
      component.unmount();
    } finally {
      detachMotion();
    }
  };
}
