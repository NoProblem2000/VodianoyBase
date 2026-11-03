# PgGitVisualizer (pg-git-vc)

**Git-like version control for PostgreSQL schemas** — one executable jar with a GitKraken-style web UI.

[English](#english) · [Русский](#русский)

---

## English

PgGitVisualizer keeps the history of a PostgreSQL schema **inside the database itself** (schema `_pgvc`), shows it as
a commit graph and moves changes between Dev, Stage, Prod and isolated customer sites.

### Why

- **No Docker, single JAR** — `java -jar pg-git-vc-1.8.1.jar`, the UI is built in; nothing to install on the servers.
- **GitKraken-style graph** — commits of all environments in one graph, colored badges show where every database is.
- **Captures changes from any IDE** — event triggers log the DDL of DBeaver / pgAdmin / DataGrip; a **Working Tree**
  node shows what is not committed yet, with ready UP / DOWN SQL.
- **Transaction-safe rollbacks** — rollback, discard and push run in one transaction, with `lock_timeout`, a schema
  check afterwards and a migration safety analyzer (locks, rewrites, data loss, zero-downtime patterns).
- **Offline sync** — self-registering `.sql` bundles for `psql`, and state-based `.pgsnap.json` snapshots for
  customer databases with their own history.
- Also: N-database matrix, ignore rules, reference data (dictionary rows), Git folder export, CLI for CI/CD,
  Russian and English UI with a built-in manual (**📖 Docs**).

### Quick start

Requirements: **Java 17+**, **PostgreSQL 11+**, a **SUPERUSER** once per database to create the `_pgvc` schema and
the event triggers (afterwards an ordinary role is enough).

```bash
# download pg-git-vc-1.8.1.jar from the Releases page, or build it:
mvn clean package                        # tests + UI -> target/pg-git-vc-1.8.1.jar
java -jar target/pg-git-vc-1.8.1.jar     # then open http://localhost:8765
```

1. **Environments → Add environment** (JDBC URL, user, password) → **Test connection** → **Save**.
2. **Initialize version control** on the yellow bar.
3. Select the **Working Tree** node → **Commit Changes** ("Baseline").

The build needs network access for Maven and npm (the frontend-maven-plugin downloads Node itself). Tests against a
real database run when `PGVC_TEST_JDBC_URL` is set. Options: `--server.port=…`, `--server.address=…` (default
`127.0.0.1`), `--pgvc.environments-file=…` (connections, passwords in plain text — keep it private).

### Documentation

| Document | Contents |
|---|---|
| [docs/MANUAL_EN.md](docs/MANUAL_EN.md) | user & architecture manual: `_pgvc` and event triggers, Workflow A / B, discard, branches and rollback, the three ways to ship changes to a customer, troubleshooting |
| [docs/MANUAL_RU.md](docs/MANUAL_RU.md) | the same in Russian |
| [docs/TECHNICAL.ru.md](docs/TECHNICAL.ru.md) | technical reference (Russian): REST API, formats, diff engine, safety analyzer, CLI, step history |
| **📖 Docs** in the UI | the manual inside the application (works offline, RU / EN) |

### License

[Apache License 2.0](LICENSE) — Copyright 2026 NoProblem2000. See also [NOTICE](NOTICE).

---

## Русский

PgGitVisualizer хранит историю схемы PostgreSQL **в самой базе** (схема `_pgvc`), показывает её графом коммитов и
переносит изменения между Dev, Stage, Prod и изолированными площадками заказчиков.

### Преимущества

- **Без Docker, один JAR** — `java -jar pg-git-vc-1.8.1.jar`, интерфейс встроен; на серверы ничего не ставится.
- **Граф в стиле GitKraken** — коммиты всех окружений в одном графе, цветные бейджи показывают, где каждая база.
- **Изменения из любой IDE** — event triggers записывают DDL из DBeaver / pgAdmin / DataGrip; узел **Working Tree**
  показывает незакоммиченное с готовыми UP / DOWN SQL.
- **Транзакционно безопасные откаты** — откат, сброс и push выполняются одной транзакцией, с `lock_timeout`,
  проверкой схемы после и анализатором безопасности (блокировки, перезапись, потеря данных, шаблоны без простоя).
- **Офлайн-синхронизация** — саморегистрирующиеся `.sql`-пакеты для `psql` и слепки `.pgsnap.json` для баз
  заказчиков со своей историей.
- А также: матрица N баз, правила игнорирования, справочники (строки таблиц), экспорт в Git-папку, CLI для CI/CD,
  интерфейс на русском и английском со встроенным руководством (**📖 Руководство**).

### Быстрый старт

Требования: **Java 17+**, **PostgreSQL 11+**, один раз на базу — **SUPERUSER** для создания схемы `_pgvc` и event
triggers (дальше достаточно обычной роли).

```bash
# скачайте pg-git-vc-1.8.1.jar со страницы Releases или соберите:
mvn clean package                        # тесты + интерфейс -> target/pg-git-vc-1.8.1.jar
java -jar target/pg-git-vc-1.8.1.jar     # затем откройте http://localhost:8765
```

1. **Окружения → Добавить окружение** (JDBC URL, пользователь, пароль) → **Проверить подключение** → **Сохранить**.
2. На жёлтой полосе — **Подключить контроль версий**.
3. Выберите узел **Незакоммиченные изменения (Working Tree)** → **Закоммитить изменения** («Baseline»).

Для сборки нужен доступ к Maven и npm (frontend-maven-plugin сам скачивает Node). Тесты на реальной базе
выполняются, если задан `PGVC_TEST_JDBC_URL`. Параметры: `--server.port=…`, `--server.address=…` (по умолчанию
`127.0.0.1`), `--pgvc.environments-file=…` (подключения, пароли открытым текстом — не публикуйте файл).

### Документация

| Документ | Содержание |
|---|---|
| [docs/MANUAL_RU.md](docs/MANUAL_RU.md) | руководство пользователя и архитектора: `_pgvc` и event triggers, Workflow A / B, сброс изменений, ветки и откат, три способа переноса изменений к заказчику, troubleshooting |
| [docs/MANUAL_EN.md](docs/MANUAL_EN.md) | то же на английском |
| [docs/TECHNICAL.ru.md](docs/TECHNICAL.ru.md) | техническая справка: REST API, форматы, Diff Engine, анализатор безопасности, CLI, история шагов |
| **📖 Руководство** в интерфейсе | руководство внутри приложения (работает без интернета, RU / EN) |

### Лицензия

[Apache License 2.0](LICENSE) — Copyright 2026 NoProblem2000. См. также [NOTICE](NOTICE).
