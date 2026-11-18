import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { access, mkdir, mkdtemp, readFile, readdir, rename, rm } from 'node:fs/promises';
import { createServer } from 'node:http';
import { createServer as tcp } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { createOperations, HISTORY_COUNT, INDEX_SQL, SCAN_SQL, SEED_SQL, services } from './operations.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const local = join(here, '.local');
const data = join(local, 'votes-v1');
const webPort = Number(process.env.PORT ?? 4173);
const children = new Set();
const localHost = '127.0.0.1';

function javaBin(name) {
  let home = process.env.JAVA_HOME;
  if (!home && process.platform === 'darwin') {
    const result = spawnSync('/usr/libexec/java_home', ['-v', '25'], { encoding: 'utf8' });
    if (result.status === 0) home = result.stdout.trim();
  }
  return home ? join(home, 'bin', name) : name;
}

const components = {
  dbms: { module: 'mini-dbms', main: 'minidb.api.Main' },
  redis: { module: 'mini-redis', main: 'miniredis.Main' },
};

function classes(key) {
  return join(local, 'classes', key);
}

async function build() {
  for (const [key, component] of Object.entries(components)) {
    const src = join(root, component.module, 'src/main/java');
    const files = (await readdir(src, { recursive: true })).filter((name) => name.endsWith('.java'));
    await mkdir(classes(key), { recursive: true });
    const compile = spawnSync(javaBin('javac'), [
      '--release', '25', '-encoding', 'UTF-8', '-d', classes(key), ...files.map((file) => join(src, file)),
    ], { stdio: 'inherit' });
    if (compile.status !== 0) throw new Error('JDK 25 이상으로 ' + component.module + '을 컴파일하세요.');
  }
}

async function freePort() {
  const server = tcp();
  server.listen(0, localHost);
  await once(server, 'listening');
  const { port } = server.address();
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}

async function startChild(key, args, marker) {
  const child = spawn(javaBin('java'), [
    '-Xmx1g', '-cp', classes(key), components[key].main, ...args,
  ], { stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, MINIREDIS_PASSWORD: '' } });
  children.add(child);
  child.once('exit', () => children.delete(child));
  child.once('error', () => children.delete(child));
  const lines = createInterface({ input: child.stdout });
  child.stderr.pipe(process.stderr, { end: false });
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(key + ' 시작 시간 초과')), 12_000);
    lines.on('line', (line) => {
      if (line.includes(marker)) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.once('error', (error) => { clearTimeout(timer); reject(error); });
    child.once('exit', (code) => { clearTimeout(timer); reject(new Error(key + ' 종료 · ' + code)); });
  });
  return child;
}

function startDb(dir, fsync = true) {
  return freePort().then(async (port) => {
    const args = ['--addr', localHost + ':' + port, '--data-dir', dir];
    if (!fsync) args.push('--fsync=off');
    const child = await startChild('dbms', args, 'Mini DBMS API listening');
    return { child, url: 'http://' + localHost + ':' + port };
  });
}

async function startRedis(dir) {
  const port = await freePort();
  const child = await startChild('redis', ['--addr', localHost + ':' + port, '--dir', dir], 'mini-redis listening');
  return { child, url: 'http://' + localHost + ':' + port };
}

async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, 'exit');
  child.kill('SIGTERM');
  await exited;
}

async function exists(path) {
  try { await access(path); return true; } catch { return false; }
}

async function prepare() {
  await build();
  if (await exists(data)) {
    console.log('준비된 과거 투표를 재사용합니다.');
    return;
  }
  const staging = await mkdtemp(join(local, 'seed-'));
  try {
    const db = await startDb(staging, false);
    const io = services(db.url);
    try {
      await io.write('CREATE TABLE lunch_menus (name VARCHAR(80) NOT NULL, votes INT NOT NULL);', 0);
      await io.write(SEED_SQL, 5);
      await io.write('CREATE TABLE vote_history (menu_id INT NOT NULL);', 0);
      const batch = 1_000;
      const rows = Array.from({ length: batch }, (_, index) => '(' + (index % 5 + 1) + ')');
      const insert = 'INSERT INTO vote_history VALUES ' + rows.join(', ') + ';';
      for (let count = batch; count <= HISTORY_COUNT; count += batch) {
        await io.write(insert, batch);
        if (count % 100_000 === 0) console.log('과거 투표 ' + count.toLocaleString('en-US') + '건 준비');
      }
    } finally {
      await stop(db.child);
    }
    await rename(staging, data);
  } finally {
    await rm(staging, { recursive: true, force: true });
  }
}

const assetTypes = {
  '.html': 'text/html',
  '.css': 'text/css',
  '.js': 'text/javascript',
};

function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

async function readInput(req) {
  const parts = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 2048) throw new Error('요청 본문이 너무 길어요.');
    parts.push(chunk);
  }
  return JSON.parse(Buffer.concat(parts).toString('utf8') || '{}');
}

function webServer(operations) {
  return createServer(async (req, res) => {
    const host = localHost + ':' + req.socket.localPort;
    if (req.headers.host !== host || (req.headers.origin && req.headers.origin !== 'http://' + host)) {
      json(res, 403, { error: '데모 주소에서 요청하세요.' });
      return;
    }
    const path = new URL(req.url, 'http://' + host).pathname;
    try {
      if (req.method === 'GET' && path === '/api/status') {
        json(res, 200, { menus: await operations.menus(), historyCount: HISTORY_COUNT });
        return;
      }
      const action = req.method === 'GET' && path === '/api/ranking' ? 'ranking'
        : req.method === 'POST' ? path.match(/^\/api\/(vote|add|remove|reset|history)$/)?.[1] : null;
      if (action) {
        const input = action === 'ranking' ? {} : await readInput(req).catch(() => null);
        if (!input || typeof input !== 'object' || Array.isArray(input)) {
          json(res, 400, { error: '요청 내용을 확인해 주세요.' });
          return;
        }
        res.writeHead(200, {
          'Content-Type': 'application/x-ndjson; charset=utf-8',
          'Cache-Control': 'no-store',
          'X-Content-Type-Options': 'nosniff',
        });
        const emit = (event) => {
          if (!res.destroyed) res.write(JSON.stringify(event) + '\n');
        };
        try {
          emit({ type: 'result', ...await operations[action](emit, input) });
        } catch (error) {
          emit({ type: 'error', message: error.message });
        }
        res.end();
        return;
      }
      const asset = path === '/' ? '/index.html' : path;
      if (req.method === 'GET' && /^\/(?:[a-z-]+\/)*[a-z-]+\.(?:html|css|js)$/.test(asset)) {
        const body = await readFile(join(here, 'public', asset));
        res.writeHead(200, { 'Content-Type': assetTypes[asset.slice(asset.lastIndexOf('.'))] + '; charset=utf-8', 'Cache-Control': 'no-store' });
        res.end(body);
        return;
      }
      json(res, 404, { error: '없는 경로입니다.' });
    } catch (error) {
      json(res, error.code === 'ENOENT' ? 404 : 500, { error: error.message });
    }
  });
}

async function start() {
  if (!(await exists(data)) || !(await exists(join(classes('dbms'), 'minidb/api/Main.class')))) {
    throw new Error('먼저 node lunch-demo/demo.mjs prepare를 실행하세요.');
  }
  const db = await startDb(data);
  const redis = await startRedis(join(local, 'redis-data'));
  const io = services(db.url, redis.url);
  const end = await io.query('SELECT id FROM vote_history WHERE id = ' + HISTORY_COUNT + ';');
  const beyond = await io.query('SELECT id FROM vote_history WHERE id > ' + HISTORY_COUNT + ';');
  if (end.rows?.[0]?.[0] !== String(HISTORY_COUNT) || beyond.rows?.length !== 0) {
    throw new Error('과거 투표 100만 건을 준비해 주세요.');
  }
  for (const sql of [INDEX_SQL, SCAN_SQL]) {
    if ((await io.query(sql)).rows?.length !== 1) throw new Error('과거 투표 예열에 실패했어요.');
  }
  const operations = createOperations(io);
  await operations.clearCache();
  const server = webServer(operations);
  server.listen(webPort, localHost);
  await once(server, 'listening');
  console.log('점심 투표 · http://' + localHost + ':' + server.address().port);
  console.log('Mini DBMS ' + db.url + ' · Mini Redis ' + redis.url);
  const shutdown = () => {
    server.close();
    server.closeAllConnections();
  };
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
  await once(server, 'close');
}

try {
  const command = process.argv[2];
  if (command === 'prepare') await prepare();
  else if (command === 'start') await start();
  else throw new Error('사용법 · node lunch-demo/demo.mjs prepare|start');
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  await Promise.all([...children].map(stop));
}
