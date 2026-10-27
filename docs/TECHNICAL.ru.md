# PgGitVisualizer — техническая справка

Подробности устройства: формат окружений, REST API, схема `_pgvc`, интроспекция, Diff Engine, офлайн-бандлы,
синхронизация, привязка окружений и тесты. Обзор, сборка и запуск — в [README](../README.md).

История разработки: каждый шаг — отдельная ветка и Pull Request в `main`.

| Шаг | Содержание |
|---|---|
| 1 | Backend core, менеджер окружений, инициализатор `_pgvc` |
| 2 | Интроспектор схемы PostgreSQL 11+ (`pg_catalog`) |
| 3 | Diff Engine, коммиты (Mode A / Mode B) и откат |
| 4 | Офлайн SQL-бандлы, синхронизация окружений, REST |
| 5 | Веб-интерфейс (React + TypeScript) |
| 6 | Привязка существующих окружений к общей истории (baseline) |
| 7 | Интерфейс и сообщения сервера на русском и английском, лицензия Apache 2.0, окно «О проекте» |

## Окружения

Список подключений хранится в `./pgvc-environments.json` рядом с jar (файл в `.gitignore`, пароли — открытым текстом):

```json
{
  "activeEnvironmentId": "dev",
  "environments": [
    { "id": "dev", "name": "Dev", "jdbcUrl": "jdbc:postgresql://localhost:5432/app",
      "username": "postgres", "password": "...", "schemaName": "public", "colorBadge": "#22c55e" }
  ]
}
```

- `schemaName` — схема по умолчанию для подключения (`search_path`). Контроль версий отслеживает **все**
  пользовательские схемы базы.
- `colorBadge` — цвет бейджа окружения на графе.
- Для каждого окружения создаётся свой пул соединений; `JdbcTemplate` и `TransactionTemplate` кэшируются и
  пересоздаются при изменении настроек.

## REST API

| Метод | Путь | Что делает |
|---|---|---|
| GET | `/api/environments` | список окружений (без паролей) |
| GET | `/api/environments/{id}` | одно окружение |
| POST | `/api/environments` | добавить |
| PUT | `/api/environments/{id}` | изменить (без `password` — пароль сохраняется) |
| DELETE | `/api/environments/{id}` | удалить |
| POST | `/api/environments/test` | проверить несохранённые настройки (`SELECT version()`) |
| POST | `/api/environments/{id}/test` | проверить сохранённое окружение |
| GET | `/api/environments/active` | активное окружение (204, если нет) |
| PUT | `/api/environments/active` | переключить: `{"environmentId": "prod"}` |
| POST | `/api/environments/{id}/pgvc/initialize` | установить схему `_pgvc` и event triggers |
| GET | `/api/environments/{id}/pgvc/status` | состояние контроля версий |
| GET | `/api/environments/{id}/pgvc/commits` | граф коммитов и `HEAD` |
| GET | `/api/environments/{id}/pgvc/head` | текущий `HEAD` |
| GET | `/api/environments/{id}/pgvc/audit-log` | перехваченные DDL с последнего коммита |
| DELETE | `/api/environments/{id}/pgvc/audit-log[?upToId=N]` | очистить лог (целиком или до id включительно) |
| GET | `/api/environments/{id}/schema/schemas` | пользовательские схемы, доступные для контроля версий |
| GET | `/api/environments/{id}/schema/snapshot[?schemas=a,b]` | снимок схемы из `pg_catalog` (по умолчанию — все схемы) |
| GET | `/api/environments/{id}/vc/working-tree` | незакоммиченные изменения: список изменений, UP/DOWN SQL, перехваченный DDL |
| POST | `/api/environments/{id}/vc/commit` | Mode A: закоммитить working tree (`message`, `author`, `upSql`, `downSql`, `expectedHead`) |
| POST | `/api/environments/{id}/vc/execute` | Mode B: выполнить миграцию и закоммитить (`message`, `author`, `upSql`, `downSql`, `allowDirty`) |
| GET | `/api/environments/{id}/vc/commits/{hash}` | коммит целиком: SQL, снимок схемы, история DDL |
| GET | `/api/environments/{id}/vc/commits/{hash}/changes` | список изменений коммита относительно родителя |
| GET | `/api/environments/{id}/vc/rollback-plan?target={hash}` | SQL, который выполнит откат до коммита |
| POST | `/api/environments/{id}/vc/rollback` | откат (`targetHash`, `customRollbackSql`, `allowDirty`, `skipVerification`) |
| GET | `/api/vc/graph` | общий граф коммитов всех окружений и `HEAD` каждого (бейджи) |
| GET | `/api/vc/compare?source=dev&target=prod` | сравнение историй и схем двух окружений |
| POST | `/api/vc/link` | привязка (baseline): `sourceEnvironmentId`, `targetEnvironmentId`, `commitHash`, `allowSchemaDifferences`, `keepTargetCommits` |
| POST | `/api/vc/push` | онлайн-накат: `sourceEnvironmentId`, `targetEnvironmentId`, `targetCommitHash`, `allowDirty`, `skipVerification` |
| GET | `/api/migrations/export?source=dev[&from=..][&to=..]` | скачать офлайн `.sql`-бандл (`Content-Disposition: attachment`) |
| GET | `/api/migrations/preview?source=dev[&from=..][&to=..]` | тот же бандл в JSON (коммиты, имя файла, SQL) |
| GET | `/api/about` | название, версия сборки, лицензия и её полный текст (окно «О проекте») |

Контроллеры: `EnvironmentController` (окружения), `VcController` (`_pgvc`, схема, коммиты, откат, граф, сравнение,
push), `MigrationExportController` (офлайн-бандлы).

Пример:

```bash
curl -X POST localhost:8765/api/environments -H 'Content-Type: application/json' \
  -d '{"name":"Dev","jdbcUrl":"jdbc:postgresql://localhost:5432/app","username":"postgres","password":"secret"}'
curl -X POST localhost:8765/api/environments/dev/test
curl -X POST localhost:8765/api/environments/dev/pgvc/initialize
```

## Схема `_pgvc`

`POST .../pgvc/initialize` выполняет `src/main/resources/db/pgvc-install.sql` в одной транзакции (идемпотентно,
нужен `SUPERUSER`):

- таблицы `_pgvc.commits`, `_pgvc.refs`, `_pgvc.ddl_audit_log`;
- функции `_pgvc.fn_log_ddl_end()` и `_pgvc.fn_log_sql_drop()`;
- `EVENT TRIGGER pgvc_ddl_trigger ON ddl_command_end` и `EVENT TRIGGER pgvc_drop_trigger ON sql_drop`.

Триггеры не логируют изменения в самой схеме `_pgvc`, во временных (`pg_temp%`) и TOAST-схемах. Функции объявлены
`SECURITY DEFINER`: DDL обычных разработчиков (не суперпользователей) логируется, а не падает с
«permission denied», и в `db_user` записывается реальный пользователь (`session_user`).

## Интроспекция схемы (Шаг 2)

`PostgresSchemaIntrospector` читает живую схему только из `pg_catalog` (`information_schema` не видит партиций и OID)
и собирает `DatabaseSnapshot` (пакет `model.schema`). Каждый запрос лежит отдельным файлом в
`src/main/resources/db/introspection/`:

| Файл | Что читает |
|---|---|
| `schemas.sql` | пользовательские схемы (без системных, временных, схем расширений и `_pgvc`) |
| `tables.sql` | таблицы `relkind IN ('r','p')`: `oid`, `pg_get_partkeydef`, родитель через `pg_inherits`, границы `pg_get_expr(relpartbound)` |
| `columns.sql` | `pg_attribute` (`attnum > 0 AND NOT attisdropped`): `format_type`, `DEFAULT` через `pg_get_expr(adbin)`, identity, generated (PG12+), collation |
| `constraints.sql` | PK / FK / UNIQUE / CHECK / EXCLUDE через `pg_get_constraintdef`; унаследованные партициями пропускаются |
| `indexes.sql` | `pg_index` (`relkind IN ('i','I')`) без индексов PK/UNIQUE/EXCLUDE (`conindid`) и без автоматически привязанных индексов партиций |
| `sequences.sql` | `pg_sequence`: параметры и колонка-владелец; последовательности identity-колонок пропускаются |
| `views.sql`, `view-dependencies.sql` | обычные и materialized view, `pg_get_viewdef`, зависимости от таблиц и колонок |

Особенности модели:

- Снимок охватывает **все** пользовательские схемы; ключи карт — полные имена `схема.объект`, у объектов есть `schemaName`.
- `oid` (таблицы, view, индексы, sequences, constraints) и `attnum` (колонки) сохраняются, чтобы находить переименования.
- `signature` — хеш каталожного представления объекта, который не меняется при переименовании таблиц и колонок:
  по нему видно, изменился ли объект на самом деле.
- Все запросы выполняются в одной read-only транзакции `REPEATABLE READ` (согласованный снимок). Если транзакция
  на окружении уже открыта, интроспектор присоединяется к ней и видит незакоммиченный DDL.

## Diff Engine и коммиты (Шаг 3)

`SchemaDiffEngine` сравнивает снимок `HEAD` с живой схемой и возвращает `SchemaDiffResult`: список `ChangeItem`
(`ADDED` / `MODIFIED` / `RENAMED` / `DELETED` для `SCHEMA`, `TABLE`, `PARTITION`, `COLUMN`, `CONSTRAINT`, `INDEX`,
`VIEW`, `SEQUENCE`) и полные скрипты `upSql` / `downSql`.

- **Переименования.** Таблица, view, sequence, индекс или constraint с тем же `oid`, но другим именем — это
  `ALTER ... RENAME`, а не удаление + создание; колонка с тем же `attnum` — `RENAME COLUMN`. После переименования
  таблицы сравниваются её колонки, ограничения и индексы. OID сравниваются только между снимками одной базы.
- **Партиции.** Новая партиционированная таблица — `CREATE TABLE ... PARTITION BY <ключ>`, новая партиция —
  `CREATE TABLE child PARTITION OF parent <границы>` (родители раньше детей); смена границ — `DETACH` + `ATTACH`.
  Индекс партиционированной таблицы создаётся без `ON ONLY`, чтобы он появился и на всех партициях.
- **Порядок DDL.** View (и materialized view), которые читают меняющуюся или удаляемую колонку / таблицу, в том числе
  через другие view, удаляются в начале скрипта (`DROP VIEW IF EXISTS`, сначала зависимые) и создаются в конце
  (`CREATE OR REPLACE VIEW`, сначала те, от кого зависят); индексы materialized view восстанавливаются. Зависимости
  берутся из `pg_depend` с точностью до колонки, поэтому добавление колонки или индекса view не пересоздаёт.
  FK удаляются раньше таблиц и добавляются после остальных ограничений.
- **UP/DOWN каждого изменения.** У каждого `ChangeItem` свои `upSql` и `downSql` (обратное изменение получено
  сравнением в обратную сторону). Выполнять нужно полные `upSql` / `downSql` результата — в них правильный порядок.

`VersionControlService`:

- `getWorkingTreeStatus` — изменения с последнего коммита и перехваченный триггерами DDL.
- `commitWorkingTree` (Mode A) — сохраняет коммит с `upSql` / `downSql` из запроса (пользователь мог их поправить;
  пустые заменяются сгенерированными) и снимком живой схемы, сдвигает `HEAD`, очищает `ddl_audit_log`.
  Если передан `expectedHead`, а `HEAD` уже другой, коммит отклоняется.
- `executeAndCommitSql` (Mode B) — в одной транзакции выполняет `upSql`, снимает новую схему, строит `downSql`
  (если не передан), сохраняет коммит. Любая ошибка — полный откат. `BEGIN/COMMIT` внутри SQL запрещены. Если в базе
  есть незакоммиченные изменения, миграция не выполняется (или включает их при `allowDirty`).
- `rollbackToCommit` — в одной транзакции выполняет `down_sql` коммитов после целевого (от новых к старым) или
  `customRollbackSql`, **проверяет, что схема совпала со снимком целевого коммита**, сдвигает `HEAD`, очищает лог.
  При ошибке или расхождении ничего не меняется. Коммиты после целевого остаются в истории (как `git reset`), новый
  коммит начнёт от целевого ветку.

Хеш коммита — SHA-256 от родителя, автора, сообщения, времени, SQL и снимка схемы.

## Офлайн-бандлы и синхронизация окружений (Шаг 4)

**Офлайн-бандл** (`OfflineMigrationService.generateOfflineSqlScript(source, from, to)`) — один `.sql` файл для
площадок без сетевого доступа. Содержит коммиты после `from` (не включая; без `from` — с самого начала) до `to`
(включая; без `to` — до `HEAD` источника). Всё внутри `BEGIN; ... COMMIT;`:

1. создаёт схему `_pgvc` и её таблицы, если их нет (event triggers — только если скрипт выполняет суперпользователь,
   иначе выводится `NOTICE`, остальное применяется);
2. блок `DO $$ ... $$` проверяет `HEAD` целевой базы: он должен быть равен `from` (или история пуста, если `from` не
   задан), иначе `RAISE EXCEPTION 'Migration mismatch: expected HEAD %, but found %'`; повторное применение даёт
   `Migration already applied`;
3. выполняет `up_sql` коммитов по порядку;
4. записывает коммиты (`schema_snapshot`, `up_sql`, `down_sql` — в dollar-quoting, без ручного экранирования) и
   сдвигает `HEAD` на `to`.

При любой ошибке ничего не применяется. Применение: `psql -v ON_ERROR_STOP=1 -d <db> -f <файл>` или DBeaver.

**Онлайн-синхронизация** (`VersionControlService`):

- `compareEnvironments(source, target)` — отношение историй (`SAME`, `TARGET_BEHIND`, `TARGET_AHEAD`, `DIVERGED`,
  `TARGET_EMPTY`, `TARGET_NOT_INITIALIZED`), общий предок, коммиты для наката, есть ли на цели незакоммиченные
  изменения, и diff живой схемы цели со схемой `HEAD` источника.
- `pushCommitsToEnvironment(source, target, commit)` — в одной транзакции цели выполняет недостающие `up_sql`,
  записывает коммиты с теми же хешами, сдвигает `HEAD` и проверяет, что схема цели совпала со снимком последнего
  коммита. При необходимости сначала устанавливает `_pgvc`. Откажет, если цель впереди или истории разошлись, или если
  на цели есть незакоммиченные изменения (можно разрешить).
- `getGlobalGraph()` — коммиты всех окружений в одном графе с `HEAD` каждого окружения.

## Привязка существующего окружения (Шаг 6)

Обычная ситуация: Dev, Stage и Prod уже существуют с одинаковой схемой. Если закоммитить каждое отдельно, у них
будут разные коммиты (хеш включает автора и время), истории не связаны, и push невозможен («Diverged»). Push в
окружение без истории тоже не подходит — он выполнил бы `CREATE TABLE` для уже существующих таблиц.

Для этого есть привязка (baseline) — `linkEnvironmentToHistory`, `POST /api/vc/link`:

- `compareEnvironments` для несвязанных историй (`DIVERGED`, или у цели нет истории, но схема уже есть) ищет
  самый новый коммит источника, снимок которого совпадает с живой схемой цели (`matchingCommit`).
- Привязка в одной транзакции цели: при необходимости устанавливает `_pgvc`; проверяет, что живая схема цели совпадает
  со снимком выбранного коммита; записывает в цель коммиты источника до него (с теми же хешами); ставит `HEAD` на
  этот коммит; удаляет прежние собственные коммиты цели (можно оставить — `keepTargetCommits`); очищает лог DDL.
  **SQL миграций не выполняется.** Если схемы расходятся — ничего не меняется, в ответе список различий
  (принять их осознанно можно флагом `allowSchemaDifferences`, тогда они останутся незакоммиченными изменениями цели).
- После привязки цель просто «отстаёт» от источника, и более новые коммиты докатываются обычным push.

В UI: **Sync / Push to Env** → при несвязанных историях появляется панель **Link … to the history of …** с найденным
коммитом и кнопкой **Link to …**; после привязки окно сразу показывает коммиты для push.

## Языки (Шаг 7)

**Интерфейс.** Лёгкий типизированный контекст `frontend/src/i18n/index.tsx` без внешних библиотек и два словаря
`frontend/src/i18n/en.json` и `ru.json`. TypeScript проверяет, что ключи в обоих словарях совпадают, а каждый
вызов `t('…')` ссылается на существующий ключ. Множественное число (`1 коммит / 3 коммита / 5 коммитов`) выбирается
через `Intl.PluralRules`, даты и «5 мин. назад» — через `Intl.DateTimeFormat` / `Intl.RelativeTimeFormat`.
Язык переключается кнопками `RU | EN` в верхней панели без перезагрузки и хранится в `localStorage` (`pgvc_lang`,
по умолчанию `ru`).

**Сервер.** Каждый запрос UI несёт заголовок `Accept-Language: ru|en`. Ошибки (`ApiException`) создаются по ключу
сообщения с аргументами; `GlobalExceptionHandler` рендерит их через Spring `MessageSource` из
`src/main/resources/i18n/messages_en.properties` / `messages_ru.properties` (UTF-8). Без заголовка или для других
языков — английский; `ApiException.getMessage()` всегда английский (логи, тесты). Тексты ошибок самого PostgreSQL
передаются как есть. Описания изменений и предупреждения Diff Engine (`new table`, `renamed from …`) пока только
на английском.

## Тесты

```bash
mvn test                                         # без БД: юнит-тесты и REST
PGVC_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres \
PGVC_TEST_USER=postgres PGVC_TEST_PASSWORD=... mvn test   # + интеграционные тесты на реальном PostgreSQL
```

Интеграционные тесты создают свои базы (`pgvc_step*`) и роль, поэтому нужен суперпользователь.
CI (`.github/workflows/build.yml`) прогоняет всё на PostgreSQL 11 и 16, проверяет типы TypeScript, запускает собранный
jar и проходит основные сценарии UI в браузере (Playwright): коммит, push, Mode B и откат.
