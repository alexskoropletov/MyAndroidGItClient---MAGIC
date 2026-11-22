# Готовые билды

После `git clone` можно синхронизировать без Gradle — нужен только Java 17+.

| Файл | Платформа | Как запускать |
|------|-----------|---------------|
| `magic-sync.jar` | Windows / Linux / macOS | `scripts/sync-vault.ps1` или `scripts/sync-vault.sh` |
| `MAGIC-debug.apk` | Android | установить на телефон |

Обновить jar после правок кода:

```bash
./gradlew :sync-cli:jar
cp sync-cli/build/libs/magic-sync.jar dist/magic-sync.jar
```

На Windows: `.\gradlew :sync-cli:jar`, затем скопировать jar в `dist\`.
