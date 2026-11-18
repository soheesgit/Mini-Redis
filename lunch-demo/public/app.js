import { startVote } from './src/app/vote.js';
import { readEvents } from './read-events.js';

const get = (id) => document.getElementById(id);
const app = get('vote-app');
const history = get('history');
const requests = get('requests');
const status = get('scenario-status');
const bench = get('bench');
const titles = {
  ranking: '순위 불러오기',
  vote: '투표',
  add: '메뉴 추가',
  remove: '메뉴 삭제',
  reset: '초기화',
  history: '과거 투표 비교',
};
let busy = false;

function milliseconds(value) {
  return Number.isFinite(value) ? value.toFixed(2) + ' ms' : '—';
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function say(text, error = false) {
  status.textContent = text;
  status.classList.toggle('error', error);
}

function beginLog(action) {
  const group = element('section', 'log-group');
  const title = element('h3', '', titles[action]);
  const items = element('ol', 'log-lines');
  group.append(title, items);
  requests.prepend(group);
  if (requests.childElementCount > 10) requests.lastElementChild.remove();
  requests.scrollTop = 0;
  return { title, items, cacheBadge: null, cacheState: null };
}

function showCall(event, log) {
  const redis = event.service === 'redis';
  const verb = event.command.split(' ')[0];
  const entry = element('li', 'log-entry ' + (redis ? 'redis' : 'dbms'));
  const meta = element('div', 'log-meta');
  const badge = element('span', 'log-verb', redis && verb === 'DELETE' ? 'DEL' : verb);
  meta.append(
    element('span', 'log-service', redis ? 'Redis' : 'DBMS'),
    badge,
    element('span', 'log-http', event.status ? 'HTTP ' + event.status : '연결 오류'),
    element('span', 'log-ms', milliseconds(event.ms)),
  );
  const response = event.response;
  let detail = JSON.stringify(response);
  if (Array.isArray(response?.rows)) detail = '반환 ' + response.rows.length + '행';
  if (typeof response?.affected === 'number') detail = '변경 ' + response.affected + '행';
  if (redis && Array.isArray(response)) detail = '캐시에서 순위 ' + response.length + '행 반환';
  if (redis && typeof response?.deleted === 'number') detail = '캐시 삭제 ' + response.deleted + '건';
  if (redis && verb === 'PUT' && event.ok) detail = '캐시 저장 · TTL ' + new URLSearchParams(event.command.split('?')[1]).get('ttl') + '초';
  if (redis && verb === 'GET') {
    log.cacheBadge = badge;
    if (event.status === 404) detail = '저장된 순위가 없어 DBMS에서 읽어요.';
  }
  if (!event.ok && !(redis && event.status === 404)) entry.classList.add('error');
  entry.append(meta, element('code', 'log-command', event.command), element('p', 'log-result', detail));
  log.items.append(entry);
}

function showEvent(event, log) {
  if (event.type === 'call') {
    showCall(event, log);
  } else if (event.type === 'cache') {
    log.cacheState = event.state;
    if (log.cacheBadge) {
      log.cacheBadge.textContent = event.state;
      log.cacheBadge.classList.add(event.state.toLowerCase());
      log.cacheBadge = null;
    } else if (event.state === 'BYPASS') {
      log.items.append(element('li', 'log-note', '캐시를 건너뛰고 DBMS에서 순위를 읽어요.'));
    }
  }
}

function finish(action, result, log) {
  if (action === 'ranking') {
    say(log.cacheState === 'HIT' ? 'Redis에서 점심 순위를 불러왔어요.' : 'DBMS에서 점심 순위를 불러왔어요.');
  } else if (action === 'vote') {
    const leader = result.menus[0];
    log.title.textContent = result.voted + ' 투표';
    say('한 표를 저장했어요. 현재 1위는 ' + leader.name + ' ' + leader.votes + '표예요.'
      + (result.deleted === null ? ' Redis 캐시 삭제에 실패했어요.' : ''), result.deleted === null);
  } else if (action === 'history') {
    bench.hidden = false;
    get('index-time').textContent = milliseconds(result.indexMs);
    get('scan-time').textContent = milliseconds(result.scanMs);
    say('100만 건에서 찾은 #' + result.row[0] + ' · 반환 행 일치 · 각 ' + result.rounds + '회 중앙값');
  } else if (action === 'reset') {
    say('투표와 순위 캐시를 초기화했어요.');
  } else {
    say(action === 'add' ? 'DB에 메뉴를 추가했어요.' : 'DB에서 메뉴를 삭제했어요.');
  }
}

async function request(action, input) {
  if (busy) throw new Error('앞선 요청이 끝나면 다시 눌러 주세요.');
  busy = true;
  history.disabled = true;
  app.inert = true;
  bench.hidden = true;
  const log = beginLog(action);
  say(action === 'history' ? 'B+ 트리와 전체 스캔을 번갈아 실행 중이에요.' : '요청을 처리하고 있어요.');
  try {
    const reply = await fetch('/api/' + action, action === 'ranking' ? undefined : {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input ?? {}),
    });
    if (!reply.ok) throw new Error((await reply.json()).error);
    let result;
    await readEvents(reply, (event) => {
      if (event.type === 'error') throw new Error(event.message);
      if (event.type === 'result') result = event;
      else showEvent(event, log);
    });
    if (!result) throw new Error('API 응답이 중간에 끊겼어요.');
    finish(action, result, log);
    return result;
  } catch (error) {
    say(error.message, true);
    log.items.append(element('li', 'log-note error', error.message));
    throw error;
  } finally {
    busy = false;
    app.inert = false;
    history.disabled = false;
  }
}

try {
  const { menus } = await request('ranking');
  startVote(app, { initialMenus: menus, request });
  history.addEventListener('click', () => request('history').catch(() => {}));
} catch (error) {
  history.disabled = true;
  say('순위를 불러오지 못했어요 · ' + error.message, true);
}
