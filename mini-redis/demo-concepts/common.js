// 시안 공통: data.js의 실제 이벤트를 "누가 → 누구에게 → 무슨 바이트를" 보냈는지(hop) 목록으로 바꾼다.
(function () {
  const ev = window.TRACE || [];
  const t0 = ev.length ? ev[0].t : 0;
  const enc = new TextEncoder();

  function hex(bytes, max = 64) {
    const b = typeof bytes === 'string' ? enc.encode(bytes) : bytes;
    const s = [...b.slice(0, max)].map((x) => x.toString(16).padStart(2, '0')).join(' ');
    return b.length > max ? s + ' …' : s;
  }
  function recArgs(h) {
    const b = h.match(/../g).map((x) => parseInt(x, 16));
    const u = (i) => ((b[i] << 24) | (b[i + 1] << 16) | (b[i + 2] << 8) | b[i + 3]) >>> 0;
    const out = []; let p = 5;
    for (let i = 0; i < b[4]; i++) { const l = u(p); out.push(new TextDecoder().decode(new Uint8Array(b.slice(p + 4, p + 4 + l)))); p += 4 + l; }
    return out;
  }
  const us = (t) => ((t - t0) / 1000).toFixed(0);

  const NODES = ['CLIENT', 'SOCKET', 'HANDLER', 'QUEUE', 'EXECUTOR', 'MEMORY', 'AOF', 'DISK'];
  const reqs = [];
  for (const r of ev.filter((e) => e.type === 'http.recv')) {
    const id = r.req;
    const mine = ev.filter((e) => e.req === id || (e.type === 'batch.start' && e.reqs.includes(id)) || (e.type === 'aof.write' && e.records.some((x) => x.req === id)));
    const get = (t) => mine.find((e) => e.type === t);
    const put = get('queue.put'), bat = get('batch.start'), end = get('exec.end'), aof = get('aof.write'), resp = get('http.respond'), fut = get('future.done');
    const rec = aof ? aof.records.find((x) => x.req === id) : null;
    const value = rec ? (recArgs(rec.hex)[2] || '') : '';
    const httpText = `${r.method} ${r.path} HTTP/1.1\r\nHost: 127.0.0.1:8090\r\n${value ? `Content-Length: ${enc.encode(value).length}\r\n` : ''}\r\n${value}`;
    const hops = [];
    // bytes: 그 구간에서 실제로 오간 바이트(없으면 null = 같은 프로세스 안에서 객체 참조만 넘김)
    const hop = (from, to, label, data, e, note, bytes = null) => hops.push({ from, to, label, data, e, note, bytes, t: e ? e.t : r.t });
    const hexBytes = (h) => new Uint8Array(h.split(' ').map((x) => parseInt(x, 16)));
    hop('CLIENT', 'SOCKET', `TCP SEND ${enc.encode(httpText).length}B`, httpText, r, '요청 바이트가 연결 소켓으로 들어간다', enc.encode(httpText));
    hop('SOCKET', 'HANDLER', `${r.method} ${r.path}`, `thread=virtual  origin=${r.origin}  auth=${r.auth}`, r, 'dispatcher가 감지 → 가상 스레드가 요청을 맡는다');
    if (put) hop('HANDLER', 'QUEUE', `${put.cmd} ${put.key}`, `argv=["${put.cmd}","${put.key}"${value ? `,"${value}"` : ''}]  queue=${put.queueBefore}`, put, '명령(byte[][] argv)과 빈 future를 큐에 넣는다', enc.encode([put.cmd, put.key, value].filter(Boolean).join('\u0000')));
    if (bat) hop('QUEUE', 'EXECUTOR', `batch#${bat.batch} size=${bat.size}`, `drain → [${bat.reqs.map((x) => '#' + x).join(',')}]`, bat, 'executor가 꺼낸다(최대 128개)');
    if (end) {
      const L = end.loc || {};
      const tb = L.table >= 0 ? L.table : 0;
      hop('EXECUTOR', 'MEMORY', `${end.cmd} ${end.key} → ${end.result}`, `siphash("${end.key}")=0x${L.hash}  & ${(tb ? L.size1 : L.size0) - 1} → bucket[${tb ? L.idx1 : L.idx0}]${tb ? ' (new table)' : ''}`, end, '해시 → 칸 번호 → 체인 맨 앞에 Node', L.hash ? hexBytes(L.hash.match(/../g).join(' ')) : null);
      const rs = mine.find((e) => e.type === 'rehash.start');
      if (rs) hop('MEMORY', 'MEMORY', `REHASH ${rs.from}→${rs.to}`, `table[1] = new Node[${rs.to}]  rehashIndex=0`, rs, '칸이 모자라 2배 테이블을 만든다');
    }
    if (rec) hop('EXECUTOR', 'AOF', `write ${rec.len}B`, rec.hex.match(/../g).join(' '), aof, `AOF 레코드 "${recArgs(rec.hex).join(' ')}"`, hexBytes(rec.hex.match(/../g).join(' ')));
    if (fut) hop('EXECUTOR', 'HANDLER', 'future.complete()', `ok=${fut.ok}`, fut, 'AOF write 뒤에 future를 완료한다');
    if (resp) hop('HANDLER', 'CLIENT', `HTTP ${resp.status}`, `HTTP/1.1 ${resp.status}\r\n\r\n${resp.body}`, resp, '응답 바이트가 소켓으로 나간다', enc.encode(`HTTP/1.1 ${resp.status}\r\n\r\n${resp.body}`));
    const fs = ev.find((e) => e.type === 'aof.fsync' && aof && e.t > aof.t);
    if (fs) hop('AOF', 'DISK', `fsync ${fs.bytes}B`, `force(false) ${fs.us}µs`, fs, '1초 주기로 디스크에 내린다');
    reqs.push({ id, method: r.method, path: r.path, value, status: resp && resp.status, end, hops, snap: end && end.snap });
  }
  const background = ev.filter((e) => e.req === 0 && e.type.startsWith('rehash.'));

  window.TR = {
    ev, reqs, background, NODES, hex, recArgs, us,
    sleep: (ms) => new Promise((r) => setTimeout(r, ms)),
    lastSnap: () => [...ev].reverse().find((e) => e.snap).snap,
    // 키 → 버킷 배열(마지막 상태). snap.keys.t0: [[{k,v,vlen,ttl}]]
    bucketsAt: (snap) => snap.keys.t0,
  };
})();

// 공통 재생 컨트롤: Space = 멈춤/재생, → = 다음 요청
window.player = function (onReq, opts = {}) {
  let i = 0, paused = false, skip = false;
  addEventListener('keydown', (e) => { if (e.code === 'Space') { paused = !paused; e.preventDefault(); } if (e.code === 'ArrowRight') skip = true; });
  const wait = async (ms) => { const end = Date.now() + ms; while (Date.now() < end || paused) { if (skip) return; await TR.sleep(30); } };
  (async () => {
    for (;;) {
      skip = false;
      await onReq(TR.reqs[i % TR.reqs.length], wait, i);
      await wait(opts.gap || 1200);
      i++;
    }
  })();
};
