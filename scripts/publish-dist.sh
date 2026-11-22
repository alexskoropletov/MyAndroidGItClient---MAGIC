#!/usr/bin/env bash
# Собирает sync-cli и кладёт jar в dist/ для коммита в репозиторий.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

./gradlew :sync-cli:jar
mkdir -p dist
cp -f sync-cli/build/libs/magic-sync.jar dist/magic-sync.jar
ls -lh dist/magic-sync.jar
echo "Готово: dist/magic-sync.jar — можно коммитить."