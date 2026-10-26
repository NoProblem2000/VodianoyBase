# PgGitVisualizer (pg-git-vc)

Контроль версий схемы PostgreSQL 11+ с визуальным графом коммитов в стиле GitKraken.
Один исполняемый файл `pg-git-vc.jar` (Spring Boot 3 + встроенный React UI), без Docker.

Проект развивается по шагам; каждый шаг — отдельная ветка и Pull Request в `main`.

| Шаг | Содержание | Статус |
|---|---|---|
| 1 | Backend core, менеджер окружений, инициализатор `_pgvc` | готов |
| 2 | Интроспектор схемы PostgreSQL 11+ (`pg_catalog`) | готов |

## Сборка и запуск

Нужны JDK 17+ и Maven 3.9+. Node.js ставить не нужно — `frontend-maven-plugin` скачает его в `target/node`,
соберёт React из `frontend/` и положит результат в `target/classes/static`.

```bash
mvn package                       # тесты + UI + target/pg-git-vc.jar
java -jar target/pg-git-vc.jar    # http://localhost:8765
```

`mvn package -DskipFrontend` собирает только бэкенд.

Настройки (`application.yml`, или `--ключ=значение` при запуске):

| Параметр | По умолчанию |
|---|---|
| `server.port` | `8765` |
| `server.address` | `127.0.0.1` (только локально) |
| `pgvc.environments-file` | `./pgvc-environments.json` |
| `pgvc.connect-timeout-seconds` | `5` |
| `pgvc.max-pool-size` | `4` (соединений на окружение) |

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

## REST API (Шаг 1)

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

## Тесты

```bash
mvn test                                         # без БД: юнит-тесты и REST
PGVC_TEST_JDBC_URL=jdbc:postgresql://localhost:5432/postgres \
PGVC_TEST_USER=postgres PGVC_TEST_PASSWORD=... mvn test   # + интеграционные тесты на реальном PostgreSQL
```

Интеграционные тесты создают свои базы (`pgvc_step*`) и роль, поэтому нужен суперпользователь.
CI (`.github/workflows/build.yml`) прогоняет всё на PostgreSQL 11 и 16.
