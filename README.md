# Лабораторная №2 — CouchDB и Etcd

Вариант **574821**. Продолжение `../lab1`: Java 17, Spring Boot, события библиотеки, временные заявки и заказы. Постоянные объекты теперь в CouchDB. API первой лабораторной и Etcd сохранены. Отчёт: `REPORT.md`, готовый PDF: `output/pdf/ITMO_NoSQL_Lab2_2026.pdf`.

## Запуск

```bash
podman machine start podman-applehv-test # только macOS; имя своей VM: podman machine list
podman compose up --build -d
```

Можно использовать Docker Compose: `docker compose up --build -d`.

- Приложение: http://localhost:18080/
- CouchDB / Fauxton: http://localhost:5984/_utils/
- Реплика: http://localhost:5985/_utils/
- Учебные credentials CouchDB: `library` / `library`.
- Etcd: `localhost:2379`.

`setup` создаёт БД, validation function, MapReduce view, Mango-индексы и постоянное задание репликации. Повторный запуск безопасен. Настройки всех узлов лежат в `compose.yaml`; данные сохраняются в отдельных volumes проекта `lab2` и не затрагивают volumes первой лабораторной.

Для локального запуска Java с контейнерными БД:

```bash
podman compose up -d couch1 couch2 etcd setup
./gradlew test bootJar
SERVER_PORT=18080 java -jar build/libs/library-etcd-1.0.0.jar
```

Для JVM 17 на macOS при необходимости: `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`.

## Старые маршруты

| Метод и путь | Назначение |
| --- | --- |
| POST /api/events | Создание события |
| GET /api/events | Полный список, прежний JSON-массив |
| GET /api/events/{id} | Событие, прежние поля |
| POST и GET /api/events/{id}/views | CAS-счётчик Etcd |
| POST /api/drafts | Заявка Etcd с TTL |
| GET /api/drafts/{id} | Чтение заявки |
| POST /api/drafts/{id}/submit | Идемпотентное оформление заказа |
| GET /api/orders/{id} | Чтение заказа |
| PUT и GET /api/managers/{id}/settings | Настройки CouchDB и cache-aside Etcd |
| POST /api/experiments/write-conflict?writers=24 | Прежний эксперимент blind write / CAS |

## Новые маршруты

| Метод и путь | Назначение |
| --- | --- |
| GET /api/events/{id}/document | Полный JSON с `_rev`, историей, schemaVersion |
| PUT /api/events/{id} | Редактирование с обязательными rev и actor |
| DELETE /api/events/{id}?rev=...&actor=alice | Логическое удаление с проверкой ревизии |
| GET /api/events/{id}/orders?limit=25&bookmark=... | Связанные заказы |
| GET /api/documents/events?type=MEETING | Mango-поиск |
| GET /api/documents/events/explain?type=MEETING | План того же запроса |
| GET /api/analytics/user-actions | MapReduce, ключ `[пользователь, действие]`, число действий |

Параметры поиска: `type` обязателен; `from`, `to` — ISO-8601 время последнего изменения включительно; `sort=asc|desc`; `limit=1..200`; `bookmark`; `actor` и `state` ищут одну и ту же запись массива `history` через `$elemMatch`. `state` здесь относится к истории, а не только к текущему состоянию. Для следующей страницы передайте полученный bookmark с теми же фильтрами и сортировкой. Пустой массив `docs` означает конец. Список не является snapshot при параллельных изменениях.

Пример создания:

```bash
curl -sS localhost:18080/api/events -H 'Content-Type: application/json' -d '{"title":"Книжная встреча","type":"MEETING","startsAt":"2026-12-01T12:00:00Z","capacity":30}'
```

Возьмите `id` из ответа, получите `/api/events/{id}/document` и скопируйте `_rev`:

```json
{
  "rev": "1-значение-из-ответа",
  "actor": "alice",
  "state": "CLOSED",
  "title": "Книжная встреча",
  "type": "MEETING",
  "startsAt": "2026-12-01T12:00:00Z",
  "capacity": 30
}
```

Отправьте JSON через PUT `/api/events/{id}`. Повтор с той же `rev` вернёт 409. Для разрешения конфликта перечитайте документ, сравните изменения и отправьте согласованный вариант с новой ревизией. Сервис не затирает чужое обновление автоматически.

## Проверки и воспроизводимые эксперименты

```bash
./gradlew test bootJar
node scripts/test_database_rules.cjs
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test_migration.py
python3 scripts/integration.py
python3 scripts/explain_experiment.py
python3 scripts/migration_experiment.py
python3 scripts/pagination_experiment.py
python3 scripts/cache_experiment.py  # наблюдение около 60 секунд
```

Последние пять команд требуют работающего стека. Они сохраняют реальные результаты в `output/evidence/`. Эксперименты индекса и миграции используют отдельные БД `lab2_explain_*` и `lab2_migration_*`; рабочая БД не очищается. Интеграционный сценарий создаёт уникальные тестовые объекты, затем логически удаляет событие, сохраняя историю и заказ.

`explain_experiment.py` сравнивает одинаковую выборку на 500 документах до и после создания индекса, сохраняет планы, `total_docs_examined`, `total_keys_examined`, `results_returned` и время. Время на таком объёме не является нагрузочным бенчмарком.

Проверка репликации:

```bash
curl -su library:library http://localhost:5984/_scheduler/docs/_replicator/library-to-replica
curl -su library:library http://localhost:5985/library/event:UUID
```

Задание должно перейти в `running`, а документ на втором узле получить ту же `_rev`. Репликация односторонняя, асинхронная. Приложение пишет только в первый узел. Автоматическое переключение и объединение конфликтующих ветвей на двух пишущих узлах не реализованы.

## Миграция документов CouchDB

```bash
python3 scripts/couch_admin.py migrate
python3 scripts/couch_admin.py migrate
```

Документы без версии или с версией 1 получают `schemaVersion=2`, `updatedAt`, `lastModified`, `state` и запись MIGRATE в истории. Версия 2 повторно не записывается, `_rev` не меняется. При конкурентном изменении скрипт считает конфликт и завершается с ненулевым кодом: повторите запуск. Чтение постраничное, по 200 документов. Перед миграцией остановите записи приложения для воспроизводимого результата.

## Перенос данных первой лабораторной

Остановите приложение первой лабораторной и экспортируйте её постоянные данные:

```bash
podman exec -i library-postgres psql -U library -d library -tA < scripts/export_lab1.sql > lab1-data.json
python3 scripts/couch_admin.py import lab1-data.json
```

Если контейнер имеет другое имя, подставьте его. Импорт сохраняет исходные UUID и связи; существующие документы не перезаписывает. Изменённые исходные данные повторным импортом не синхронизируются. PostgreSQL не нужен для обычной работы lab2. Автоматического удаления прежней базы нет.

Etcd в lab2 использует отдельный volume. Логика первой лабораторной сохранена; прежние ключи физически не копируются. При необходимости сохранения старых заявок подключите существующий Etcd через `ETCD_ENDPOINTS` при локальном запуске после импорта событий. Пока выполняется перенос, остановите записи обеих версий приложения.

## Кэш и ограничения

Настройки читаются из CouchDB по ID и кэшируются в Etcd с lease (`CACHE_TTL`, по умолчанию 60 секунд). Обновление сохраняет документ, затем удаляет ключ кэша. При отказе Etcd настройки читаются из CouchDB. Гонка заполнения кэша и обновления может временно вернуть старое значение; TTL ограничивает жизнь такой копии с момента её записи. Изменения напрямую в CouchDB не инвалидируют кэш и видны после TTL.

Нет общей транзакции CouchDB/Etcd или внешнего ключа между документами. Ссылка проверяется сервисом, логическое удаление сохраняет связанный объект. Проверка существования события и сохранение заказа не атомарны. `actor` — передаваемое имя, а не аутентифицированная личность. Учебный API не предназначен для публичного доступа.

## Сборка PDF

```bash
uv venv .venv
uv pip install --python .venv/bin/python -r scripts/report-requirements.txt
.venv/bin/python scripts/build_report.py
```

Генератор использует Times New Roman и Courier New из системной папки macOS, как в первой лабораторной. Для другой ОС измените `FONT` в скрипте.
