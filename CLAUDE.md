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
  Одно исключение по решению владельца - сервис статистики `usage-service/` там: он общий для обоих
  плагинов (поле `product`: `acc` / `acx`, вкладки в админке), и его тест `codexFork.test.ts` сверяет
  списки с `usage/UsageFeatures.kt` форка.

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

**Конец хода с планом придерживается до решения** (`CodexSession.heldPlanTurn`). Codex заканчивает ход
вместе с предметом `plan`, а панель (кнопки карточки, «ответ на план» из поля, телефон) считает план
вопросом внутри идущего хода и гасит кнопки на `result`. Поэтому `turn/completed` такого хода не
переводится, пока план не решён: одобрение, «Keep planning», Stop, новое сообщение мимо плана или смерть
процесса отпускают его (`releasePlanTurn`). Без этого план нельзя было одобрить вовсе (найдено живьём).

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
- `/rename имя` - имя треда в записи Codex (`thread/name/set`) и вкладки. За столом панель делает его
  сама (поле на вкладке), `CodexCommands` разбирает его для телефона.
- `/btw вопрос` и `/side вопрос` - вопрос сбоку (см. ниже); `/config` без аргумента - экран настроек
  Codex, `/config ключ=значение` - запись одной настройки.

### Вопрос сбоку (`/btw`, `/side`)

У терминала Codex это `/side` - «side conversation in an ephemeral fork», экран TUI, а не метод
`app-server`. Панель повторяет его рецепт (`SideQuestion`, `CodexSession.askAside`):
- эфемерный `thread/fork` треда вкладки в её же процессе (`ephemeral` + обязательный `excludeTurns`),
  read-only, `approvalPolicy: never`, через `config` выключены субагенты (`features.multi_agent`),
  встроенные приложения Codex и серверы плагинов (`features.apps`, `features.plugins`) и по имени - только
  серверы из таблицы `mcp_servers` конфига (спрашивается `config/read` перед форком). Имя без таблицы
  (`codex_apps`) Codex принимает за сервер без транспорта и не грузит конфиг вовсе - так `/btw` падал; инструкции разработчика и граница - дословно тексты `/side` из бинаря Codex;
- `thread/inject_items`: граница сообщением developer, потом прошлые вопросы и ответы нити парами;
  не принял - те же слова уходят перед вопросом текстом (`SideQuestion.inlined`);
- `turn/start` с вопросом и `summary: none`; события чужого треда уводятся в `asideNotification`;
- **новая копия на каждый вопрос**: нить держит клиент и забывает её молча, а телефон с релея не умеет
  сказать «забудь»; после ответа `thread/unsubscribe`;
- отмена и таймаут 660 с - `turn/interrupt`; остановка процесса - всем `failed/ended`; разговор без
  треда на диске - эфемерный `thread/start` без истории; форк посреди хода Codex делает (проверено
  живьём на 0.152), не принял - через `lastTurnId` последнего законченного хода.

### Контекст редактора

Открытый файл и выделение уходят **вторым text-элементом** `turn/start`/`turn/steer` после слов
человека, в формате `/ide` терминала Codex (`IdeContextPrompt`: «# Context from my IDE setup:», «##
Active file:», «## Active selection range:», «## Active selection of the file:») - модель обучена на
нём. В истории `CodexDialect.userContextOf` отделяет этот элемент, а `userPrompt` кладёт его блоком в
словах, которые читает `feed/editorContext.ts`. Превью треда в `thread/list` склеивает части сообщения
**без разделителя** (замерено) - строка истории режется по заголовку; поиск вырезает блок целиком.

### Настройки Codex и доверие проекту

- Экран `/config` (`CodexConfig`, `CodexConfigDesk`, панель - `CodexConfig.tsx`): чтение -
  `config/read {cwd, includeLayers}` (значения, `origins` - откуда значение, слои) +
  `configRequirements/read` (что разрешила организация) + `experimentalFeature/list` (стадии beta /
  experimental как `features.<имя>`); запись - `config/batchWrite` с **явным путём** к настоящему
  `~/.codex/config.toml` (в ящике аккаунта это символьная ссылка) и `expectedVersion`. Codex отвечает
  `ok` на значение, которое перебивает слой проекта (замерено), поэтому после записи конфиг читается
  снова, и перебитое значение помечается `overridden`. После записи живые разговоры всех проектов
  поднимаются заново между ходами (`restartAll`): многое Codex читает при старте процесса.
- **Доверие**: слой проекта (`.codex/config.toml`, хуки, exec policies) Codex грузит только у
  доверенного проекта (`[projects."<путь>"] trust_level`). Терминал Codex спрашивает это при первом
  запуске в папке, панель не спрашивала никогда. Теперь: блок «This project» наверху экрана настроек
  (кнопка Trust / Stop trusting, запись upsert'ом объекта `projects` - путь с точками ломал бы ключ) и
  строка в ленте раз на разговор, если у проекта есть свои настройки, а доверия нет
  (`CodexSessionHub.checkProjectSettings`, `accountOutranked` с `reason: untrusted`). Телефону
  `setProjectTrust` запрещён.
- Доверенный проект может требовать способ входа или рабочее пространство (`forced_login_method`,
  `forced_chatgpt_workspace_id`): Codex тогда считает вход аккаунта отсутствующим **в этой папке**.
  Строка в ленте (`reason: account`) и объяснение на экране входа (`heldBackBy` в сообщении `auth`)
  вместо бесполезного «войдите снова» (`CodexConfig.demands`).
- Каждым ходом панель шлёт `sandboxPolicy` с `network_access` / `writable_roots` / `exclude_*` из
  `[sandbox_workspace_write]` конфига, а `summary` - только если конфиг не задаёт
  `model_reasoning_summary` (`CodexConfigDesk.workspaceWrite`, кэш на 5 минут, греется на старте
  процесса). `CodexSettings` (режим новой вкладки) читает слой проекта только у доверенного и в
  порядке Codex: проект, человек, система.
- Подпись «Default» в меню модели - модель из `model` конфига, если она в каталоге, иначе `isDefault`
  Codex; «auto» усилия - `model_reasoning_effort` конфига, если модель его умеет (`CodexShapes.models`).

### Сценарии под Codex

- Голова, поднятая в другую роль над тем же тредом (подхват карточки и обратно), получает новую
  инструкцию **сообщением developer через `thread/inject_items`** (`CodexSession.roleChange`):
  `thread/resume` поле `developerInstructions` принимает и игнорирует (замерено: тред, начатый «по-
  французски», после resume с «по-немецки» отвечал по-французски; после inject - по-немецки).
- Ход, остановленный посреди реплики, Codex не завершает `item/completed`, и в файле треда её нет вовсе
  (замерено); `CodexStream` держит набранные дельты и при конце хода кладёт недописанную реплику в ленту
  как есть, иначе панель стирала то, что человек уже читал. После `exitedReviewMode` Codex 0.152 повторяет
  находки обычным `agentMessage` - повтор пропускается (`reviewSaid`, в живом ходе и в истории).
  `/compact` помечается ручным (`compactionAsked` -> `trigger: manual`).
- `CodexStream` выпускает `content_block_start` на каждый `agentMessage` (живая строка карточки не
  склеивает реплики, `LiveWords`) и `message_delta` с `end_turn` после ответа с `phase: final_answer` и
  на предмет `hookPrompt` (хук Stop вернул агента в работу) - голова видит все концовки хода
  (`TurnEndings`).

### История, форки, контекст, лимиты

- **История** (`CodexHistory`): список - `thread/list` с фильтром по папке проекта, листается по
  `nextCursor`, пока не наберётся 40 строк без разговоров прогонов сценариев (`ScenarioConversations`);
  страница - `thread/turns/list` с конца, по 10 ходов на запрос, курсор - id самого старого хода; ход
  проигрывается в диалект `CodexReplay.lines`. Бюджеты страницы: стол 30 ходов / 512K символов /
  12K вывода команды, телефон 10 ходов / **128 КБ в байтах UTF-8** (кадр релея считает байты) / 2K.
  Модель разговора читается из файла треда (`turn_context`), последняя занятость контекста - из
  `token_count`.
- **Вопросы с кнопками** в `thread/turns/list` не попадают вовсе (замерено). Они есть только в файле
  треда: `function_call` `request_user_input` и `function_call_output` с ответами, связанные `call_id`
  (`CodexHistory.asksOf`). `CodexReplay` ставит вопрос перед финальным ответом хода, ответ - строкой
  человека (`toolUseResult.answers`), а ход, оборванный на вопросе без ответа, не закрывает - панель
  поднимает такой вопрос живой карточкой.
- **Форк** получает имя родителя: Codex сам копирует его в запись нового треда, и вкладка берёт его с тем
  же происхождением (модельное остаётся модельным, остальное - человеческое, `adoptForkName`), иначе
  генератор названий перезаписывал имя, данное человеком.
- Вопрос из файла треда встаёт в истории туда, где был задан: после реплики или мысли агента, за которой
  шёл (`Ask.after`, по id из `response_item`), с временем ответа (`answeredAt`).
- **Имена**: у Codex одно поле на тред, туда пишут и модель (`CodexTitles`), и человек. Что дала
  модель, записано в `AutoTitles` (файл на проект); любое другое имя - человеческое (`TITLE_USER`) и
  выше модельного - в истории, поиске и вкладке. `thread/name/updated` с чужим именем - переименование
  в другом клиенте (`onRenamed`). Незагруженный тред переименовывается через общий процесс каталога.
- Файлы тредов - `~/.codex/sessions/ГГГГ/ММ/ДД/rollout-<время>-<uuid>.jsonl`, имена - в
  `~/.codex/session_index.jsonl`. По ним же работают поисковый индекс (`search/TranscriptText`,
  `SearchIndex`) и счётчик токенов за день (`CodexTokenUsage`).
- **Контекст** - из `thread/tokenUsage/updated`: занято `total - reasoning`, окно -
  `modelContextWindow`.
- **Лимиты** - одна картина на аккаунт для всей IDE (`AccountUsage`), из `account/rateLimits/read`
  (весь ответ, с `rateLimitsByLimitId`) и уведомлений `account/rateLimits/updated`. Корзины, кроме
  основной `codex`, с окном - отдельные кольца (`model_scoped`, подпись - `limitName` или id словом,
  «Premium»). Уведомление про другую корзину основные кольца не трогает - картина перечитывается.
  Судьи «чужих цифр» оригинала нет: у Codex нет общего кэша, а честные ответы двух ящиков одной
  подписки он выбрасывал. Codex сообщает
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
  `chatgpt_plan_type`; ключ API на экране - последние четыре символа, а в записи - отпечаток всего
  ключа, чтобы два ключа с одним хвостом не стали одним аккаунтом). Токен только читается локально.
  Личность читается из файла сразу (`probe`/`identityOf`), без процессов и окон свежести оригинала;
  ключи в системном keyring файла не оставляют - тогда адрес берётся из `account/read`, без рабочего
  пространства, и такой ящик никогда не заменяется по догадке (`Holds.Unknown`).
- Логика записей - оригинала (непрозрачные id с `key`, `renew`/`refile`, слияние дублей), но с
  условием Codex: `auth.json` называет аккаунт, даже если токен отозван. Поэтому вход-дубль обычного
  входа отклоняется, а дубли сливаются **только если оставляемый вход недавно ответил о лимитах**
  (`AccountUsage.provenSince`, `CodexAccounts.credentialWorks`) - иначе удалился бы единственный
  рабочий вход.
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

- **Экран «Settings sources» оригинала** (выбор слоёв настроек Claude Code) - у Codex флага слоёв нет
  и не нужно: слой проекта грузится только у доверенного проекта, а ключи провайдера и адреса
  (`model_provider`, `openai_base_url` и т.п.) Codex из проектного конфига выбрасывает сам (замерено).
  Вместо экрана - доверие проекту и строки в ленте (см. «Настройки Codex и доверие проекту»).
- **Ложное «exit code 137» при плановом рестарте** - в форке его не бывает: флаг остановки живёт в
  каждом `AppServer`.
- **`JournalStrands`** перенесён как есть, но у Codex всегда отвечает «не нить»: потока отчётов флота
  и `parent_tool_use_id` нет, события субагентов отсекаются по треду.
- Идентификаторы общего кода панели оставлены как в оригинале (`--acc-*`, `ClaudePreferences` в
  комментариях, коды вроде `noClaude` в очереди сценариев - он хранится в файлах на диске), чтобы
  следующие слияния с оригиналом шли чисто. Исключение - экран настроек: `CodexConfig.tsx`,
  сообщения `askCodexConfig`/`setCodexConfig`/`codexConfig`, экран меню `codexConfig`.

- **Вход в Claude Design** (`/design-login`, `DesignLogin`) - у Codex такого нет; удалено вместе с
  кнопкой на экране аккаунтов и сообщением протокола.
- **Остановка одной фоновой задачи** (`stopTask`) - у `app-server` нет такого запроса; отвечает
  отказом.
- **Число сообщений в строке истории** - `thread/list` его не отдаёт, строка показывает только то,
  что известно.
- **Страница в маркетплейсе** не заведена: это решение владельца.
- **Релей у форка свой, а не оригинала** - `wss://relay-codex.mzpizote.com` (`RemoteAgent.DEFAULT_RELAY`,
  приложение `acx-relay` в Coolify). Он раздаёт телефону клиента форка (манифест Amazing Codex, иконки
  ACX) и `/privacy` из `PRIVACY.md` форка. Сохранённый в настройках адрес релея оригинала считается
  старым умолчанием и не держит (`RemoteAgent.chooseRelay`); спаренные через оригинал телефоны
  спариваются заново. Dev-релея на сервере нет: локально - `./scripts/relay-dev.sh` (:4450), выкатка -
  `./scripts/relay-deploy.sh`, который до сборки проверяет, что цель - приложение форка, а в
  `relay/public/` - клиент форка. Подробности - `docs/panel-notes.md` («Свой релей форка») и
  `relay/README.md` (первичная настройка - «First deploy»).
- Релей развёрнут 2026-10-02: приложение Coolify `acx-relay` (uuid в `scripts/relay-deploy.sh`), ключи
  VAPID - в `secrets/relay-vapid.env` (в git не едет, значения в чат не выводить). Логи релея хранятся
  7 дней, как обещает `PRIVACY.md`: docker сервера режет их только по размеру, поэтому срок держит
  `container-log-retention.py` на сервере раз в час (`/etc/cron.d/relay-log-retention`). Заводишь
  форку ещё один релей - впиши его uuid туда же, иначе обещание политики для него станет неправдой.
- `PromptDeliveries` и часть `PromptDelivery` остались от оригинала: там проверка доставки читала
  транскрипт CLI. У `app-server` доставку подтверждает сам ответ на `turn/start` / `turn/steer`, и
  живой код их не зовёт.

## Как проверять

- Статистика из песочницы уходит на `http://localhost:8082` (общий сервис из репозитория оригинала,
  `pnpm dev:usage` там), а не в боевой; `-PusageUrl`/`-PusageKey` направляют её в другое место.
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
- **Живой прогон панели в песочнице агентом**: `./gradlew runIde -PopenProject=$PWD/sandbox-project
  -PjcefDebugPort=4452`, затем Playwright `chromium.connectOverCDP('http://127.0.0.1:4452')` и страница с
  адресом `acx-webview` (у README в редакторе своя страница JCEF - не перепутать). Кнопки - по ролям и
  именам из `ariaSnapshot()`. Модальное окно самой WebStorm (например, «Evaluation Feedback») молча держит
  действия IDE: файл из ленты не открывается, пока его не закрыть (`osascript`, кнопка по `description`).
  Телефон к такой песочнице спаривается через ссылку `.../p#...` с экрана Remote access и «Allow» в IDE.
- Скриншоты для стора: `scripts/screenshots.mjs` (харнесс на :5190, кадры из
  `webview/src/harness/scenarios/showcase.ts`); телефон снимается настоящим клиентом через релей.
- Протокол `app-server` своей версии Codex можно выгрузить схемой:
  `codex app-server generate-json-schema --out /tmp/codex-schema` - это самый надёжный ответ на
  «какие поля у этого уведомления».
