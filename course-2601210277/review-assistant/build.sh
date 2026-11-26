#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ -n "${SDK_POM:-}" ]; then
  sdk_pom="$SDK_POM"
elif [ -f ../../rocketmq-a2a/pom.xml ]; then
  sdk_pom=../../rocketmq-a2a/pom.xml
elif [ -f ../rocketmq-ai/rocketmq-a2a/pom.xml ]; then
  sdk_pom=../rocketmq-ai/rocketmq-a2a/pom.xml
else
  echo 'SDK source missing: set SDK_POM to rocketmq-a2a/pom.xml' >&2
  exit 1
fi
mvn -B -s maven-settings.xml -f "$sdk_pom" install "$@"
mvn -B -s maven-settings.xml package dependency:copy-dependencies -DoutputDirectory=target/lib "$@"
