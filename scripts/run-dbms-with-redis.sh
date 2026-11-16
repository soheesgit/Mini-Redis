#!/bin/bash
# Mini Redis 서버를 띄우고, Mini DBMS가 그 서버를 SELECT 캐시로 쓰게 실행한다. 끝나면 서버를 내린다.
# 사용: ./scripts/run-dbms-with-redis.sh <sql 파일> [<sql 파일> ...]
#       SQL 파일마다 DBMS 프로세스를 하나씩 따로 띄운다 (두 번째 프로세스부터 캐시 HIT가 보인다)
# 환경변수: JAVA_HOME(JDK 25), REDIS_PORT(기본 8090), DBMS_DATA(기본 mini-dbms/data), REDIS_DATA(기본 mini-dbms/data/redis)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-$HOME/tools/jdk-25.0.5+8/Contents/Home}" # mini-redis가 Java 25로 컴파일되므로 25가 필요하다
JAVA="$JDK/bin/java"
MVN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
[ -x "$MVN" ] || MVN=mvn
PORT="${REDIS_PORT:-8090}"
DBMS_DATA="${DBMS_DATA:-$ROOT/mini-dbms/data}"
REDIS_DATA="${REDIS_DATA:-$ROOT/mini-dbms/data/redis}"

[ $# -ge 1 ] || { echo "사용: $0 <sql 파일> [<sql 파일> ...]" >&2; exit 2; }

# 1. 두 프로젝트를 각자 방식으로 빌드한다 (서로의 코드를 참조하지 않는다)
(cd "$ROOT/mini-redis" && JAVA_HOME="$JDK" "$MVN" -q compile)
(cd "$ROOT/mini-dbms" && mkdir -p out/main && find src/main/java -name '*.java' > out/main-sources.txt \
  && "$JDK/bin/javac" --release 21 -encoding UTF-8 -d out/main @out/main-sources.txt)

# 2. Mini Redis 서버를 띄우고, 스크립트가 어떻게 끝나든 내린다
mkdir -p "$REDIS_DATA"
"$JAVA" -cp "$ROOT/mini-redis/target/classes" miniredis.Main --addr "127.0.0.1:$PORT" --dir "$REDIS_DATA" &
REDIS_PID=$!
trap 'kill $REDIS_PID 2>/dev/null; wait $REDIS_PID 2>/dev/null || true' EXIT
for _ in $(seq 1 50); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/ping" && break
  kill -0 $REDIS_PID 2>/dev/null || { echo "Mini Redis가 시작하지 못했다" >&2; exit 1; }
  sleep 0.2
done

# 3. SQL 파일마다 DBMS 프로세스를 따로 실행한다. 캐시 통계(cache: hits=.. misses=..)는 stderr로 나온다
for sql in "$@"; do
  echo "=== $sql"
  MINIDBMS_REDIS="http://127.0.0.1:$PORT" "$JAVA" -cp "$ROOT/mini-dbms/out/main" minidbms.cli.Main --data-dir "$DBMS_DATA" "$sql"
done
