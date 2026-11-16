// 미니 Redis 흐름 관찰 화면. 서버가 기록한 이벤트와 상태 사진만 그린다(스스로 계산하는 것은 표시 형식뿐).
const $ = (s) => document.querySelector(s);

function h(tag, attrs, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'text') e.textContent = v;
    else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of kids.flat(Infinity)) if (c != null && c !== false) e.append(c instanceof Node ? c : String(c));
  return e;
}

async function api(path, opts = {}) {
  const r = await fetch(path, { method: opts.method || 'GET', headers: { 'X-Flow-Demo': '1' }, body: opts.body });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}
const qs = (o) => Object.entries(o).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const enc = (k) => encodeURIComponent(k);
const dec = (s) => { try { return decodeURIComponent(s); } catch { return s; } }; // %ff 같은 비 UTF-8 경로도 그대로 보여 준다
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------- 상태 ----------------
const S = {
  since: 0, events: [], sent: [],      // 보낸 요청 [{id, method, path, status}]
  sel: null, cursor: -1, playing: null, // 선택한 요청 ID, 그 요청 이벤트 목록 안의 위치
  table: 'keys', focusKey: null, aof: null, status: null, dropped: 0,
};

const LABEL = {
  'http.recv': '요청 도착 (핸들러 스레드)', 'queue.put': '명령 큐에 넣음', 'batch.start': '배치 시작',
  'exec.start': '명령 실행 시작', 'exec.end': '명령 실행 끝', 'rehash.start': '리해싱 시작',
  'rehash.step': '버킷 1개 이동', 'rehash.done': '리해싱 끝', 'expire.lazy': '조회 중 만료 발견 → 삭제',
  'expire.active': '주기 청소가 만료 키 삭제', evict: 'maxmemory 초과 → LRU 꼬리 축출', 'aof.write': 'AOF write (OS로 넘김)',
  'future.done': 'future 완료 → 핸들러 깨움', 'http.respond': '응답 보냄', 'aof.fsync': 'fsync (디스크에 내려씀)',
  'tick.work': 'work 틱 (배경 리해싱·스냅샷)', 'tick.expire': 'expire 틱 (만료 청소)', 'tick.control': '틱 제어 변경',
  'server.start': '서버 시작', 'server.fail': '서버 시작 실패', 'recover.file': '복구: 파일 읽음', 'recover.replay': '복구: 레코드 재실행',
  'recover.truncated': '복구: 잘린 꼬리 잘라냄', 'job.start': '백그라운드 작업 시작', 'job.finish': '백그라운드 작업 끝',
};
const LANE = (t) => t.startsWith('http.') ? 0 : t === 'queue.put' ? 1 : t === 'aof.write' ? 3 : t === 'aof.fsync' ? 4 : 2;

// 이벤트 → 코드 위치. 줄 번호 대신 찾을 문자열을 둬서 코드가 바뀌어도 찾아간다
const CODE = {
  'http.recv': ['HttpApi.java', 'private void handle(HttpExchange ex)', 'study 04'],
  'queue.put': ['Engine.java', 'queue.put(new Request(', 'study 04'],
  'batch.start': ['Engine.java', 'queue.drainTo(batch, MAX_BATCH - 1)', 'study 04'],
  'exec.start': ['Store.java', 'public Object execute(byte[][] argv)', 'study 02'],
  'exec.end': ['HashTable.java', 'public V set(String key, V value)', 'study 01'],
  'rehash.start': ['HashTable.java', 'private void resizeIfNeeded()', 'study 01'],
  'rehash.step': ['HashTable.java', 'public boolean rehashStep()', 'study 01'],
  'rehash.done': ['HashTable.java', 'table[0] = table[1];', 'study 01'],
  'expire.lazy': ['Store.java', 'private Entry lookup(String key)', 'study 02'],
  'expire.active': ['Store.java', 'public void activeExpireCycle()', 'study 02'],
  evict: ['Store.java', 'private void evictIfNeeded()', 'study 05'],
  'aof.write': ['Persistence.java', 'public boolean append(List<byte[]> records)', 'study 03'],
  'future.done': ['Engine.java', 'else f.complete(results[i]);', 'study 04'],
  'http.respond': ['HttpApi.java', 'ex.sendResponseHeaders(', 'study 04'],
  'aof.fsync': ['Persistence.java', 'c.force(false);', 'study 03'],
  'tick.work': ['Engine.java', 'private void workTick()', 'study 01'],
  'tick.expire': ['Engine.java', 'private void expireTick()', 'study 02'],
  'server.start': ['Engine.java', 'persistence.recover(store);', 'study 03'],
  'recover.file': ['Persistence.java', 'public void recover(Store store)', 'study 03'],
  'recover.replay': ['Persistence.java', 'parsed.records().forEach(store::execute);', 'study 03'],
  'recover.truncated': ['Persistence.java', 'c.truncate(parsed.goodEnd());', 'study 03'],
  'job.start': ['Persistence.java', 'public void startRewrite(Store store)', 'study 06'],
  'job.finish': ['Persistence.java', 'done.finish();', 'study 06'],
};

// ---------------- 요청 프리셋 ----------------
const P = [
  { id: 'set', label: 'SET  값 저장 (PUT /keys/{키})', m: 'PUT', p: (k, t) => `/keys/${enc(k)}${t ? `?ttl=${enc(t)}` : ''}`, body: true, ttl: true },
  { id: 'get', label: 'GET  값 읽기', m: 'GET', p: (k) => `/keys/${enc(k)}` },
  { id: 'del', label: 'DEL  지우기', m: 'DELETE', p: (k) => `/keys/${enc(k)}` },
  { id: 'incr', label: 'INCR  1 올리기', m: 'POST', p: (k) => `/keys/${enc(k)}/incr` },
  { id: 'exists', label: 'EXISTS  있나? (HEAD)', m: 'HEAD', p: (k) => `/keys/${enc(k)}` },
  { id: 'ttl', label: 'TTL  남은 시간', m: 'GET', p: (k) => `/keys/${enc(k)}/ttl` },
  { id: 'expire', label: 'EXPIRE  TTL 설정', m: 'PUT', p: (k, t) => `/keys/${enc(k)}/ttl?seconds=${enc(t || '60')}`, ttl: true },
  { id: 'persist', label: 'PERSIST  TTL 해제', m: 'DELETE', p: (k) => `/keys/${enc(k)}/ttl` },
  { id: 'keys', label: 'KEYS  키 목록', m: 'GET', p: () => '/keys?pattern=*', nokey: true },
  { id: 'flush', label: 'FLUSHALL  전부 지우기', m: 'DELETE', p: () => '/keys', nokey: true },
  { id: 'ping', label: 'PING', m: 'GET', p: () => '/ping', nokey: true },
  { id: 'bgsave', label: 'BGSAVE  백그라운드 저장', m: 'POST', p: () => '/admin/bgsave', nokey: true },
  { id: 'rewrite', label: 'REWRITEAOF  AOF 압축', m: 'POST', p: () => '/admin/rewrite-aof', nokey: true },
  { id: 'save', label: 'SAVE  저장(동기)', m: 'POST', p: () => '/admin/save', nokey: true },
  { id: 'stats', label: 'STATS  통계', m: 'GET', p: () => '/admin/stats', nokey: true },
];
for (const p of P) $('#preset').append(h('option', { value: p.id, text: p.label }));
const preset = () => P.find((p) => p.id === $('#preset').value);
function syncForm() {
  const p = preset();
  $('#valWrap').classList.toggle('hidden', !p.body);
  $('#ttlWrap').classList.toggle('hidden', !p.ttl);
  $('#key').disabled = !!p.nokey;
  $('#sendPath').textContent = `${p.m} ${p.p($('#key').value, $('#ttl').value)}`;
}
['#preset', '#key', '#ttl'].forEach((s) => $(s).addEventListener('input', syncForm));
syncForm();
$('#longKey').addEventListener('click', () => { $('#key').value = 'x'.repeat(1025); syncForm(); });

// ---------------- 보내기 ----------------
async function send({ method, path, body = '', origin = false, auth = 'ok', preset = null }) {
  const r = await api(`/api/send?${qs({ method, path, origin: origin ? 1 : 0, auth, preset })}`, { method: 'POST', body });
  S.sent.unshift({ id: r.traceId, method, path, status: r.status });
  S.sent.length = Math.min(S.sent.length, 200);
  showResp(method, path, r);
  renderHistory();
  return r;
}

function showResp(method, path, r) {
  const ok = r.status < 400;
  $('#resp').replaceChildren(
    h('span', { class: `status ${ok ? 'ok' : 'bad'}`, text: `${r.status}` }), ` ${method} ${path.length > 80 ? path.slice(0, 80) + '…' : path}\n`,
    `요청 #${r.traceId}  왕복 ${r.us}µs  본문 ${r.bodyBytes}B\n`, r.body.length > 400 ? r.body.slice(0, 400) + '…' : r.body,
  );
}

$('#send').addEventListener('click', async () => {
  const p = preset();
  try {
    const r = await send({
      method: p.m, path: p.p($('#key').value, $('#ttl').value), body: p.body ? $('#val').value : '',
      origin: $('#optOrigin').checked, auth: $('#optAuth').value, preset: $('#opt1mb').checked ? '1mb' : null,
    });
    select(r.traceId);
    if (!p.nokey) S.focusKey = $('#key').value;
  } catch (e) { $('#resp').textContent = `데모 서버 오류: ${e.message}`; }
});

$('#burst').addEventListener('click', async () => {
  const n = $('#burstN').value, con = $('#burstCon').checked ? 1 : 0;
  const k = $('#key').value || 'counter';
  const q = $('#burstMode').value === 'incr'
    ? { method: 'POST', path: `/keys/${enc(k)}/incr` } : { method: 'PUT', path: `/keys/${enc(k)}-{i}` };
  $('#burstOut').textContent = '보내는 중…';
  try {
    const r = await api(`/api/burst?${qs({ ...q, count: n, concurrent: con })}`, { method: 'POST', body: 'v' });
    $('#burstOut').textContent = `${r.sent}개 → 상태 ${JSON.stringify(r.statuses)}. 배치 묶음은 ③에서`;
    for (const id of r.traceIds.slice(0, 50)) S.sent.unshift({ id, method: q.method, path: q.path, status: 200 });
    renderHistory();
    if (r.traceIds.length) select(Math.max(...r.traceIds));
  } catch (e) { $('#burstOut').textContent = e.message; }
});

// ---------------- 이벤트 수신 (0.5초 폴링, executor를 건드리지 않는다) ----------------
async function poll() {
  try {
    const r = await api(`/api/trace?since=${S.since}`);
    if (r.dropped) { S.dropped += r.dropped; $('#dropped').textContent = `버려진 이벤트 ${S.dropped}`; $('#dropped').classList.remove('hidden'); }
    if (r.events.length) {
      S.events.push(...r.events);
      if (S.events.length > 6000) S.events.splice(0, S.events.length - 6000);
      S.since = r.next;
      onNewEvents(r.events);
    }
  } catch { /* 서버 재시작 중 */ }
  setTimeout(poll, r_fast ? 150 : 500);
}
let r_fast = false;

let statsTimer = null;
function onNewEvents(evs) {
  if ($('#live').checked) S.cursor = Infinity;
  if (evs.some((e) => e.type === 'aof.write' || e.type === 'job.finish' || e.type === 'server.start')) refreshAof();
  if (evs.some((e) => e.type.startsWith('server.'))) refreshStatus();
  clearTimeout(statsTimer); statsTimer = setTimeout(refreshStats, 300);
  render();
}

// ---------------- 선택한 요청의 이벤트 ----------------
function related(id) {
  if (id == null) return S.events.slice(-150);
  const out = [];
  let lastWrite = null;
  const rehashing = new Set(); // 이 요청이 시작시킨 리해싱: 끝날 때까지 틱이 진행시킨 스텝도 함께 보여 준다
  for (const e of S.events) {
    if (e.req === id || (e.type === 'batch.start' && e.reqs.includes(id))
      || (e.type === 'aof.write' && e.records.some((r) => r.req === id))) {
      out.push(e);
      if (e.type === 'aof.write') lastWrite = e;
      if (e.type === 'rehash.start') rehashing.add(e.table);
      if (e.type === 'rehash.done') rehashing.delete(e.table);
      continue;
    }
    if (rehashing.size && ((e.type.startsWith('rehash.') && rehashing.has(e.table)) || e.type === 'tick.work')) {
      out.push(e);
      if (e.type === 'rehash.done') rehashing.delete(e.table);
      continue;
    }
    if (lastWrite && e.type === 'aof.fsync' && e.t > lastWrite.t && e.t - lastWrite.t < 2.5e9) { out.push(e); lastWrite = null; }
  }
  return out.sort((a, b) => a.seq - b.seq);
}
const view = () => ($('#showAll').checked ? S.events.slice(-200) : related(S.sel));

function select(id) {
  S.sel = id; S.cursor = Infinity; stop();
  $('#live').checked = true;
  renderHistory(); render();
}

function renderHistory() {
  $('#history').replaceChildren(...S.sent.map((s) => h('li', { class: s.id === S.sel ? 'sel' : '', onclick: () => select(s.id) },
    h('span', { class: 'mono dim', text: `#${s.id}` }), h('span', { class: `status ${s.status < 400 ? 'ok' : 'bad'}`, text: s.status }),
    h('span', { class: 'mono', text: `${s.method} ${dec(s.path).slice(0, 40)}` }))));
}

// ---------------- 재생 ----------------
function stop() { clearInterval(S.playing); S.playing = null; $('#play').textContent = '▶'; }
function step(d) { const n = view().length; S.cursor = Math.max(0, Math.min(n - 1, (S.cursor === Infinity ? n - 1 : S.cursor) + d)); $('#live').checked = false; render(); }
$('#first').onclick = () => { stop(); S.cursor = 0; $('#live').checked = false; render(); };
$('#last').onclick = () => { stop(); S.cursor = Infinity; render(); };
$('#prev').onclick = () => { stop(); step(-1); };
$('#next').onclick = () => { stop(); step(1); };
$('#play').onclick = () => {
  if (S.playing) return stop();
  if (S.cursor === Infinity || S.cursor >= view().length - 1) S.cursor = -1;
  $('#live').checked = false; $('#play').textContent = '⏸';
  S.playing = setInterval(() => { if (S.cursor >= view().length - 1) return stop(); step(1); }, +$('#speed').value);
};
$('#showAll').onchange = () => { S.cursor = Infinity; render(); };

// ---------------- 그리기 ----------------
function render() {
  const evs = view();
  const cur = Math.min(S.cursor, evs.length - 1);
  const e = evs[cur];
  renderTimeline(evs, cur);
  renderDetail(e);
  const snapEv = [...evs.slice(0, cur + 1)].reverse().find((x) => x.snap) || [...S.events].reverse().find((x) => x.snap);
  const snap = snapEv && snapEv.snap;
  const locEv = [...evs.slice(0, cur + 1)].reverse().find((x) => x.loc);
  renderBuckets(snap, e, locEv && locEv.loc);
  renderLocate(locEv ? locEv.loc : null);
  renderLru(snap, e);
  renderBatches();
  renderAof(evs);
  renderCode(e);
}

function renderTimeline(evs, cur) {
  $('#tlTitle').textContent = $('#showAll').checked ? '(전체)' : S.sel != null ? `요청 #${S.sel}` : '(요청을 보내거나 고르세요)';
  const t0 = evs.length ? evs[0].t : 0;
  const rows = evs.map((e, i) => {
    const cells = [0, 1, 2, 3, 4].map(() => h('div', { class: 'tl-cell' }));
    const bad = (e.type === 'http.respond' && e.status >= 400) || e.error || e.ok === false || e.type === 'server.fail';
    const gray = e.req === 0 || e.type === 'aof.fsync';
    cells[LANE(e.type)].append(h('span', { class: `dot ${bad ? 'bad' : gray ? 'gray' : ''}` }), short(e),
      h('div', { class: 'dt', text: `+${fmtNs(e.t - t0)}` }));
    return h('div', { class: `tl-row ${i === cur ? 'cur' : ''} ${i > cur ? 'future' : ''}`, onclick: () => { stop(); S.cursor = i; $('#live').checked = false; render(); } }, cells);
  });
  $('#timeline').replaceChildren(...rows);
  const curRow = $('#timeline .cur');
  if (curRow) curRow.scrollIntoView({ block: 'nearest' });
}

function short(e) {
  switch (e.type) {
    case 'http.recv': return `${e.method} ${dec(e.path).slice(0, 28)}`;
    case 'queue.put': return `put ${e.cmd} (큐 ${e.queueBefore}개 대기)`;
    case 'batch.start': return `배치 #${e.batch} (${e.size}개)`;
    case 'exec.start': return `실행 ${e.cmd} ${e.key || ''}`;
    case 'exec.end': return `→ ${e.result} (레코드 ${e.records})`;
    case 'aof.write': return `write ${e.records.reduce((a, r) => a + r.len, 0)}B`;
    case 'aof.fsync': return `fsync ${e.us}µs`;
    case 'future.done': return 'future 완료';
    case 'http.respond': return `${e.status} 응답`;
    case 'rehash.start': return `리해싱 ${e.from}→${e.to} (${e.table})`;
    case 'rehash.step': return `버킷 ${e.bucket} 이동 [${e.moved.join(',')}]`;
    case 'rehash.done': return `리해싱 끝 (${e.size}칸)`;
    case 'expire.lazy': case 'expire.active': return `만료 ${e.key}`;
    case 'evict': return `축출 ${e.key}`;
    default: return LABEL[e.type] || e.type;
  }
}

function fmtNs(ns) { return ns < 1e6 ? `${(ns / 1e3).toFixed(0)}µs` : ns < 1e9 ? `${(ns / 1e6).toFixed(2)}ms` : `${(ns / 1e9).toFixed(2)}s`; }

function renderDetail(e) {
  const d = $('#evDetail');
  if (!e) { d.textContent = '요청을 보내면 단계가 여기 하나씩 나온다. ◀ ▶로 한 단계씩 넘겨 보자.'; return; }
  const lines = [h('b', { text: LABEL[e.type] || e.type }), ` · 스레드 ${e.thread}${e.req > 0 ? ` · 요청 #${e.req}` : e.req === 0 ? ' · 요청 없는 일' : ''}`];
  const say = (t) => lines.push(h('div', { text: t }));
  switch (e.type) {
    case 'http.recv': say(`요청마다 가상 스레드 1개가 이 요청을 맡는다. Origin 헤더 ${e.origin ? '있음 → 403으로 거절될 것' : '없음'}, 인증 헤더 ${e.auth ? '있음' : '없음'}.`); break;
    case 'queue.put': say(`핸들러는 데이터를 직접 만지지 않는다. 명령 [${e.cmd} ${e.key}]과 빈 future를 큐에 넣고 기다린다. 넣기 전 큐 대기 ${e.queueBefore}개.`); break;
    case 'batch.start': say(`executor가 큐에서 1개를 꺼내고, 쌓여 있던 것을 최대 127개 더 꺼내 한 배치로 묶었다: 요청 ${e.reqs.map((x) => '#' + x).join(' ')}${e.observe ? ` (+관찰 ${e.observe})` : ''}. 큐에 남은 것 ${e.queueLeft}.`); break;
    case 'exec.start': say('executor 스레드(1개뿐)가 Store.execute를 부른다. 이 스레드만 해시 테이블을 만지므로 락이 없다.'); break;
    case 'exec.end': say(`결과: ${e.result}. 이 명령이 만든 AOF 레코드 ${e.records}개는 아직 메모리(pendingRecords)에 있다 — 배치가 끝나야 한 번에 쓴다.`); break;
    case 'aof.write': say(`배치의 레코드 ${e.records.length}개(${e.records.reduce((a, r) => a + r.len, 0)}B)를 write 1번으로 ${e.file} 오프셋 ${e.offset}에 붙였다. 응답보다 먼저다. 아직 OS 버퍼(페이지 캐시)에 있고 디스크에는 fsync가 내린다.`); break;
    case 'future.done': say('AOF write가 끝난 뒤에야 future를 완료한다. 그래서 응답을 받은 쓰기는 프로세스가 죽어도 AOF에 있다.'); break;
    case 'http.respond': say(`핸들러가 future.get()에서 깨어나 ${e.status}를 보냈다. ${e.body ? '본문: ' + e.body : ''}`); break;
    case 'aof.fsync': say(`aof-fsync 스레드가 1초마다 force(false). 파일 ${e.bytes}B를 디스크로 내렸다(${e.us}µs). 응답은 이것을 기다리지 않는다.`); break;
    case 'rehash.start': say(`${e.table} 테이블: 키 수가 버킷 수(${e.from})에 닿아 ${e.to}칸 table[1]을 만들었다. 한꺼번에 옮기지 않고 조금씩 옮긴다.`); break;
    case 'rehash.step': say(`table[0]의 버킷 ${e.bucket} 체인 전체(${e.moved.join(', ')})를 table[1]로 옮겼다. ${e.req > 0 ? '명령이 진행시킴(명령마다 1스텝)' : 'work 틱이 진행시킴(10ms마다 1ms)'}.`); break;
    case 'rehash.done': say(`옮기기 끝. table[1]이 새 table[0](${e.size}칸)이 됐다.`); break;
    case 'expire.lazy': say(`${e.key}를 조회했는데 이미 만료 → 그 자리에서 지우고 "없음"으로 답한다. 이 삭제도 DEL 레코드로 AOF에 남는다(읽기 명령이 AOF를 쓰는 경우).`); break;
    case 'expire.active': say(`100ms마다 expires 테이블에서 무작위 20개를 뽑아 만료된 ${e.key}를 지웠다. 요청이 없어도 지워진다.`); break;
    case 'evict': say(`메모리 ${e.memory}B > 상한 ${e.maxmemory}B. 정책 ${e.policy}: LRU 리스트 꼬리(가장 오래 안 쓴 키) ${e.key}를 지운다. 쓰기 명령 실행 "전"에 검사한다.`); break;
    case 'server.start': say(`seed ${e.fixedSeed ? '고정(0)' : '무작위'} · maxmemory ${e.maxmemory} · ${e.policy}. 복구 뒤 상태다.`); break;
    case 'recover.truncated': say(`${e.file} 끝 레코드가 잘려 있었다(${e.size}B 중 ${e.goodEnd}B까지 정상). 잘린 꼬리는 잘라내고 시작한다.`); break;
    case 'server.fail': say(`시작 거부: ${e.error}. 중간 손상은 데이터를 잃지 않으려고 시작하지 않는다. "데이터 비우고 재시작"으로 돌아갈 수 있다.`); break;
    default: break;
  }
  d.replaceChildren(...lines);
}

// 버킷 뷰: table[0], table[1]을 나란히. 키를 누르면 ⑤에 그 키가 담긴 모습
function renderBuckets(snap, e, loc) {
  const box = $('#buckets');
  if (!snap) { box.replaceChildren(h('div', { class: 'dim', text: '아직 상태 사진이 없다' })); return; }
  const t = snap[S.table];
  const hot = e && (e.key || (e.moved && e.moved[0]));
  const moved = e && e.type === 'rehash.step' ? e.moved : [];
  $('#snapNote').textContent = `${S.table}: table[0] ${t.size0}칸(키 ${t.used0})${t.size1 ? ` · table[1] ${t.size1}칸(키 ${t.used1}) · rehashIndex ${t.rehashIndex}` : ''}${t.openCursors ? ` · 열린 커서 ${t.openCursors}(리해싱 멈춤)` : ''}`;
  if (!t.t0) { box.replaceChildren(h('div', { class: 'dim', text: '버킷이 256칸을 넘어 요약만 보인다(상태 사진이 커지지 않게)' })); return; }
  const col = (chains, n, idx) => h('div', { class: 'btable' },
    h('h4', { text: `table[${idx}] — ${chains.length}칸, hash & ${chains.length - 1}` }),
    chains.map((chain, i) => h('div', {
      class: `brow ${idx === 0 && t.rehashIndex >= 0 && i < t.rehashIndex ? 'done' : ''} ${idx === 0 && i === t.rehashIndex ? 'ptr' : ''}`,
    },
    h('span', { class: 'bidx', text: `${i}  ${i.toString(2).padStart(Math.log2(chains.length) || 1, '0')}` }),
    h('div', { class: 'chain' }, chain.length ? chain.flatMap((nd, j) => [
      j ? h('span', { class: 'arrow', text: '→' }) : null,
      h('span', {
        class: `node ${nd.k === hot || moved.includes(nd.k) ? 'hot' : ''} ${nd.k === S.focusKey ? 'sel' : ''} ${nd.expired ? 'expired' : ''}`,
        title: `${nd.k} = ${nd.v} (${nd.vlen}B${nd.ttl >= 0 ? `, TTL ${(nd.ttl / 1000).toFixed(1)}s` : ''})`,
        onclick: () => focusKey(nd.k),
        text: `${nd.k}=${nd.v}${nd.ttl >= 0 ? ' ⏱' : ''}`,
      })]) : [h('span', { class: 'nil', text: idx === 0 && t.rehashIndex >= 0 && i < t.rehashIndex ? '옮김 끝' : 'null' })]),
    idx === 0 && i === t.rehashIndex ? h('span', { class: 'arrow', text: '◀ rehashIndex' }) : null)));
  box.replaceChildren(...[col(t.t0, t.size0, 0), t.size1 ? col(t.t1, t.size1, 1) : null].filter(Boolean));
}

async function focusKey(k) {
  S.focusKey = k;
  try {
    const loc = await api(`/api/debug?${qs({ cmd: 'LOCATE', a1: k })}`);
    renderLocate(loc, true);
    render2Buckets();
  } catch (e) { $('#locate').textContent = e.message; }
}
function render2Buckets() { $('#buckets').querySelectorAll('.node').forEach((n) => n.classList.toggle('sel', n.textContent.startsWith(S.focusKey + '='))); }

function renderLocate(loc, live) {
  const box = $('#locate');
  if (!loc) { box.replaceChildren(h('div', { class: 'dim', text: '키가 있는 명령을 보내거나, 버킷의 키를 누르면 그 키가 어떻게 계산되고 담기는지 나온다' })); $('#locKey').textContent = ''; return; }
  $('#locKey').textContent = `${loc.key}${live ? ' (지금)' : ' (그 시점)'}`;
  const H = BigInt('0x' + loc.hash);
  const tIdx = loc.table >= 0 ? loc.table : loc.rehashing ? 1 : 0;
  const size = tIdx === 1 ? loc.size1 : loc.size0;
  const bits = Math.log2(size);
  const bin = H.toString(2).padStart(64, '0');
  const rows = [
    ['① 키 바이트', `"${loc.key}" → UTF-8 ${new TextEncoder().encode(loc.key).length}바이트`],
    ['② SipHash-2-4', h('span', { class: 'mono', text: `0x${loc.hash} (서버 시작 때 뽑은 비밀 seed 16바이트로 계산)` })],
    ['③ 버킷 번호', h('div', {},
      h('div', { class: 'bits' }, bin.slice(0, 64 - bits), h('span', { class: 'low', text: bin.slice(64 - bits) })),
      h('div', { text: `hash & (${size} - 1) = 아래 ${bits}비트 = ${tIdx === 1 ? loc.idx1 : loc.idx0}번 (table[${tIdx}])${loc.rehashing ? ` · 리해싱 중이라 table[0]이면 ${loc.idx0}번, table[1]이면 ${loc.idx1}번` : ''}` }))],
    ['④ 체인 안 위치', loc.table >= 0 ? `table[${loc.table}] 버킷의 ${loc.pos + 1}번째 노드 (체인 길이 ${loc.chainLen}) — 새 노드는 체인 맨 앞에 붙는다` : `없음 (넣으면 체인 길이 ${loc.chainLen}인 버킷의 맨 앞)`],
  ];
  const en = loc.entry;
  if (en) {
    rows.push(['Entry.key', `${loc.key} (${en.keyBytes}B)`]);
    rows.push(['Entry.value', h('div', {}, h('div', { text: `"${en.value}" (${en.valueLen}B)` }), h('div', { class: 'mono dim', text: en.valueHex.replace(/(..)/g, '$1 ') }))]);
    rows.push(['Entry.expireAt', en.expireAtUnixMs < 0 ? '0 (TTL 없음)' : `단조 시계 ns로 저장. 지금 기준 ${(en.ttlMs / 1000).toFixed(1)}초 남음 (파일에는 유닉스 ${en.expireAtUnixMs}ms로 기록)`]);
    rows.push(['메모리 cost', `${en.keyBytes} + ${en.valueLen} + 139(엔트리 고정비) ${en.expireAtUnixMs < 0 ? '' : '+ 32(TTL)'} = ${en.cost}B`]);
    rows.push(['LRU 위치', `머리(최근)에서 ${en.lruRank + 1}번째`]);
  }
  box.replaceChildren(h('table', {}, rows.map(([k, v]) => h('tr', {}, h('td', { text: k }), h('td', {}, v)))));
}

function renderLru(snap, e) {
  const box = $('#lru');
  if (!snap) return;
  const hot = e && e.key;
  const list = (arr) => h('div', { class: 'lrulist' }, h('span', { class: 'dim small', text: '머리(최근)' }),
    arr.flatMap((k, i) => [i ? h('span', { class: 'arrow', text: '⇄' }) : null, h('span', { class: `node ${k === hot ? 'hot' : ''}`, onclick: () => focusKey(k), text: k })]),
    h('span', { class: 'dim small', text: '꼬리(가장 오래)' }));
  const pct = snap.maxmemory > 0 ? Math.min(100, (snap.memory / snap.maxmemory) * 100) : 0;
  box.replaceChildren(
    h('div', { class: 'small', text: `전체 LRU (${snap.lru.length}개${snap.lru.length >= 64 ? ', 앞 64개' : ''})` }), list(snap.lru),
    h('div', { class: 'small', text: 'TTL 키 LRU (volatile-lru가 보는 리스트)' }), list(snap.ttl),
    h('div', { class: 'small', text: `메모리 ${snap.memory}B / ${snap.maxmemory > 0 ? snap.maxmemory + 'B' : '무제한'} · 정책 ${snap.policy} · 만료 ${snap.expired} · 축출 ${snap.evicted}` }),
    h('div', { class: `gauge ${pct >= 100 ? 'full' : ''}` }, h('div', { style: null })),
  );
  box.querySelector('.gauge > div').style.width = `${pct}%`;
}

function renderBatches() {
  const bs = S.events.filter((e) => e.type === 'batch.start').slice(-12).reverse();
  $('#batches').replaceChildren(...(bs.length ? bs.map((b) => h('div', { class: 'batch' },
    h('span', { class: 'mono', text: `배치 #${b.batch}` }), h('span', { class: 'dim', text: `${b.size}개 → write 1번` }),
    b.reqs.slice(0, 40).map((id) => h('span', { class: `req mono ${id === S.sel ? 'sel' : ''}`, text: `#${id}` })),
    b.reqs.length > 40 ? h('span', { class: 'dim', text: `… +${b.reqs.length - 40}` }) : null,
    b.observe ? h('span', { class: 'dim', text: `(+관찰 ${b.observe})` }) : null)) : [h('div', { class: 'dim', text: '동시 요청을 보내면 한 배치에 여러 요청이 묶인다' })]));
}

// AOF: 파일 바이트를 형식대로 나눈다 [4B 본문 길이][1B 인자 수][인자마다 4B 길이 + 바이트][4B CRC32]
async function refreshAof() {
  try { S.aof = await api('/api/debug?cmd=AOF'); renderAof(view()); } catch { /* 멈춤 */ }
}
$('#aofRefresh').onclick = refreshAof;
function renderAof(evs) {
  if (!S.aof) return;
  const a = S.aof;
  $('#aofMeta').replaceChildren(
    `manifest: ${a.manifest.map((f) => `${f.name}(${f.bytes}B)`).join(' → ')} · 지금 쓰는 파일 ${a.current} ${a.size}B${a.background !== 'none' ? ` · 진행 중 ${a.background}` : ''}`,
    h('div', { class: 'legend' }, h('span', { class: 'b-len', text: '■ 본문 길이' }), h('span', { class: 'b-argc', text: '■ 인자 수' }),
      h('span', { class: 'b-alen', text: '■ 인자 길이' }), h('span', { class: 'b-arg', text: '■ 인자' }), h('span', { class: 'b-crc', text: '■ CRC32' })));
  const bytes = a.hex.match(/../g)?.map((x) => parseInt(x, 16)) || [];
  // 오프셋 → 요청 ID (aof.write 이벤트로)
  const owner = new Map();
  for (const e of S.events) if (e.type === 'aof.write' && e.file === a.current) {
    let off = e.offset; for (const r of e.records) { owner.set(off, r.req); off += r.len; }
  }
  const sel = new Set(evs.filter((e) => e.type === 'aof.write').flatMap((e) => { let off = e.offset; return e.records.map((r) => { const o = off; off += r.len; return r.req === S.sel ? o : -1; }); }));
  const recs = [];
  let p = 0;
  const hx = (from, to) => bytes.slice(from, to).map((b) => b.toString(16).padStart(2, '0')).join(' ');
  const u32 = (i) => ((bytes[i] << 24) | (bytes[i + 1] << 16) | (bytes[i + 2] << 8) | bytes[i + 3]) >>> 0;
  while (p + 4 <= bytes.length && recs.length < 300) {
    const start = p, n = u32(p);
    if (p + 4 + n + 4 > bytes.length) { recs.push(h('div', { class: 'rec' }, h('span', { class: 'b-crc', text: `오프셋 ${p}: 잘린 꼬리 ${bytes.length - p}B — ${hx(p, bytes.length)}` }))); break; }
    const parts = [h('span', { class: 'b-len', text: hx(p, p + 4) }), ' '];
    let q = p + 4; const argc = bytes[q];
    parts.push(h('span', { class: 'b-argc', text: hx(q, q + 1) }), ' '); q++;
    const args = [];
    for (let i = 0; i < argc && q + 4 <= p + 4 + n; i++) {
      const l = u32(q);
      parts.push(h('span', { class: 'b-alen', text: hx(q, q + 4) }), ' ', h('span', { class: 'b-arg', text: hx(q + 4, q + 4 + l) }), ' ');
      args.push(new TextDecoder().decode(new Uint8Array(bytes.slice(q + 4, q + 4 + l))));
      q += 4 + l;
    }
    parts.push(h('span', { class: 'b-crc', text: hx(p + 4 + n, p + 8 + n) }));
    const id = owner.get(start);
    recs.push(h('div', { class: `rec ${sel.has(start) ? 'sel' : ''}` },
      h('div', { class: 'dec', text: `@${start} · ${8 + n}B · ${args.map((x) => (x.length > 30 ? x.slice(0, 30) + '…' : x)).join(' ')}${id ? `  ← 요청 #${id}` : id === 0 ? '  ← 틱/만료' : ''}` }),
      h('div', { class: 'hex' }, parts)));
    p += 8 + n;
  }
  if (a.truncated) recs.push(h('div', { class: 'dim', text: '… 64KB 넘는 부분은 생략' }));
  $('#aof').replaceChildren(...(recs.length ? recs : [h('div', { class: 'dim', text: `${a.current}가 비어 있다. 쓰기 명령을 보내 보자.` })]));
  const s = $('#aof .rec.sel'); if (s) s.scrollIntoView({ block: 'nearest' });
}

// 코드 패널
const codeCache = new Map();
let codeFor = null;
async function renderCode(e) {
  const c = e && CODE[e.type];
  if (!c) { if (!e) $('#code').textContent = ''; return; }
  const key = c.join('|');
  if (codeFor === key) return;
  codeFor = key;
  let r = codeCache.get(key);
  if (!r) {
    try { r = await api(`/api/code?${qs({ file: c[0], find: c[1] })}`); codeCache.set(key, r); } catch (x) { $('#code').textContent = x.message; return; }
  }
  if (codeFor !== key) return;
  $('#code').replaceChildren(h('div', { class: 'dim', text: `${r.file}:${r.line} · ${c[2]}` }),
    ...r.lines.map((l, i) => h('span', { class: `line ${r.from + i === r.line ? 'hit' : ''}` }, h('span', { class: 'ln', text: r.from + i }), l)));
}

// 통계와 서버 상태
async function refreshStats() {
  try {
    const t = await api('/api/debug?cmd=TABLE');
    const k = t.keys;
    const items = { 키: t.count, 메모리: `${t.memory}B`, 'keys 버킷': `${k.size0}${k.size1 ? ' + ' + k.size1 : ''}`, 리해싱: k.rehashIndex >= 0 ? `중 (index ${k.rehashIndex})` : '아님',
      '열린 커서': k.openCursors, 만료: t.expired, 축출: t.evicted, 큐: t.queue, 'AOF 파일': t.aofFile, 'AOF 전체': `${t.aofBytes}B`, 백그라운드: t.background,
      'work 틱': t.workTick ? '돎' : '멈춤', 'expire 틱': t.expireTick ? '돎' : '멈춤' };
    $('#stats').replaceChildren(...Object.entries(items).map(([a, b]) => h('div', {}, h('span', { class: 'dim', text: `${a} ` }), `${b}`)));
    $('#ticks').textContent = `틱 work ${t.workTick ? 'ON' : 'OFF'} · expire ${t.expireTick ? 'ON' : 'OFF'}`;
    $('#ticks').className = `chip ${t.workTick && t.expireTick ? '' : 'warn'}`;
    $('#tickBanner').classList.toggle('hidden', t.workTick && t.expireTick);
    $('#tickWork').checked = t.workTick; $('#tickExpire').checked = t.expireTick;
  } catch (e) { $('#stats').textContent = e.message; }
}
async function refreshStatus() {
  try {
    const s = S.status = await api('/api/server');
    $('#srv').textContent = s.running ? `미니 Redis 실행 중 :${s.redisPort} (시작 ${s.epoch}회)` : `멈춤: ${s.error}`;
    $('#srv').className = `chip ${s.running ? 'ok' : 'bad'}`;
    $('#seed').textContent = `seed ${s.fixedSeed ? '고정(0)' : '무작위'} · maxmemory ${s.maxmemory || '무제한'} · ${s.policy}`;
    $('#setMax').value = s.maxmemory; $('#setPolicy').value = s.policy; $('#setSeed').checked = s.fixedSeed;
    $('#optAuth').disabled = !s.password;
  } catch (e) { $('#srv').textContent = e.message; }
}

// 테이블 탭
document.querySelectorAll('.tab').forEach((b) => b.addEventListener('click', () => {
  S.table = b.dataset.table; document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === b)); render();
}));

// ---------------- 서버 설정 · 틱 제어 ----------------
$('#openSettings').onclick = () => { refreshStatus(); $('#settings').showModal(); };
async function restart(extra) {
  $('#setOut').textContent = '재시작 중…';
  try {
    const s = await api(`/api/server?${qs({ maxmemory: $('#setMax').value, policy: $('#setPolicy').value, fixedSeed: $('#setSeed').checked ? 1 : 0, ...extra })}`, { method: 'POST' });
    $('#setOut').textContent = `${s.running ? '실행 중' : '시작 거부: ' + s.error}${s.note ? ' · ' + s.note : ''}`;
    await refreshStatus(); refreshAof(); refreshStats();
    return s;
  } catch (e) { $('#setOut').textContent = e.message; }
}
$('#doRestart').onclick = () => restart({});
$('#doReset').onclick = () => restart({ action: 'reset' });
$('#doTrunc').onclick = () => restart({ action: 'truncate', n: $('#truncN').value });
$('#doFlip').onclick = () => restart({ action: 'flip', n: $('#flipN').value });
async function ticks(kind, on) { await api(`/api/debug?${qs({ cmd: 'TICKS', a1: kind, a2: on ? 'on' : 'off' })}`); refreshStats(); }
async function tickOnce(kind) { await api(`/api/debug?${qs({ cmd: 'TICK', a1: kind })}`); refreshStats(); }
$('#tickWork').onchange = (e) => ticks('work', e.target.checked);
$('#tickExpire').onchange = (e) => ticks('expire', e.target.checked);
$('#tickWorkOnce').onclick = () => tickOnce('work');
$('#tickExpireOnce').onclick = () => tickOnce('expire');

// ---------------- 실험 S1~S10 ----------------
const put = (k, v = '1', ttl) => send({ method: 'PUT', path: `/keys/${enc(k)}${ttl ? `?ttl=${ttl}` : ''}`, body: v });
const SC = [
  { t: 'S1 요청 하나', q: '200 응답이 나간 시점에 이 변경은 ① 메모리에만 ② OS에 넘어감(write) ③ 디스크에 내려감(fsync) 중 어디까지 갔을까?',
    a: '②. 타임라인에서 aof.write가 응답보다 앞, aof.fsync는 응답 뒤(최대 1초)에 있다.',
    run: async () => select((await put('a', '1', 10)).traceId) },
  { t: 'S2 충돌·체이닝', q: '같은 버킷에 세 번째로 들어간 키는 체인의 맨 앞, 맨 뒤 중 어디에 있을까?',
    a: '맨 앞. 새 노드는 table[i] = new Node(key, value, table[i])로 머리에 붙는다.',
    run: async () => {
      await put('a', '1');
      const c = await api(`/api/debug?${qs({ cmd: 'COLLIDE', a1: 'a', a2: 2 })}`);
      let last; for (const k of c.candidates) last = await put(k, 'x');
      S.focusKey = c.candidates.at(-1); select(last.traceId);
    } },
  { t: 'S3 리해싱', q: '버킷 4칸에서 몇 번째 키가 들어갈 때 리해싱이 시작될까?',
    a: '4번째. 키 수 ≥ 버킷 수가 되는 순간 2배(8칸) table[1]을 만들고, 그다음부터 조금씩 옮긴다. 타임라인을 ◀ ▶로 넘기며 rehashIndex를 보자.',
    run: async () => {
      await send({ method: 'DELETE', path: '/keys' });
      for (let i = 1; i <= 3; i++) await put('k' + i);
      select((await put('k4')).traceId);
    } },
  { t: 'S4 TTL 만료', q: 'GET은 읽기 명령이다. GET 때문에 AOF 파일이 커질 수 있을까?',
    a: '그렇다. 만료된 키를 GET하면 그 자리에서 지우고 DEL 레코드를 쓴다(expire.lazy). 이 실험은 expire 틱을 잠깐 멈춰 lazy 만료를 확실히 보여 준다.',
    run: async () => {
      await ticks('expire', false);
      await put('t2', 'bye', 1);
      await sleep(1300);
      const g = await send({ method: 'GET', path: '/keys/t2' });
      await ticks('expire', true);
      await put('t1', 'auto', 1);
      select(g.traceId);
    } },
  { t: 'S5 LRU 축출', q: '상한이 키 5개 분량(710B)일 때 k1~k5를 넣고 k1을 읽은 뒤 k6, k7을 넣으면 누가 지워질까?',
    a: 'k2. GET k1이 k1을 머리로 올려서 꼬리는 k2다. 검사는 쓰기 "전"에 하므로 k6을 넣을 때는 아직 710이라 안 지우고, k7을 넣기 전에 852 > 710이라 k2를 지운다. (데모 데이터를 비우고 maxmemory=710, allkeys-lru로 재시작한다. 다른 실험 전에는 서버 설정에서 maxmemory를 0으로 돌려 놓자)',
    run: async () => {
      $('#setMax').value = 710; $('#setPolicy').value = 'allkeys-lru';
      await restart({ action: 'reset' });
      for (let i = 1; i <= 5; i++) await put('k' + i, 'v');
      await send({ method: 'GET', path: '/keys/k1' });
      await put('k6', 'v');
      select((await put('k7', 'v')).traceId);
    } },
  { t: 'S6 배치', q: '동시에 INCR 50개를 보내면 AOF write는 50번일까, 1번일까, 그 사이일까?',
    a: '그 사이(보통 몇 번). executor가 한 번 꺼낼 때 쌓여 있던 것을 최대 128개까지 묶어 실행하고 write는 배치당 1번이다. ③을 보자.',
    run: async () => { $('#burstMode').value = 'incr'; $('#burstN').value = 50; $('#burstCon').checked = true; $('#key').value = 'counter'; $('#burst').click(); } },
  { t: 'S7 AOF 바이트', q: 'INCR을 3번 하면 AOF에는 어떤 명령이 몇 개 남을까?',
    a: 'SET counter N 3개. INCR 그대로 남기면 복구 때 두 번 실행될 위험이 있어서 결과값 SET(멱등)으로 남긴다. ⑦을 보자.',
    run: async () => {
      await put('greeting', 'hello');
      for (let i = 0; i < 3; i++) await send({ method: 'POST', path: '/keys/counter/incr' });
      await send({ method: 'PUT', path: '/keys/greeting/ttl?seconds=60' });
      select((await send({ method: 'DELETE', path: '/keys/greeting' })).traceId);
      refreshAof();
    } },
  { t: 'S8 재시작 복구', q: '재시작 뒤 같은 키는 같은 버킷 번호에 있을까?',
    a: 'seed가 무작위면 대개 다르다(값·TTL은 같다). seed 고정을 켜면 같다. 설정 창에서 꼬리 자르기·바이트 뒤집기도 해 보자.',
    run: async () => {
      await put('a', '1'); await put('b', '2', 300);
      const before = await api(`/api/debug?${qs({ cmd: 'LOCATE', a1: 'a' })}`);
      await restart({});
      const after = await api(`/api/debug?${qs({ cmd: 'LOCATE', a1: 'a' })}`);
      S.focusKey = 'a'; renderLocate(after, true);
      $('#scenBox').append(h('p', { text: `a의 버킷: 재시작 전 ${before.idx0}번 → 후 ${after.idx0}번 (hash ${before.hash} → ${after.hash})` }));
      S.sel = null; S.cursor = Infinity; $('#showAll').checked = true; render();
    } },
  { t: 'S9 BGSAVE 중', q: 'BGSAVE가 도는 동안 키를 계속 넣으면 버킷 수는 늘어날까?',
    a: '안 늘어난다. 스냅샷 커서가 열려 있는 동안 리해싱·확장이 멈춘다(openCursors). work 틱을 멈춰 그 상태를 붙잡았다. 설정 창에서 work 틱 "1회 실행"을 누르면 스냅샷이 끝나고 미뤄 둔 확장이 시작된다.',
    run: async () => {
      await send({ method: 'DELETE', path: '/keys' });
      await ticks('work', false);
      for (let i = 1; i <= 3; i++) await put('s' + i);
      await send({ method: 'POST', path: '/admin/bgsave' });
      let last; for (let i = 4; i <= 9; i++) last = await put('s' + i);
      select(last.traceId); refreshStats();
    } },
  { t: 'S10 보안 거절', q: '비밀번호가 맞아도 Origin 헤더가 있으면 통과할까? 거절된 요청은 큐에 들어갈까?',
    a: '통과 못 한다(403). 거절된 요청은 핸들러에서 끝나 큐 이벤트가 없다. 보낸 요청 목록에서 하나씩 눌러 보자.',
    run: async () => {
      const tries = [
        { method: 'PUT', path: '/keys/a', body: '1', origin: true },
        { method: 'PUT', path: `/keys/${'x'.repeat(1025)}`, body: '1' },
        { method: 'PUT', path: '/keys/a', preset: '1mb' },
        { method: 'PUT', path: '/keys/a?ttl=0', body: '1' },
        { method: 'GET', path: '/keys/%ff' }, // 올바른 % 표기지만 UTF-8이 아닌 바이트 → 미니 Redis가 400
        { method: 'GET', path: '/nope' },
        { method: 'DELETE', path: '/ping' },
      ];
      if (S.status && S.status.password) tries.push({ method: 'GET', path: '/ping', auth: 'wrong' });
      let last;
      for (const t of tries) {
        try { last = await send(t); } catch (e) { $('#scenBox').append(h('p', { text: `${t.method} ${t.path.slice(0, 30)}: 데모 서버가 거절 (${e.message})` })); }
      }
      $('#showAll').checked = false;
      if (last) select(last.traceId);
    } },
];
$('#scenarios').replaceChildren(...SC.map((s) => h('button', { title: s.t, onclick: () => openScen(s), text: s.t.split(' ')[0] })));
function openScen(s) {
  const box = $('#scenBox');
  box.classList.remove('hidden');
  const ans = h('p', { class: 'hidden', text: `답: ${s.a}` });
  box.replaceChildren(h('p', { class: 'q', text: s.t }), h('p', { text: `예측: ${s.q}` }),
    h('div', { class: 'row' }, h('button', { class: 'primary', text: '실행', onclick: async (ev) => {
      ev.target.disabled = true; r_fast = true;
      try { await s.run(); } catch (e) { box.append(h('p', { text: `오류: ${e.message}` })); }
      r_fast = false; ans.classList.remove('hidden');
    } }), h('button', { text: '답 보기', onclick: () => ans.classList.remove('hidden') })), ans);
}

// 시작
refreshStatus(); refreshStats(); refreshAof(); render(); poll();
