import assert from 'node:assert/strict';
import { test } from 'node:test';
import { INDEX_SQL, SCAN_SQL } from './operations.mjs';
import { readEvents } from './public/read-events.js';

const base = 'http://127.0.0.1:' + (process.env.PORT ?? 4173);

async function run(action, input = {}) {
  const reply = await fetch(base + '/api/' + action, {
    signal: AbortSignal.timeout(30_000),
    ...(action === 'ranking' ? {} : {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    }),
  });
  assert.equal(reply.status, 200);
  assert.match(reply.headers.get('content-type'), /application\/x-ndjson/);
  const events = [];
  await readEvents(reply, (event) => events.push(event));
  const result = events.at(-1);
  assert.equal(result.type, 'result', result.message);
  const calls = events.filter(({ type }) => type === 'call');
  assert.ok(calls.every(({ ms }) => Number.isFinite(ms) && ms > 0));
  const cache = events.filter(({ type }) => type === 'cache').map(({ state }) => state);
  return { result, calls, cache };
}

test('응답 스트림에 pipeThrough가 없어도 한글 로그를 읽음', async () => {
  const expected = [{ type: 'cache', state: 'MISS' }, { type: 'result', name: '짜장면' }];
  const text = expected.map((event) => JSON.stringify(event)).join('\n');
  const bytes = new TextEncoder().encode(text);
  const stream = new ReadableStream({
    start(controller) {
      for (const value of bytes) controller.enqueue(Uint8Array.of(value));
      controller.close();
    },
  });
  const received = [];
  await readEvents({ body: { getReader: () => stream.getReader() } }, (event) => received.push(event));
  assert.deepEqual(received, expected);
  assert.equal(stream.locked, false);
});

test('브라우저가 응답 스트림을 노출하지 않아도 로그를 읽음', async () => {
  const received = [];
  await readEvents({ body: null, text: async () => '{"type":"result","name":"김치찌개"}\n' },
    (event) => received.push(event));
  assert.deepEqual(received, [{ type: 'result', name: '김치찌개' }]);
});

await test('Mini React 점심 투표 시연을 실제 Mini DBMS·Mini Redis에 연결', async (t) => {
  const started = performance.now();
  const html = await fetch(base + '/').then((reply) => reply.text());
  assert.match(html, /시스템 로그/);
  assert.doesNotMatch(html, /순위 조회 2회|Mini React/);
  assert.match(html, /실제 API 실행 내역/);
  const initial = await run('reset');
  assert.equal(initial.result.menus.length, 5);
  assert.deepEqual(initial.result.menus.slice(0, 2).map(({ name, votes }) => [name, votes]), [
    ['짜장면', 12], ['김치찌개', 12],
  ]);
  const { id } = initial.result.menus[1];

  try {
    await t.test('동일 순위 조회의 실제 MISS → DBMS SELECT → PUT → HIT', async () => {
      const cold = await run('ranking');
      assert.deepEqual(cold.cache, ['MISS']);
      assert.deepEqual(cold.calls.map(({ service, status }) => [service, status]), [
        ['redis', 404], ['dbms', 200], ['redis', 200],
      ]);
      assert.match(cold.calls[1].command, /^SELECT id, name, votes FROM lunch_menus/);
      assert.equal(cold.calls[2].command, 'PUT /keys/lunch:ranking?ttl=60');
      const warm = await run('ranking');
      assert.deepEqual(warm.cache, ['HIT']);
      assert.equal(warm.calls.length, 1);
      assert.equal(warm.calls[0].command, 'GET /keys/lunch:ranking');
      assert.deepEqual(warm.result.menus, cold.result.menus);
    });

    await t.test('UPDATE 뒤 Redis 키 삭제, 다음 조회 MISS, 기존 2위가 1위', async () => {
      const { cache, calls, result } = await run('vote', { id });
      assert.deepEqual(cache, ['MISS']);
      assert.equal(result.deleted, 1);
      assert.deepEqual(result.menus[0], { id, name: '김치찌개', votes: 13 });
      assert.match(calls[1].command, /^UPDATE lunch_menus SET votes = 13 WHERE id = \d+;$/);
      assert.equal(calls[1].response.affected, 1);
      assert.equal(calls[2].command, 'DELETE /keys/lunch:ranking');
      assert.equal(calls[2].response.deleted, 1);
      assert.equal(calls[3].status, 404);
      const stored = await fetch(base + '/api/status').then((reply) => reply.json());
      assert.deepEqual(stored.menus[0], result.menus[0]);
      assert.equal(stored.historyCount, 1_000_000);
    });

    await t.test('100만 건에서 실제 인덱스 조회와 전체 스캔의 동일 행 반환', async () => {
      const { cache, calls, result } = await run('history');
      assert.deepEqual(cache, []);
      assert.deepEqual(calls.map(({ command }) => command), [
        INDEX_SQL, SCAN_SQL, SCAN_SQL, INDEX_SQL, INDEX_SQL,
        SCAN_SQL, SCAN_SQL, INDEX_SQL, INDEX_SQL, SCAN_SQL,
      ]);
      assert.ok(calls.every(({ service, status, response }) => service === 'dbms' && status === 200
        && JSON.stringify(response.rows) === JSON.stringify([result.row])));
      assert.equal(result.row[0], '500000');
      assert.equal(result.rounds, 5);
      assert.ok(result.scanMs > 0 && result.indexMs > 0);
      t.diagnostic('B+ 트리 ' + result.indexMs.toFixed(2) + ' ms · 전체 스캔 ' + result.scanMs.toFixed(2) + ' ms');
    });
    t.diagnostic('HTTP 시연 세 단계 합계 ' + ((performance.now() - started) / 1000).toFixed(2) + '초');

    await t.test('메뉴 실제 INSERT·DELETE와 동시 투표 보존', async () => {
      const name = "오늘의 '특선'";
      const added = await run('add', { name });
      const newMenu = added.result.menus.find((item) => item.name === name);
      assert.equal(newMenu.votes, 0);
      assert.ok(added.calls.some(({ command, response }) => command.startsWith('INSERT') && response.affected === 1));
      const removed = await run('remove', { id: newMenu.id });
      assert.ok(!removed.result.menus.some((item) => item.id === newMenu.id));
      assert.ok(removed.calls.some(({ command, response }) => command.startsWith('DELETE FROM') && response.affected === 1));
      await Promise.all([run('vote', { id }), run('vote', { id })]);
      const saved = await fetch(base + '/api/status').then((reply) => reply.json());
      assert.equal(saved.menus[0].votes, 15);
    });
  } finally {
    await run('reset');
  }
});
