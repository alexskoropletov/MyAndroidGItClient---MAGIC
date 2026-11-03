#!/usr/bin/env bash
# Деплой magic-sync на тот же VPS, что и lootkeep.
#
#   cp deploy/.env.example deploy/.env
#   ./scripts/deploy-remote.sh up      # jar, Java 17, systemd, проверка
#   ./scripts/deploy-remote.sh check
#   ./scripts/deploy-remote.sh logs
#   ./scripts/deploy-remote.sh token   # показать токен из deploy/server.env
#   ./scripts/deploy-remote.sh down    # остановить, данные на сервере остаются
#
# deploy/.env и deploy/server.env в git не коммитятся.
# Ключ заметок на сервер не отправляется.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ENV="${DEPLOY_ENV:-$ROOT/deploy/.env}"
SERVER_ENV="${SERVER_ENV:-$ROOT/deploy/server.env}"

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \?//'
  exit "${1:-0}"
}

die() {
  echo "deploy: $*" >&2
  exit 1
}

require_file() {
  local path="$1" hint="$2"
  [[ -f "$path" ]] || die "нет файла $path — $hint"
}

load_env() {
  require_file "$DEPLOY_ENV" "скопируйте deploy/.env.example в deploy/.env"
  # shellcheck disable=SC1090
  set -a
  # shellcheck disable=SC1090
  source "$DEPLOY_ENV"
  set +a
  : "${DEPLOY_HOST:?укажите DEPLOY_HOST в deploy/.env}"
  : "${DEPLOY_USER:?укажите DEPLOY_USER в deploy/.env}"
  DEPLOY_PORT="${DEPLOY_PORT:-22}"
  DEPLOY_REMOTE_DIR="${DEPLOY_REMOTE_DIR:-/opt/magic-sync}"
}

load_server_env() {
  require_file "$SERVER_ENV" "запустите ./scripts/deploy-remote.sh up, он создаст deploy/server.env"
  # shellcheck disable=SC1090
  set -a
  # shellcheck disable=SC1090
  source "$SERVER_ENV"
  set +a
  : "${MAGIC_SYNC_TOKEN:?в deploy/server.env нет MAGIC_SYNC_TOKEN}"
  MAGIC_SYNC_LISTEN="${MAGIC_SYNC_LISTEN:-0.0.0.0:8787}"
  MAGIC_SYNC_DATA="${MAGIC_SYNC_DATA:-/opt/magic-sync/data}"
}

ssh_base() {
  local -a opts=(-p "$DEPLOY_PORT" -o BatchMode=yes -o StrictHostKeyChecking=accept-new)
  if [[ -n "${DEPLOY_SSH_KEY:-}" ]]; then
    opts+=(-i "${DEPLOY_SSH_KEY/#\~/$HOME}")
  fi
  printf '%s\n' "${opts[@]}"
}

remote() {
  local -a ssh_opts
  mapfile -t ssh_opts < <(ssh_base)
  ssh "${ssh_opts[@]}" "${DEPLOY_USER}@${DEPLOY_HOST}" "$@"
}

rsync_rsh() {
  local -a ssh_opts
  mapfile -t ssh_opts < <(ssh_base)
  printf 'ssh'
  printf ' %q' "${ssh_opts[@]}"
  printf '\n'
}

listen_port() {
  local listen="${MAGIC_SYNC_LISTEN}"
  printf '%s\n' "${listen##*:}"
}

ensure_server_env() {
  if [[ -f "$SERVER_ENV" ]]; then
    return
  fi
  local token
  token="$(openssl rand -hex 24)"
  umask 077
  cat > "$SERVER_ENV" <<EOF
MAGIC_SYNC_TOKEN=${token}
MAGIC_SYNC_LISTEN=0.0.0.0:8787
MAGIC_SYNC_DATA=/opt/magic-sync/data
MAGIC_SYNC_JAVA=/usr/bin/java
EOF
  chmod 600 "$SERVER_ENV"
  echo "Создан ${SERVER_ENV}. Токен в git не попадёт."
}

set_env_value() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$SERVER_ENV"; then
    local tmp
    tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" 'index($0, k "=") == 1 { print k "=" v; next } { print }' "$SERVER_ENV" > "$tmp"
    mv "$tmp" "$SERVER_ENV"
  else
    printf '%s=%s\n' "$key" "$value" >> "$SERVER_ENV"
  fi
  chmod 600 "$SERVER_ENV"
}

build_jar() {
  echo "==> Собираю jar"
  "$ROOT/gradlew" :sync-cli:jar
  [[ -f "$ROOT/sync-cli/build/libs/magic-sync.jar" ]] || die "jar не собрался"
}

ensure_remote_java() {
  echo "==> Java 17 на ${DEPLOY_HOST}"
  local java_bin
  java_bin="$(remote bash -s <<'EOF'
set -euo pipefail
java17() {
  command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -Eq 'version "(1[7-9]|[2-9][0-9])'
}
if java17; then
  command -v java
  exit 0
fi
if ! command -v apt-get >/dev/null 2>&1; then
  echo "нужна Java 17, apt-get на сервере нет" >&2
  exit 1
fi
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >&2
apt-get install -y openjdk-17-jre-headless >&2
if [[ -x /usr/lib/jvm/java-17-openjdk-amd64/bin/java ]]; then
  echo /usr/lib/jvm/java-17-openjdk-amd64/bin/java
elif [[ -x /usr/lib/jvm/java-17-openjdk-arm64/bin/java ]]; then
  echo /usr/lib/jvm/java-17-openjdk-arm64/bin/java
else
  command -v java
fi
EOF
)"
  [[ -n "$java_bin" ]] || die "не нашёл Java на сервере"
  set_env_value MAGIC_SYNC_JAVA "$java_bin"
}

sync_files() {
  local dest="${DEPLOY_USER}@${DEPLOY_HOST}:${DEPLOY_REMOTE_DIR}/"
  local -a rsh
  mapfile -t rsh < <(rsync_rsh)
  echo "==> Копирую на ${DEPLOY_REMOTE_DIR}"
  remote "mkdir -p $(printf '%q' "$DEPLOY_REMOTE_DIR")/data"
  RSYNC_RSH="${rsh[*]}" rsync -az "$ROOT/sync-cli/build/libs/magic-sync.jar" "${dest}magic-sync.jar"
  RSYNC_RSH="${rsh[*]}" rsync -az "$ROOT/deploy/serve.sh" "${dest}serve.sh"
  RSYNC_RSH="${rsh[*]}" rsync -az "$SERVER_ENV" "${dest}magic-sync.env"
  remote "cat > /etc/systemd/system/magic-sync.service" < "$ROOT/deploy/magic-sync.service"
}

install_service() {
  local port
  port="$(listen_port)"
  echo "==> systemd magic-sync"
  remote bash -s -- "$DEPLOY_REMOTE_DIR" "$port" <<'EOF'
set -euo pipefail
dir="$1"
port="$2"
if ! id magic-sync >/dev/null 2>&1; then
  shell=/usr/sbin/nologin
  [[ -x "$shell" ]] || shell=/bin/false
  useradd --system --home-dir "$dir" --shell "$shell" magic-sync
fi
mkdir -p "$dir/data"
chown magic-sync:magic-sync "$dir" "$dir/data" "$dir/magic-sync.env"
chmod 755 "$dir/serve.sh"
chmod 644 "$dir/magic-sync.jar"
chmod 600 "$dir/magic-sync.env"
systemctl daemon-reload
systemctl enable magic-sync
systemctl restart magic-sync
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  ufw allow "${port}/tcp" >/dev/null || true
  echo "ufw: открыт порт ${port}/tcp"
fi
EOF
}

cmd_check() {
  local port
  port="$(listen_port)"
  echo "==> ${DEPLOY_USER}@${DEPLOY_HOST}"
  remote "systemctl is-active magic-sync"
  local code
  code="$(remote "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:${port}/v1/index")"
  if [[ "$code" == "401" ]]; then
    echo "  /v1/index без токена отвечает 401"
  else
    remote "journalctl -u magic-sync -n 40 --no-pager" || true
    die "ждал 401 от /v1/index, получил ${code:-нет ответа}"
  fi
  echo "Адрес для телефона и компьютера: http://${DEPLOY_HOST}:${port}"
  echo "Токен: ${SERVER_ENV}"
}

cmd_up() {
  ensure_server_env
  load_server_env
  build_jar
  ensure_remote_java
  load_server_env
  sync_files
  install_service
  sleep 1
  cmd_check
}

cmd_token() {
  load_server_env
  printf '%s\n' "$MAGIC_SYNC_TOKEN"
}

cmd_down() {
  echo "==> Останавливаю magic-sync, каталог с данными остаётся"
  remote "systemctl disable --now magic-sync"
}

cmd_logs() {
  remote "journalctl -u magic-sync -n 100 -f"
}

main() {
  local cmd="${1:-}"
  case "$cmd" in
    -h|--help|help|'') usage ;;
    up) load_env; cmd_up ;;
    check|status) load_env; load_server_env; cmd_check ;;
    token) load_server_env; cmd_token ;;
    down) load_env; cmd_down ;;
    logs) load_env; cmd_logs ;;
    *) die "не понял команду: $cmd (см. --help)" ;;
  esac
}

main "$@"
