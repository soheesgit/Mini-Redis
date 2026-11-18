// 미니 Redis 흐름 보기 (간단한 화면). 서버가 기록한 이벤트와 상태 사진만 그린다.
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
const B = (x) => h('b', { text: String(x) }); // 사용자 입력은 글자로만 넣는다
async function api(path, opts = {}) {
  const r = await fetch(path, { method: opts.method || 'GET', headers: { 'X-Flow-Demo': '1' }, body: opts.body });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}
const qs = (o) => Object.entries(o).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('&');
const enc = encodeURIComponent;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const STAGES = [
  { id: 'client', ic: '💻', t: '클라이언트', s: '브라우저' },
  { id: 'socket', ic: '🔌', t: '소켓', s: 'TCP 연결 · dispatcher' },
  { id: 'handler', ic: '🧵', t: '핸들러 스레드', s: '요청마다 가상 스레드' },
  { id: 'queue', ic: '📥', t: '명령 큐', s: '여러 스레드 → 한 줄' },
  { id: 'executor', ic: '⚙️', t: 'executor', s: '데이터를 만지는 유일한 스레드' },
  { id: 'memory', ic: '🗃️', t: 'Redis 메모리', s: '해시 테이블' },
  { id: 'aof', ic: '📄', t: 'AOF 파일', s: 'write · 1초마다 fsync' },
  { id: 'resp', ic: '📨', t: '응답', s: '핸들러 → 소켓 → 클라이언트' },
];
const ORDER = Object.fromEntries(STAGES.map((s, i) => [s.id, i]));

const S = { since: 0, events: [], current: null, req: null, frames: [], idx: -1, timer: null, sel: null, focusKey: null };

// ---------------- 입력 ----------------
const CMD = {
  set: { m: 'PUT', p: (k, t) => `/keys/${enc(k)}${t ? `?ttl=${enc(t)}` : ''}`, val: true, ttl: true },
  get: { m: 'GET', p: (k) => `/keys/${enc(k)}` },
  del: { m: 'DELETE', p: (k) => `/keys/${enc(k)}` },
  incr: { m: 'POST', p: (k) => `/keys/${enc(k)}/incr` },
  expire: { m: 'PUT', p: (k, t) => `/keys/${enc(k)}/ttl?seconds=${enc(t || '30')}`, ttl: true },
};
function syncForm() {
  const c = CMD[$('#cmd').value];
  $('#valWrap').classList.toggle('hidden', !c.val);
  $('#ttlWrap').classList.toggle('hidden', !c.ttl);
}
$('#cmd').onchange = syncForm;
syncForm();

async function send(cmd, key, val, ttl, replay = true) {
  const c = CMD[cmd];
  const path = c.p(key, ttl);
  const r = await api(`/api/send?${qs({ method: c.m, path })}`, { method: 'POST', body: c.val ? val : '' });
  S.req = { id: r.traceId, method: c.m, path: decodeURIComponentSafe(path), key, status: r.status, body: r.body };
  S.focusKey = key; S.sel = null;
  await sleep(200);
  await pollOnce();
  if (replay) play(); else { buildFrames(); S.idx = S.frames.length - 1; render(); }
  return r;
}
function decodeURIComponentSafe(s) { try { return decodeURIComponent(s); } catch { return s; } }

$('#form').onsubmit = async (e) => {
  e.preventDefault();
  try { await send($('#cmd').value, $('#key').value.trim() || 'apple', $('#val').value, $('#ttl').value.trim()); } catch (x) { say([`보내기 실패: ${x.message}`]); }
};
$('#many').onclick = async () => {
  const fruits = [['banana', 'yellow'], ['cherry', 'red'], ['grape', 'purple'], ['lemon', 'yellow'], ['mango', 'orange'], ['kiwi', 'green']];
  const sent = [];
  for (const [k, v] of fruits) { await send('set', k, v, '', false); sent.push({ ...S.req }); }
  await sleep(300);
  await pollOnce();
  // 칸이 모자라 리해싱을 일으킨 요청이 있으면 그 요청의 흐름을 재생한다
  const trig = sent.find((q) => S.events.some((e) => e.type === 'rehash.start' && e.table === 'keys' && e.req === q.id));
  if (trig) { S.req = trig; S.focusKey = trig.key; play(); return; }
  say(['키 6개를 넣었다. 리해싱은 일어나지 않았다(칸이 충분했다). 칸을 눌러 각 키가 담긴 모습을 보자.']);
};
$('#reset').onclick = async () => {
  try {
    await api('/api/server?action=reset', { method: 'POST' });
    await skipHistory();
    S.req = null; S.frames = []; S.idx = -1; S.sel = null; S.focusKey = null;
    await refreshCurrent();
    say(['데이터를 모두 지우고 빈 Redis로 다시 시작했다. 버킷 4칸에서 시작한다.']);
    render();
  } catch (x) { say([`초기화 실패: ${x.message}`]); }
};

// ---------------- 이벤트 받기 ----------------
async function skipHistory() { const r = await api(`/api/trace?since=${Number.MAX_SAFE_INTEGER}`); S.since = r.next; S.events = []; }
async function refreshCurrent() { try { S.current = await api('/api/debug?cmd=TABLE'); } catch { /* 재시작 중 */ } }
async function pollOnce() {
  try {
    const r = await api(`/api/trace?since=${S.since}`);
    if (!r.events.length) return false;
    S.events.push(...r.events);
    if (S.events.length > 5000) S.events.splice(0, S.events.length - 5000);
    S.since = r.next;
    const last = [...r.events].reverse().find((e) => e.snap);
    if (last) S.current = last.snap;
    return true;
  } catch { return false; }
}
async function loop() {
  if (await pollOnce() && S.req) { const keepEnd = S.idx >= S.frames.length - 1; buildFrames(); if (keepEnd && !S.timer) S.idx = S.frames.length - 1; render(); }
  setTimeout(loop, 400);
}

// ---------------- 이벤트 → 흐름 단계 ----------------
function relatedEvents(id) {
  const out = [];
  const rehashing = new Set();
  let wrote = false;
  for (const e of S.events) {
    const mine = e.req === id || (e.type === 'batch.start' && e.reqs.includes(id)) || (e.type === 'aof.write' && e.records.some((r) => r.req === id));
    if (mine) {
      out.push(e);
      if (e.type === 'rehash.start') rehashing.add(e.table);
      if (e.type === 'aof.write') wrote = true;
      continue;
    }
    if (rehashing.size && e.type.startsWith('rehash.') && rehashing.has(e.table)) { out.push(e); if (e.type === 'rehash.done') rehashing.delete(e.table); continue; }
    if (wrote && e.type === 'aof.fsync') { out.push(e); wrote = false; }
  }
  return out;
}

function decodeRecord(hex) {
  const b = hex.match(/../g).map((x) => parseInt(x, 16));
  const u32 = (i) => ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0;
  const args = [];
  let p = 5;
  for (let i = 0; i < b[4] && p + 4 <= b.length; i++) { const l = u32(p); args.push(new TextDecoder().decode(new Uint8Array(b.slice(p + 4, p + 4 + l)))); p += 4 + l; }
  return args.join(' ');
}

function buildFrames() {
  const q = S.req;
  if (!q) { S.frames = []; return; }
  const evs = relatedEvents(q.id);
  const first = evs[0];
  const before = first ? [...S.events].filter((e) => e.seq < first.seq).reverse().find((e) => e.snap) : null;
  let snap = before ? before.snap : S.current;
  const F = [];
  const add = (st, text, extra = {}) => F.push({ st, text, snap, ...extra });
  const find = (t) => evs.find((e) => e.type === t);

  add('client', ['브라우저가 ', B(`${q.method} ${q.path}`), ' 요청을 보냈다. (데모 서버가 이 요청을 그대로 미니 Redis로 보낸다)']);
  add('socket', ['요청 바이트가 서버의 ', B('연결 소켓'), '에 도착했다. dispatcher 스레드가 "읽을 데이터가 왔다"를 알아채고, 이 요청을 맡을 핸들러 스레드를 하나 만든다.']);
  add('handler', ['핸들러 스레드가 요청을 읽고 검사한다: Origin 헤더, 인증, 키 길이, 본문 크기. 그리고 HTTP 요청을 Redis 명령으로 바꾼다.']);
  const put = find('queue.put');
  const resp = find('http.respond');
  if (!put) {
    if (resp) add('resp', ['검사에서 거절됐다 → ', B(resp.status), ` ${resp.body}. 큐에는 들어가지 않았다.`], { bad: true });
    S.frames = F; return;
  }
  add('queue', ['핸들러는 데이터를 직접 만지지 않는다. 명령 ', B(`${put.cmd} ${put.key}`), ' 하나와 결과를 받을 빈 상자(future)를 ', B('명령 큐'), '에 넣고, 결과가 올 때까지 잠든다.']);
  const bat = find('batch.start');
  if (bat) add('executor', ['executor 스레드가 큐에서 명령을 꺼냈다', bat.size > 1 ? `(다른 요청 ${bat.size - 1}개와 함께 한 번에)` : '', '. 해시 테이블을 만지는 스레드는 이것 ', B('하나뿐'), '이라 락 없이도 꼬이지 않는다.']);

  for (const e of evs) {
    if (e.type === 'exec.end') {
      if (e.snap) snap = e.snap;
      add('memory', memoryText(e, evs), { loc: e.loc, hot: hotOf(e.loc) });
    } else if (e.type === 'aof.write') {
      const mine = e.records.filter((r) => r.req === q.id);
      add('aof', ['변경 내용을 AOF 레코드 ', B(mine.map((r) => decodeRecord(r.hex)).join(' / ')), `(${mine.reduce((a, r) => a + r.len, 0)}바이트)로 만들어 파일 끝에 붙였다(write). `, B('응답보다 먼저'), ' 쓴다. 아직 OS 메모리에 있고, 디스크로는 1초 안에 fsync가 내린다.']);
    } else if (e.type === 'http.respond') {
      if (!evs.some((x) => x.type === 'aof.write')) add('aof', ['읽기이거나 바뀐 것이 없어서 AOF에 남길 것이 없다.'], { skip: true });
      add('resp', ['executor가 future를 완료하자 잠들어 있던 핸들러가 깨어나 응답(', B(`${e.status} ${e.body || ''}`.trim()), ')을 소켓으로 보냈다. 클라이언트가 응답을 받았다.'], { bad: e.status >= 400 });
    } else if (e.type === 'rehash.step' && e.req === 0) {
      if (e.snap) snap = e.snap;
      add('memory', ['(응답 뒤, 배경에서) work 틱이 옛 칸 ', B(`${e.bucket}번`), `의 키 [${e.moved.join(', ')}]를 새 테이블로 옮겼다. 한 번에 다 옮기지 않고 조금씩 옮겨서 요청이 오래 멈추지 않는다.`], { hot: null, rehash: true });
    } else if (e.type === 'rehash.done' && e.req === 0) {
      if (e.snap) snap = e.snap;
      add('memory', ['리해싱이 끝났다. 새 테이블(', B(`${e.size}칸`), ')이 이제 기본 테이블이 됐다.'], { hot: null });
    } else if (e.type === 'aof.fsync') {
      add('aof', ['fsync 스레드(1초마다)가 파일을 ', B('디스크'), `로 내렸다(${e.us}µs). 이제 전원이 꺼져도 이 변경은 남는다.`]);
    }
  }
  S.frames = F;
}

function hotOf(loc) {
  if (!loc) return null;
  const t = loc.table >= 0 ? loc.table : loc.rehashing ? 1 : 0;
  return { t, i: t === 1 ? loc.idx1 : loc.idx0 };
}

function memoryText(e, evs) {
  const L = e.loc || {};
  const where = L.table >= 0 ? `${L.table === 1 ? '새 테이블 ' : ''}${L.table === 1 ? L.idx1 : L.idx0}번 칸` : `${L.idx0}번 칸`;
  const out = [];
  const err = e.error;
  switch (e.cmd) {
    case 'SET': out.push('키 ', B(e.key), ' → 해시 → ', B(where), '에 넣었다.');
      if (L.chainLen > 1) out.push(` 그 칸에 이미 키가 ${L.chainLen - 1}개 있어서 줄(체인)의 맨 앞에 붙었다 — 이게 해시 충돌이다.`);
      if (L.entry && L.entry.ttlMs >= 0) out.push(' TTL이 있어서 ', B('expires 테이블'), '에도 같은 데이터를 가리키는 항목을 넣었다.');
      break;
    case 'GET': out.push(L.table >= 0 ? ['키 ', B(e.key), ' → 해시 → ', B(where), '으로 바로 가서 찾았다 → 값 ', B(e.result)] : ['키 ', B(e.key), ' → 해시 → ', B(where), '을 봤지만 없다 → (nil)']); break;
    case 'DEL': out.push(e.result === '1' ? ['키 ', B(e.key), ' → ', B(where), '에서 지웠다.'] : ['지울 키가 없었다: ', B(e.key)]); break;
    case 'INCR': out.push(err ? ['INCR 실패: ', B(e.result)] : ['키 ', B(e.key), ' 값 → ', B(e.result), ' (', B(where), ')']); break;
    case 'EXPIRE': out.push(e.result === 'true' ? ['키 ', B(e.key), '에 TTL을 걸었다. ', B('expires 테이블'), '(TTL 있는 키만 모은 곳)에도 등록했다.'] : ['TTL을 걸 키가 없다: ', B(e.key)]); break;
    default: out.push(`${e.cmd} 실행 → ${e.result}`);
  }
  for (const x of evs) {
    if (x.seq > e.seq) break;
    if (x.type === 'rehash.start' && x.req === e.req) out.push(' 키 수가 칸 수에 닿아서 ', B(`칸을 ${x.from} → ${x.to}개로 늘리는 리해싱`), '을 시작했다.');
    if (x.type === 'expire.lazy' && x.req === e.req) out.push(' 찾고 보니 ', B('이미 만료'), '된 키라 그 자리에서 지웠다.');
    if (x.type === 'evict' && x.req === e.req) out.push(' 메모리 상한을 넘어서 가장 오래 안 쓴 키 ', B(x.key), ' 삭제(LRU).');
  }
  return out;
}

// ---------------- 재생 ----------------
function stopAuto() { clearInterval(S.timer); S.timer = null; $('#auto').textContent = '자동 재생'; }
function play() {
  stopAuto();
  buildFrames();
  S.idx = 0; render();
  $('#auto').textContent = '멈춤';
  S.timer = setInterval(() => { if (S.idx >= S.frames.length - 1) return stopAuto(); S.idx++; render(); }, 1300);
}
$('#prev').onclick = () => { stopAuto(); if (S.idx > 0) S.idx--; render(); };
$('#next').onclick = () => { stopAuto(); if (S.idx < S.frames.length - 1) S.idx++; render(); };
$('#auto').onclick = () => (S.timer ? stopAuto() : S.req && play());

// ---------------- 그리기 ----------------
function say(parts) { $('#say').replaceChildren(...parts.flat(Infinity)); }

function render() {
  const f = S.frames[S.idx];
  renderFlow(f);
  if (f) say(f.text);
  $('#stepNo').textContent = S.frames.length ? `${S.idx + 1} / ${S.frames.length}` : '';
  $('#prev').disabled = !S.frames.length || S.idx <= 0;
  $('#next').disabled = !S.frames.length || S.idx >= S.frames.length - 1;
  $('#reqTitle').textContent = S.req ? `요청 #${S.req.id}  ${S.req.method} ${S.req.path}` : '';
  const snap = f ? f.snap : S.current;
  const atEnd = !f || S.idx === S.frames.length - 1;
  $('#memTitle').textContent = f && !atEnd ? `이 단계 시점의 모습 (${S.idx + 1}단계)` : '지금 모습';
  renderGrids(snap, f && f.hot, f && f.rehash);
  renderDetail(snap, f);
}

function renderFlow(f) {
  const cur = f ? ORDER[f.st] : -1;
  const seen = new Set(S.frames.slice(0, S.idx + 1).map((x) => ORDER[x.st]));
  const items = [];
  STAGES.forEach((s, i) => {
    if (i) items.push(h('div', { class: `arr ${seen.has(i) ? 'done' : ''}`, text: '➜' }));
    const cls = ['stage'];
    if (i === cur) cls.push(f.bad ? 'bad' : 'on'); else if (seen.has(i)) cls.push('done');
    if (i === cur && f.skip) cls.push('skip');
    items.push(h('div', { class: cls.join(' ') }, h('div', { class: 'ic', text: s.ic }), h('div', { class: 't', text: s.t }), h('div', { class: 's', text: s.s })));
  });
  $('#flow').replaceChildren(...items);
}

function level(n) { return n === 0 ? '' : n === 1 ? 'g1' : n === 2 ? 'g2' : 'g3'; }

function grid(title, sub, chains, opts) {
  const n = chains.length;
  // 칸 크기는 화면 폭에 맞춘다: 한 줄에 cols칸이 들어가도록 줄이고, 너무 작아지면 줄 수를 늘린다
  const avail = Math.max(200, $('#grids').clientWidth || 600);
  let cols = n <= 8 ? n : n <= 64 ? 8 : 16;
  const fit = (c) => Math.floor((avail - 6 * (c - 1)) / c);
  while (cols > 4 && fit(cols) < 40) cols /= 2;
  const size = Math.min(n <= 8 ? 84 : n <= 16 ? 70 : n <= 32 ? 56 : n <= 64 ? 44 : 26, fit(cols));
  const g = h('div', { class: 'grid' });
  g.style.gridTemplateColumns = `repeat(${cols}, ${size}px)`;
  chains.forEach((chain, i) => {
    const hot = opts.hot && opts.hot.t === opts.t && opts.hot.i === i;
    const sel = S.sel && S.sel.name === opts.name && S.sel.t === opts.t && S.sel.i === i;
    const ttl = chain.length > 0 && chain[0].ttl >= 0; // 칸에 보이는 첫 키 기준
    const cls = ['cell', level(chain.length), hot ? 'hot' : '', sel ? 'sel' : '',
      opts.t === 0 && opts.rehashIndex >= 0 && i < opts.rehashIndex ? 'moved' : '', opts.t === 0 && i === opts.rehashIndex ? 'ptr' : ''];
    const c = h('div', { class: cls.join(' '), title: chain.length ? chain.map((x) => `${x.k} = ${x.v}`).join('\n') : `${i}번 칸: 비어 있음`, onclick: () => pick(opts.name, opts.t, i, chain) },
      h('span', { class: 'no', text: i }),
      size >= 44 && chain.length ? h('span', { class: 'k', text: `${chain[0].k}${ttl ? ' ⏱' : ''}` }) : null,
      size >= 44 && chain.length > 1 ? h('span', { class: 'n', text: `+${chain.length - 1}` }) : null);
    c.style.width = c.style.height = `${size}px`;
    g.append(c);
  });
  return h('div', { class: 'grid-wrap' }, h('h3', {}, title, ' ', h('span', { class: 'dim small', text: sub })), g);
}

function renderGrids(snap, hot, rehashFrame) {
  const box = $('#grids');
  if (!snap || !snap.keys) { box.replaceChildren(h('div', { class: 'dim', text: '불러오는 중…' })); return; }
  const k = snap.keys, x = snap.expires;
  const parts = [];
  if (!k.t0) { box.replaceChildren(h('div', { class: 'dim', text: `칸이 ${k.size0}개라 너무 많아서 그림은 생략한다 (키 ${snap.count}개)` })); return; }
  const rh = k.rehashIndex >= 0;
  parts.push(grid(rh ? 'keys 테이블 — 옛 테이블' : 'keys 테이블', `모든 키 · ${k.size0}칸 · 키 ${k.used0}개`, k.t0, { name: 'keys', t: 0, hot, rehashIndex: k.rehashIndex }));
  if (rh) parts.push(h('div', { class: 'link-arrow', text: '➜' }), grid('keys 테이블 — 새 테이블(2배)', `${k.size1}칸 · 옮겨 온 키 ${k.used1}개`, k.t1, { name: 'keys', t: 1, hot }));
  if (x && x.t0 && (x.used0 + x.used1 > 0)) parts.push(grid('expires 테이블', `TTL 있는 키만 · ${x.size0}칸`, x.t0, { name: 'expires', t: 0, hot: null, rehashIndex: x.rehashIndex }));
  box.replaceChildren(...parts);
  if (rh) box.append(h('div', { class: 'note', text: `리해싱 중: 옛 테이블의 칸을 앞에서부터 하나씩 새 테이블로 옮기는 중이다(흐린 칸 = 옮김 끝). 이 동안 새 키는 새 테이블에 들어가고, 찾을 때는 두 테이블을 다 본다.` }));
  const mem = snap.maxmemory > 0 ? `${snap.memory}/${snap.maxmemory}B` : `${snap.memory}B`;
  $('#memTitle').textContent += ` · 키 ${snap.count}개 · 메모리 ${mem}`;
}

async function pick(name, t, i, chain) {
  S.sel = { name, t, i };
  S.focusKey = chain.length ? chain[0].k : null;
  stopAuto();
  if (S.frames.length) S.idx = S.frames.length - 1; // 칸을 고르면 "지금" 모습으로
  render();
}

let locCache = { key: null, loc: null, at: 0 };
async function liveLocate(key) {
  if (locCache.key === key && Date.now() - locCache.at < 800) return locCache.loc;
  const loc = await api(`/api/debug?${qs({ cmd: 'LOCATE', a1: key })}`);
  locCache = { key, loc, at: Date.now() };
  return loc;
}

async function renderDetail(snap, f) {
  const box = $('#detail');
  let key = S.focusKey;
  let loc = f && f.loc && f.loc.key === key ? f.loc : null;
  if (S.sel && !key) { box.replaceChildren(h('div', { text: `${S.sel.name} 테이블의 ${S.sel.i}번 칸은 비어 있다. 이 칸에 들어갈 키가 생기면 여기에 줄(체인)이 생긴다.` })); $('#detTitle').textContent = ''; return; }
  if (!key) return;
  if (!loc) { try { loc = await liveLocate(key); } catch { return; } }
  if (key !== S.focusKey) return;
  $('#detTitle').textContent = `키 "${key}"`;
  const H = BigInt('0x' + loc.hash); // 해시는 서버가 켜져 있는 동안 키마다 고정
  // 칸 위치는 지금 보고 있는 시점의 상태 사진에서 찾는다(리해싱 중이면 단계마다 다르다)
  let t = loc.table >= 0 ? loc.table : loc.rehashing ? 1 : 0;
  let size = t === 1 ? loc.size1 : loc.size0;
  let idx = t === 1 ? loc.idx1 : loc.idx0;
  const K = snap && snap.keys;
  if (K && K.t0) {
    const tables = [[0, K.t0], [1, K.t1 || []]];
    const hit = tables.find(([, tb]) => tb.some((c) => c.some((n) => n.k === key)));
    const [tt, tb] = hit || (K.size1 ? tables[1] : tables[0]);
    t = tt; size = tb.length; idx = Number(H & BigInt(size - 1));
  }
  const bits = Math.round(Math.log2(size));
  const bin = H.toString(2).padStart(64, '0');
  const calc = h('div', { class: 'calc' },
    h('span', { class: 'lbl', text: '① 키' }), h('span', {}, B(key), ` → 바이트로 바꾸면 ${new TextEncoder().encode(key).length}바이트`),
    h('span', { class: 'lbl', text: '② 해시' }), h('span', {}, 'SipHash(키, 서버 시작 때 뽑은 비밀 seed) = ', h('span', { class: 'mono', text: `0x${loc.hash}` })),
    h('span', { class: 'lbl', text: '③ 칸 번호' }), h('div', {},
      h('div', { class: 'bits' }, bin.slice(0, 64 - bits), h('span', { class: 'low', text: bin.slice(64 - bits) })),
      h('div', {}, `칸이 ${size}개라 해시의 `, B(`아래 ${bits}비트`), `만 쓴다 (hash & ${size - 1}) → `, B(`${idx}번 칸`), t === 1 ? ' (리해싱 중이라 새 테이블)' : '')));
  // 그 칸의 줄(체인)
  const tb = snap && snap.keys ? (t === 1 ? snap.keys.t1 : snap.keys.t0) : null;
  const chain = tb && tb[idx] ? tb[idx] : [];
  const line = h('div', { class: 'chainline' },
    h('div', { class: 'box' }, h('div', { class: 'bt', text: `keys 테이블` }), h('div', { class: 'f', text: `칸[${idx}]` })),
    chain.flatMap((nd) => [h('span', { class: 'link-arrow', text: '→' }),
      h('div', { class: `box ${nd.k === key ? 'focus' : ''}` }, h('div', { class: 'bt', text: 'Node' }), h('div', { class: 'f', text: `key: "${nd.k}"` }), h('div', { class: 'f dim', text: 'value: → Entry' }))]),
    h('span', { class: 'link-arrow', text: '→' }), h('span', { class: 'dim mono', text: 'null' }));
  const parts = [calc, h('div', { class: 'small dim', text: `${idx}번 칸에 달린 줄(체인). 새 키는 줄의 맨 앞에 붙는다.` }), line];
  const en = chain.some((n) => n.k === key) ? loc.entry : null; // 이 시점에 아직(또는 이미) 없으면 Entry도 없다
  if (en) {
    const ttl = en.ttlMs >= 0;
    let expIdx = null;
    if (ttl && snap && snap.expires) expIdx = Number(H & BigInt(snap.expires.size0 - 1));
    parts.push(h('div', { class: 'entry' }, h('div', { class: 'small dim', text: `"${key}"의 Node가 가리키는 Entry (실제 데이터)` }),
      h('div', { class: 'box focus' }, h('table', {},
        h('tr', {}, h('td', { text: 'key' }), h('td', {}, `"${key}"  (${en.keyBytes}바이트)`)),
        h('tr', {}, h('td', { text: 'value' }), h('td', {}, `"${en.value}"  (${en.valueLen}바이트: `, h('span', { class: 'mono', text: en.valueHex.replace(/(..)(?=.)/g, '$1 ') }), ')')),
        h('tr', {}, h('td', { text: 'expireAt' }), h('td', { text: ttl ? `${(en.ttlMs / 1000).toFixed(1)}초 뒤 만료` : '0 (TTL 없음)' })),
        h('tr', {}, h('td', { text: '메모리' }), h('td', { text: `${en.keyBytes} + ${en.valueLen} + 139(고정비)${ttl ? ' + 32(TTL)' : ''} = ${en.cost}바이트` })),
        h('tr', {}, h('td', { text: 'LRU' }), h('td', { text: `최근 쓴 순서 ${en.lruRank + 1}번째 (메모리가 꽉 차면 뒤에서부터 지운다)` }))))));
    parts.push(h('div', { class: 'sub' }, ttl
      ? ['TTL이 있어서 ', B(`expires 테이블${expIdx != null ? `의 ${expIdx}번 칸` : ''}`), '에도 Node가 하나 더 있고, 그 Node도 ', B('같은 Entry'), '를 가리킨다. 데이터는 1벌이고 가리키는 줄만 2개다. 만료 청소가 100ms마다 TTL 키만 골라 뽑으려고 따로 모아 둔다.']
      : ['TTL이 없어서 keys 테이블에만 있다. ', B('expires 테이블'), '은 TTL이 있는 키만 따로 가리키는 목록이라 여기엔 없다.']));
  } else {
    parts.push(h('div', { class: 'sub', text: `"${key}"는 지금 Redis에 없다. 넣으면 ${idx}번 칸 줄의 맨 앞에 붙는다.` }));
  }
  box.classList.remove('dim');
  box.replaceChildren(...parts);
}

async function refreshServer() {
  try {
    const s = await api('/api/server');
    $('#srv').textContent = s.running ? `미니 Redis 실행 중 · seed ${s.fixedSeed ? '고정' : '무작위'}` : `멈춤: ${s.error}`;
    $('#srv').className = `chip ${s.running ? 'ok' : 'bad'}`;
  } catch (e) { $('#srv').textContent = e.message; }
}

let resizeTimer;
window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(render, 150); });

// 시작: 지난 기록은 건너뛰고 지금 상태부터
(async () => {
  await skipHistory();
  await refreshCurrent();
  await refreshServer();
  render();
  loop();
})();
