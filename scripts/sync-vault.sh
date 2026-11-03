#!/usr/bin/env bash
# Синхронизация хранилища на SSD с сервером. Запускается из любой папки.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/deploy/server.env"
JAR="$ROOT/sync-cli/build/libs/magic-sync.jar"
VAULT="/media/turbo/SSD03/work/obsidian-sync"
KEY="${HOME}/.config/magic-sync/sync.key"

[[ -f "$ENV_FILE" ]] || { echo "нет $ENV_FILE" >&2; exit 1; }
[[ -f "$JAR" ]] || { echo "нет $JAR — сначала ./gradlew :sync-cli:jar" >&2; exit 1; }
[[ -f "$KEY" ]] || { echo "нет $KEY" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

exec java -jar "$JAR" sync \
  --vault "$VAULT" \
  --server "http://130.12.45.85:8787" \
  --token "$MAGIC_SYNC_TOKEN" \
  --key "$KEY" \
  --device computer
