# PgGitVisualizer (pg-git-vc)

**[English](#english) · [Русский](#русский)**

Version control for PostgreSQL 11+ schemas with a GitKraken-style commit graph.
One executable `pg-git-vc-1.4.0.jar` (Spring Boot 3 + embedded React UI), no Docker.
Open source under the [Apache License 2.0](LICENSE).

---

## English

### What it does

- **Mode A — capture changes made in your IDE.** Change the schema in DBeaver, pgAdmin or DataGrip: `EVENT TRIGGER`s
  log the DDL into `_pgvc.ddl_audit_log`, and the introspector compares `pg_catalog` with the snapshot of `HEAD`.
  A pulsing **Working Tree** node appears on top of the graph with ready-made `UP SQL` / `DOWN SQL` that you can edit
  and commit.
- **Mode B — migrations from the UI.** `UP SQL` runs in one transaction together with introspection and the commit
  record; any error rolls everything back. `DOWN SQL` is generated if you leave it empty.
- **Environments (Dev / Test / Stage / Prod …).** Connections live in `./pgvc-environments.json`. Colored badges on
  the graph show which commit every database is at. **Sync / Push to Env** applies the missing commits to another
  environment in one transaction; **Link** attaches an environment that already has the schema to the shared history
  without running SQL.
- **Rollback** of a database to any earlier commit, with an editable script and a schema check afterwards.
- **Delete** commits that are no longer used (left behind by a rollback) — a single commit with the commits on top of
  it, or a whole abandoned branch. Commits in the current history of any environment are protected.
- **Reference snapshots for isolated sites.** Export the schema of any commit as `snapshot_<branch>_<hash>.pgsnap.json`,
  load it into the installation at the customer, see what the customer database lacks or has extra (matched by object
  names — OIDs of another server mean nothing), pick the changes, adjust the SQL and **Apply Selected Changes &
  Commit** in one transaction. Objects that exist only at the customer are never dropped unless you select them.
- **Offline bundles.** A self-contained `.sql` for isolated sites: checks the target `HEAD`, applies the commits,
  records them — all inside `BEGIN … COMMIT`.
- **Every user schema** of the database is tracked: tables, partitions, columns, constraints, indexes, views,
  materialized views and sequences; renames are detected by `oid` / `attnum`. Only `pg_catalog` is used.
- **Russian and English UI**, switched instantly; server messages follow the selected language.

### Download and run

Download `pg-git-vc-<version>.jar` from [Releases](https://github.com/NoProblem2000/VodianoyBase/releases).
Only **Java 17+** is needed:

```bash
java -jar pg-git-vc-1.4.0.jar
```

Open http://localhost:8765.

### Build from source

Requirements: **JDK 17+** and **Maven 3.9+**. Node.js is not needed — Maven downloads it into `target/node`, builds
the React UI from `frontend/` and puts it into the jar.

```bash
mvn clean package                  # tests + UI + target/pg-git-vc-1.4.0.jar
mvn clean package -DskipTests      # faster, without tests
java -jar target/pg-git-vc-1.4.0.jar
```

Change the port with `--server.port=9000`. By default the application listens on `127.0.0.1` only.
UI development with hot reload: start the jar, then `cd frontend && npm ci && npm run dev`
(http://localhost:5173, `/api` is proxied to `:8765`).

### First steps

1. **Environments** (top bar) → name, JDBC URL (`jdbc:postgresql://host:5432/db`), user, password →
   **Test connection** → **Save**.
2. **Initialize version control** — creates the `_pgvc` schema and the event triggers (needs a `SUPERUSER`).
3. The **Working Tree** shows the whole current schema: enter an author and a message and press **Commit Changes** —
   this is the baseline.
4. Do **not** commit other environments that already have the same schema separately: open **Sync / Push to Env**,
   choose the first environment as the source and the other one as the target, press **Link to …**. Later commits are
   delivered with **Push**.

### Interface

- **Top bar:** environment switcher and **Environments**; **New SQL Migration (Mode B)**; **Export Offline .SQL**;
  **Sync / Push to Env**; **Export Snapshot** (camera) and **Compare with Snapshot**; language switch **RU | EN**;
  **About & License**.
- **Left:** Schema Explorer (Tables / Partitions / Views / Sequences) with a filter and change indicators
  **A** added · **M** modified · **R** renamed · **D** deleted; below it the **Live DDL Audit Log**.
- **Center:** commit graph growing upwards, with environment badges and a separate lane for diverged histories.
- **Right:** Working Tree (changes, editable UP/DOWN SQL, commit) or a commit (changes, UP / DOWN / Snapshot /
  DDL Trigger Log, single-commit export, rollback, delete).

The language is stored in the browser (`localStorage`, key `pgvc_lang`, default `ru`); every API request carries
`Accept-Language`, so server errors come back in the same language.

### Architecture

```
frontend/                         React 18 + TypeScript + Vite + Tailwind, @xyflow/react, @monaco-editor/react
  src/i18n/                       typed i18n context, en.json / ru.json
src/main/java/com/vodianoy/pgvc/
  environment/                    connections (pgvc-environments.json), one Hikari pool per environment
  storage/                        _pgvc schema: commits, refs, DDL audit log, event triggers
  introspection/                  pg_catalog -> DatabaseSnapshot (SQL in resources/db/introspection)
  diff/                           SchemaDiffEngine: snapshot A -> B = ordered UP / DOWN DDL
  vcs/                            commit (Mode A/B), rollback, compare, push, link, global graph
  migration/                      offline .sql bundles
  snapshot/                       .pgsnap.json reference snapshots: export, compare by name, apply as a commit
  about/, common/, config/        /api/about, errors and i18n (resources/i18n/messages_*.properties)
```

Everything that changes a database runs in one transaction of that database and is verified against the schema
snapshot afterwards. Details (REST API, `_pgvc`, introspection, diff rules, bundles, linking, tests):
[docs/TECHNICAL.ru.md](docs/TECHNICAL.ru.md) (in Russian).

### Configuration

| Property | Default |
|---|---|
| `server.port` | `8765` |
| `server.address` | `127.0.0.1` (local only) |
| `pgvc.environments-file` | `./pgvc-environments.json` (passwords in plain text — keep it private) |
| `pgvc.connect-timeout-seconds` | `5` |
| `pgvc.max-pool-size` | `4` connections per environment |

### Releases

Bump the version in `pom.xml`, add release notes `.github/release/vX.Y.Z.md` and merge into `main`:
`.github/workflows/release.yml` builds the jar, creates the tag `vX.Y.Z` and publishes the release with the jar and
its SHA-256.

### License

Copyright 2026 NoProblem2000.

Licensed under the **Apache License, Version 2.0** (the "License"); you may not use this software except in compliance
with the License. You may obtain a copy of the License in [LICENSE](LICENSE) or at
https://www.apache.org/licenses/LICENSE-2.0. Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an **"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND**,
either express or implied. See the License for the specific language governing permissions and limitations.

---

## Русский

### Что умеет

- **Mode A — перехват изменений из IDE.** Меняете схему в DBeaver, pgAdmin или DataGrip — `EVENT TRIGGER` пишут DDL в
  `_pgvc.ddl_audit_log`, а интроспектор сравнивает `pg_catalog` со снимком `HEAD`. Вверху графа пульсирует узел
  **Working Tree** с готовыми `UP SQL` / `DOWN SQL`, которые можно поправить и закоммитить.
- **Mode B — миграция из интерфейса.** `UP SQL` выполняется в одной транзакции вместе с интроспекцией и записью
  коммита; любая ошибка откатывает всё. `DOWN SQL` сгенерируется, если оставить его пустым.
- **Окружения (Dev / Test / Stage / Prod …).** Подключения хранятся в `./pgvc-environments.json`. Цветные бейджи на
  графе показывают, на каком коммите каждая база. **Синхронизация / Push** накатывает недостающие коммиты в другое
  окружение одной транзакцией; **привязка (Link)** подключает к общей истории окружение, где схема уже есть, без
  выполнения SQL.
- **Откат** базы к любому более раннему коммиту — с редактируемым скриптом и проверкой схемы после него.
- **Удаление** ненужных коммитов (оставшихся после отката): один коммит вместе с коммитами поверх него или целиком
  брошенная ветка. Коммиты из текущей истории любого окружения защищены от удаления.
- **Эталонные слепки для изолированных площадок.** Схема любого коммита выгружается в
  `snapshot_<ветка>_<хеш>.pgsnap.json`; на площадке заказчика файл загружается в приложение, и видно, чего в его базе
  не хватает и что в ней лишнее (сопоставление по именам объектов — OID другого сервера ничего не значат). Нужные
  изменения отмечаются галочками, SQL можно поправить, и **«Применить выбранное и закоммитить»** накатывает их одной
  транзакцией. Объекты, которые есть только у заказчика, не удаляются, пока их явно не выбрать.
- **Офлайн-бандлы.** Самодостаточный `.sql` для изолированных площадок: проверяет `HEAD` целевой базы, накатывает
  коммиты и записывает их — всё внутри `BEGIN … COMMIT`.
- Отслеживаются **все пользовательские схемы** базы: таблицы, партиции, колонки, ограничения, индексы, представления,
  материализованные представления и последовательности; переименования распознаются по `oid` / `attnum`. Источник —
  только `pg_catalog`.
- **Интерфейс на русском и английском** с мгновенным переключением; сообщения сервера приходят на выбранном языке.

### Скачать и запустить

Скачайте `pg-git-vc-<версия>.jar` со страницы [Releases](https://github.com/NoProblem2000/VodianoyBase/releases).
Нужна только **Java 17+**:

```bash
java -jar pg-git-vc-1.4.0.jar
```

Откройте http://localhost:8765.

### Сборка из исходников

Нужны **JDK 17+** и **Maven 3.9+**. Node.js ставить не нужно — Maven сам скачает его в `target/node`, соберёт
React-интерфейс из `frontend/` и положит его внутрь jar.

```bash
mvn clean package                  # тесты + интерфейс + target/pg-git-vc-1.4.0.jar
mvn clean package -DskipTests      # быстрее, без тестов
java -jar target/pg-git-vc-1.4.0.jar
```

Порт меняется так: `--server.port=9000`. По умолчанию приложение слушает только `127.0.0.1`.
Разработка интерфейса с горячей перезагрузкой: запустите jar, затем `cd frontend && npm ci && npm run dev`
(http://localhost:5173, `/api` проксируется на `:8765`).

### Первые шаги

1. **Окружения** (верхняя панель) → имя, JDBC URL (`jdbc:postgresql://host:5432/db`), пользователь, пароль →
   **Проверить подключение** → **Сохранить**.
2. **Подключить контроль версий** — создаёт схему `_pgvc` и event triggers (нужен `SUPERUSER`).
3. В **Working Tree** появится вся текущая схема: укажите автора и сообщение и нажмите **Закоммитить изменения** —
   это базовый коммит.
4. Остальные окружения, где такая же схема уже есть, **не коммитьте отдельно**: откройте **Синхронизация / Push**,
   источник — первое окружение, цель — другое, нажмите **Привязать к …**. Дальнейшие коммиты доставляются кнопкой
   **Накатить**.

### Интерфейс

- **Верхняя панель:** переключатель окружения и **Окружения**; **Новая SQL-миграция (Mode B)**;
  **Экспорт офлайн .SQL**; **Синхронизация / Push**; **Экспорт слепка** (камера) и **Сравнить со слепком**; переключатель языка **RU | EN**;
  **О проекте и лицензии**.
- **Слева:** обозреватель схемы (Таблицы / Партиции / Представления / Последовательности) с фильтром и индикаторами
  **A** добавлено · **M** изменено · **R** переименовано · **D** удалено; ниже — **Журнал DDL (онлайн)**.
- **В центре:** граф коммитов растёт снизу вверх, с бейджами окружений и отдельной линией для разошедшихся историй.
- **Справа:** Working Tree (изменения, редактируемые UP/DOWN SQL, коммит) или коммит (изменения,
  UP / DOWN / Снимок / Журнал DDL, экспорт одного коммита, откат, удаление).

Язык хранится в браузере (`localStorage`, ключ `pgvc_lang`, по умолчанию `ru`); каждый запрос к API несёт
`Accept-Language`, поэтому ошибки сервера приходят на том же языке.

### Архитектура

```
frontend/                         React 18 + TypeScript + Vite + Tailwind, @xyflow/react, @monaco-editor/react
  src/i18n/                       типизированный контекст переводов, en.json / ru.json
src/main/java/com/vodianoy/pgvc/
  environment/                    подключения (pgvc-environments.json), свой пул Hikari на окружение
  storage/                        схема _pgvc: коммиты, refs, журнал DDL, event triggers
  introspection/                  pg_catalog -> DatabaseSnapshot (SQL в resources/db/introspection)
  diff/                           SchemaDiffEngine: снимок A -> B = упорядоченный UP / DOWN DDL
  vcs/                            коммит (Mode A/B), откат, сравнение, push, привязка, общий граф
  migration/                      офлайн .sql-бандлы
  snapshot/                       эталонные слепки .pgsnap.json: экспорт, сравнение по именам, накат коммитом
  about/, common/, config/        /api/about, ошибки и i18n (resources/i18n/messages_*.properties)
```

Всё, что меняет базу, выполняется в одной транзакции этой базы и затем сверяется со снимком схемы. Подробности
(REST API, `_pgvc`, интроспекция, правила Diff Engine, бандлы, привязка, тесты) —
в [docs/TECHNICAL.ru.md](docs/TECHNICAL.ru.md).

### Настройки

| Параметр | По умолчанию |
|---|---|
| `server.port` | `8765` |
| `server.address` | `127.0.0.1` (только локально) |
| `pgvc.environments-file` | `./pgvc-environments.json` (пароли открытым текстом — не публикуйте файл) |
| `pgvc.connect-timeout-seconds` | `5` |
| `pgvc.max-pool-size` | `4` соединения на окружение |

### Релизы

Поднимите версию в `pom.xml`, добавьте заметки `.github/release/vX.Y.Z.md` и влейте в `main`:
`.github/workflows/release.yml` соберёт jar, создаст тег `vX.Y.Z` и опубликует релиз с jar и его SHA-256.

### Лицензия

Copyright 2026 NoProblem2000.

Распространяется по лицензии **Apache License, Version 2.0**. Программу можно свободно использовать, изменять и
распространять, в том числе в коммерческих целях, при условии сохранения текста лицензии и уведомлений об авторских
правах. Полный текст — в файле [LICENSE](LICENSE) и на https://www.apache.org/licenses/LICENSE-2.0. Программа
предоставляется **«КАК ЕСТЬ», БЕЗ КАКИХ-ЛИБО ГАРАНТИЙ И УСЛОВИЙ**, явных или подразумеваемых.
