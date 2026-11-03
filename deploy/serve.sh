#!/bin/sh
# Запускается systemd на VPS. Токен читается из окружения, не из командной строки.
set -eu
set -a
# shellcheck disable=SC1091
. /opt/magic-sync/magic-sync.env
set +a
exec "${MAGIC_SYNC_JAVA:-java}" -jar /opt/magic-sync/magic-sync.jar serve \
  --data "${MAGIC_SYNC_DATA:-/opt/magic-sync/data}" \
  --listen "${MAGIC_SYNC_LISTEN:-127.0.0.1:8787}"
