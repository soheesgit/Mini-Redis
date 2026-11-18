#!/bin/bash
# 미니 Redis 흐름 관찰 데모 (데모페이지-기획서.md). 브라우저로 http://127.0.0.1:8083
# 사용: ./run-demo.sh [--port 8083] [--redis-port 8090] [--dir ./data-demo]
# 비밀번호(401 실험용): MINIREDIS_PASSWORD=... ./run-demo.sh  — 서버에서만 쓰고 브라우저로 보내지 않는다
cd "$(dirname "$0")"
JDK="${JAVA_HOME:-$HOME/tools/jdk-25.0.5+8/Contents/Home}"
JAVA="$JDK/bin/java"
[ -x "$JAVA" ] || JAVA=java
MVN="$HOME/tools/apache-maven-3.9.16/bin/mvn"
[ -x "$MVN" ] || MVN=mvn
JAVA_HOME="$JDK" "$MVN" -q -o compile || exit 1
exec "$JAVA" -Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError \
  -Dsun.net.httpserver.maxReqTime=10000 \
  -Dsun.net.httpserver.maxRspTime=10000 \
  -Dsun.net.httpserver.idleInterval=60 \
  -Djdk.httpserver.maxConnections=1000 \
  -cp target/classes example.flowdemo.FlowDemoMain "$@"
