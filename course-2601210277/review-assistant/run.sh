#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-amd64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
fi
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [ -d target/lib ]; then
  "$JAVA_BIN" -cp 'target/classes:target/lib/*' course.Main "$@"
else
  "$JAVA_BIN" -cp "target/classes:$(cat target/classpath.txt)" course.Main "$@"
fi
