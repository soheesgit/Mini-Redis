// 입력 콘솔: 요청을 보내기만 한다. 흐름은 관제 화면(monitor.html)이 서버 기록을 보고 그린다.
const $ = (s) => document.querySelector(s);
const enc = encodeURIComponent;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const qs = (o) => Object.entries(o).filter(([, v]) => v != null && v !== '').map(([k, v]) => `${enc(k)}=${enc(v)}`).join('&');
async function api(path, opts = {}) {
  const r = await fetch(path, { method: opts.method || 'GET', headers: { 'X-Flow-Demo': '1' }, body: opts.body });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || `HTTP ${r.status}`);
  return j;
}
const REQ = {
  set: (k, v, t) => ['PUT', `/keys/${enc(k)}${t ? `?ttl=${enc(t)}` : ''}`, v, `SET ${k} ${v}${t ? ` ttl=${t}` : ''}`],
  get: (k) => ['GET', `/keys/${enc(k)}`, '', `GET ${k}`],
  del: (k) => ['DELETE', `/keys/${enc(k)}`, '', `DEL ${k}`],
  incr: (k) => ['POST', `/keys/${enc(k)}/incr`, '', `INCR ${k}`],
  expire: (k, v, t) => ['PUT', `/keys/${enc(k)}/ttl?seconds=${enc(t || '30')}`, '', `EXPIRE ${k} ${t || 30}`],
};
let n = 0;
async function send([method, path, body, label], extra = {}) {
  try {
    const r = await api(`/api/send?${qs({ method, path, ...extra })}`, { method: 'POST', body });
    show(r.traceId, r.status, label, r.body);
    return r;
  } catch (e) { show('-', 0, label, e.message); }
}
function show(id, status, label, body) {
  const li = document.createElement('li');
  for (const [cls, text] of [['mono dim', `#${id}`], [`mono ${status && status < 400 ? 'ok' : 'bad'}`, status || 'ERR'], ['mono', `${label}  `]]) {
    const s = document.createElement('span'); s.className = cls; s.textContent = text; li.append(s);
  }
  const b = document.createElement('span'); b.className = 'body mono'; b.textContent = body; li.lastChild.append(b);
  $('#results').prepend(li);
  $('#cnt').textContent = `${++n}건`;
}

$('#send').onclick = () => send(REQ[$('#cmd').value]($('#key').value.trim() || 'apple', $('#val').value, $('#ttl').value.trim()));
document.querySelectorAll('.form input').forEach((i) => i.addEventListener('keydown', (e) => { if (e.key === 'Enter') $('#send').click(); }));
$('#fruits').onclick = async () => { for (const [k, v] of [['banana', 'yellow'], ['cherry', 'red'], ['grape', 'purple'], ['lemon', 'yellow'], ['mango', 'orange'], ['kiwi', 'green']]) { await send(REQ.set(k, v)); await sleep(700); } };
$('#burst').onclick = async () => {
  const r = await api(`/api/burst?${qs({ method: 'POST', path: '/keys/counter/incr', count: 50, concurrent: 1 })}`, { method: 'POST', body: '' });
  show(`${Math.min(...r.traceIds)}~${Math.max(...r.traceIds)}`, 200, 'INCR counter ×50 (동시)', JSON.stringify(r.statuses));
};
$('#ttlTest').onclick = async () => { await send(REQ.set('temp', 'bye', '2')); await sleep(3000); await send(REQ.get('temp')); };
$('#attack').onclick = () => send(REQ.set('evil', 'x'), { origin: 1 });
$('#reset').onclick = async () => { await api('/api/server?action=reset', { method: 'POST' }); show('-', 200, '데이터 초기화', '빈 Redis로 재시작'); };
$('#openMon').onclick = () => window.open('/monitor.html', 'miniredis-monitor', 'width=1400,height=900');
api('/api/server').then((s) => ($('#srv').textContent = s.running ? `● 미니 Redis 실행 중 (127.0.0.1:${s.redisPort})` : `멈춤: ${s.error}`)).catch(() => {});
