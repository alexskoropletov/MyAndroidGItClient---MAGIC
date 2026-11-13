#!/usr/bin/env bash
# QR для телефона: адрес, токен и имя устройства. Ключ в код не входит.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/.env"
JAR="$ROOT/sync-cli/build/libs/magic-sync.jar"

device="${1:-}"
if [[ -z "$device" || "$device" == "-h" || "$device" == "--help" ]]; then
  echo "Использование: ./scripts/show-qr.sh ИМЯ-ТЕЛЕФОНА [--out файл.png]" >&2
  echo "Имя попадёт на телефон. Это не имя компьютера." >&2
  exit 2
fi
shift

[[ -f "$ENV_FILE" ]] || { echo "нет $ENV_FILE — скопируйте .env.example" >&2; exit 1; }
[[ -f "$JAR" ]] || { echo "нет $JAR — сначала ./gradlew :sync-cli:jar" >&2; exit 1; }

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${SYNC_SERVER:?укажите SYNC_SERVER в .env}"
: "${MAGIC_SYNC_TOKEN:?укажите MAGIC_SYNC_TOKEN в .env}"

out="${HOME}/.config/magic-sync/setup-qr.png"
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out)
      [[ $# -ge 2 ]] || { echo "нет файла у --out" >&2; exit 1; }
      out="$2"
      shift 2
      ;;
    *)
      extra+=("$1")
      shift
      ;;
  esac
done

mkdir -p -- "$(dirname -- "$out")"
export MAGIC_SYNC_TOKEN
java -jar "$JAR" qr --server "$SYNC_SERVER" --device "$device" --out "$out" "${extra[@]}"
chmod 600 "$out"
