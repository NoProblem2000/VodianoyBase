import { Fragment, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AlertOctagon, ArrowRight, BookOpen, Lightbulb, Search, TriangleAlert, X } from 'lucide-react';
import { useI18n, type Lang } from '../i18n';
import { loadSetting, saveSetting } from '../lib/storage';
import { Modal } from './Modal';

/*
 * Documentation Center: the user manual inside the application (isolated sites get only the jar, no repository).
 * The same content, in more detail and with Mermaid diagrams, is in docs/MANUAL_RU.md and docs/MANUAL_EN.md.
 *
 * Text conventions: `code` and **bold** inside strings are rendered as code and bold.
 */

type SectionId = 'quickstart' | 'commits' | 'rollback' | 'snapshot' | 'offline' | 'internals' | 'faq';

type Block =
  | { k: 'h'; text: string }
  | { k: 'p'; text: string }
  | { k: 'ul'; items: string[] }
  | { k: 'steps'; items: [title: string, text: string][] }
  | { k: 'note'; tone: 'tip' | 'warning' | 'danger'; text: string }
  | { k: 'code'; lang: 'sql' | 'bash'; code: string }
  | { k: 'flow'; items: string[] }
  | { k: 'table'; head: string[]; rows: string[][] };

interface Section {
  id: SectionId;
  icon: string;
  title: string;
  lead: string;
  blocks: Block[];
}

const SECTION_KEY = 'pgvc.docsSection';

// ------------------------------------------------------------------ Russian

const RU: Section[] = [
  {
    id: 'quickstart',
    icon: '🚀',
    title: 'Быстрый старт и концепция',
    lead: 'PgGitVisualizer — контроль версий схемы PostgreSQL в стиле Git: история хранится в самой базе, изменения видны графом коммитов и переносятся между окружениями и площадками заказчиков.',
    blocks: [
      { k: 'h', text: 'Концепция' },
      {
        k: 'ul',
        items: [
          '**Один jar без Docker.** `java -jar pg-git-vc-1.9.0.jar`, затем http://localhost:8765. Интерфейс встроен, на серверы ничего не ставится.',
          '**История в базе.** Каждая база хранит свои коммиты в служебной схеме `_pgvc`. Один и тот же коммит имеет одинаковый хеш на Dev, Stage и Prod — поэтому общий граф знает, где какая база.',
          '**Окружение** — подключение к одной базе (Dev, Test, Stage, Prod, заказчик). Цветные бейджи на графе показывают `HEAD` каждого окружения.',
          '**Working Tree** — то, что изменилось в базе после последнего коммита (как незакоммиченные файлы в Git).',
          '**Коммит** хранит UP SQL (как перейти к нему), DOWN SQL (как вернуться), полный снимок схемы и перехваченный DDL.',
        ],
      },
      { k: 'h', text: 'Требования' },
      {
        k: 'table',
        head: ['Что', 'Зачем'],
        rows: [
          ['Java 17+', 'запуск jar'],
          ['PostgreSQL 11+', 'поддерживаются версии 11–17'],
          ['SUPERUSER один раз на базу', 'создание схемы `_pgvc` и event triggers; дальше хватает обычной роли'],
        ],
      },
      { k: 'h', text: 'Первые шаги' },
      {
        k: 'steps',
        items: [
          ['Добавьте окружение', '**Окружения → Добавить окружение**: имя, цвет, JDBC URL `jdbc:postgresql://host:5432/db`, пользователь, пароль. Нажмите **Проверить подключение**, затем **Сохранить**.'],
          ['Подключите контроль версий', 'На жёлтой полосе нажмите **Подключить контроль версий**. Создаётся схема `_pgvc` и два event trigger, которые записывают DDL.'],
          ['Сделайте baseline', 'Существующая схема появится как узел **Незакоммиченные изменения (Working Tree)**. Выберите его, напишите сообщение «Baseline» и нажмите **Закоммитить изменения**.'],
          ['Работайте как обычно', 'Меняйте схему в любой IDE или через **Новая SQL-миграция**, коммитьте, переносите изменения на другие окружения через **Синхронизация / Push**.'],
        ],
      },
      { k: 'code', lang: 'bash', code: 'mvn clean package                        # сборка: target/pg-git-vc-1.9.0.jar\njava -jar target/pg-git-vc-1.9.0.jar     # http://localhost:8765\njava -jar pg-git-vc-1.9.0.jar --server.port=9000   # другой порт' },
      { k: 'note', tone: 'warning', text: 'Подключения хранятся в `pgvc-environments.json` рядом с jar, **пароли — открытым текстом**. Не публикуйте файл и не добавляйте его в Git. По умолчанию приложение слушает только `127.0.0.1`.' },
      { k: 'h', text: 'Верхняя панель' },
      {
        k: 'table',
        head: ['Кнопка', 'Что делает'],
        rows: [
          ['Выбор окружения', 'активная база: её Working Tree, обозреватель схемы и журнал DDL'],
          ['Окружения', 'добавить, изменить, проверить подключения'],
          ['Новая SQL-миграция (Mode B)', 'выполнить SQL и сразу закоммитить'],
          ['Экспорт офлайн .SQL', 'саморегистрирующийся пакет для `psql`'],
          ['Синхронизация / Push', 'накатить коммиты в другое окружение или привязать его к истории'],
          ['📷 / Сравнить со слепком', 'выгрузить `.pgsnap.json` / сравнить базу со слепком'],
          ['Матрица N баз', 'сравнить много баз и слепков с эталоном'],
          ['📁 / 📌 / ползунки', 'экспорт в Git-папку / справочники / правила игнорирования'],
          ['📖 Руководство', 'этот центр документации'],
        ],
      },
    ],
  },
  {
    id: 'commits',
    icon: '🌿',
    title: 'Working Tree, коммиты и ветки',
    lead: 'Два способа вносить изменения: менять схему где угодно и коммитить Working Tree (Workflow A) или писать миграцию в приложении (Workflow B).',
    blocks: [
      { k: 'h', text: 'Workflow A: изменения во внешней IDE' },
      { k: 'flow', items: ['Меняете схему в DBeaver / pgAdmin', 'Event triggers пишут DDL', 'В графе пульсирует Working Tree', 'Проверяете UP / DOWN SQL', 'Закоммитить изменения'] },
      {
        k: 'steps',
        items: [
          ['Измените схему', 'Например: `ALTER TABLE sales.customers ADD COLUMN phone varchar(32);` в любой IDE.'],
          ['Откройте Working Tree', 'Вверху графа появится узел **Незакоммиченные изменения (Working Tree) · N изменений**, в панели **Журнал DDL (онлайн)** — перехваченные команды. Нажмите на узел.'],
          ['Проверьте изменения', 'Список: A — добавлено, M — изменено, R — переименовано, D — удалено. У каждого — бейджи риска. Вкладки **UP SQL** / **DOWN SQL** можно править (например, добавить `USING`).'],
          ['Закоммитьте', '**Закоммитить изменения…** → **Автор**, **Сообщение коммита** (одна строка для графа) и при желании **Описание коммита** (подробности, номер задачи) → **Закоммитить изменения** (Ctrl+Enter). Ничего не выполняется — изменения уже в базе, коммит их записывает.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'Переименования распознаются по внутренним идентификаторам (`oid`, `attnum`): `RENAME COLUMN` останется переименованием, а не «удалить и создать» с потерей данных.' },
      { k: 'h', text: 'Workflow B: Новая SQL-миграция' },
      {
        k: 'steps',
        items: [
          ['Напишите UP SQL', 'Справа сразу виден анализ безопасности: SAFE / WARNING / DANGEROUS, блокировка, размер таблицы, оценка времени. Для опасных операций есть **⚡ Безопасный шаблон без простоя**.'],
          ['DOWN SQL — по желанию', 'Пустой DOWN SQL сгенерируется по разнице схем.'],
          ['Проверьте Dry-Run', '**🧪 Dry-Run** выполняет скрипт на реальной базе в транзакции, которая откатывается: видно, пройдёт ли он и сколько займёт.'],
          ['Выполните', '**Выполнить и закоммитить**: SQL, интроспекция и коммит — одна транзакция. Любая ошибка откатывает всё.'],
        ],
      },
      { k: 'code', lang: 'sql', code: '-- шаг 1: обычная транзакция\nALTER TABLE billing.ledger ADD COLUMN account_ref bigint;\n-- pgvc:step\n-- шаг 2: вне транзакции\nCREATE INDEX CONCURRENTLY ledger_account_ref_idx ON billing.ledger (account_ref);' },
      { k: 'note', tone: 'warning', text: 'Строка `-- pgvc:step` делит миграцию на шаги — у каждого шага своя транзакция. Если упал второй шаг, первый уже применён и появится как незакоммиченное изменение.' },
      { k: 'note', tone: 'danger', text: '`BEGIN` / `COMMIT` / `ROLLBACK` внутри SQL запрещены: транзакциями управляет приложение. `lock_timeout` (3s) и `statement_timeout` (60s) задаются в окне миграции.' },
      { k: 'h', text: 'Ветки' },
      { k: 'p', text: 'Именованных веток (как `git branch`) **нет**: у каждой базы одна история и один `HEAD`. Ветка — линия в графе, которая появляется сама:' },
      {
        k: 'ul',
        items: [
          '**после отката:** база откатилась к коммиту B, затем сделан новый коммит — коммиты после B остались брошенной веткой;',
          '**при расхождении окружений:** на Prod сделали хотфикс в базе, а Dev ушёл вперёд другими коммитами.',
        ],
      },
      { k: 'p', text: '**Переключить окружение** — список слева в верхней панели. **Перевести базу на другую линию:** откатите её к общему предку, затем сделайте **Синхронизация / Push** из окружения, где нужные коммиты.' },
      { k: 'h', text: 'Удаление коммитов и веток' },
      { k: 'p', text: 'Кнопка **Удалить…** в панели коммита. Удалить можно только коммиты, которые не входят в текущую историю ни одного окружения. Режимы: **Только этот коммит** (с коммитами поверх него) и **Всю ветку**. Схема баз не меняется — удаляется только история.' },
    ],
  },
  {
    id: 'rollback',
    icon: '⏪',
    title: 'Откаты и сброс изменений',
    lead: 'Откат возвращает базу к более раннему коммиту, сброс (Discard) — отменяет незакоммиченные изменения. И то и другое — одной транзакцией с проверкой результата.',
    blocks: [
      { k: 'h', text: 'Откат к коммиту' },
      {
        k: 'steps',
        items: [
          ['Выберите коммит', 'Коммит в графе должен быть предком текущего `HEAD` активного окружения.'],
          ['Откройте откат', 'Нажмите **Откатить БД к этому коммиту**. Скрипт — DOWN SQL всех коммитов после выбранного, от новых к старым. Его можно отредактировать.'],
          ['Подтвердите', '**Откатить базу данных**. Скрипт, проверка «схема = снимок выбранного коммита» и перенос `HEAD` — одна транзакция. При расхождении или ошибке ничего не меняется.'],
        ],
      },
      { k: 'flow', items: ['BEGIN + таймауты', 'DOWN SQL новых → старых', 'Проверка схемы', 'HEAD := выбранный коммит', 'COMMIT'] },
      { k: 'note', tone: 'warning', text: 'Откат не удаляет коммиты: они остаются брошенной веткой. Данные колонок и таблиц, удалённых откатом, не восстанавливаются.' },
      { k: 'h', text: 'Сброс незакоммиченных изменений (git reset --hard HEAD)' },
      {
        k: 'ul',
        items: [
          '**🗑️ Отменить все изменения (сброс к HEAD)** — красная кнопка в панели незакоммиченных изменений. Если коммитов ещё нет, база возвращается к пустой схеме.',
          '**Отменить это изменение** — иконка ↶ у каждого изменения в списке.',
        ],
      },
      {
        k: 'steps',
        items: [
          ['Откройте диалог', 'В нём видно, что отменяется, оценка риска и SQL отмены.'],
          ['Прочитайте предупреждения', 'Таблица, созданная после `HEAD`, удаляется вместе с данными. Удалённая колонка вернётся, но пустой.'],
          ['Поправьте SQL при необходимости', 'SQL редактируется прямо в окне.'],
          ['Подтвердите', 'Всё выполняется одной транзакцией. После полного сброса схема проверяется на совпадение с `HEAD`, журнал DDL очищается. При отмене одного изменения ваши записи в журнале остаются.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'Застряли старые события в журнале DDL, а схема в порядке? Кнопка **Только очистить журнал DDL** в том же диалоге.' },
      { k: 'note', tone: 'danger', text: 'Сброс и откат — необратимые операции над данными: то, что удаляется, не возвращается. Если сомневаетесь, сначала выгрузите слепок (📷) или сделайте резервную копию.' },
      { k: 'note', tone: 'danger', text: 'Таблица, партиция или колонка, уже удалённая в живой базе, помечена **💀 Данные УЖЕ утеряны в БД**: строки удалены безвозвратно, сброс к HEAD вернёт только пустую структуру. В скриптах, которые ещё выполнятся (синхронизация, слепок, откат, Revert), — **⚠️ Потеря данных при накате**.' },
      { k: 'h', text: 'Обращение одного коммита (Revert Commit)' },
      { k: 'p', text: 'Как `git revert`: отменяет изменения одного коммита (даже из середины истории) **новым коммитом** поверх `HEAD`, коммиты после него остаются. Правый клик по коммиту → **Обратить этот коммит…**.' },
      {
        k: 'steps',
        items: [
          ['Проверьте SQL', 'Компенсирующий SQL — DOWN SQL коммита; если схема с тех пор менялась, поправьте его прямо в окне.'],
          ['Прочитайте предупреждения', 'Удалённая коммитом таблица / колонка вернётся без строк; созданная им — удалится с данными; объекты, которые меняли позже, отмечены отдельно.'],
          ['Обратите', '**Обратить и закоммитить**: SQL и новый коммит `Revert "…"` — одна транзакция, журнал DDL очищается.'],
        ],
      },
      { k: 'h', text: 'Если есть незакоммиченные изменения' },
      { k: 'p', text: 'Откат и push в окружение с незакоммиченными изменениями отказываются выполняться — чтобы не затереть чужую работу. Закоммитьте или отмените изменения, либо включите «Разрешить при незакоммиченных изменениях».' },
    ],
  },
  {
    id: 'snapshot',
    icon: '📸',
    title: 'Синхронизация со слепком .pgsnap.json',
    lead: 'Для заказчиков со своей базой без нашей истории и без сетевого доступа: сравнивается состояние схемы, объекты сопоставляются по именам.',
    blocks: [
      { k: 'flow', items: ['Dev: 📷 Экспорт слепка', 'Файл .pgsnap.json', 'Заказчик: Сравнить со слепком', 'Выбор изменений', 'Применить и закоммитить'] },
      { k: 'h', text: 'На Dev' },
      {
        k: 'steps',
        items: [
          ['Закоммитьте всё', 'В слепок попадает схема коммита (по умолчанию `HEAD`).'],
          ['Выгрузите слепок', 'Кнопка 📷 в верхней панели или **Слепок .pgsnap** в панели любого коммита. Скачается `snapshot_<окружение>_<хеш>.pgsnap.json`.'],
          ['Передайте файл', 'Флешка, почта, файловый обмен — сеть между площадками не нужна.'],
        ],
      },
      { k: 'h', text: 'У заказчика' },
      {
        k: 'steps',
        items: [
          ['Подключите его базу', 'Тот же jar, окружение с базой заказчика. `_pgvc` там может ещё не быть — установится при применении.'],
          ['Сравните', '**Сравнить со слепком** → перетащите файл. Две группы: **Недостаёт или отличается** (отмечено) и **Лишнее в этой базе** с красным бейджем **Удаление** (не отмечено).'],
          ['Проверьте SQL', 'Галочки пересобирают UP / DOWN SQL; добавьте Pre / Post-скрипты, таймауты, выполните **🧪 Dry-Run**.'],
          ['Примените', '**Применить выбранное и закоммитить**: одна транзакция, в истории заказчика появится коммит «Sync with external snapshot [хеш]».'],
        ],
      },
      { k: 'note', tone: 'danger', text: 'Отмечая объект из группы **Лишнее в этой базе**, вы удаляете его у заказчика вместе с данными. Окно предупредит: «Выбрано удаляющих изменений: N».' },
      { k: 'note', tone: 'tip', text: '**Правила игнорирования этого сравнения** — разово не сравнивать комментарии, права, tablespace, партиции или объекты по шаблону (`tmp_%`, `customer_*`).' },
      { k: 'note', tone: 'warning', text: 'Разные мажорные версии PostgreSQL у слепка и базы — окно покажет предупреждение. Выполните Dry-Run перед применением.' },
      { k: 'h', text: 'Матрица N баз' },
      { k: 'p', text: '**Матрица N баз** сравнивает сразу несколько окружений и слепков с одной эталонной базой: строка — объект, столбец — база, статусы «совпадает / отличается / нет / только здесь». По клику на ячейку — что отличается и SQL, который приводит эту базу к эталону.' },
      { k: 'h', text: 'Справочники' },
      { k: 'p', text: 'Таблицы, отмеченные в **📌 Справочники**, переносятся вместе со строками: новые и изменённые строки — `INSERT … ON CONFLICT … DO UPDATE`, удалённые — `DELETE`. При сравнении со слепком строки читаются и в базе заказчика.' },
    ],
  },
  {
    id: 'offline',
    icon: '📦',
    title: 'Офлайн .sql пакеты и мульти-окружения',
    lead: 'Онлайн-синхронизация между окружениями, привязка истории и офлайн-пакет для площадок, где можно запустить только psql.',
    blocks: [
      { k: 'h', text: 'Какой способ выбрать' },
      {
        k: 'table',
        head: ['Ситуация', 'Способ'],
        rows: [
          ['Обе базы доступны из одного приложения', '**Синхронизация / Push**'],
          ['Нет сети, но у заказчика можно запустить jar; у него своя история', 'слепок `.pgsnap.json`'],
          ['Нет сети и можно только передать SQL администратору', 'офлайн `.sql`'],
        ],
      },
      { k: 'h', text: 'Синхронизация / Push' },
      {
        k: 'steps',
        items: [
          ['Выберите направление', '**Откуда (источник)**, **Куда (цель)**, при необходимости **До коммита**.'],
          ['Посмотрите статус', '«Отстаёт» — можно накатить; «Не подключено» / «Пустая история» — накатится вся история (нужен SUPERUSER); «Истории не связаны» — push невозможен; «Впереди» / «Совпадает» — нечего накатывать.'],
          ['Накатите', 'Недостающие коммиты выполняются одной транзакцией (с `-- pgvc:step` — по шагам), затем схема цели проверяется по снимку последнего коммита.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'Если у цели уже есть схема, но нет общей истории, используйте **привязку (Link)**: окружение привязывается к коммиту источника, схема которого совпадает с его живой схемой. SQL не выполняется.' },
      { k: 'h', text: 'Офлайн .sql пакет' },
      {
        k: 'steps',
        items: [
          ['Откройте экспорт', '**Экспорт офлайн .SQL**: **Исходное окружение**, **Целевая база сейчас на (не включая)** — коммит базы заказчика (или «пусто» для новой), **Выгрузить до (включительно)**.'],
          ['Скачайте файл', 'Имя вида `pgvc-dev-<от>-to-<до>.sql`. Для одного коммита — **Экспорт коммита в .SQL** в его панели.'],
          ['Администратор выполняет', 'Командой `psql` (ниже). Всё — одна транзакция.'],
        ],
      },
      { k: 'code', lang: 'bash', code: 'psql -v ON_ERROR_STOP=1 -d app_db -f pgvc-dev-a1b2c3d-to-789abcd.sql' },
      { k: 'flow', items: ['BEGIN', 'Установка _pgvc', 'Проверка HEAD', 'UP SQL коммитов', 'Регистрация коммитов', 'HEAD := последний', 'COMMIT'] },
      {
        k: 'ul',
        items: [
          '**Саморегистрация:** после выполнения в базе заказчика те же коммиты с теми же хешами — следующий пакет строится от этого `HEAD`, позже работает и обычный Push.',
          '**Защита:** база не на том коммите — «Migration mismatch: expected HEAD …»; повторный запуск — «Migration already applied». В обоих случаях ничего не меняется.',
          '`CREATE INDEX CONCURRENTLY` в пакете выполняется без `CONCURRENTLY` — внутри транзакции иначе нельзя.',
        ],
      },
      { k: 'note', tone: 'warning', text: 'Event triggers пакет устанавливает, только если его выполняет SUPERUSER. На PostgreSQL 11 пакет с `ALTER TYPE … ADD VALUE` не пройдёт — используйте Push или слепок.' },
      { k: 'h', text: 'CI/CD и Git' },
      { k: 'code', lang: 'bash', code: '# ночная проверка дрейфа: код 2 — схему меняли мимо контроля версий\njava -jar pg-git-vc-1.9.0.jar --cli --action=check-drift --env=prod --output=drift.md\n# Markdown-отчёт для Merge Request; код 2 при опасных изменениях\njava -jar pg-git-vc-1.9.0.jar --cli --action=diff-report --env=stage --snapshot=release.pgsnap.json --output=pr-comment.md --fail-on=dangerous' },
      { k: 'p', text: '**📁 Экспорт в Git-папку** раскладывает схему по файлу на объект (`schema/tables`, `views`, `functions` …, `migrations/`) — для код-ревью в GitLab / GitHub.' },
    ],
  },
  {
    id: 'internals',
    icon: '🛠️',
    title: 'Как это работает: _pgvc и триггеры',
    lead: 'Служебная схема, перехват DDL, интроспекция pg_catalog и устройство коммита.',
    blocks: [
      { k: 'h', text: 'Схема _pgvc' },
      {
        k: 'table',
        head: ['Таблица', 'Содержимое'],
        rows: [
          ['`_pgvc.commits`', '`hash` (SHA-256), `parent_hash`, `author`, `message`, `created_at`, `up_sql`, `down_sql`, `schema_snapshot` (JSONB), `ddl_log_history`'],
          ['`_pgvc.refs`', 'указатели; строка `HEAD` — коммит, на котором база'],
          ['`_pgvc.ddl_audit_log`', 'DDL после последнего коммита: команда, объект, текст, пользователь, время'],
          ['`_pgvc.tracked_data_tables`', 'таблицы-справочники'],
        ],
      },
      { k: 'h', text: 'Event triggers' },
      {
        k: 'table',
        head: ['Триггер', 'Событие', 'Функция'],
        rows: [
          ['`pgvc_ddl_trigger`', '`ddl_command_end`', '`_pgvc.fn_log_ddl_end()` — каждая команда CREATE / ALTER / COMMENT / GRANT …'],
          ['`pgvc_drop_trigger`', '`sql_drop`', '`_pgvc.fn_log_sql_drop()` — удалённые объекты, названные в команде'],
        ],
      },
      { k: 'flow', items: ['DDL в IDE', 'ddl_command_end / sql_drop', 'INSERT в _pgvc.ddl_audit_log', 'Приложение опрашивает журнал', 'Снимок HEAD ↔ живая схема', 'Working Tree'] },
      { k: 'p', text: 'Функции объявлены `SECURITY DEFINER`: DDL обычных разработчиков записывается с правами установившего суперпользователя, а в `db_user` остаётся настоящий логин. Объекты `_pgvc`, временные таблицы и TOAST не записываются.' },
      { k: 'note', tone: 'tip', text: 'Журнал DDL — подсказка «кто и что менял». Источник истины Working Tree — сравнение снимка `HEAD` с живой схемой, поэтому видны даже изменения при выключенном триггере.' },
      { k: 'h', text: 'Почему pg_catalog, а не information_schema' },
      {
        k: 'ul',
        items: [
          '`pg_class.oid` и `pg_attribute.attnum` переживают переименование — так `RENAME` отличается от удаления и создания;',
          'партиции видны как партиции: `relkind = \'p\'`, `pg_inherits`, `pg_get_expr(relpartbound)`;',
          'точные определения: `pg_get_constraintdef`, `pg_get_indexdef`, `pg_get_viewdef`, `pg_get_functiondef`, `pg_get_triggerdef`;',
          'видны объекты, недоступные текущей роли; materialized view, ENUM, identity, generated-колонки;',
          'все запросы — одна read-only транзакция `REPEATABLE READ`: снимок согласован.',
        ],
      },
      { k: 'p', text: 'OID и attnum сравниваются только внутри одной базы одного окружения (идентичность снимка — `system_identifier:oid_базы@окружение`). Между окружениями объекты сопоставляются строго по именам.' },
      { k: 'h', text: 'Коммит и хеш' },
      { k: 'p', text: 'Коммит хранит UP SQL, DOWN SQL, полный снимок схемы (таблицы, колонки, ограничения, индексы, представления, последовательности, функции, триггеры, ENUM, расширения, комментарии, строки справочников) и перехваченный DDL. Хеш — SHA-256 от родителя, автора, сообщения, времени, SQL и снимка.' },
      { k: 'note', tone: 'danger', text: 'Не редактируйте таблицы `_pgvc` руками: хеши перестанут соответствовать содержимому, а `HEAD` может указать в пустоту. Что делать, если это всё-таки произошло, — в разделе «Частые вопросы и ошибки».' },
    ],
  },
  {
    id: 'faq',
    icon: '❓',
    title: 'Частые вопросы и ошибки',
    lead: 'Конфликты HEAD, блокировки, права и ручное вмешательство в _pgvc.',
    blocks: [
      { k: 'h', text: 'Конфликты HEAD и истории' },
      {
        k: 'table',
        head: ['Сообщение', 'Что делать'],
        rows: [
          ['«HEAD moved» при коммите', 'кто-то закоммитил в эту базу, пока вы смотрели Working Tree: обновите (↻), проверьте и закоммитьте снова'],
          ['«Истории не связаны» в синхронизации', 'привязка (если схема цели совпадает с коммитом источника) или откат лишнего + push'],
          ['«Migration mismatch: expected HEAD X, but found Y»', 'офлайн-пакет построен не от того коммита: постройте заново с «Целевая база сейчас на» = Y'],
          ['«Migration already applied»', 'пакет уже выполнен, ничего делать не нужно'],
          ['«В цели есть незакоммиченные изменения»', 'закоммитьте или отмените их в той базе, либо разрешите push с незакоммиченными изменениями'],
          ['«Verification failed» после push / отката', 'схема не совпала со снимком: изучите различия, поправьте скрипт; в крайнем случае — «Не проверять схему после выполнения»'],
        ],
      },
      { k: 'h', text: 'Блокировки таблиц' },
      { k: 'p', text: 'Каждая миграция, откат, сброс и push начинаются с `SET LOCAL lock_timeout = \'3s\'`: если таблицу держит другая транзакция, команда падает с «could not obtain lock», а не висит. Ничего не применяется.' },
      { k: 'code', lang: 'sql', code: '-- кто держит блокировку\nSELECT a.pid, a.usename, a.state, now() - a.xact_start AS in_transaction, left(a.query, 120) AS query\nFROM pg_stat_activity a\nWHERE a.pid IN (SELECT unnest(pg_blocking_pids(b.pid))\n                FROM pg_stat_activity b\n                WHERE cardinality(pg_blocking_pids(b.pid)) > 0);\n\n-- завершить сессию (только если уверены)\nSELECT pg_terminate_backend(12345);' },
      { k: 'note', tone: 'tip', text: 'Для больших таблиц: `CREATE INDEX CONCURRENTLY`, `ADD CONSTRAINT … NOT VALID` + `VALIDATE CONSTRAINT`, nullable-колонка + `CHECK … NOT VALID`. Кнопка **⚡ Безопасный шаблон без простоя** вставит их сама. Для заведомо долгих команд увеличьте `statement_timeout`.' },
      { k: 'h', text: 'Права и подключение' },
      {
        k: 'table',
        head: ['Проблема', 'Решение'],
        rows: [
          ['Инициализация: нужен SUPERUSER', 'инициализирует суперпользователь один раз, дальше — обычная роль'],
          ['DDL не появляется в журнале', '`SELECT evtname, evtenabled FROM pg_event_trigger WHERE evtname LIKE \'pgvc%\';` — `D` значит выключен: `ALTER EVENT TRIGGER pgvc_ddl_trigger ENABLE;`'],
          ['Не удаётся подключиться', 'JDBC URL, сеть, `pg_hba.conf`; «Проверить подключение» покажет ошибку драйвера'],
          ['Порт 8765 занят', '`--server.port=9000`'],
          ['Открыть интерфейс с другой машины', '`--server.address=0.0.0.0` — авторизации нет, только во внутренней сети'],
        ],
      },
      { k: 'h', text: 'Ручное вмешательство в _pgvc' },
      {
        k: 'table',
        head: ['Что случилось', 'Что делать'],
        rows: [
          ['Удалили таблицу `_pgvc`', '**Подключить контроль версий** восстановит структуру; если удалена `_pgvc.commits`, история потеряна — сделайте новый baseline'],
          ['`HEAD` указывает на несуществующий коммит', '`UPDATE _pgvc.refs SET commit_hash = \'<хеш>\' WHERE ref_name = \'HEAD\';`'],
          ['Правили `up_sql` / `down_sql`', 'не правьте строки коммитов; нужный SQL задавайте при откате (скрипт редактируется)'],
          ['Выключили event triggers', '`ALTER EVENT TRIGGER pgvc_ddl_trigger ENABLE; ALTER EVENT TRIGGER pgvc_drop_trigger ENABLE;`'],
          ['Застряли старые события', '**Только очистить журнал DDL** в диалоге отмены изменений'],
        ],
      },
      { k: 'code', lang: 'sql', code: '-- полностью убрать контроль версий из базы (история удаляется безвозвратно)\nDROP EVENT TRIGGER IF EXISTS pgvc_ddl_trigger;\nDROP EVENT TRIGGER IF EXISTS pgvc_drop_trigger;\nDROP SCHEMA _pgvc CASCADE;' },
      { k: 'note', tone: 'danger', text: '`DROP SCHEMA _pgvc CASCADE` удаляет всю историю этой базы. Перед этим выгрузите слепок и офлайн-пакет, если история может понадобиться.' },
      { k: 'h', text: 'Прочее' },
      {
        k: 'ul',
        items: [
          'Удалить или переставить значение ENUM PostgreSQL не умеет — программа покажет `MANUAL ACTION REQUIRED`.',
          'Правый клик по коммиту в графе — меню: обратить, откатить, создать ветку, слепок, .SQL, скопировать хэш / сообщение, удалить. Именованных веток нет: «Создать ветку отсюда» предлагает откатить базу к коммиту или накатить историю до него в другое окружение.',
          'Смена секционирования (обычная таблица ↔ `PARTITION BY`, другой ключ) — таблица пересоздаётся: строки копируются во временную таблицу и возвращаются, внешние ключи, view и serial-последовательности восстанавливаются. Строка вне всех партиций останавливает миграцию целиком.',
          'Справочник больше 10 000 строк перестаёт сравниваться («превышен лимит» в списке справочников).',
          'Коммиты старых версий не содержат функций, триггеров, ENUM: при сравнении с ними эти категории пропускаются, первый новый коммит их запишет.',
          'Подробное руководство со схемами — `docs/MANUAL_RU.md`, техническая справка — `docs/TECHNICAL.ru.md` в репозитории.',
        ],
      },
    ],
  },
];

// ------------------------------------------------------------------ English

const EN: Section[] = [
  {
    id: 'quickstart',
    icon: '🚀',
    title: 'Quick Start & Concepts',
    lead: 'PgGitVisualizer is Git-like version control for PostgreSQL schemas: the history lives in the database itself, changes are shown as a commit graph and moved between environments and customer sites.',
    blocks: [
      { k: 'h', text: 'Concepts' },
      {
        k: 'ul',
        items: [
          '**One jar, no Docker.** `java -jar pg-git-vc-1.9.0.jar`, then http://localhost:8765. The UI is built in; nothing is installed on the servers.',
          '**History in the database.** Every database keeps its commits in the `_pgvc` schema. A commit has the same hash on Dev, Stage and Prod — that is how the shared graph knows where each database is.',
          '**Environment** — a connection to one database (Dev, Test, Stage, Prod, a customer). Colored badges in the graph show the `HEAD` of every environment.',
          '**Working Tree** — what changed in the database since the last commit (like uncommitted files in Git).',
          '**Commit** — UP SQL (how to get there), DOWN SQL (how to go back), the complete schema snapshot and the captured DDL.',
        ],
      },
      { k: 'h', text: 'Requirements' },
      {
        k: 'table',
        head: ['What', 'Why'],
        rows: [
          ['Java 17+', 'runs the jar'],
          ['PostgreSQL 11+', 'versions 11–17 are supported'],
          ['A SUPERUSER once per database', 'creates the `_pgvc` schema and the event triggers; afterwards an ordinary role is enough'],
        ],
      },
      { k: 'h', text: 'First steps' },
      {
        k: 'steps',
        items: [
          ['Add an environment', '**Environments → Add environment**: name, color, JDBC URL `jdbc:postgresql://host:5432/db`, user, password. Press **Test connection**, then **Save**.'],
          ['Initialize version control', 'Press **Initialize version control** on the yellow bar. The `_pgvc` schema and two event triggers that record DDL are created.'],
          ['Make a baseline', 'The existing schema shows up as the **Working Tree (Uncommitted Changes)** node. Select it, write "Baseline" and press **Commit Changes**.'],
          ['Work as usual', 'Change the schema in any IDE or with **New SQL Migration**, commit, and move changes to other environments with **Sync / Push to Env**.'],
        ],
      },
      { k: 'code', lang: 'bash', code: 'mvn clean package                        # build: target/pg-git-vc-1.9.0.jar\njava -jar target/pg-git-vc-1.9.0.jar     # http://localhost:8765\njava -jar pg-git-vc-1.9.0.jar --server.port=9000   # another port' },
      { k: 'note', tone: 'warning', text: 'Connections are stored in `pgvc-environments.json` next to the jar, **passwords in plain text**. Do not publish the file or add it to Git. By default the application listens on `127.0.0.1` only.' },
      { k: 'h', text: 'Top bar' },
      {
        k: 'table',
        head: ['Button', 'What it does'],
        rows: [
          ['Environment selector', 'the active database: its Working Tree, Schema Explorer and DDL log'],
          ['Environments', 'add, edit and test connections'],
          ['New SQL Migration (Mode B)', 'execute SQL and commit it at once'],
          ['Export Offline .SQL', 'a self-registering bundle for `psql`'],
          ['Sync / Push to Env', 'push commits to another environment or link it to the history'],
          ['📷 / Compare with Snapshot', 'export a `.pgsnap.json` / compare the database with a snapshot'],
          ['N-Database Matrix', 'compare many databases and snapshots with a baseline'],
          ['📁 / 📌 / sliders', 'Git folder export / reference data / ignore rules'],
          ['📖 Docs', 'this documentation center'],
        ],
      },
    ],
  },
  {
    id: 'commits',
    icon: '🌿',
    title: 'Working Tree, Commits & Branches',
    lead: 'Two ways to make changes: change the schema anywhere and commit the Working Tree (Workflow A), or write a migration in the application (Workflow B).',
    blocks: [
      { k: 'h', text: 'Workflow A: changes in an external IDE' },
      { k: 'flow', items: ['Change the schema in DBeaver / pgAdmin', 'Event triggers record the DDL', 'The Working Tree node pulses', 'Review UP / DOWN SQL', 'Commit Changes'] },
      {
        k: 'steps',
        items: [
          ['Change the schema', 'For example: `ALTER TABLE sales.customers ADD COLUMN phone varchar(32);` in any IDE.'],
          ['Open the Working Tree', '**Working Tree (Uncommitted Changes) · N changes** appears on top of the graph, the **Live DDL Audit Log** lists the captured commands. Click the node.'],
          ['Review the changes', 'A — added, M — modified, R — renamed, D — deleted, each with risk badges. The **UP SQL** / **DOWN SQL** tabs are editable (e.g. add `USING`).'],
          ['Commit', '**Commit Changes…** → **Author**, **Commit summary** (one line for the graph) and optionally **Commit description** (details, ticket number) → **Commit Changes** (Ctrl+Enter). Nothing is executed — the changes are already in the database, the commit records them.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'Renames are recognised by internal identities (`oid`, `attnum`): a `RENAME COLUMN` stays a rename instead of "drop and create" with data loss.' },
      { k: 'h', text: 'Workflow B: New SQL Migration' },
      {
        k: 'steps',
        items: [
          ['Write the UP SQL', 'The safety analysis on the right updates as you type: SAFE / WARNING / DANGEROUS, lock mode, table size, duration estimate. Dangerous operations offer **⚡ Use Zero-Downtime Pattern**.'],
          ['DOWN SQL is optional', 'An empty DOWN SQL is generated from the schema difference.'],
          ['Check with a Dry-Run', '**🧪 Dry-Run** runs the script on the real database in a transaction that is rolled back: you see whether it passes and how long it takes.'],
          ['Execute', '**Execute & Commit**: SQL, introspection and the commit are one transaction. Any error rolls back everything.'],
        ],
      },
      { k: 'code', lang: 'sql', code: '-- step 1: an ordinary transaction\nALTER TABLE billing.ledger ADD COLUMN account_ref bigint;\n-- pgvc:step\n-- step 2: outside of a transaction\nCREATE INDEX CONCURRENTLY ledger_account_ref_idx ON billing.ledger (account_ref);' },
      { k: 'note', tone: 'warning', text: 'A `-- pgvc:step` line splits the migration into steps — each step is its own transaction. If step 2 fails, step 1 is already applied and shows up as an uncommitted change.' },
      { k: 'note', tone: 'danger', text: '`BEGIN` / `COMMIT` / `ROLLBACK` inside the SQL are refused: the application manages transactions. `lock_timeout` (3s) and `statement_timeout` (60s) are set in the migration dialog.' },
      { k: 'h', text: 'Branches' },
      { k: 'p', text: 'There are **no** named branches (like `git branch`): every database has one history and one `HEAD`. A branch is a line in the graph that appears by itself:' },
      {
        k: 'ul',
        items: [
          '**after a rollback:** the database went back to commit B and a new commit was made — the commits after B stay as an abandoned branch;',
          '**when environments diverge:** a hotfix was made directly on Prod while Dev moved on with other commits.',
        ],
      },
      { k: 'p', text: '**Switch the environment** with the selector on the left of the top bar. **Move a database to another line:** roll it back to the common ancestor, then **Sync / Push to Env** from the environment that has the wanted commits.' },
      { k: 'h', text: 'Deleting commits and branches' },
      { k: 'p', text: '**Delete…** in the commit panel. Only commits that are not in the current history of any environment can be deleted. Modes: **This commit** (with the commits on top of it) and **The whole branch**. The database schemas do not change — only the history.' },
    ],
  },
  {
    id: 'rollback',
    icon: '⏪',
    title: 'Rollbacks & Discard',
    lead: 'A rollback returns the database to an earlier commit; discard throws away uncommitted changes. Both run in one transaction and check the result.',
    blocks: [
      { k: 'h', text: 'Rollback to a commit' },
      {
        k: 'steps',
        items: [
          ['Select a commit', 'It must be an ancestor of the current `HEAD` of the active environment.'],
          ['Open the rollback', 'Press **Rollback DB to here**. The script is the DOWN SQL of every commit after the selected one, newest first. It can be edited.'],
          ['Confirm', '**Roll back database**. Script, the check "schema = snapshot of the selected commit" and the `HEAD` move are one transaction. On a difference or an error nothing changes.'],
        ],
      },
      { k: 'flow', items: ['BEGIN + timeouts', 'DOWN SQL newest → oldest', 'Schema check', 'HEAD := selected commit', 'COMMIT'] },
      { k: 'note', tone: 'warning', text: 'A rollback does not delete commits: they stay as an abandoned branch. Data of columns and tables dropped by the rollback is not restored.' },
      { k: 'h', text: 'Discarding uncommitted changes (git reset --hard HEAD)' },
      {
        k: 'ul',
        items: [
          '**🗑️ Discard All Changes (Reset to HEAD)** — the red button in the Working Tree panel. Without commits the database returns to an empty schema.',
          '**Discard / Revert Item** — the ↶ icon next to every change.',
        ],
      },
      {
        k: 'steps',
        items: [
          ['Open the dialog', 'It shows what is reverted, the risk and the revert SQL.'],
          ['Read the warnings', 'A table created after `HEAD` is dropped with its data. A dropped column comes back, but empty.'],
          ['Edit the SQL if needed', 'The SQL is editable right in the dialog.'],
          ['Confirm', 'Everything runs in one transaction. After a full reset the schema is checked against `HEAD` and the DDL log is cleared. Reverting one change keeps your log entries.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'Stale events stuck in the DDL log while the schema is fine? **Only clear the DDL log** in the same dialog.' },
      { k: 'note', tone: 'danger', text: 'Discard and rollback cannot be undone for data: what is dropped does not come back. When in doubt, export a snapshot (📷) or make a backup first.' },
      { k: 'note', tone: 'danger', text: 'A table, partition or column already dropped in the live database is marked **💀 Data ALREADY lost in DB**: its rows are gone for good, a reset to HEAD brings back only an empty structure. Scripts that still have to run (sync, snapshot, rollback, revert) say **⚠️ Data will be lost on apply**.' },
      { k: 'h', text: 'Reverting one commit (Revert Commit)' },
      { k: 'p', text: 'Like `git revert`: undoes the changes of one commit (even from the middle of the history) with a **new commit** on top of `HEAD`; the commits after it stay. Right-click the commit → **Revert Commit…**.' },
      {
        k: 'steps',
        items: [
          ['Check the SQL', 'The compensating SQL is the DOWN SQL of the commit; edit it right in the dialog if the schema changed since.'],
          ['Read the warnings', 'A table / column the commit dropped comes back without rows; one it created is dropped with its data; objects changed later are listed separately.'],
          ['Revert', '**Revert & Commit**: the SQL and the new commit `Revert "…"` are one transaction, the DDL log is cleared.'],
        ],
      },
      { k: 'h', text: 'With uncommitted changes' },
      { k: 'p', text: 'Rollbacks and pushes into an environment with uncommitted changes are refused, so nobody’s work is overwritten. Commit or discard the changes, or enable "Allow uncommitted changes".' },
    ],
  },
  {
    id: 'snapshot',
    icon: '📸',
    title: 'Snapshot Sync (.pgsnap.json) for Customers',
    lead: 'For customers with their own database, without our history and without network access: the schema state is compared, objects are matched by name.',
    blocks: [
      { k: 'flow', items: ['Dev: 📷 Export Snapshot', '.pgsnap.json file', 'Customer: Compare with Snapshot', 'Pick the changes', 'Apply & Commit'] },
      { k: 'h', text: 'On Dev' },
      {
        k: 'steps',
        items: [
          ['Commit everything', 'The snapshot holds the schema of a commit (`HEAD` by default).'],
          ['Export the snapshot', 'The 📷 button in the top bar or **Snapshot .pgsnap** in the panel of any commit. `snapshot_<environment>_<hash>.pgsnap.json` is downloaded.'],
          ['Hand the file over', 'USB stick, mail, file exchange — no network between the sites is needed.'],
        ],
      },
      { k: 'h', text: 'At the customer' },
      {
        k: 'steps',
        items: [
          ['Connect their database', 'The same jar, an environment with the customer database. `_pgvc` may be missing — it is installed when applying.'],
          ['Compare', '**Compare with Snapshot** → drop the file. Two groups: **Missing or different** (selected) and **Extra in this database** with the red **Destructive** badge (not selected).'],
          ['Review the SQL', 'The checkboxes rebuild the UP / DOWN SQL; add pre / post scripts and timeouts, run **🧪 Dry-Run**.'],
          ['Apply', '**Apply Selected Changes & Commit**: one transaction, the customer history gets the commit "Sync with external snapshot [hash]".'],
        ],
      },
      { k: 'note', tone: 'danger', text: 'Selecting an object of **Extra in this database** drops it at the customer together with its data. The dialog warns: "N destructive change(s) selected".' },
      { k: 'note', tone: 'tip', text: '**Ignore rules of this comparison** — skip comments, grants, tablespaces, partitions or objects by pattern (`tmp_%`, `customer_*`) for this comparison only.' },
      { k: 'note', tone: 'warning', text: 'Different PostgreSQL major versions of the snapshot and the database — the dialog shows a warning. Run a Dry-Run before applying.' },
      { k: 'h', text: 'N-Database Matrix' },
      { k: 'p', text: '**N-Database Matrix** compares several environments and snapshots with one baseline: a row per object, a column per database, statuses "identical / differs / missing / only here". Click a cell to see what differs and the SQL that brings that database to the baseline.' },
      { k: 'h', text: 'Reference data' },
      { k: 'p', text: 'Tables marked in **📌 Reference Data Tables** travel with their rows: new and changed rows become `INSERT … ON CONFLICT … DO UPDATE`, removed ones `DELETE`. A snapshot compare reads those rows in the customer database too.' },
    ],
  },
  {
    id: 'offline',
    icon: '📦',
    title: 'Offline Bundles & Multi-Env',
    lead: 'Online sync between environments, linking histories, and the offline bundle for sites where only psql can run.',
    blocks: [
      { k: 'h', text: 'Which method' },
      {
        k: 'table',
        head: ['Situation', 'Method'],
        rows: [
          ['Both databases are reachable from one application', '**Sync / Push to Env**'],
          ['No network, but the jar can run at the customer; they have their own history', '`.pgsnap.json` snapshot'],
          ['No network and only an SQL file can be handed to the admin', 'offline `.sql`'],
        ],
      },
      { k: 'h', text: 'Sync / Push' },
      {
        k: 'steps',
        items: [
          ['Choose the direction', '**From (source)**, **To (target)**, optionally **Up to commit**.'],
          ['Read the status', '"Behind" — can be pushed; "Not initialized" / "Empty history" — the whole history is pushed (needs a SUPERUSER); "Diverged" — no push possible; "Ahead" / "Up to date" — nothing to push.'],
          ['Push', 'The missing commits run in one transaction (with `-- pgvc:step` step by step), then the target schema is checked against the snapshot of the last commit.'],
        ],
      },
      { k: 'note', tone: 'tip', text: 'When the target already has the schema but no shared history, use **Link**: the environment is attached to the source commit whose schema equals its live schema. No SQL is executed.' },
      { k: 'h', text: 'Offline .sql bundle' },
      {
        k: 'steps',
        items: [
          ['Open the export', '**Export Offline .SQL**: **Source environment**, **Target database is at (exclusive)** — the commit of the customer database (or "empty" for a new one), **Export up to (inclusive)**.'],
          ['Download the file', 'Named like `pgvc-dev-<from>-to-<to>.sql`. For one commit — **Export Single Commit .SQL** in its panel.'],
          ['The admin runs it', 'With `psql` (below). Everything is one transaction.'],
        ],
      },
      { k: 'code', lang: 'bash', code: 'psql -v ON_ERROR_STOP=1 -d app_db -f pgvc-dev-a1b2c3d-to-789abcd.sql' },
      { k: 'flow', items: ['BEGIN', 'Install _pgvc', 'Check HEAD', 'UP SQL of the commits', 'Register the commits', 'HEAD := last', 'COMMIT'] },
      {
        k: 'ul',
        items: [
          '**Self-registering:** afterwards the customer database holds the same commits with the same hashes — the next bundle starts from this `HEAD`, and an ordinary Push works later too.',
          '**Protection:** a database at another commit — "Migration mismatch: expected HEAD …"; running it twice — "Migration already applied". Nothing changes in both cases.',
          '`CREATE INDEX CONCURRENTLY` runs without `CONCURRENTLY` in the bundle — inside a transaction it cannot.',
        ],
      },
      { k: 'note', tone: 'warning', text: 'The bundle installs the event triggers only when a SUPERUSER runs it. On PostgreSQL 11 a bundle with `ALTER TYPE … ADD VALUE` fails — use Push or a snapshot.' },
      { k: 'h', text: 'CI/CD and Git' },
      { k: 'code', lang: 'bash', code: '# nightly drift check: exit code 2 = the schema was changed outside of version control\njava -jar pg-git-vc-1.9.0.jar --cli --action=check-drift --env=prod --output=drift.md\n# Markdown report for a Merge Request; exit code 2 on dangerous changes\njava -jar pg-git-vc-1.9.0.jar --cli --action=diff-report --env=stage --snapshot=release.pgsnap.json --output=pr-comment.md --fail-on=dangerous' },
      { k: 'p', text: '**📁 Export Git Folder** writes the schema as one file per object (`schema/tables`, `views`, `functions` …, `migrations/`) — for code review in GitLab / GitHub.' },
    ],
  },
  {
    id: 'internals',
    icon: '🛠️',
    title: 'Architecture & Internals',
    lead: 'The metadata schema, DDL capture, pg_catalog introspection and what a commit consists of.',
    blocks: [
      { k: 'h', text: 'The _pgvc schema' },
      {
        k: 'table',
        head: ['Table', 'Contents'],
        rows: [
          ['`_pgvc.commits`', '`hash` (SHA-256), `parent_hash`, `author`, `message`, `created_at`, `up_sql`, `down_sql`, `schema_snapshot` (JSONB), `ddl_log_history`'],
          ['`_pgvc.refs`', 'pointers; the `HEAD` row is the commit the database is at'],
          ['`_pgvc.ddl_audit_log`', 'DDL since the last commit: command, object, text, user, time'],
          ['`_pgvc.tracked_data_tables`', 'reference-data tables'],
        ],
      },
      { k: 'h', text: 'Event triggers' },
      {
        k: 'table',
        head: ['Trigger', 'Event', 'Function'],
        rows: [
          ['`pgvc_ddl_trigger`', '`ddl_command_end`', '`_pgvc.fn_log_ddl_end()` — every CREATE / ALTER / COMMENT / GRANT … command'],
          ['`pgvc_drop_trigger`', '`sql_drop`', '`_pgvc.fn_log_sql_drop()` — dropped objects named in the command'],
        ],
      },
      { k: 'flow', items: ['DDL in an IDE', 'ddl_command_end / sql_drop', 'INSERT into _pgvc.ddl_audit_log', 'The application polls the log', 'HEAD snapshot ↔ live schema', 'Working Tree'] },
      { k: 'p', text: 'The functions are `SECURITY DEFINER`: DDL of ordinary developers is recorded with the rights of the installing superuser, while `db_user` keeps the real login. Objects of `_pgvc`, temporary tables and TOAST are not recorded.' },
      { k: 'note', tone: 'tip', text: 'The DDL log tells who changed what. The source of truth of the Working Tree is the comparison of the `HEAD` snapshot with the live schema, so even changes made while a trigger was disabled show up.' },
      { k: 'h', text: 'Why pg_catalog and not information_schema' },
      {
        k: 'ul',
        items: [
          '`pg_class.oid` and `pg_attribute.attnum` survive renames — that is how a `RENAME` differs from drop + create;',
          'partitions are visible as partitions: `relkind = \'p\'`, `pg_inherits`, `pg_get_expr(relpartbound)`;',
          'exact definitions: `pg_get_constraintdef`, `pg_get_indexdef`, `pg_get_viewdef`, `pg_get_functiondef`, `pg_get_triggerdef`;',
          'objects the current role cannot see; materialized views, ENUMs, identity and generated columns;',
          'all queries run in one read-only `REPEATABLE READ` transaction: the snapshot is consistent.',
        ],
      },
      { k: 'p', text: 'OIDs and attnums are compared only within one database of one environment (snapshot identity `system_identifier:database_oid@environment`). Between environments objects are matched strictly by name.' },
      { k: 'h', text: 'Commit and hash' },
      { k: 'p', text: 'A commit stores UP SQL, DOWN SQL, the complete schema snapshot (tables, columns, constraints, indexes, views, sequences, functions, triggers, ENUMs, extensions, comments, reference-data rows) and the captured DDL. The hash is SHA-256 of parent, author, message, time, SQL and snapshot.' },
      { k: 'note', tone: 'danger', text: 'Do not edit the `_pgvc` tables by hand: hashes would no longer match their content and `HEAD` could point nowhere. If it happened anyway, see "FAQ & Troubleshooting".' },
    ],
  },
  {
    id: 'faq',
    icon: '❓',
    title: 'FAQ & Troubleshooting',
    lead: 'HEAD conflicts, locks, rights and manual changes to _pgvc.',
    blocks: [
      { k: 'h', text: 'HEAD and history conflicts' },
      {
        k: 'table',
        head: ['Message', 'What to do'],
        rows: [
          ['"HEAD moved" on commit', 'someone committed in this database while you looked at the Working Tree: refresh (↻), review and commit again'],
          ['"Diverged" in Sync', 'Link (when the target schema equals a source commit) or roll back the extra commits and push'],
          ['"Migration mismatch: expected HEAD X, but found Y"', 'the offline bundle was built from another commit: build it again with "Target database is at" = Y'],
          ['"Migration already applied"', 'the bundle already ran, nothing to do'],
          ['"The target has uncommitted changes"', 'commit or discard them in that database, or allow the push with uncommitted changes'],
          ['"Verification failed" after a push / rollback', 'the schema does not equal the snapshot: read the differences, fix the script; as a last resort "Skip schema verification"'],
        ],
      },
      { k: 'h', text: 'Table locks' },
      { k: 'p', text: 'Every migration, rollback, discard and push starts with `SET LOCAL lock_timeout = \'3s\'`: when another transaction holds the table, the command fails with "could not obtain lock" instead of hanging. Nothing is applied.' },
      { k: 'code', lang: 'sql', code: '-- who holds the lock\nSELECT a.pid, a.usename, a.state, now() - a.xact_start AS in_transaction, left(a.query, 120) AS query\nFROM pg_stat_activity a\nWHERE a.pid IN (SELECT unnest(pg_blocking_pids(b.pid))\n                FROM pg_stat_activity b\n                WHERE cardinality(pg_blocking_pids(b.pid)) > 0);\n\n-- end a session (only when you are sure)\nSELECT pg_terminate_backend(12345);' },
      { k: 'note', tone: 'tip', text: 'For big tables: `CREATE INDEX CONCURRENTLY`, `ADD CONSTRAINT … NOT VALID` + `VALIDATE CONSTRAINT`, a nullable column + `CHECK … NOT VALID`. **⚡ Use Zero-Downtime Pattern** inserts them for you. Raise `statement_timeout` for known long statements.' },
      { k: 'h', text: 'Rights and connections' },
      {
        k: 'table',
        head: ['Problem', 'Solution'],
        rows: [
          ['Initialization needs a SUPERUSER', 'a superuser initializes once, afterwards an ordinary role works'],
          ['DDL does not appear in the log', '`SELECT evtname, evtenabled FROM pg_event_trigger WHERE evtname LIKE \'pgvc%\';` — `D` means disabled: `ALTER EVENT TRIGGER pgvc_ddl_trigger ENABLE;`'],
          ['Cannot connect', 'JDBC URL, network, `pg_hba.conf`; "Test connection" shows the driver error'],
          ['Port 8765 is busy', '`--server.port=9000`'],
          ['Open the UI from another machine', '`--server.address=0.0.0.0` — there is no authentication, internal networks only'],
        ],
      },
      { k: 'h', text: 'Manual changes to _pgvc' },
      {
        k: 'table',
        head: ['What happened', 'What to do'],
        rows: [
          ['A `_pgvc` table was dropped', '**Initialize version control** restores the structure; if `_pgvc.commits` was dropped the history is lost — make a new baseline'],
          ['`HEAD` points at a missing commit', '`UPDATE _pgvc.refs SET commit_hash = \'<hash>\' WHERE ref_name = \'HEAD\';`'],
          ['`up_sql` / `down_sql` were edited', 'do not edit commit rows; give the wanted SQL when rolling back (the script is editable)'],
          ['The event triggers were disabled', '`ALTER EVENT TRIGGER pgvc_ddl_trigger ENABLE; ALTER EVENT TRIGGER pgvc_drop_trigger ENABLE;`'],
          ['Stale events are stuck', '**Only clear the DDL log** in the discard dialog'],
        ],
      },
      { k: 'code', lang: 'sql', code: '-- remove version control from a database completely (the history is deleted for good)\nDROP EVENT TRIGGER IF EXISTS pgvc_ddl_trigger;\nDROP EVENT TRIGGER IF EXISTS pgvc_drop_trigger;\nDROP SCHEMA _pgvc CASCADE;' },
      { k: 'note', tone: 'danger', text: '`DROP SCHEMA _pgvc CASCADE` deletes the whole history of that database. Export a snapshot and an offline bundle first if the history may be needed.' },
      { k: 'h', text: 'Other' },
      {
        k: 'ul',
        items: [
          'Dropping or reordering an ENUM value is impossible in PostgreSQL — the application shows `MANUAL ACTION REQUIRED`.',
          'Right-click a commit in the graph for its menu: revert, roll back, create a branch, snapshot, .SQL, copy hash / message, delete. There are no named branches: "Create branch here" offers to roll a database back to the commit or to push the history up to it into another environment.',
          'Changing the partitioning (ordinary table ↔ `PARTITION BY`, another key) recreates the table: the rows are copied into a temporary table and back, foreign keys, views and serial sequences are restored. A row that fits no partition stops the whole migration.',
          'A reference-data table with more than 10 000 rows is no longer compared ("over the limit" in the reference-data list).',
          'Commits of older versions do not contain functions, triggers or ENUMs: comparisons with them skip these categories, the first new commit records them.',
          'The detailed manual with diagrams is `docs/MANUAL_EN.md`, the technical reference `docs/TECHNICAL.ru.md` in the repository.',
        ],
      },
    ],
  },
];

const DOCS: Record<Lang, Section[]> = { ru: RU, en: EN };

// ------------------------------------------------------------------ search

function blockText(b: Block): string {
  switch (b.k) {
    case 'h':
    case 'p':
    case 'note':
      return b.text;
    case 'ul':
      return b.items.join(' ');
    case 'steps':
      return b.items.map(([title, text]) => `${title} ${text}`).join(' ');
    case 'code':
      return b.code;
    case 'flow':
      return b.items.join(' ');
    case 'table':
      return [...b.head, ...b.rows.flat()].join(' ');
  }
}

function sectionText(s: Section): string {
  return [s.title, s.lead, ...s.blocks.map(blockText)].join(' ').replace(/[`*]/g, '').toLowerCase();
}

function terms(query: string): string[] {
  return query.toLowerCase().split(/\s+/).map((t) => t.trim()).filter((t) => t.length >= 2);
}

/** A short piece of text around the first match. */
function snippet(s: Section, words: string[]): string {
  const plain = [s.lead, ...s.blocks.map(blockText)].join(' ').replace(/[`*]/g, '').replace(/\s+/g, ' ');
  const lower = plain.toLowerCase();
  const at = Math.min(...words.map((w) => lower.indexOf(w)).filter((i) => i >= 0));
  if (!Number.isFinite(at)) return s.lead;
  const start = Math.max(0, at - 50);
  return (start > 0 ? '…' : '') + plain.slice(start, at + 90) + '…';
}

// ------------------------------------------------------------------ rendering

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Plain text with the search terms marked. */
function highlight(text: string, words: string[]): ReactNode {
  if (words.length === 0) return text;
  const re = new RegExp(`(${words.map(escapeRegExp).join('|')})`, 'gi');
  return text.split(re).map((part, i) =>
    i % 2 === 1 ? (
      <mark key={i} className="rounded bg-amber-400/30 px-0.5 text-amber-100">
        {part}
      </mark>
    ) : (
      <Fragment key={i}>{part}</Fragment>
    ),
  );
}

/** `code` and **bold** inside a string. */
function Inline({ text, words }: { text: string; words: string[] }) {
  const parts = text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
  return (
    <>
      {parts.map((part, i) => {
        if (part.startsWith('`') && part.endsWith('`') && part.length > 1) {
          return (
            <code key={i} className="rounded bg-kraken-bg px-1 py-px font-mono text-[0.92em] text-teal-200">
              {highlight(part.slice(1, -1), words)}
            </code>
          );
        }
        if (part.startsWith('**') && part.endsWith('**') && part.length > 3) {
          return (
            <strong key={i} className="font-semibold text-gray-100">
              {highlight(part.slice(2, -2), words)}
            </strong>
          );
        }
        return <Fragment key={i}>{highlight(part, words)}</Fragment>;
      })}
    </>
  );
}

const NOTE_STYLE = {
  tip: { box: 'border-emerald-700/70 bg-emerald-950/40', title: 'text-emerald-300', Icon: Lightbulb },
  warning: { box: 'border-amber-700/70 bg-amber-950/40', title: 'text-amber-300', Icon: TriangleAlert },
  danger: { box: 'border-red-800 bg-red-950/40', title: 'text-red-300', Icon: AlertOctagon },
} as const;

function BlockView({ block, words }: { block: Block; words: string[] }) {
  const { t } = useI18n();
  switch (block.k) {
    case 'h':
      return (
        <h3 className="mt-6 text-[15px] font-semibold text-gray-100 first:mt-0">
          <Inline text={block.text} words={words} />
        </h3>
      );
    case 'p':
      return (
        <p className="leading-relaxed text-gray-300">
          <Inline text={block.text} words={words} />
        </p>
      );
    case 'ul':
      return (
        <ul className="list-disc space-y-1.5 pl-5 leading-relaxed text-gray-300 marker:text-teal-500">
          {block.items.map((item, i) => (
            <li key={i}>
              <Inline text={item} words={words} />
            </li>
          ))}
        </ul>
      );
    case 'steps':
      return (
        <ol className="grid gap-2 sm:grid-cols-2 xl:grid-cols-4">
          {block.items.map(([title, text], i) => (
            <li key={i} className="flex flex-col rounded-md border border-kraken-line bg-kraken-bg p-3">
              <span className="mb-1.5 flex items-center gap-2">
                <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-teal-500/20 text-[12px] font-bold text-teal-300">
                  {i + 1}
                </span>
                <span className="text-[10.5px] uppercase tracking-wide text-gray-500">{t('docs.step', { n: i + 1 })}</span>
              </span>
              <span className="mb-1 font-medium text-gray-100">
                <Inline text={title} words={words} />
              </span>
              <span className="text-[12.5px] leading-relaxed text-gray-400">
                <Inline text={text} words={words} />
              </span>
            </li>
          ))}
        </ol>
      );
    case 'note': {
      const style = NOTE_STYLE[block.tone];
      return (
        <div className={`flex gap-2.5 rounded-md border px-3 py-2.5 ${style.box}`} role="note">
          <style.Icon size={16} className={`mt-0.5 shrink-0 ${style.title}`} />
          <div className="leading-relaxed text-gray-200">
            <span className={`mr-1.5 font-semibold ${style.title}`}>{t(`docs.${block.tone}`)}.</span>
            <Inline text={block.text} words={words} />
          </div>
        </div>
      );
    }
    case 'code':
      return (
        <div className="overflow-hidden rounded-md border border-kraken-line">
          <div className="border-b border-kraken-line bg-kraken-panel px-3 py-1 font-mono text-[10.5px] uppercase text-gray-500">
            {block.lang}
          </div>
          <pre className="overflow-auto bg-kraken-bg p-3 font-mono text-[12px] leading-relaxed text-gray-200">
            {highlight(block.code, words)}
          </pre>
        </div>
      );
    case 'flow':
      return (
        <div className="flex flex-wrap items-center gap-1.5 rounded-md border border-dashed border-kraken-line bg-kraken-bg/60 p-3">
          {block.items.map((item, i) => (
            <Fragment key={i}>
              {i > 0 && <ArrowRight size={14} className="shrink-0 text-teal-500" aria-hidden="true" />}
              <span className="rounded-md border border-teal-800/70 bg-teal-950/40 px-2.5 py-1 text-[12px] text-teal-100">
                {highlight(item, words)}
              </span>
            </Fragment>
          ))}
        </div>
      );
    case 'table':
      return (
        <div className="overflow-auto rounded-md border border-kraken-line">
          <table className="w-full text-left text-[12.5px]">
            <thead>
              <tr className="bg-kraken-panel">
                {block.head.map((h, i) => (
                  <th key={i} className="border-b border-kraken-line px-3 py-1.5 font-medium text-gray-300">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, r) => (
                <tr key={r} className="border-b border-kraken-line/60 last:border-0">
                  {row.map((cell, c) => (
                    <td key={c} className={`px-3 py-1.5 align-top leading-relaxed ${c === 0 ? 'text-gray-100' : 'text-gray-400'}`}>
                      <Inline text={cell} words={words} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
  }
}

// ------------------------------------------------------------------ the dialog

interface Props {
  onClose: () => void;
  /** section to open first (default: the last one read) */
  initialSection?: SectionId;
}

/** "📖 Docs": the manual in the language of the UI, with section navigation and search. */
export function DocumentationModal({ onClose, initialSection }: Props) {
  const { t, lang } = useI18n();
  const sections = DOCS[lang];
  const [active, setActive] = useState<SectionId>(() => {
    const stored = loadSetting(SECTION_KEY, 'quickstart') as SectionId;
    return initialSection ?? (sections.some((s) => s.id === stored) ? stored : 'quickstart');
  });
  const [query, setQuery] = useState('');
  const article = useRef<HTMLDivElement>(null);
  const searchBox = useRef<HTMLInputElement>(null);

  useEffect(() => {
    saveSetting(SECTION_KEY, active);
    article.current?.scrollTo({ top: 0 });
  }, [active]);

  useEffect(() => {
    searchBox.current?.focus();
  }, []);

  const words = useMemo(() => terms(query), [query]);
  const index = useMemo(() => new Map(sections.map((s) => [s.id, sectionText(s)])), [sections]);
  const matches = useMemo(
    () => (words.length === 0 ? [] : sections.filter((s) => words.every((w) => index.get(s.id)?.includes(w)))),
    [sections, index, words],
  );
  const section = sections.find((s) => s.id === active) ?? sections[0];

  return (
    <Modal
      title={t('docs.title')}
      subtitle={t('docs.subtitle')}
      onClose={onClose}
      width="max-w-[min(1500px,97vw)]"
    >
      <div className="grid h-[calc(100vh-9.5rem)] min-h-[460px] grid-cols-[270px_minmax(0,1fr)] gap-5">
        {/* ---------------------------------------------------------- navigation */}
        <nav className="flex min-h-0 flex-col gap-3" aria-label={t('docs.sections')}>
          <div className="relative">
            <Search size={13} className="pointer-events-none absolute left-2.5 top-2.5 text-gray-500" />
            <input
              ref={searchBox}
              className="input pl-8 pr-7"
              placeholder={t('docs.search')}
              aria-label={t('docs.search')}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && matches[0]) setActive(matches[0].id);
              }}
            />
            {query && (
              <button
                className="absolute right-1.5 top-1.5 rounded p-0.5 text-gray-500 hover:text-gray-200"
                onClick={() => setQuery('')}
                aria-label={t('docs.clearSearch')}
              >
                <X size={14} />
              </button>
            )}
          </div>

          {words.length > 0 ? (
            <div className="min-h-0 flex-1 overflow-auto">
              <div className="mb-1.5 text-[11px] uppercase text-gray-500">{t('docs.results', { count: matches.length })}</div>
              {matches.length === 0 && <p className="text-xs text-gray-500">{t('docs.noResults')}</p>}
              <ul className="space-y-1">
                {matches.map((s) => (
                  <li key={s.id}>
                    <button
                      className={`w-full rounded-md px-2.5 py-2 text-left hover:bg-kraken-hover ${
                        s.id === active ? 'bg-teal-500/15 ring-1 ring-teal-700' : ''
                      }`}
                      onClick={() => setActive(s.id)}
                    >
                      <span className="block font-medium text-gray-100">
                        {s.icon} {s.title}
                      </span>
                      <span className="mt-0.5 block text-[11.5px] leading-snug text-gray-400">{highlight(snippet(s, words), words)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <ul className="min-h-0 flex-1 space-y-0.5 overflow-auto">
              {sections.map((s, i) => (
                <li key={s.id}>
                  <button
                    className={`flex w-full items-start gap-2 rounded-md px-2.5 py-2 text-left hover:bg-kraken-hover ${
                      s.id === active ? 'bg-teal-500/15 text-teal-100 ring-1 ring-teal-700' : 'text-gray-300'
                    }`}
                    onClick={() => setActive(s.id)}
                    aria-current={s.id === active ? 'page' : undefined}
                  >
                    <span className="w-5 shrink-0 text-center">{s.icon}</span>
                    <span className="min-w-0">
                      <span className="mr-1 text-gray-500">{i + 1}.</span>
                      {s.title}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="rounded-md border border-kraken-line bg-kraken-bg px-3 py-2 text-[11px] leading-snug text-gray-500">
            <BookOpen size={12} className="mb-1 text-gray-400" />
            {t('docs.repoHint', { file: lang === 'ru' ? 'docs/MANUAL_RU.md' : 'docs/MANUAL_EN.md' })}
          </div>
        </nav>

        {/* ---------------------------------------------------------- article */}
        <article ref={article} className="min-h-0 overflow-auto pr-2" aria-label={section.title}>
          <header className="mb-4 border-b border-kraken-line pb-3">
            <h2 className="text-xl font-semibold text-gray-100">
              <span className="mr-2">{section.icon}</span>
              {highlight(section.title, words)}
            </h2>
            <p className="mt-1.5 max-w-4xl leading-relaxed text-gray-400">
              <Inline text={section.lead} words={words} />
            </p>
          </header>
          <div className="flex max-w-5xl flex-col gap-3 text-[13px]">
            {section.blocks.map((b, i) => (
              <BlockView key={`${section.id}-${i}`} block={b} words={words} />
            ))}
          </div>
          <footer className="mt-8 flex max-w-5xl items-center justify-between border-t border-kraken-line pt-3 text-xs">
            {(() => {
              const at = sections.findIndex((s) => s.id === section.id);
              const prev = sections[at - 1];
              const next = sections[at + 1];
              return (
                <>
                  {prev ? (
                    <button className="btn" onClick={() => setActive(prev.id)}>
                      ← {prev.icon} {prev.title}
                    </button>
                  ) : (
                    <span />
                  )}
                  {next && (
                    <button className="btn" onClick={() => setActive(next.id)}>
                      {next.icon} {next.title} →
                    </button>
                  )}
                </>
              );
            })()}
          </footer>
        </article>
      </div>
    </Modal>
  );
}
