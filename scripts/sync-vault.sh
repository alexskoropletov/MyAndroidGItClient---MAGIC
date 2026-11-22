#!/usr/bin/env bash
# Синхронизация хранилища. Адрес, папка и ключ берутся из .env.
# Готовый jar: dist/magic-sync.jar (клонировали — можно сразу запускать).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
JAR="$("$ROOT/scripts/resolve-jar.sh")"

[[ -f "$ENV_FILE" ]] || { echo "нет $ENV_FILE — скопируйте .env.example" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

SYNC_KEY="${SYNC_KEY/#\~/$HOME}"
: "${SYNC_SERVER:?укажите SYNC_SERVER в .env}"
: "${SYNC_VAULT:?укажите SYNC_VAULT в .env}"
: "${SYNC_KEY:?укажите SYNC_KEY в .env}"
: "${SYNC_DEVICE:?укажите SYNC_DEVICE в .env}"
: "${MAGIC_SYNC_TOKEN:?укажите MAGIC_SYNC_TOKEN в .env}"
[[ -f "$SYNC_KEY" ]] || { echo "нет $SYNC_KEY" >&2; exit 1; }

extra=()
if [[ "${1:-}" == "--allow-mass-delete" || "${SYNC_ALLOW_MASS_DELETE:-}" == "1" ]]; then
  extra+=(--allow-mass-delete)
fi

exec java -jar "$JAR" sync \
  --vault "$SYNC_VAULT" \
  --server "$SYNC_SERVER" \
  --token "$MAGIC_SYNC_TOKEN" \
  --key "$SYNC_KEY" \
  --device "$SYNC_DEVICE" \
  "${extra[@]}"
