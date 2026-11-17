#!/usr/bin/env bash
# SQL 파일마다 독립 DBMS 프로세스를 실행해 Redis 캐시를 공유한다.
# JAVA_HOME(JDK 25+), MVN, REDIS_PORT, DBMS_DATA, REDIS_DATA로 실행 환경 지정.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
MVN="${MVN:-mvn}"
PORT="${REDIS_PORT:-8090}"
DBMS_DATA="${DBMS_DATA:-$ROOT/mini-dbms/data}"
REDIS_DATA="${REDIS_DATA:-$DBMS_DATA/redis}"
[ $# -ge 1 ] || { echo "사용: $0 <sql 파일> [<sql 파일> ...]" >&2; exit 2; }
for sql in "$@"; do
  [ -r "$sql" ] || { echo "SQL 파일을 읽을 수 없습니다: $sql" >&2; exit 2; }
done
"$MVN" -q -f "$ROOT/mini-redis/pom.xml" compile
"$MVN" -q -f "$ROOT/mini-dbms/pom.xml" compile
mkdir -p "$REDIS_DATA"
REDIS_LOG="$(mktemp)"
"$JAVA" -cp "$ROOT/mini-redis/target/classes" miniredis.Main --addr "127.0.0.1:$PORT" --dir "$REDIS_DATA" >"$REDIS_LOG" 2>&1 &
REDIS_PID=$!
trap 'kill "$REDIS_PID" 2>/dev/null || true; wait "$REDIS_PID" 2>/dev/null || true; rm -f "$REDIS_LOG"' EXIT
ready=false
for ((i=0; i<50; i++)); do
  kill -0 "$REDIS_PID" 2>/dev/null || { cat "$REDIS_LOG" >&2; echo "Mini Redis가 시작하지 못했습니다" >&2; exit 1; }
  if grep -q "mini-redis listening on" "$REDIS_LOG"; then
    ready=true
    break
  fi
  sleep 0.2
done
[ "$ready" = true ] || { echo "Mini Redis 시작 시간이 초과되었습니다" >&2; exit 1; }
# 이미 쓰는 포트로 서버 시작이 실패했으면 다른 서버에 연결하지 않는다.
kill -0 "$REDIS_PID" 2>/dev/null || { echo "Mini Redis가 종료되었습니다" >&2; exit 1; }
cat "$REDIS_LOG"
for sql in "$@"; do
  echo "=== $sql"
  MINIDBMS_REDIS="http://127.0.0.1:$PORT" "$JAVA" -cp "$ROOT/mini-dbms/target/classes" minidb.cli.Main --data-dir "$DBMS_DATA" "$sql"
done
