# Staging MAGIC sync and dist builds

- Date: 2026-11-22
- Participants: user, agent

## Goal

Настроить синхронизацию Obsidian-хранилища `E:\work\obsidian-sync` через staging VPS на телефон; обеспечить запуск sync с Windows / Linux / Android из клона репозитория без локальной сборки.

## Decisions

- Не Syncthing: на VPS крутится **MAGIC sync** (`magic-sync.jar serve` на `:8787`, unit `magic-sync.service`, данные в `/opt/magic-sync/data`).
- Клиент для десктопа — `sync-cli` → `dist/magic-sync.jar` (одна JVM-сборка для Windows и Linux).
- Android — приложение MAGIC / `dist/MAGIC-debug.apk`.
- Готовые билды хранить в `dist/`; скрипты сначала берут jar оттуда.
- Чат-сессии агента обязательно сохранять в `docs/chats/` (правило `.cursor/rules/save-chat-sessions.mdc`).

## Done

- На VPS подтверждён работающий `magic-sync` (порт 8787).
- В репозитории: правило сохранения чатов; `scripts/sync-vault.ps1`; jar resolution через `dist/`; `publish-dist` скрипты; `.gitignore` разрешает `dist/*.jar`.
- `dist/magic-sync.jar` скопирован с staging VPS.

## Next

- На этом ПК: Java 17+, `.env` из `.env.example` (vault / server / token / key / device), первый `.\scripts\sync-vault.ps1`.
- Закоммитить изменения (включая `dist/magic-sync.jar`) и запушить.
- Не хранить root-пароль VPS в чатах/репо; лучше ротировать.
