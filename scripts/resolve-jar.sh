#!/usr/bin/env bash
# Печатает путь к magic-sync.jar: сначала dist/, иначе локальная сборка.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_JAR="$ROOT/dist/magic-sync.jar"
BUILD_JAR="$ROOT/sync-cli/build/libs/magic-sync.jar"

if [[ -f "$DIST_JAR" ]]; then
  echo "$DIST_JAR"
elif [[ -f "$BUILD_JAR" ]]; then
  echo "$BUILD_JAR"
else
  echo "нет jar: положите dist/magic-sync.jar или соберите ./gradlew :sync-cli:jar" >&2
  exit 1
fi
