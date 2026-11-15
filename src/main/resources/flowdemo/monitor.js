// 관제 화면. 서버를 지나간 모든 요청과 서버 내부 작업을 "장면"으로 만들어 한 줄로 세우고,
// 재생 시계 하나로 한 번에 하나씩 보여 준다(서버 실행 순서 = 화면 순서). 실제 처리는 µs 단위라 사람이 볼 수 있게 늦춘다.
const $ = (s) => document.querySelector(s);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
async function api(path) {
  const r = await fetch(path, { headers: { 'X-Flow-Demo': '1' } });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}
const MOVE = 450, HOLD = 750, BACK = 600; // 1× 기준: 이동 0.45초 + 멈춤 0.75초

// ---------------- 재생 시계: 일시정지 · 속도 · 한 단계 ----------------
const P = { paused: false, speed: 1, step: false };
async function wait(ms) {
  let left = ms;
  for (;;) {
    if (P.paused) { if (P.step) { P.step = false; return; } await sleep(40); continue; }
    if (left <= 0) return;
    await sleep(40); left -= 40 * P.speed;
  }
}
function setPaused(v) { P.paused = v; $('#bPlay').textContent = v ? '▶ 재생' : '⏸ 일시정지'; $('#cap').classList.toggle('is-paused', v); }
function setSpeed(s) { P.speed = s; document.querySelectorAll('[data-speed]').forEach((b) => b.classList.toggle('on', +b.dataset.speed === s)); }
$('#bPlay').onclick = () => setPaused(!P.paused);
$('#bStep').onclick = () => { if (!P.paused) setPaused(true); else P.step = true; };
document.querySelectorAll('[data-speed]').forEach((b) => (b.onclick = () => setSpeed(+b.dataset.speed)));
addEventListener('keydown', (e) => {
  if (e.target.tagName === 'INPUT') return;
  if (e.code === 'Space') { e.preventDefault(); $('#bPlay').click(); }
  if (e.code === 'ArrowRight') { e.preventDefault(); $('#bStep').click(); }
  const sp = { Digit1: 0.5, Digit2: 1, Digit3: 2, Digit4: 4 }[e.code]; if (sp) setSpeed(sp);
});

// ---------------- 무대: 상자와 선 ----------------
const stage = $('#stage'), flow = $('#flowside'), wires = $('#wires');
const NODES = [
  ['CLIENT', '클라이언트', '요청 출발'], ['SOCKET', '소켓', 'TCP · dispatcher'], ['HANDLER', '핸들러 스레드', '요청마다 1개'],
  ['QUEUE', '명령 큐', '한 줄로 세움'], ['EXECUTOR', 'executor', '데이터를 만지는 유일한 스레드'],
];
const node = {};
NODES.forEach(([id, nm, sb], i) => {
  const n = el('div', 'node'); n.style.left = `${10 + i * 20}%`; n.style.top = '42%';
  n.append(el('div', 'nm', nm), el('div', 'sb', sb), el('span', 'occ', ''));
  if (id === 'QUEUE') n.append(el('div', 'qdots'));
  flow.append(n); node[id] = n;
});
const waitDots = el('div', 'waitdots'); flow.append(waitDots);
function rel(elm) { const r = elm.getBoundingClientRect(), s = stage.getBoundingClientRect(); return { x: r.left - s.left, y: r.top - s.top, w: r.width, h: r.height, cx: r.left - s.left + r.width / 2, cy: r.top - s.top + r.height / 2 }; }
const W = {};
function layout() {
  wires.textContent = '';
  const NS = 'http://www.w3.org/2000/svg';
  const line = (id, cls, x1, y1, x2, y2) => { const l = document.createElementNS(NS, 'line'); Object.entries({ x1, y1, x2, y2 }).forEach(([k, v]) => l.setAttribute(k, v)); l.setAttribute('class', cls); wires.append(l); W[id] = l; };
  const ids = NODES.map((n) => n[0]);
  for (let i = 0; i + 1 < ids.length; i++) { const a = rel(node[ids[i]]), b = rel(node[ids[i + 1]]); line(`${ids[i]}>${ids[i + 1]}`, 'req', a.x + a.w, a.cy, b.x, b.cy); }
  const ex = rel(node.EXECUTOR), mem = rel($('#memBox')), aof = rel($('#aofBox')), cl = rel(node.CLIENT);
  line('EX>MEM', 'req', ex.x + ex.w, ex.cy, mem.x, mem.y + 40);
  line('EX>AOF', 'req', ex.x + ex.w, ex.cy + 10, aof.x, aof.cy);
  const ry = ex.y + ex.h + 22;
  line('RESP1', 'resp', ex.cx, ex.y + ex.h, ex.cx, ry);
  line('RESP2', 'resp', ex.cx, ry, cl.cx, ry);
  line('RESP3', 'resp', cl.cx, ry, cl.cx, cl.y + cl.h);
  waitDots.style.left = `${cl.x - 8}px`; waitDots.style.top = `${cl.cy}px`;
}
function lit(ids) { Object.values(W).forEach((l) => l.classList.remove('on')); ids.forEach((i) => W[i] && W[i].classList.add('on')); }
function activate(ids, bad = false) {
  Object.values(node).forEach((n) => n.classList.remove('active', 'bad'));
  ['#memBox', '#aofBox'].forEach((s) => $(s).classList.remove('active'));
  ids.forEach((i) => { const n = node[i] || $(i); if (n) { n.classList.add('active'); if (bad) n.classList.add('bad'); } });
}

// 토큰: 포커스 = 이름표 알약, 그 밖 = 점
class Tok {
  constructor(label, cls) { this.e = el('div', label ? `tok focus ${cls || ''}` : `tok dot ${cls || ''}`, label || null); stage.append(this.e); }
  async to(p, ms = MOVE, hold = 0) {
    this.e.style.transitionDuration = `${ms / P.speed}ms`;
    this.e.style.left = `${Math.max(60, p.x)}px`; this.e.style.top = `${p.y}px`;
    await wait(ms); if (hold) await wait(hold);
  }
  at(p) { this.e.style.transitionDuration = '0ms'; this.e.style.left = `${Math.max(60, p.x)}px`; this.e.style.top = `${p.y}px`; }
  kind(cls) { this.e.className = `tok focus ${cls}`; }
  done() { this.e.classList.add('gone'); setTimeout(() => this.e.remove(), 500); }
}
const above = (id) => { const r = rel(node[id]); return { x: r.cx, y: r.y - 18 }; };

// ---------------- 설명 띠 · 기록 ----------------
const STEPS = ['출발', '핸들러 검사', '명령 큐', 'executor', '메모리', 'AOF', '응답'];
function cap(t1, t2, cur = -1, states = []) {
  $('#t1').textContent = t1; $('#t2').textContent = t2;
  $('#prog').replaceChildren(...STEPS.map((s, i) => { const x = el('span', states[i] || (i < cur ? 'done' : i === cur ? 'cur' : '')); x.title = s; return x; }));
}
function steps(title, rows) {
  $('#steps').replaceChildren(el('div', 'h', title), ...rows.map(([st, name, t, d]) => {
    const r = el('div', `row ${st}`); r.append(el('span', null, { done: '✓', cur: '▶', bad: '✕', skip: '–' }[st] || '○'), el('span', null, name), el('span', null, t), el('span', null, d)); return r;
  }));
}
function logReq(cls, text) { const d = el('div', cls, text); $('#reqlog').append(d); $('#reqlog').scrollTop = 1e9; while ($('#reqlog').childElementCount > 200) $('#reqlog').firstChild.remove(); }

// ---------------- 저장소 ----------------
const S = { since: 0, events: [], byReq: new Map(), staging: new Map(), sysStage: [], Q: [], drawn: 0, aof: [], total: 0, fails: 0, aofBytes: 0, latest: null, expKeys: [], expRecs: [] };
function drawStore(snap, touched = [], seq = 0, force = false) {
  if (!snap || !snap.keys) return;
  if (!force && seq && seq < S.drawn) return;
  if (seq) S.drawn = seq;
  const K = snap.keys, box = $('#cells');
  const avail = Math.max(160, $('#memBox').clientWidth - 24);
  const table = (title, chains, rehashIndex, tag) => {
    const n = chains.length;
    let cols = Math.min(n, n <= 16 ? 8 : 16);
    const fit = (c) => Math.floor((avail - 5 * (c - 1)) / c);
    while (cols > 4 && fit(cols) < 46) cols /= 2;
    const w = Math.min(n <= 16 ? 78 : 54, fit(cols)), hgt = n <= 16 ? 52 : 38;
    const t = el('div', 'tbl'); t.append(el('h4', null, title));
    const cells = el('div', 'cells');
    chains.forEach((c, i) => {
      const keys = c.map((x) => x.k);
      const cell = el('div', `cell ${c.length ? 'full' : ''} ${rehashIndex >= 0 && i < rehashIndex ? 'moved' : ''} ${keys.some((k) => touched.includes(k)) ? 'pulse' : ''}`);
      cell.dataset.cell = `${tag}:${i}`;
      cell.style.width = `${w}px`; cell.style.height = `${hgt}px`;
      cell.title = c.map((x) => `${x.k} = ${x.v}`).join('\n') || `${i}번 칸: 비어 있음`;
      cell.append(el('span', 'i', i));
      if (c.length && w >= 44) cell.append(el('span', 'k', c[0].k));
      if (c.length > 1) cell.append(el('span', 'n', `+${c.length - 1}`));
      cells.append(cell);
    });
    t.append(cells); return t;
  };
  if (!K.t0) { box.replaceChildren(el('div', 'empty', `칸이 ${K.size0}개라 그림 생략 (키 ${snap.count}개)`)); }
  else {
    const parts = [table(K.size1 ? `keys 테이블 · 옛 ${K.size0}칸 (새 테이블로 옮기는 중, 흐린 칸 = 옮김 끝)` : `keys 테이블 · ${K.size0}칸 · 모든 키`, K.t0, K.rehashIndex, 'k0')];
    if (K.size1) parts.push(table(`keys 테이블 · 새 ${K.size1}칸 (2배)`, K.t1, -1, 'k1'));
    const X = snap.expires;
    if (X && X.t0 && X.used0 + X.used1 > 0) parts.push(table('expires 테이블 · TTL 있는 키만 같은 데이터를 한 번 더 가리킴', X.t0, X.rehashIndex, 'x0'));
    box.replaceChildren(...parts);
  }
  $('#memInfo').textContent = `키 ${snap.count}개 · ${snap.memory}B${snap.maxmemory > 0 ? ` / ${snap.maxmemory}B` : ''}`;
  $('#sKeys').textContent = snap.count; $('#sMem').textContent = `${snap.memory}B`;
}
function addAof(texts) { texts.forEach((t) => S.aof.push({ t, fresh: true })); drawAof(); }
function drawAof() {
  const list = S.aof.slice(-40);
  $('#aof').replaceChildren(...(list.length ? list.map((x) => el('span', `rec ${x.fresh ? 'new' : ''}`, x.t)) : [el('span', 'empty', '비어 있음')]));
  S.aof.forEach((x) => (x.fresh = false));
}

// ---------------- 이벤트 → 장면 ----------------
async function poll() {
  try {
    const r = await api(`/api/trace?since=${S.since}`);
    if (r.events.length) { S.since = r.next; r.events.forEach(onEvent); }
  } catch { /* 재시작 중 */ }
  setTimeout(poll, 120);
}
function idx(e, id) { if (!S.byReq.has(id)) S.byReq.set(id, []); S.byReq.get(id).push(e); }
function onEvent(e) {
  S.events.push(e); if (S.events.length > 8000) S.events.splice(0, 2000);
  if (e.snap) S.latest = e;
  if (e.req > 0) idx(e, e.req);
  if (e.type === 'batch.start') e.reqs.forEach((id) => idx(e, id));
  if (e.type === 'aof.write') { new Set(e.records.map((r) => r.req)).forEach((id) => id > 0 && idx(e, id)); S.aofBytes = e.offset + e.records.reduce((a, r) => a + r.len, 0); $('#sAof').textContent = `${S.aofBytes}B`; }
  if (e.type === 'http.recv') { S.total++; S.staging.set(e.req, Date.now()); $('#sReq').textContent = S.total; }
  if (e.type === 'http.respond' && e.status >= 400) $('#sFail').textContent = ++S.fails;
  if (e.req !== 0) return;
  const now = Date.now();
  if (e.type === 'aof.fsync' && e.bytes > 0) S.sysStage.push({ seq: e.seq, at: now, kind: 'fsync', e });
  if (e.type === 'rehash.step' || e.type === 'rehash.done') S.sysStage.push({ seq: e.seq, at: now, kind: e.type, e });
  if (e.type === 'expire.active') S.expKeys.push(e.key);
  if (e.type === 'aof.write' && e.records.every((r) => r.req === 0)) S.expRecs.push(...e.records.map((r) => recText(r.hex)));
  if (e.type === 'tick.expire' && S.expKeys.length) { S.sysStage.push({ seq: e.seq, at: now, kind: 'expire', e, keys: S.expKeys, recs: S.expRecs }); S.expKeys = []; S.expRecs = []; }
  if (e.type === 'server.start') S.sysStage.push({ seq: e.seq, at: now, kind: 'start', e });
}
// 응답까지 도착한 요청과 0.25초 지난 서버 작업만 재생 줄에 넣고, 서버 순서(seq)대로 정렬한다
setInterval(() => {
  const now = Date.now();
  for (const [id, at] of S.staging) {
    const ev = S.byReq.get(id) || [];
    const resp = ev.find((e) => e.type === 'http.respond');
    if (resp || now - at > 4000) {
      S.staging.delete(id);
      const end = ev.find((e) => e.type === 'exec.end');
      S.Q.push({ kind: 'req', id, seq: (end || resp || ev[0]).seq, ev });
    }
  }
  S.sysStage = S.sysStage.filter((s) => { if (now - s.at > 250) { S.Q.push(s); return false; } return true; });
  S.Q.sort((a, b) => a.seq - b.seq);
  const n = S.Q.filter((s) => s.kind === 'req').length;
  $('#waitN').textContent = n ? `대기 ${n}건` : '';
  waitDots.replaceChildren(...Array.from({ length: Math.min(n, 10) }, () => el('i')), n > 10 ? el('span', null, `+${n - 10}`) : '');
}, 100);

$('#bLive').onclick = () => {
  S.Q = []; S.staging.clear(); S.sysStage = [];
  if (S.latest) drawStore(S.latest.snap, [], S.latest.seq, true);
  cap('실시간으로 건너뜀', '밀려 있던 재생을 버리고 지금 서버 상태를 보여 준다');
};

// ---------------- 재생 ----------------
async function player() {
  for (;;) {
    const sc = S.Q.shift();
    if (!sc) { await sleep(80); continue; }
    try {
      if (sc.kind !== 'req') await playSys(sc);
      else {
        const group = [sc];
        if (!$('#oSolo').checked) {
          const bat = sc.ev.find((e) => e.type === 'batch.start');
          // 묶는 경우: 같은 배치로 실행된 요청, 또는 같은 명령·같은 키가 3개 이상 밀려 있을 때(동시 INCR 같은 폭주)
          const put = sc.ev.find((e) => e.type === 'queue.put') || {};
          const inBatch = (s) => bat && bat.reqs.length > 1 && bat.reqs.includes(s.id);
          const same = (s) => { const p = s.ev.find((e) => e.type === 'queue.put') || {}; return put.cmd && p.cmd === put.cmd && p.key === put.key; };
          const burst = S.Q.filter((s) => s.kind === 'req' && same(s)).length >= 2;
          while (S.Q.length && S.Q[0].kind === 'req' && group.length < 60) {
            if (inBatch(S.Q[0]) || (burst && same(S.Q[0]))) group.push(S.Q.shift()); else break;
          }
        }
        if (group.length > 1) await playBundle(group); else await playOne(sc);
      }
    } catch (x) { console.error(x); }
    activate([]); lit([]);
  }
}

function info(ev) {
  const g = (t) => ev.find((e) => e.type === t);
  const o = { recv: g('http.recv'), put: g('queue.put'), bat: g('batch.start'), end: g('exec.end'), resp: g('http.respond') };
  const w = g('aof.write'); o.w = w;
  o.recs = w && o.recv ? w.records.filter((r) => r.req === o.recv.req) : [];
  o.us = (e) => (e && o.recv ? `+${Math.round((e.t - o.recv.t) / 1000)}µs` : '');
  return o;
}
function cellOf(loc) {
  if (!loc) return null;
  const t = loc.table >= 0 ? loc.table : loc.rehashing ? 1 : 0;
  const i = t ? loc.idx1 : loc.idx0;
  return document.querySelector(`[data-cell="k${t}:${i}"]`);
}
function memText(end) {
  const L = end.loc || {}; const t = L.table >= 0 ? L.table : 0; const i = t ? L.idx1 : L.idx0; const size = t ? L.size1 : L.size0;
  const calc = L.hash ? `hash …${L.hash.slice(-4)} & ${size - 1} = 칸 ${i}` : `칸 ${i}`;
  if (end.error) return [`${end.cmd} ${end.key}: 실패 — ${end.result}`, '명령 실행 중 오류. 데이터는 바뀌지 않는다'];
  switch (end.cmd) {
    case 'SET': return [`${end.key} 저장 → ${t ? '새 테이블 ' : ''}칸 ${i}${L.chainLen > 1 ? ` (이미 ${L.chainLen - 1}개 → 줄 맨 앞, 충돌)` : ''}`, `SipHash로 키를 해시하고 아래 비트만 써서 칸을 고른다: ${calc}`];
    case 'GET': return L.table >= 0 ? [`${end.key} 조회 → 칸 ${i}에서 찾음: "${end.result}"`, `같은 계산(${calc})으로 칸을 바로 찾아간다. 줄을 따라가며 키를 비교`] : [`${end.key} 조회 → 칸 ${i}에 없음`, `계산한 칸(${calc})의 줄에 그 키가 없다 → 404`];
    case 'DEL': return [end.result === '1' ? `${end.key} 삭제 → 칸 ${i}에서 뺌` : `${end.key} 삭제 → 없음`, '삭제도 AOF에 DEL로 남아야 재시작 때 되살아나지 않는다'];
    case 'INCR': return [`${end.key} 값 → ${end.result} (칸 ${i})`, 'AOF에는 INCR이 아니라 결과값 SET으로 남긴다(다시 실행해도 같은 결과)'];
    case 'EXPIRE': return [`${end.key} TTL 설정${end.result === 'true' ? '' : ' 실패(키 없음)'}`, 'TTL 키는 expires 테이블에도 등록된다. 만료 청소가 TTL 키만 골라 보려고'];
    default: return [`${end.cmd} → ${end.result}`, ''];
  }
}
const respPath = async (t) => {
  const ex = rel(node.EXECUTOR), cl = rel(node.CLIENT), ry = ex.y + ex.h + 22;
  await t.to({ x: ex.cx, y: ry }, BACK / 3); await t.to({ x: cl.cx, y: ry }, BACK / 2); await t.to(above('CLIENT'), BACK / 6);
};

async function playOne(sc) {
  const o = info(sc.ev);
  if (!o.recv) return;
  const id = sc.id, r = o.recv;
  const name = `#${id} ${o.put ? `${o.put.cmd} ${o.put.key}` : `${r.method} ${decodeSafe(r.path)}`}`;
  const st = Array(7).fill('');
  const rows = () => steps(`요청 ${name} · 서버가 실제로 걸린 시간 (요청 도착 기준)`, [
    [st[0] || 'cur', '출발 · 소켓', '+0µs', `${r.method} ${decodeSafe(r.path)}`], [st[1], '핸들러 검사', o.us(o.put || o.resp), o.put ? 'Origin·인증·키 통과' : o.resp ? `거절 ${o.resp.status}` : ''],
    [st[2], '명령 큐', o.us(o.put), o.put ? `앞에 ${o.put.queueBefore}개` : ''], [st[3], 'executor', o.us(o.bat), o.bat ? `배치 #${o.bat.batch}` : ''],
    [st[4], '메모리', o.us(o.end), o.end ? memText(o.end)[0] : ''], [st[5], 'AOF write', o.us(o.w), o.recs.map((x) => recText(x.hex)).join(' / ') || '쓸 것 없음'],
    [st[6], '응답', o.us(o.resp), o.resp ? `${o.resp.status}` : ''],
  ]);
  const t = new Tok(name);
  t.at(above('CLIENT')); activate(['CLIENT']);
  cap(`${name} · 출발`, `${r.method} ${decodeSafe(r.path)} 요청 바이트가 TCP 연결 소켓으로 들어간다`, 0, st); rows();
  lit(['CLIENT>SOCKET']); activate(['SOCKET']); await t.to(above('SOCKET'), MOVE, HOLD); st[0] = 'done';
  lit(['SOCKET>HANDLER']); activate(['HANDLER']);
  cap(`${name} · 핸들러 검사`, 'dispatcher가 요청마다 가상 스레드 하나를 붙인다. Origin·인증·키 길이를 여기서 검사한다', 1, st); rows();
  await t.to(above('HANDLER'), MOVE);
  if (!o.put) {
    st[1] = 'bad'; t.kind('bad'); activate(['HANDLER'], true); rows();
    cap(`${name} · 거절 ${o.resp ? o.resp.status : ''}`, `${o.resp ? o.resp.body : ''} — 큐에 들어가지 않고 핸들러에서 끝난다`, 1, st);
    if ($('#oAuto').checked) setPaused(true);
    await wait(HOLD);
    lit(['RESP2', 'RESP3']); const cl = rel(node.CLIENT);
    await t.to({ x: cl.cx, y: cl.y + cl.h + 22 }, BACK, HOLD / 2);
    logReq('no', `${name} → ${o.resp ? o.resp.status : '?'} 거절`); t.done(); return;
  }
  await wait(HOLD); st[1] = 'done';
  lit(['HANDLER>QUEUE']); activate(['QUEUE']);
  cap(`${name} · 명령 큐`, `핸들러는 데이터를 직접 만지지 않는다. [${o.put.cmd} ${o.put.key}]와 빈 future를 큐에 넣고 잠든다 (앞에 ${o.put.queueBefore}개)`, 2, st); rows();
  await t.to(above('QUEUE'), MOVE, HOLD); st[2] = 'done';
  lit(['QUEUE>EXECUTOR']); activate(['EXECUTOR']);
  cap(`${name} · executor가 꺼냄`, `데이터를 만지는 스레드는 이것 하나뿐이라 락이 필요 없다${o.bat && o.bat.size > 1 ? ` (이번 배치 ${o.bat.size}개)` : ''}`, 3, st); rows();
  await t.to(above('EXECUTOR'), MOVE, HOLD); st[3] = 'done';
  if (o.end) {
    const [t1, t2] = memText(o.end);
    const extra = [];
    let stop = false;
    for (const x of sc.ev) {
      if (x.type === 'rehash.start') { extra.push(`칸이 모자라 리해싱 시작 ${x.from}→${x.to}칸`); stop = true; }
      if (x.type === 'evict') { extra.push(`메모리 초과 → ${x.key} 축출(LRU)`); stop = true; }
      if (x.type === 'expire.lazy') extra.push('이미 만료된 키 → 그 자리에서 삭제');
    }
    lit(['EX>MEM']); activate(['#memBox']);
    cap(`${name} · ${t1}`, extra.length ? extra.join(' · ') : t2, 4, st); rows();
    const target = cellOf(o.end.loc);
    const p = target ? rel(target) : rel($('#memBox'));
    await t.to({ x: p.cx, y: p.y - 14 }, MOVE);
    drawStore(o.end.snap, [o.end.key], o.end.seq);
    if (stop && $('#oAuto').checked) setPaused(true);
    await wait(HOLD); st[4] = o.end.error ? 'bad' : 'done';
  }
  if (o.recs.length) {
    lit(['EX>AOF']); activate(['#aofBox']);
    const txt = o.recs.map((x) => recText(x.hex)).join(' / ');
    cap(`${name} · AOF write ${o.recs.reduce((a, x) => a + x.len, 0)}B`, `"${txt}" 를 파일 끝에 붙인다. 응답보다 먼저 쓴다(디스크로는 1초마다 fsync)`, 5, st); rows();
    const a = rel($('#aof'));
    await t.to({ x: a.x + 60, y: a.y + 10 }, MOVE);
    addAof(o.recs.map((x) => recText(x.hex)));
    await wait(HOLD); st[5] = 'done';
  } else {
    st[5] = 'skip'; cap(`${name} · AOF`, '읽기이거나 바뀐 것이 없어서 파일에 쓸 것이 없다', 5, st); rows(); await wait(HOLD / 2);
  }
  const bad = o.resp && o.resp.status >= 400 && o.end && o.end.error;
  t.kind(bad ? 'bad' : 'ret');
  lit(['RESP1', 'RESP2', 'RESP3']); activate(['CLIENT']);
  cap(`${name} · 응답 ${o.resp ? o.resp.status : ''}`, `AOF 기록이 끝난 뒤 executor가 future를 완료 → 잠들어 있던 핸들러가 깨어나 ${o.resp ? o.resp.body : ''} 를 보낸다`, 6, st); rows();
  await respPath(t);
  st[6] = bad ? 'bad' : 'done'; rows();
  cap(`${name} · 완료 ${o.resp ? o.resp.status : ''}`, `서버 안에서 실제로 걸린 시간 ${o.us(o.resp).replace('+', '')} (화면은 사람이 볼 수 있게 늦춘 재생)`, 7, st);
  logReq(o.resp && o.resp.status >= 400 ? 'no' : 'ok', `${name} → ${o.resp ? o.resp.status : '?'}${o.end && o.end.loc ? ` · 칸 ${o.end.loc.table === 1 ? o.end.loc.idx1 : o.end.loc.idx0}` : ''} · ${o.us(o.resp).replace('+', '')}`);
  await wait(HOLD / 2); t.done();
}

// 여러 요청이 몰리면 묶음 토큰 하나로: 큐까지 함께 가고, executor가 하나씩 꺼내 메모리에 넣는 것을 빠르게 반복해 보여 준다
async function playBundle(group) {
  const infos = group.map((s) => ({ id: s.id, o: info(s.ev) })).filter((x) => x.o.recv);
  if (!infos.length) return;
  const n = infos.length, f = infos[0].o;
  const cmd = f.put ? `${f.put.cmd} ${f.put.key}` : f.recv.method;
  const name = `×${n} ${cmd}${new Set(infos.map((x) => x.o.put && x.o.put.key)).size > 1 ? ' 등' : ''}`;
  const t = new Tok(name);
  const st = Array(7).fill('');
  t.at(above('CLIENT')); activate(['CLIENT']);
  cap(`${name} · 요청 ${n}개가 한꺼번에 도착`, '여러 클라이언트(스레드)가 동시에 보낸 요청이다. 한 덩어리로 묶어 보여 준다', 0, st);
  lit(['CLIENT>SOCKET']); activate(['SOCKET']); await t.to(above('SOCKET'), MOVE, HOLD / 2); st[0] = 'done';
  lit(['SOCKET>HANDLER']); activate(['HANDLER']);
  cap(`${name} · 핸들러 ${n}개`, `요청마다 가상 스레드가 하나씩, 총 ${n}개가 동시에 검사한다`, 1, st);
  await t.to(above('HANDLER'), MOVE, HOLD / 2); st[1] = 'done';
  lit(['HANDLER>QUEUE']); activate(['QUEUE']);
  const qd = node.QUEUE.querySelector('.qdots'); qd.replaceChildren(...infos.map(() => el('i')));
  cap(`${name} · 명령 큐에 ${n}개가 한 줄로`, '여러 스레드가 보낸 명령이 큐 하나로 모인다. 여기서부터는 한 줄이다', 2, st);
  await t.to(above('QUEUE'), MOVE, HOLD); st[2] = 'done';
  lit(['QUEUE>EXECUTOR', 'EX>MEM']); activate(['EXECUTOR', '#memBox']);
  cap(`${name} · executor가 하나씩 꺼내 처리`, '동시에 처리하는 것이 아니라, 스레드 하나가 순서대로 하나씩 실행한다', 3, st);
  await t.to(above('EXECUTOR'), MOVE, HOLD / 2); st[3] = 'done';
  const per = Math.max(90, Math.min(300, 4000 / n));
  let i = 0;
  for (const { id, o } of infos) {
    i++;
    if (qd.firstChild) qd.firstChild.remove();
    if (!o.end) continue;
    const [t1] = memText(o.end);
    cap(`#${id} ${t1}  (${i}/${n})`, `executor가 큐에서 하나 꺼내 실행 → 메모리 → AOF. 나머지 ${n - i}개는 큐에서 기다린다`, 4, st);
    const d = new Tok(null); d.at(above('EXECUTOR'));
    const target = cellOf(o.end.loc); const p = target ? rel(target) : rel($('#memBox'));
    await d.to({ x: p.cx, y: p.cy }, per * 0.7);
    d.done();
    drawStore(o.end.snap, [o.end.key], o.end.seq);
    if (o.recs.length) addAof(o.recs.map((x) => recText(x.hex)));
    await wait(per * 0.3);
  }
  qd.replaceChildren();
  st[4] = 'done'; st[5] = infos.some((x) => x.o.recs.length) ? 'done' : 'skip';
  const codes = {}; infos.forEach((x) => { const c = x.o.resp ? x.o.resp.status : '?'; codes[c] = (codes[c] || 0) + 1; });
  const codeText = Object.entries(codes).map(([c, k]) => `${c}×${k}`).join(' ');
  t.kind('ret'); lit(['RESP1', 'RESP2', 'RESP3']); activate(['CLIENT']);
  cap(`${name} · 응답 ${n}개`, `${codeText} — 배치마다 AOF write 한 번 뒤에 future를 한꺼번에 완료한다`, 6, st);
  await respPath(t);
  const last = infos[infos.length - 1].o;
  logReq('ok', `#${infos[0].id}~#${infos[infos.length - 1].id} ${cmd} ×${n} → ${codeText}${last.end ? ` · 마지막 결과 ${last.end.result}` : ''}`);
  steps(`묶음 ${name} (마지막 6개)`, infos.slice(-6).map(({ id, o }) => ['done', `#${id}`, o.us(o.resp), o.end ? memText(o.end)[0] : '']));
  await wait(HOLD / 2); t.done();
}

async function playSys(sc) {
  const e = sc.e;
  if (sc.kind === 'fsync') {
    const t = new Tok('fsync', 'sys'); const a = rel($('#aof')), d = rel($('#disk'));
    t.at({ x: a.x + 60, y: a.cy }); activate(['#aofBox']);
    cap(`fsync · ${e.bytes}B를 디스크에 내림`, `aof-fsync 스레드가 1초마다 한다(${e.us}µs). 이제 전원이 꺼져도 남는다. 응답은 이걸 기다리지 않았다`);
    $('#disk').classList.add('on'); await t.to({ x: d.cx, y: d.cy }, MOVE, HOLD / 2); $('#disk').classList.remove('on'); t.done();
    logReq('sys', `fsync ${e.bytes}B → 디스크 (${e.us}µs)`);
  } else if (sc.kind === 'rehash.step') {
    activate(['#memBox']); drawStore(e.snap, e.moved, e.seq);
    cap(`리해싱 · 옛 칸 ${e.bucket}의 [${e.moved.join(', ')}] → 새 테이블`, 'work 틱(10ms마다 1ms)이 칸 하나씩 옮긴다. 한 번에 다 옮기면 그동안 모든 요청이 멈추기 때문');
    logReq('sys', `리해싱: 칸 ${e.bucket} [${e.moved.join(', ')}] 이동`); await wait(HOLD);
  } else if (sc.kind === 'rehash.done') {
    activate(['#memBox']); drawStore(e.snap, [], e.seq);
    cap(`리해싱 끝 · ${e.size}칸`, '새 테이블이 기본 테이블이 됐다. 키 수가 칸 수에 닿으면 다시 2배로 늘린다');
    logReq('sys', `리해싱 끝 (${e.size}칸)`); await wait(HOLD);
  } else if (sc.kind === 'expire') {
    activate(['#memBox', '#aofBox']); drawStore(e.snap, [], e.seq); addAof(sc.recs);
    cap(`만료 청소 · [${sc.keys.join(', ')}] 삭제`, '100ms마다 expire 틱이 TTL 키를 무작위로 골라 검사한다. 지운 키는 AOF에 DEL로 남는다');
    logReq('sys', `만료 청소: ${sc.keys.join(', ')}`); await wait(HOLD);
  } else if (sc.kind === 'start') {
    S.drawn = 0; S.aof = []; drawAof(); drawStore(e.snap, [], e.seq, true);
    cap('서버 시작', `seed ${e.fixedSeed ? '고정' : '무작위'} · 복구가 끝난 상태에서 시작한다`); logReq('sys', '서버 (재)시작'); await wait(HOLD / 2);
  }
}

function recText(hex) {
  const b = hex.match(/../g).map((x) => parseInt(x, 16));
  const u = (i) => ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0;
  const a = []; let p = 5;
  for (let i = 0; i < b[4] && p + 4 <= b.length; i++) { const l = u(p); a.push(new TextDecoder().decode(new Uint8Array(b.slice(p + 4, p + 4 + l)))); p += 4 + l; }
  return a.join(' ');
}
function decodeSafe(s) { try { return decodeURIComponent(s); } catch { return s; } }
setInterval(() => ($('#clock').textContent = new Date().toTimeString().slice(0, 8)), 500);
let rz; addEventListener('resize', () => { clearTimeout(rz); rz = setTimeout(() => { layout(); if (S.latest) drawStore(S.latest.snap, [], 0, true); }, 150); });

(async () => {
  cap('요청을 기다리는 중', '입력 콘솔이나 curl로 요청을 보내면 여기서 한 단계씩 따라간다. Space 일시정지 · → 한 단계 · 1~4 속도');
  const r = await api(`/api/trace?since=${Number.MAX_SAFE_INTEGER}`); S.since = r.next;
  const t = await api('/api/debug?cmd=TABLE');
  try {
    const a = await api('/api/debug?cmd=AOF');
    const b = (a.hex.match(/../g) || []).map((x) => parseInt(x, 16));
    const u = (i) => ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0;
    for (let p = 0; p + 4 <= b.length; ) { const n = u(p); if (p + 8 + n > b.length) break; S.aof.push({ t: recText(b.slice(p, p + 8 + n).map((x) => x.toString(16).padStart(2, '0')).join('')), fresh: false }); p += 8 + n; }
    S.aofBytes = a.size; $('#sAof').textContent = `${a.size}B`;
  } catch { /* 없음 */ }
  drawStore(t, [], 0, true); drawAof(); layout();
  steps('요청을 보내면 단계별 실제 소요 시간이 여기 나온다', []);
  poll(); player();
})();
