# PgGitVisualizer (pg-git-vc)

Контроль версий схемы PostgreSQL 11+ с визуальным графом коммитов в стиле GitKraken.
Один исполняемый файл `pg-git-vc.jar` (Spring Boot 3 + встроенный React UI), без Docker.

## Что умеет

- **Mode A — перехват изменений из IDE.** Меняете схему в DBeaver / pgAdmin / DataGrip, событийные триггеры
  (`EVENT TRIGGER`) пишут DDL в `_pgvc.ddl_audit_log`, а интроспектор сравнивает `pg_catalog` со снапшотом `HEAD`.
  Вверху графа появляется узел **Working Tree (Uncommitted Changes)** с готовыми `UP SQL` и `DOWN SQL` в Monaco —
  их можно поправить и закоммитить.
- **Mode B — миграция из UI.** Вкладка **New SQL migration**: `UP SQL` (и опционально `DOWN SQL`) выполняется в одной
  транзакции вместе с интроспекцией и записью коммита. Ошибка — полный `ROLLBACK`. `DOWN SQL` достраивается автоматически.
- **Окружения (Dev / Test / Stage / Prod).** Список подключений в `./pgvc-environments.json`. На графе — цветные бейджи,
  показывающие, на каком коммите каждая БД. **Push commit(s) to environment** накатывает недостающие коммиты одной транзакцией.
- **Офлайн-бандл.** Выделите коммит (или диапазон через Shift+клик) → **Export offline SQL bundle**. Получится
  автономный `.sql`: создаёт `_pgvc`, проверяет `HEAD` целевой БД (`RAISE EXCEPTION` при рассинхроне), выполняет
  `UP SQL`, записывает коммиты со снапшотами и сдвигает `HEAD` — всё внутри `BEGIN ... COMMIT`.

## Сборка и запуск

Нужны JDK 17+ и Maven 3.9+. Node.js ставить не нужно — Maven скачает его сам в `target/node`.

```bash
mvn package            # тесты + сборка UI + target/pg-git-vc.jar
java -jar target/pg-git-vc.jar
```

Откройте http://localhost:8765, нажмите **Environments** и добавьте подключение, затем **Set up version control**
(создаёт схему `_pgvc` и event triggers — нужен `SUPERUSER`).

Параметры (через `--ключ=значение` или `application.properties` рядом с jar):

| Параметр | По умолчанию |
|---|---|
| `server.port` | `8765` |
| `server.address` | `127.0.0.1` (только локально) |
| `pgvc.environments-file` | `./pgvc-environments.json` |
| `pgvc.connect-timeout-seconds` | `5` |

Формат `pgvc-environments.json` (пароли хранятся открытым текстом, файл в `.gitignore`):

```json
[
  { "id": "dev",  "name": "Dev",  "color": "#22c55e", "jdbcUrl": "jdbc:postgresql://localhost:5432/app", "username": "postgres", "password": "..." },
  { "id": "prod", "name": "Prod", "color": "#ef4444", "jdbcUrl": "jdbc:postgresql://prod:5432/app",      "username": "postgres", "password": "..." }
]
```

Применение офлайн-бандла на закрытой площадке:

```bash
psql -v ON_ERROR_STOP=1 -d app -f pgvc-bundle-<from>-<to>.sql
```

## Разработка

```bash
mvn package -DskipFrontend    # только бэкенд (UI возьмётся из frontend/dist, если он собран)
cd frontend && npm install && npm run dev   # UI на :5173 с прокси /api -> :8765
```

Интеграционный тест полного цикла (Mode A/B, push, проверка DOWN SQL, офлайн-бандл) запускается на реальном PostgreSQL:

```bash
PGVC_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres PGVC_TEST_USER=postgres PGVC_TEST_PASSWORD=... mvn test
```

CI (`.github/workflows/build.yml`) гоняет сборку и тесты на PostgreSQL 11 и 16 и выкладывает `pg-git-vc.jar` как артефакт.

## Как устроено

```
src/main/resources/sql/install.sql      схема _pgvc + event triggers (идемпотентно)
src/main/resources/sql/introspect.sql   вся модель схемы одним запросом к pg_catalog -> JSON
core/model        DatabaseSnapshot: таблицы (в т.ч. партиции), колонки, ограничения, индексы, sequences, view/matview
core/diff         DiffEngine: снапшот A -> снапшот B = упорядоченный DDL
core/service      Mode A, Mode B, push, offline bundle, граф
web               REST API (/api/**) для React UI
frontend          React + @xyflow/react (граф) + @monaco-editor/react (редактор SQL, Monaco собран локально, без CDN)
```

Ключевые решения Diff Engine:

- **Переименования** определяются по `pg_class.oid` (таблицы, view, sequences, индексы), `pg_attribute.attnum` (колонки)
  и `pg_constraint.oid`. OID сравниваются только между снапшотами одной и той же БД (`system_identifier:oid базы`),
  между разными БД объекты сопоставляются по имени.
- **Изменение объекта** без учёта переименований определяется по «сигнатуре» из каталога (attnum-ы, деревья выражений
  `conbin`/`indexprs`, `pg_rewrite.ev_action`), поэтому переименование колонки не пересоздаёт PK или индекс.
- **Безопасный порядок DDL:** view, зависящие (в т.ч. транзитивно) от меняющих тип или удаляемых колонок, сначала
  `DROP VIEW` (зависимые первыми), затем `ALTER TABLE`, затем `CREATE VIEW` в порядке зависимостей; индексы
  пересоздаваемых materialized view восстанавливаются. Родительские партиционированные таблицы создаются раньше партиций,
  FK удаляются раньше таблиц и добавляются после всех остальных ограничений.
- `information_schema` не используется вообще — только `pg_catalog`.

## Ограничения текущей версии

- Отслеживаются схемы, таблицы (обычные и партиционированные), колонки, ограничения, индексы, sequences, view и
  materialized view. Функции, триггеры, пользовательские типы, расширения, гранты и комментарии пока не отслеживаются.
- Смену ключа партиционирования PostgreSQL не умеет делать на месте — Diff Engine выдаёт предупреждение.
- `UP SQL` выполняется внутри транзакции, поэтому в нём нельзя использовать `BEGIN/COMMIT` и `CREATE INDEX CONCURRENTLY`.
- Текст `pg_get_viewdef` различается между мажорными версиями PostgreSQL: если окружения на разных версиях, view могут
  выглядеть изменёнными. Для push в такое окружение есть флажок «Allow uncommitted changes on target».
