# Amazing Codex GUI - заметки для работы с проектом

Плагин для JetBrains IDE: чат-панель для OpenAI Codex внутри IDE. Это порт
[Amazing Claude Code GUI](https://github.com/crmapache/amazing-claude-code) (соседняя папка
`../amazing-claude-code`) на Codex: интерфейс (`webview/`) - тот же код, движок под ним (`src/`)
переписан под `codex app-server`.

Заметки оригинала про саму панель (лента, поле ввода, харнесс, телефон, поиск, сценарии, словари,
статистика) лежат в `docs/panel-notes.md` и остаются верными - это тот же код. Всё, что там сказано
про Claude Code CLI, к форку не относится; как это устроено у Codex - ниже.

## Правила проекта

- **Git-коммиты только с явного разрешения на каждый конкретный коммит.** Правки в рабочем дереве
  можно делать свободно; перед `git commit` - остановиться и спросить.
- **Секреты.** `.env` и `secrets/` в форк не копировались. Если появятся - те же правила, что в
  оригинале: `.env` не читать ни целиком, ни кусками, значения не печатать.
- **Порты** - из `~/.claude/ports.md`: dev-сервер панели и харнесса - `5190` (`webview/vite.config.ts`,
  `strictPort`), телефонный клиент - `5191`, релей локально - `4450`, приёмник отзывов - `4451`,
  отладочный порт панели в песочнице - `4452` (`./gradlew runIde -PjcefDebugPort=4452`).
- **Запущенное гасить по PID**, не `pkill -f <слово>`: на машине рядом живут песочницы оригинала.
- **Оригинал не трогать.** `../amazing-claude-code` - отдельный проект с незакоммиченной работой.

## Главное решение: переводчик, а не второй интерфейс

Codex говорит своим протоколом (JSON-RPC `app-server`: `thread/*`, `turn/*`, `item/*`), а панель,
телефон, статистика, сценарии, поиск и журнал разговоров умеют читать один язык - события потока
Claude Code (`system/init`, `assistant` с блоками `text`/`thinking`/`tool_use`, `user` с
`tool_result`, `stream_event`, `result`, `rate_limit_event` и т.д.). Поэтому между Codex и всем
остальным стоит слой перевода, а всё выше него не менялось:

- `codex/AppServer.kt` - процесс `codex app-server` и клиент JSON-RPC поверх stdin/stdout.
  Сообщения без поля `jsonrpc`; рукопожатие `initialize` (с `capabilities.experimentalApi = true`),
  потом уведомление `initialized`; запросы до конца рукопожатия ждут в очереди.
- `codex/CodexDialect.kt` - чистые функции: предмет Codex -> строки потока в диалекте панели.
- `codex/CodexStream.kt` - живой переводчик одного хода: дельты текста и рассуждений, начало и конец
  предметов, план, счётчики токенов, повторы, конец хода.
- `codex/CodexSession.kt` - один разговор: публичное API то же, что было у `ClaudeSession`, поэтому
  `CodexSessions`, хаб, очередь, телефон и сценарии работают с ним без изменений.

Правило для правок: **новое поведение Codex переводится в существующий диалект, а не протаскивается
в панель новым типом сообщения.** Панель и телефон - общий код, и новый тип пришлось бы учить трём
клиентам. Исключения делались только там, где у диалекта не было места для факта: `unified_diff` у
правки (см. ниже) и уровни усилия у модели в сообщении `models`.

### Процессы

- **Один `app-server` на разговор** (`CodexSession`). Тред открывается `thread/start`
  (`thread/resume` для разговора из истории, `thread/fork` для форка); брифинг панели
  (`CodexLaunch.PANEL_BRIEFING`) уходит как `developerInstructions`.
- **Один общий `app-server` для вопросов не про разговор** (`CodexCatalog`): список тредов, страницы
  истории, агенты workflow. Поднимается на первый вопрос, гаснет после двух минут тишины. Звать только
  не с EDT (`call` проверяет).
- **Разовые запуски** (`CodexOneShot`): вопрос про аккаунт в его окружении (`account/read`,
  `account/rateLimits/read`, `model/list`) и одноразовый ответ модели - эфемерный тред, read-only,
  `approvalPolicy: never`, свои `baseInstructions` вместо инструкций агента, при нужде `outputSchema`.
  На нём стоят улучшение промпта (`PromptImprover`), ИИ-поиск (`search/AiSearch`), названия вкладок
  (`CodexTitles`) и написание сценария (`scenario/ScenarioAuthor`). В историю они ничего не пишут.
- Процесс, разговор которого простоял полчаса, отдаётся (`IdleSleep`, как в оригинале); следующее
  сообщение поднимает тред через `thread/resume`.

### Как предметы Codex становятся карточками

| Предмет Codex | Карточка в ленте |
|---|---|
| `agentMessage` (+ `item/agentMessage/delta`) | текст ответа |
| `reasoning` (+ `summaryTextDelta`/`textDelta`) | мысли |
| `commandExecution` с одним действием `read` / `search` / `listFiles` | `Read` / `Grep` / `Glob` |
| `commandExecution` прочее | `Bash` (команда без обёртки `/bin/zsh -lc '...'`, `CodexDialect.unwrapShell`) |
| `fileChange`: `add` / `delete` / `update` | `Write` / `Edit` с `deleted` / `Edit` с `unified_diff` (и `moved_from`) |
| `mcpToolCall` | `mcp__<сервер>__<инструмент>` |
| `webSearch` | `WebSearch` |
| `imageView` | `Read` картинки |
| `collabAgentToolCall` | `Task` (субагент) |
| `plan` (ход в режиме плана) | `ExitPlanMode` - карточка плана с кнопками |
| `turn/plan/updated` | `TodoWrite` - полоса задач над полем |
| `contextCompaction`, `thread/compacted` | строка компакта |
| `error` с `willRetry` | `api_retry` - карточка повтора |
| `model/rerouted` | `model_refusal_fallback`, причина словами (`CodexDialect.rerouteReason`) |
| провал хода по входу | ответ `<synthetic>` с `authentication_failed` - строка с кнопкой входа |
| провал хода по лимиту | `rate_limit_event` - карточка лимита |

- **Правка рисуется из диффа, который применил Codex**, с настоящими номерами строк
  (`unified_diff` во входе `Edit`; `feed/tools.ts` - `unifiedHunks`, `countUnified`). `old_string` и
  `new_string` рядом всё равно собираются (`CodexDialect.editInput`): по ним считает строки
  статистика, и их рисует телефон, собранный до этой правки.
- **Патч на несколько файлов - несколько карточек**: id предмета, дальше `id:0`, `id:1`
  (`CodexDialect.fileCallIds`).
- Результат команды режется до 30 000 символов; отклонённое человеком - `Declined.`.

### Запросы к человеку

| Запрос сервера | Что видит панель | Ответ |
|---|---|---|
| `item/commandExecution/requestApproval` | разрешение на `Bash` | `accept` / `acceptForSession` / `acceptWithExecpolicyAmendment` / `decline` |
| `item/fileChange/requestApproval` | разрешение на `Edit`/`Write` | `accept` / `acceptForSession` / `decline` |
| `item/tool/requestUserInput` | вопрос с вариантами (`AskUserQuestion`) | ответы по id вопросов |
| `item/permissions/requestApproval` | разрешение `Permissions` | выданные права, на ход или на сессию |
| `mcpServer/elicitation/request` | разрешение `mcp__<сервер>__open` | `accept` / `decline` |
| конец хода в режиме плана с предметом `plan` | карточка плана | одобрение - режим «Авто» и ход «Implement the plan.» |

Идентификатор запроса для панели - свой (`codex-<эпоха процесса>-<id>`): id JSON-RPC начинаются
заново у каждого процесса, и без эпохи ответ на карточку, пережившую перезапуск, ушёл бы чужому
запросу. Таймеров в пути разрешений нет, как и в оригинале: вопрос ждёт человека сколько угодно.

### Режимы, модели, усилие

Режим (`PermissionModes.kt`) - это пара «политика одобрений + песочница», id сохранены от оригинала,
потому что их называют сохранённые настройки и телефон:

| id | На экране | approvalPolicy | sandbox |
|---|---|---|---|
| `manual` | Ask every time | `untrusted` | workspace-write |
| `acceptEdits` | Auto | `on-request` | workspace-write |
| `readOnly` | Read only | `on-request` | read-only |
| `plan` | Plan | `on-request` | read-only + `collaborationMode: plan` |
| `bypassPermissions` | Full access | `never` | danger-full-access |

- Старые значения из настроек оригинала читаются (`normalize`): `default` -> `manual`, `auto` и
  `dontAsk` -> `acceptEdits`, незнакомое -> `acceptEdits`.
- Режим, модель и усилие едут с каждым `turn/start`, поэтому смена работает посреди разговора без
  перезапуска процесса и применяется со следующего хода.
- **Одобренный план переводит разговор в «Авто», и за столом, и с телефона** (`decidePlan`). В
  оригинале за столом был «без вопросов»; у Codex ближайшее - полный доступ без песочницы и с сетью,
  это намного больше, чем одобряет план.
- Модели - из `model/list` (`CodexShapes.models` -> `ProjectUsage.sendModels`). В меню сверху всегда
  стоит «Default»: список Codex называет только модели, а вкладка без выбора работает на `default` (в
  `turn/start` модель тогда не передаётся). Подпись под ним называет модель с `isDefault`.
- **Уровни усилия - свои у каждой модели**: у `gpt-5.5` нет `max` и `ultra`, и умолчание у неё
  `xhigh`. Codex принимает недоступный уровень молча и работает на другом (проверено живьём), поэтому
  меню показывает только уровни модели (`levelsOf`, `effortOptions` в `catalog.ts`), а смена модели на
  ту, где выбранного уровня нет, сбрасывает усилие на `auto` (`pickModel` в `App.tsx`, та же логика в
  `mobile/screens/RunSheet.tsx`). Уровни Codex 0.152: `low`, `medium`, `high`, `xhigh`, `max`, `ultra`
  (+ `minimal` у старых моделей); `ultracode` из настроек оригинала читается как `ultra`.

### Команды в поле

`app-server` слэш-команд не знает, поэтому их разбирает IDE (`CodexCommands.parse`):

- `/compact` -> `thread/compact/start`; `/clear` и `/new` -> новый тред в той же вкладке и
  `conversation_reset` панели; `/init` -> ход с текстом `CodexLaunch.INIT_PROMPT`; `/review [ветка |
  commit <sha> | инструкции]` -> `review/start` (без аргумента - незакоммиченные изменения).
- `/prompts:<имя> аргументы` - свой промпт из `~/.codex/prompts/<имя>.md`, раскрытый с `$1..$9`,
  `$ARGUMENTS`, `ИМЯ=значение`.
- `/<скилл>` или `$<скилл>` - скилл уходит предметом `skill` рядом с текстом (реестр - `CodexSkills`,
  наполняется сканом `CodexCommandHints`: `.codex/skills` и `.agents/skills` проекта,
  `~/.codex/skills`, `~/.agents/skills`).
- Слэш-команда, отправленная посреди хода, ждёт его конца (`PromptDelivery.waitsForTheTurn`), а
  обычный текст посреди хода уходит в идущий ход через `turn/steer` (с `expectedTurnId`; не
  принялся - ждёт конца хода).

### История, форки, контекст, лимиты

- **История** (`CodexHistory`): список - `thread/list` с фильтром по папке проекта; страница -
  `thread/turns/list` с конца, по 10 ходов на запрос, курсор - id самого старого хода; ход
  проигрывается в диалект `CodexReplay.lines`. Бюджеты страницы: стол 30 ходов / 512K символов /
  12K вывода команды, телефон 10 / 150K / 2K. Модель разговора читается из файла треда
  (`turn_context`), последняя занятость контекста - из `token_count`.
- Файлы тредов - `~/.codex/sessions/ГГГГ/ММ/ДД/rollout-<время>-<uuid>.jsonl`, имена - в
  `~/.codex/session_index.jsonl`. По ним же работают поисковый индекс (`search/TranscriptText`,
  `SearchIndex`) и счётчик токенов за день (`CodexTokenUsage`).
- **Контекст** - из `thread/tokenUsage/updated`: занято `total - reasoning`, окно -
  `modelContextWindow`.
- **Лимиты** - `account/rateLimits/read` и уведомления `account/rateLimits/updated`. Codex сообщает
  до двух окон с их длиной; окно до суток идёт на пятичасовое кольцо, длиннее - на недельное
  (`CodexShapes.usage`). У места в business-плане окон нет вовсе, есть только лимит трат, который
  задало рабочее пространство (`individualLimit`, сбрасывается раз в месяц): он едет блоком «бюджет
  сверх плана» (`extra` с `percent` и `resets`) и рисуется своим кольцом «Spending limit» под полем и
  строкой «spend» на экране аккаунтов (`spendingWindow` в `feed/usage.ts`). Уведомления бывают
  частичными, поэтому поля сливаются, а не заменяются.

### Аккаунты

- У каждого добавленного аккаунта свой `CODEX_HOME`: `~/.amazing-codex/accounts/<id>/` с его
  `auth.json` и **символьными ссылками** на всё остальное в `~/.codex` (кроме `auth.json` и баз
  `*.sqlite*`); `CODEX_SQLITE_HOME` указывает на обычный `~/.codex`. Так вход у аккаунтов разный, а
  история, конфиг, скиллы, промпты и MCP-серверы - общие (`AccountDrawer.sync`).
- В окружении процесса аккаунта `OPENAI_API_KEY`, `CODEX_API_KEY` и `CODEX_ACCESS_TOKEN`
  выставляются **пустыми**, а не выбрасываются: окружение ребёнка собирается поверх родительского, и
  выброшенный ключ вернулся бы оттуда и перебил выбранный аккаунт.
- **Удалять ящик - только `AccountDrawer.delete`** (NIO, без перехода по ссылкам). Котлиновский
  `deleteRecursively` идёт по символьным ссылкам и снёс бы `~/.codex/sessions` - всю историю машины.
  Держит это тест.
- Имя и план строки - из `auth.json` ящика (`AccountIdentity`: клеймы ID-токена `email` и
  `chatgpt_plan_type`, у ключа API - последние четыре символа). Токен только читается локально.
- Вход - `codex login` во встроенном терминале с `CODEX_HOME` ящика (`AccountSignIn`), выход -
  `codex logout`.
- На Windows ссылки могут не создаться (нет прав на symlink); тогда проба (`IsolationProof`)
  выключает добавление аккаунтов, а не работает на наполовину собранном ящике.

### MCP и плагины

- Статус - `mcpServerStatus/list` + `codex mcp list --json` (настроенные, включая выключенные) +
  уведомления `mcpServer/startupStatus/updated`; слова статуса - как ждёт панель (`connected`,
  `needs-auth`, `failed`, `pending`, `disabled`).
- Добавить / убрать - `codex mcp add` / `codex mcp remove`, после чего разговор перезапускается над
  тем же тредом (ждёт конца идущего хода), как в оригинале. Кнопка переподключения -
  `config/mcpServer/reload`: переподключения одного сервера у Codex нет, перечитываются все.
- Вход в сервер - `mcpServer/oauth/login`: адрес открывается в браузере машины. С телефона не
  выполняется - колбэк приходит на порт этой машины.
- Плагины - `codex plugin list/add/remove`. **Включать и выключать плагин Codex не умеет**, и панель
  на это честно отказывает, а не делает вид.

## Что не перенесено и почему

- **Вход в Claude Design** (`/design-login`, `DesignLogin`) - у Codex такого нет; удалено вместе с
  кнопкой на экране аккаунтов и сообщением протокола.
- **Остановка одной фоновой задачи** (`stopTask`) - у `app-server` нет такого запроса; отвечает
  отказом.
- **Число сообщений в строке истории** - `thread/list` его не отдаёт, строка показывает только то,
  что известно.
- **Страница в маркетплейсе и публичный релей для форка** не заведены: это решение владельца. Плагин
  по умолчанию смотрит на тот же релей, что и оригинал (`RemoteAgent.DEFAULT_RELAY`), а
  `scripts/relay-dev.sh` в форке закрыт проверкой, чтобы случайно не выкатить релей оригинала.
- `PromptDeliveries` и часть `PromptDelivery` остались от оригинала: там проверка доставки читала
  транскрипт CLI. У `app-server` доставку подтверждает сам ответ на `turn/start` / `turn/steer`, и
  живой код их не зовёт.

## Как проверять

- `./gradlew test` - движок, перевод, аккаунты, история, сценарии. После смены сигнатуры в
  `src/main` (например, нового параметра конструктора) тестовые классы могут остаться собранными против
  старой и упасть с `NoSuchMethodError`: тогда сначала `./gradlew compileTestKotlin --rerun`, потом
  отдельно `./gradlew instrumentCode instrumentTestCode` и `./gradlew cleanTest test`. В одной команде
  с `--rerun` шаги инструментации падают на ошибке плагина платформы («doesn't support the nested
  "skip" element») - это не поломка проекта.
- Живой тест против настоящего Codex (тратит ходы аккаунта, поэтому выключен по умолчанию):
  `echo gpt-5.6-luna > /tmp/acx-live-codex && ./gradlew test --tests '*CodexSessionLiveTest*'`, после
  прогона файл удалить. Проверяет ответ, инструменты, одобрение в режиме «Ask every time», продолжение
  треда в новом процессе и чтение его из истории.
- `cd webview && pnpm tsc --noEmit && pnpm vitest run` - панель.
- Харнесс: `cd webview && pnpm dev`, затем http://localhost:5190/harness.html - настоящий `App` без
  агента и без IDE (как устроен - в `docs/panel-notes.md`, раздел про харнесс).
- Песочница: `./scripts/sandbox.sh` - собирает плагин и поднимает отдельный WebStorm на
  `sandbox-project/` (лог - `build/sandbox.log`). Полка сценариев песочницы
  (`sandbox-project/.codex/scenarios/`) пустая и не в git: сценарий для проверки пишется на месте,
  кнопкой «New scenario» или фразой в модель. Песочница гасит только свою копию (маркер
  `io.github.crmapache.amazingcodex`), песочницу оригинала не трогает.
- Протокол `app-server` своей версии Codex можно выгрузить схемой:
  `codex app-server generate-json-schema --out /tmp/codex-schema` - это самый надёжный ответ на
  «какие поля у этого уведомления».
