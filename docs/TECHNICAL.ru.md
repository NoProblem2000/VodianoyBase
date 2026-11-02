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
| 8 | Удаление брошенных коммитов и веток |
| 9 | Эталонные слепки `.pgsnap.json`: экспорт, сравнение с базой заказчика, накат |
| 10 | Анализатор безопасности миграций, Dry-Run, таймауты, Pre/Post-скрипты, многошаговые миграции |
| 11 | Функции, процедуры, триггеры, ENUM, расширения, комментарии, владельцы/права/tablespace; правила игнорирования; матрица N баз |

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
| POST | `/api/environments/{id}/vc/execute` | Mode B: `message`, `author`, `upSql`, `downSql`, `allowDirty`, `lockTimeout`, `statementTimeout`, `preScript`, `postScript`, `dryRun` (при `dryRun: true` — отчёт `DryRunResult`, ничего не сохраняется) |
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
| GET | `/api/vc/commits/{hash}/deletion-plan?mode=COMMIT\|BRANCH` | что удалит удаление коммита / ветки и что мешает |
| DELETE | `/api/vc/commits/{hash}?mode=COMMIT\|BRANCH` | удалить неиспользуемый коммит (с потомками) или брошенную ветку |
| GET | `/api/vc/snapshot/export?envId=dev[&commitHash=..]` | скачать эталонный слепок `snapshot_<ветка>_<хеш>.pgsnap.json` |
| POST | `/api/vc/snapshot/compare` | `{envId, bundle, ignoreRules}`: живая схема окружения против слепка (`ignoreRules` — разовые правила, `null` — сохранённые) |
| POST | `/api/vc/snapshot/apply` | `envId`, `upSql`, `downSql`, `author`, `message`, `sourceCommitHash`: накат и коммит |
| POST | `/api/environments/{id}/vc/analyze` | `{sql, preScript, postScript}`: анализ безопасности произвольного SQL |
| GET | `/api/about` | название, версия сборки, лицензия и её полный текст (окно «О проекте») |
| GET | `/api/settings/ignore-rules` | правила игнорирования по умолчанию (`pgvc-settings.json`) |
| PUT | `/api/settings/ignore-rules` | сохранить правила: `ignoreComments`, `ignoreTablespaces`, `ignoreOwnersAndGrants`, `ignorePartitions`, `excludedObjectPatterns` |
| POST | `/api/vc/matrix/compare` | матрица N баз: `envIds`, `snapshots` (`[{name, bundle}]`), `baseline` (`env:<id>` / `snapshot:<n>`), `ignoreRules` |

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
| `functions.sql` | `pg_proc` (`prokind` f / p / w, агрегаты пропускаются): `pg_get_functiondef`, перегрузки через `pg_get_function_identity_arguments`, `pg_get_function_result` |
| `triggers.sql` | `pg_trigger` с `NOT tgisinternal`, `pg_get_triggerdef(oid, true)`, `tgenabled`, функция триггера; на PG13+ клоны в партициях (`tgparentid <> 0`) пропускаются |
| `enums.sql` | `pg_type` (`typtype = 'e'`) и метки `pg_enum` в порядке `enumsortorder` |
| `extensions.sql` | `pg_extension` (кроме `plpgsql`): имя, схема, версия |

Комментарии (`obj_description` / `col_description`), владельцы (`pg_get_userbyid`), права (`aclexplode` от `relacl` /
`proacl`, а если они `NULL` — от `acldefault`; права самого владельца не хранятся) и tablespace (`reltablespace`, `0` —
tablespace базы по умолчанию) читаются теми же запросами таблиц, представлений, последовательностей и функций.
Снимок v1.6.0 имеет `formatVersion = 2`.

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

## Функции, триггеры, ENUM, расширения, комментарии, права; правила игнорирования; матрица N баз (Шаг 11)

### Новые объекты в Diff Engine

| Объект | Новый | Изменён | Удалён |
|---|---|---|---|
| Функция / процедура | `CREATE OR REPLACE` (из `pg_get_functiondef`) | тело или параметры по умолчанию — `CREATE OR REPLACE`; тип результата или функция ↔ процедура — `DROP` + `CREATE` (триггеры на ней пересоздаются) | `DROP FUNCTION / PROCEDURE имя(аргументы)` |
| Триггер | `CREATE TRIGGER … EXECUTE FUNCTION` (+ `DISABLE` / `ENABLE REPLICA / ALWAYS`) | определение — `DROP TRIGGER` + `CREATE TRIGGER`; только состояние — `ALTER TABLE … ENABLE / DISABLE TRIGGER` | `DROP TRIGGER IF EXISTS … ON …` |
| ENUM | `CREATE TYPE … AS ENUM (…)` | добавленные метки — `ALTER TYPE … ADD VALUE IF NOT EXISTS 'x' AFTER 'y'`; удалённые или переставленные — предупреждение `MANUAL ACTION REQUIRED` | `DROP TYPE` |
| Расширение | `CREATE EXTENSION IF NOT EXISTS "x" WITH SCHEMA s` | версия — `ALTER EXTENSION … UPDATE TO`, схема — `SET SCHEMA` | `DROP EXTENSION IF EXISTS` |
| Комментарий | `COMMENT ON TABLE / COLUMN / VIEW / FUNCTION / TYPE … IS '…'` (кавычки удваиваются) | то же | `… IS NULL` |
| Владелец / права / tablespace | `ALTER … OWNER TO`, `GRANT`, у новой функции `REVOKE EXECUTE … FROM PUBLIC`, если его нет в эталоне; `TABLESPACE` в `CREATE TABLE` | `OWNER TO`, `GRANT` / `REVOKE` (в т. ч. `GRANT OPTION`), `SET TABLESPACE` | — |

Перегрузки функций — разные объекты: ключ `схема.имя(аргументы идентичности)`. Переименование функции, триггера и
ENUM-типа с тем же OID — `ALTER … RENAME`. `EXECUTE PROCEDURE` (так пишет PostgreSQL 11) и `EXECUTE FUNCTION`
считаются одинаковыми. Тела функций сравниваются текстом `pg_get_functiondef` без собственного имени функции.

Порядок фаз: `ALTER TYPE … ADD VALUE` (отдельным шагом вне транзакции — PostgreSQL 11 не выполняет его в
транзакции, на 12+ новая метка доступна только после `COMMIT`) → `-- pgvc:step` → `SET LOCAL check_function_bodies =
off` (если создаются функции) → схемы → расширения → удаление триггеров и view → … → ENUM-типы → последовательности →
функции → таблицы → функции, чья сигнатура использует новую таблицу → ограничения, индексы → view → триггеры →
комментарии → владельцы, права, tablespace → удаление функций, типов, расширений, схем.

У каждого `ChangeItem` теперь есть `rootType` / `rootName` — объект верхнего уровня, к которому относится изменение
(колонка, ограничение, индекс, триггер, комментарий — к своей таблице). Ими пользуются матрица и обозреватель схемы.

**Старые снимки.** Снимки v1.5.0 и раньше (`formatVersion = 1`) не содержат новых категорий. При сравнении с таким
снимком функции, триггеры, ENUM, расширения, комментарии, владельцы, права и tablespace пропускаются с обеих сторон,
поэтому после обновления Working Tree не покажет все функции как новые. Следующий коммит запишет снимок версии 2 —
дальше они сравниваются. Если на разных окружениях функции разные, проверка после push это покажет (матрица — тоже).

### Правила игнорирования (`DiffIgnoreRules`)

`ignoreComments`, `ignoreTablespaces`, `ignoreOwnersAndGrants`, `ignorePartitions`, `excludedObjectPatterns`.
По умолчанию игнорируются владельцы, права и tablespace (роли и диски на серверах обычно разные). Правила хранятся в
`pgvc-settings.json` рядом с `pgvc-environments.json` (`pgvc.settings-file`), меняются окном «Правила игнорирования»
или `PUT /api/settings/ignore-rules`.

`SnapshotFilter` применяет правила к копиям обоих снимков внутри `SchemaDiffEngine`, поэтому они действуют везде,
где сравниваются схемы: Working Tree и коммит из него, проверки «база совпадает с HEAD» перед push / откатом / привязкой
и проверка после них, сравнение окружений, слепков и матрица. Сравнение со слепком и матрица принимают разовые правила
(`ignoreRules` в запросе). Сохранённые в коммитах снимки остаются полными.

Шаблоны: glob (`%` и `*` — любые символы, `?` — один, `_` — буквально, совпадение целиком) или regex (`re:…` или
`/…/`, поиск подстроки); без учёта регистра; проверяются имя и `схема.имя`. Подходят таблицы (и их партиции),
view, последовательности (и последовательности исключённых таблиц), функции, ENUM, триггеры, индексы и расширения.
`ignorePartitions` убирает все партиции, сами партиционированные таблицы сравниваются.

### Матрица N баз

`MatrixCompareService`: каждая база (живое окружение — интроспекция параллельно, или загруженный слепок)
сравнивается с эталоном `SchemaDiffEngine.compare(база, эталон, matchByNameOnly = true, правила)`. Изменения
группируются по `rootType` / `rootName` в строки `MatrixRowDto (type, name, differs, cells)`; ячейка
`MatrixCellDto (status, differences, upSql, downSql, warnings)`:

| Статус | Значение |
|---|---|
| `IDENTICAL` | как в эталоне |
| `MODIFIED` | есть в обоих, отличается (колонки, определение, комментарий, права …) |
| `MISSING` | есть в эталоне, нет здесь |
| `EXTRA_ONLY_HERE` | есть только здесь |
| `ABSENT` | нет ни здесь, ни в эталоне (объект есть только в других базах) |
| `UNAVAILABLE` | базу не удалось прочитать (ошибка — в заголовке столбца) |

`upSql` ячейки приводит объект в этой базе к состоянию эталона. Окно «🌐 Матрица N баз»: выбор окружений,
загрузка слепков, выбор эталона, разовые правила (с кнопкой «Сохранить по умолчанию»), фильтр «Только различия»,
поиск; клик по отличающейся ячейке открывает различия, предупреждения и SQL (UP / обратный).

## Безопасность миграций и безопасное выполнение (Шаг 10)

**Статистика размеров.** `TableModel` хранит `estimatedRows` (`pg_class.reltuples`; 0, если таблица ни разу не
анализировалась), `totalSizeBytes` (`pg_total_relation_size`) и `prettySize` (`pg_size_pretty`). У
партиционированной таблицы — суммы по всем её партициям. Diff Engine эти поля не сравнивает.

**`MigrationSafetyAnalyzer`** разбирает текст команд (без полноценного парсера; `SqlScripts` понимает строки,
`$$`-тела, комментарии) и для каждой выдаёт `SafetyReport`: `riskLevel`, `lockType`, `causesTableRewrite`,
`causesDataLoss`, `longRunning`, `nonTransactional`, таблицу и её размер, `estimatedDurationHint` (грубая оценка по
размеру: сканирование ≈ 400 МБ/с, перезапись ≈ 80 МБ/с, индекс ≈ 100 МБ/с), пояснения и
`zeroDowntimeAlternativeSql` (+ `alternatives`: точные замены «команда → шаблон»). Правила риска:

- потеря данных (`DROP TABLE/COLUMN`, `TRUNCATE`, `DELETE`, сужение типа) — **DANGEROUS**;
- долгая операция (сканирование, перезапись, построение индекса) под блокировкой, мешающей записи (`SHARE` и сильнее),
  на **большой** таблице (> 100 МБ или > 100 000 строк) — **DANGEROUS**, на таблице с данными — **WARNING**;
- короткая `ACCESS EXCLUSIVE` (например, `ADD COLUMN` без перезаписи) на большой таблице — **WARNING**: сама она
  мгновенная, но в очереди за долгими запросами блокирует всё (ограничивается `lock_timeout`);
- объекты, созданные тем же скриптом, и неблокирующие операции (`VALIDATE`, `CONCURRENTLY`) — **SAFE**.

Шаблоны: `ADD CONSTRAINT … FOREIGN KEY/CHECK` → `… NOT VALID;` + шаг + `VALIDATE CONSTRAINT`; `CREATE INDEX` на таблице
с данными → `CREATE INDEX CONCURRENTLY` отдельным шагом (не для партиционированных таблиц); `PRIMARY KEY/UNIQUE` →
`CREATE UNIQUE INDEX CONCURRENTLY` + `ADD CONSTRAINT … USING INDEX`; `ADD COLUMN … NOT NULL` без DEFAULT и
`SET NOT NULL` → nullable-колонка + `CHECK (col IS NOT NULL) NOT VALID` + шаг + `VALIDATE` (+ на PG12+
`SET NOT NULL`, который использует проверенный CHECK без сканирования, и `DROP CONSTRAINT`); `DROP INDEX` на большой
таблице → `DROP INDEX CONCURRENTLY`. Анализ показывается у изменений Working Tree, при сравнении окружений и со
слепком, а для произвольного SQL — `POST …/vc/analyze`.

**Шаги.** Строка `-- pgvc:step` делит скрипт. Без маркеров и `CONCURRENTLY` миграция — одна транзакция вместе с
записью коммита, как раньше. С шагами: проверка состояния → каждый шаг в своей транзакции (`SET LOCAL lock_timeout /
statement_timeout`), шаг с `CREATE/DROP INDEX CONCURRENTLY`, `REINDEX … CONCURRENTLY`, `DETACH … CONCURRENTLY` или
`VACUUM` — по одной команде вне транзакции (`SET`/`RESET` на уровне сеанса) → запись коммита. Если шаг упал,
предыдущие остаются в базе и видны как незакоммиченные изменения (Working Tree). Pre/Post-скрипты добавляются в
начало и конец (`-- pgvc:pre-script`, `-- pgvc:post-script`) и сохраняются в `up_sql` коммита, поэтому push повторяет и
их. Push коммитов с шагами идёт коммит за коммитом (HEAD цели сдвигается после каждого); обычные коммиты — одной
транзакцией. Офлайн-бандл и откат — одна транзакция: в них `CONCURRENTLY` убирается (с пометкой в скрипте).

**Таймауты.** `pgvc.lock-timeout` (3s) и `pgvc.statement-timeout` (60s) — значения по умолчанию для Mode B, наката
слепка, отката и push; запрос может их переопределить. Значения проверяются (`3s`, `500ms`, `2min`, `0`) до
подстановки в `SET LOCAL`. Срабатывание (`55P03` / `57014`) превращается в понятное сообщение.

**Dry-Run** (`dryRun: true`): pre + up + post выполняются на реальной базе с теми же таймаутами в одной транзакции,
которая всегда откатывается (в том числе журнал DDL). Отчёт: успех или ошибка и шаг, реальное время по шагам,
изменения схемы, сгенерированный DOWN SQL, анализ безопасности; команды, которые нельзя выполнить в транзакции,
выполняются в обычной форме и перечисляются.

## Эталонные слепки и сравнение с базой заказчика (Шаг 9)

Сценарий: на рабочей базе (Dev) много коммитов, а на изолированном сервере заказчика стоит своя установка
приложения со своей базой. Сетевой связи нет, общей истории тоже.

**Файл `.pgsnap.json`** (`SnapshotBundleDto`): `formatVersion` (`"1.0"`), `exportedAt`, `sourceEnvironmentName`,
`sourceBranch` (окружение-источник: именованных веток в проекте нет, история окружения и есть ветка),
`sourceCommitHash`, `sourceCommitMessage`, `schemaSnapshot` (полный `DatabaseSnapshot`).
`GET /api/vc/snapshot/export?envId=&commitHash=` отдаёт слепок выбранного коммита; без `commitHash` — `HEAD`,
а если коммитов нет — живую схему (`sourceCommitHash = null`, имя файла `snapshot_<ветка>_live.pgsnap.json`).

**Сравнение** — `SchemaDiffEngine.compare(currentDbState, targetExternalSnapshot, matchByNameOnly = true)`:

- текущая база заказчика — исходное состояние, слепок — целевое;
- таблицы, партиции, колонки, индексы, ограничения, последовательности и представления сопоставляются **строго по
  полным именам** (с учётом регистра), `oid` и `attnum` игнорируются — переименований в этом режиме нет;
- чего нет у заказчика — `ADDED` (`CREATE TABLE`, `ADD COLUMN`, `PARTITION OF` …); то, что есть только у заказчика, —
  `DELETED` с признаком `extraInTargetDb` (в ответе — `destructiveItems`, по умолчанию не выбраны);
- у каждого изменения есть `id`, а скрипты возвращаются ещё и пооператорно (`upStatements`/`downStatements`,
  в порядке выполнения, с `changeId`). Выбор подмножества изменений = фильтр операторов по `changeId` с сохранением
  исходного безопасного порядка (`SchemaDiffEngine.assemble`, в UI — та же функция). Индексы, ограничения и партиции
  новой таблицы входят в изменение этой таблицы.

**Накат** — `POST /api/vc/snapshot/apply`: через Mode B (`executeAndCommitSql`) в одной транзакции выполняется
проверенный пользователем `upSql`, снимается новая схема, сохраняется коммит (сообщение по умолчанию
`Sync with external snapshot [<короткий хеш>]`), сдвигается `HEAD`, очищается `ddl_audit_log`. Незакоммиченные
изменения в базе заказчика не мешают — коммит фиксирует фактическую итоговую схему; `_pgvc` устанавливается, если
её ещё нет. Любая ошибка — полный откат.

**UI:** камера в верхней панели (слепок `HEAD` активного окружения) и «Слепок .pgsnap» в инспекторе коммита;
**Сравнить со слепком** → окно «Синхронизация с внешним слепком»: зона drag-and-drop → шапка «Сравнение текущей БД …
с эталонным слепком коммита #… (ветка …)», слева изменения с галочками (лишнее у заказчика — красным, «Удаление»,
выключено), справа Monaco с UP / DOWN SQL (пересобираются при каждой галочке, можно править), внизу автор, сообщение
и «Применить выбранное и закоммитить». После наката окно сравнивает базу со слепком ещё раз.

## Удаление коммитов и веток (Шаг 8)

Откат (`rollback`) только переносит `HEAD`: коммиты после целевого остаются в истории и на графе. Ненужные коммиты
удаляет `CommitDeletionService`:

- **`COMMIT`** — выбранный коммит и все его потомки (коммит без родителя существовать не может).
- **`BRANCH`** — то же плюс более ранние коммиты брошенной ветки: вниз по родителям, пока родитель не используется
  ни одним окружением и у него нет других детей (точка ответвления остаётся).
- Удалить можно только коммиты, которые **не входят в текущую историю (`HEAD` и предки) ни одного окружения** —
  иначе ответ `409` со списком окружений; сначала нужен откат. Если какое-то окружение недоступно — тоже `409`:
  неизвестно, использует ли оно коммиты.
- Коммиты удаляются из `_pgvc.commits` всех окружений, где они записаны: каждое — в своей транзакции под блокировкой
  истории, с повторной проверкой `HEAD`. **Схема базы не меняется**, удаляется только история. Операция необратима.

В интерфейсе: инспектор коммита → **Удалить…** (неактивна с подсказкой, если коммит в чьей-то истории) → окно
с выбором «Только этот коммит» / «Всю ветку», списком удаляемых коммитов и окружений.

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
