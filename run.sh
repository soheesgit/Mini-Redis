#!/bin/bash
# 저장소 서버 실행. HTTP 타임아웃·연결 상한은 JVM 시스템 속성이라 -D로 넣는다 (기획서 7절).
# 사용: ./run.sh [--addr 127.0.0.1:8080] [--dir ./data] [--maxmemory 바이트] [--policy allkeys-lru]
# 비밀번호: MINIREDIS_PASSWORD=... ./run.sh
cd "$(dirname "$0")"
# JAVA_HOME이 없으면 ~/tools의 JDK 25를 쓴다 (PATH의 옛 JDK로 실행하면 클래스 버전 오류)
JDK="${JAVA_HOME:-$HOME/tools/jdk-25.0.5+8/Contents/Home}"
JAVA="$JDK/bin/java"
[ -x "$JAVA" ] || JAVA=java
# ExitOnOutOfMemoryError: 핸들러 스레드에서 난 OOM까지 포함해, 메모리가 바닥나면 바로 종료 → 재시작 시 AOF로 복구
exec "$JAVA" -Xms1g -Xmx1g -XX:+ExitOnOutOfMemoryError \
  -Dsun.net.httpserver.maxReqTime=10000 \
  -Dsun.net.httpserver.maxRspTime=10000 \
  -Dsun.net.httpserver.idleInterval=60 \
  -Djdk.httpserver.maxConnections=1000 \
  -cp target/classes miniredis.Main "$@"
