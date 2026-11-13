#!/bin/sh
# Запускается systemd на VPS. Токен читается из окружения, не из командной строки.
set -eu
dir=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
set -a
# shellcheck disable=SC1091
. "$dir/magic-sync.env"
set +a
exec "${MAGIC_SYNC_JAVA:?}" -jar "$dir/magic-sync.jar" serve \
  --data "${MAGIC_SYNC_DATA:?}" \
  --listen "${MAGIC_SYNC_LISTEN:?}"
