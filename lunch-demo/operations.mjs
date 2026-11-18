import { performance } from 'node:perf_hooks';

export const HISTORY_COUNT = 1_000_000;
export const INDEX_SQL = 'SELECT * FROM vote_history WHERE id = 500000;';
export const SCAN_SQL = 'SELECT * FROM vote_history WHERE id = 500000 AND TRUE;';
const RANK_SQL = 'SELECT id, name, votes FROM lunch_menus ORDER BY votes DESC, id ASC;';
const CACHE_KEY = 'lunch:ranking';
const CACHE_TTL = 60;
const cachePath = '/keys/' + encodeURIComponent(CACHE_KEY);
const quote = (value) => "'" + value.replaceAll("'", "''") + "'";
export const SEED_SQL = "INSERT INTO lunch_menus VALUES ('짜장면', 12), ('김치찌개', 12), ('돈까스', 7), ('마라탕', 5), ('쌀국수', 3);";

async function request(service, base, path, method, body, command, emit) {
  const start = performance.now();
  let result;
  try {
    const reply = await fetch(base + path, {
      method, body, signal: AbortSignal.timeout(12_000),
      headers: body === undefined ? {} : { 'Content-Type': 'text/plain; charset=utf-8' },
    });
    const text = await reply.text();
    let response = text;
    try { response = JSON.parse(text); } catch { /* Preserve the original response. */ }
    result = { ok: reply.ok, status: reply.status, response, ms: performance.now() - start };
  } catch (error) {
    result = { ok: false, status: null, response: { error: error.message }, ms: performance.now() - start };
  }
  emit?.({ type: 'call', service, command, ...result });
  return result;
}

function requireResult(result) {
  if (!result.ok) throw new Error(result.response?.error || '서버 요청에 실패했어요.');
  return result.response;
}

export function services(dbUrl, redisUrl) {
  const sql = (command, emit) => request('dbms', dbUrl, '/query', 'POST', command, command, emit);
  return {
    sql,
    async query(command, emit) {
      return requireResult(await sql(command, emit));
    },
    async write(command, count, emit) {
      const { affected } = requireResult(await sql(command, emit));
      if (count !== null && count !== affected) throw new Error('SQL 변경 건수가 예상과 달라요.');
    },
    redis(method, emit, value) {
      const search = method === 'PUT' ? '?ttl=' + CACHE_TTL : '';
      return request('redis', redisUrl, cachePath + search, method, value,
        method + ' /keys/' + CACHE_KEY + search, emit);
    },
  };
}

function toMenu([id, name, votes]) {
  return { id: Number(id), name, votes: Number(votes) };
}

function validMenus(value) {
  return Array.isArray(value) && value.every((menu) =>
    menu && Number.isSafeInteger(menu.id) && typeof menu.name === 'string' && Number.isSafeInteger(menu.votes));
}

function menuId(id) {
  if (!Number.isSafeInteger(id) || id <= 0) throw new Error('메뉴 번호를 확인해 주세요.');
  return id;
}

function median(samples) {
  return [...samples].sort((a, b) => a - b)[Math.floor(samples.length / 2)];
}

export function createOperations(io) {
  // 메뉴 쓰기와 캐시 읽기/저장이 겹치면 삭제 직후 오래된 순위를 다시 저장할 수 있다.
  // 데모 API의 순위 경로를 순서대로 처리하고, 과거 투표 조회는 독립적으로 실행한다.
  let tail = Promise.resolve();
  const serial = (work) => (...args) => {
    const result = tail.then(() => work(...args));
    tail = result.catch(() => {});
    return result;
  };
  let cacheUsable = false;
  const menus = async (emit) => (await io.query(RANK_SQL, emit)).rows.map(toMenu);

  async function invalidate(emit) {
    cacheUsable = false;
    const result = await io.redis('DELETE', emit);
    cacheUsable = result.ok;
    return result.ok ? result.response.deleted : null;
  }

  async function loadRanking(emit) {
    const cached = cacheUsable ? await io.redis('GET', emit) : null;
    if (cached?.ok && validMenus(cached.response)) {
      emit?.({ type: 'cache', state: 'HIT', ms: cached.ms });
      return cached.response;
    }
    const missed = cached?.status === 404;
    emit?.({ type: 'cache', state: missed ? 'MISS' : 'BYPASS', ms: cached?.ms });
    const ranking = await menus(emit);
    const stored = await io.redis('PUT', emit, JSON.stringify(ranking));
    cacheUsable = stored.ok;
    return ranking;
  }

  async function afterWrite(emit) {
    const deleted = await invalidate(emit);
    emit?.({ type: 'invalidate', deleted });
    return { menus: await loadRanking(emit), deleted };
  }

  return {
    menus: serial(() => menus()),
    clearCache: serial(() => invalidate()),
    reset: serial(async (emit) => {
      cacheUsable = false;
      await io.write('DELETE FROM lunch_menus;', null, emit);
      await io.write(SEED_SQL, 5, emit);
      const deleted = await invalidate(emit);
      return { menus: await menus(emit), deleted };
    }),
    ranking: serial(async (emit) => ({ menus: await loadRanking(emit) })),
    vote: serial(async (emit, { id }) => {
      const selectedId = menuId(id);
      const rows = (await io.query('SELECT id, name, votes FROM lunch_menus WHERE id = ' + selectedId + ';', emit)).rows;
      if (rows.length !== 1) throw new Error('이미 없는 메뉴예요.');
      const menu = toMenu(rows[0]);
      cacheUsable = false;
      await io.write('UPDATE lunch_menus SET votes = ' + (menu.votes + 1) + ' WHERE id = ' + menu.id + ';', 1, emit);
      return { ...await afterWrite(emit), voted: menu.name };
    }),
    add: serial(async (emit, { name }) => {
      const cleaned = typeof name === 'string' ? name.trim() : '';
      if (!cleaned || cleaned.length > 20) throw new Error('메뉴 이름은 1~20자로 입력해 주세요.');
      const exists = await io.query('SELECT id FROM lunch_menus WHERE name = ' + quote(cleaned) + ';', emit);
      if (exists.rows.length) throw new Error('이미 있는 메뉴예요.');
      cacheUsable = false;
      await io.write('INSERT INTO lunch_menus VALUES (' + quote(cleaned) + ', 0);', 1, emit);
      return afterWrite(emit);
    }),
    remove: serial(async (emit, { id }) => {
      cacheUsable = false;
      await io.write('DELETE FROM lunch_menus WHERE id = ' + menuId(id) + ';', 1, emit);
      return afterWrite(emit);
    }),
    async history(emit) {
      const samples = { index: [], scan: [] };
      const rounds = 5;
      let row;
      for (let turn = 0; turn < rounds; turn++) {
        const trial = {};
        for (const path of turn % 2 === 0 ? ['index', 'scan'] : ['scan', 'index']) {
          const report = await io.sql(path === 'index' ? INDEX_SQL : SCAN_SQL, emit);
          const { rows } = requireResult(report);
          if (rows.length !== 1 || rows[0][0] !== '500000') throw new Error('500000번 투표를 찾지 못했어요.');
          trial[path] = rows[0];
          samples[path].push(report.ms);
        }
        if (JSON.stringify(trial.index) !== JSON.stringify(trial.scan)) throw new Error('두 조회의 결과가 달라요.');
        row = trial.index;
      }
      return { row, rounds, indexMs: median(samples.index), scanMs: median(samples.scan) };
    },
  };
}
