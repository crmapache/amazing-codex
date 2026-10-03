# Догоняем оригинал: ACC 0.12.14 - 0.13.16

Рабочий план переноса. Составлен 2026-10-02 разбором всех коммитов оригинала после точки отрыва.

## Исходные факты

- **Точка отрыва** - коммит оригинала `bc1bbb3` (0.12.13, 2026-09-19). Проверено по содержимому: из 396 файлов `webview/` форка 343 совпадают с ним байт в байт, с более поздними - меньше.
- После него в оригинале 30 коммитов, 0.12.14 - 0.13.16: ~39 тыс. строк вставок, из них Kotlin 12,5 тыс., webview 17 тыс., сервисы 3 тыс.
- Форк сейчас: `./gradlew test` зелёный, `pnpm tsc --noEmit` и `pnpm vitest run` зелёные (68 файлов, 1357 тестов).
- **Коммиты оригинала опираются друг на друга** (App.tsx, protocol.ts, хаб, StatusBar, RemoteAgent, i18n меняются почти в каждом). Переносить в хронологическом порядке оригинала - иначе каждый следующий патч конфликтует с пропущенным.
- **Телефонный клиент форку раздаёт релей оригинала** (`Pairing.offerUrl` -> `https://relay.mzpizote.com/p`, Dockerfile релея кладёт туда `dist-mobile` оригинала). Значит:
  - телефоны пользователей форка уже работают на клиенте 0.13.16, а IDE форка говорит протоколом 0.12.13;
  - правки форка в `webview/src/mobile/` (`History.tsx`, `RunSheet.tsx`) до телефонов не доходят;
  - сейчас из-за расхождения: `/btw` с телефона висит вечно (IDE молча отбрасывает `sideQuestion`), сообщение в очереди через 12 с становится «Not delivered» и может уйти второй раз (нет `promptReceived` и `ArrivedMessages`), фото идут в узком режиме с просьбой «обновить плагин» (нет `parts` в возможностях IDE).
- **Вход Codex на этой машине отозван** (`401 token_revoked` в `~/.codex`). Живые проверки (раздел в конце) возможны только после `codex login`.

## Ход работ

Снимок форка до переноса - дерево git `7dd7c8c01e40bf377ebde5344c6719ec1c80d101` (без коммита, только объект: `git diff 7dd7c8c0 -- путь`, `git show 7dd7c8c0:путь`).

Сделано (2026-10-02):
- слияние всего диапазона bc1bbb3..04bc17a трёхсторонним `git merge-file` с переименованием под форк (скрипт был в /tmp/acxm/merge.py): 84 новых файла, 106 взято из оригинала, 40 слито чисто, 30 конфликтов разрешено вручную;
- экран «Settings sources» убран целиком (панель, протокол, харнесс, движок), кнопка строки «перебил аккаунт» ведёт на экран настроек Codex;
- аккаунты: личность из `auth.json` (`AccountIdentity.probe`/`probeDrawer`, отпечаток API-ключа), `identityOf`/`probedIdentity`/`sameAccount` без процессов, вход-дубль отклоняется только если обычный вход доказал работу (`AccountUsage.provenSince`), слияние дублей - только если оставляемый вход рабочий (`AccountDesk.mergeTwin`), папки проб убраны;
- история: листание `thread/list` без разговоров прогонов, источник имени (`AutoTitles`), страница телефона в байтах (A1);
- поиск: имена модели и человека по `AutoTitles`, без разговоров прогонов;
- контекст редактора в формате Codex (`IdeContextPrompt`), вторым text-элементом, обратный перевод в историю, вырезается из поиска;
- `/btw` по рецепту `/side` (`SideQuestion`, `CodexSession.askAside`);
- переименование: `thread/name/set`, `ownTitle`/`onRenamed`, `/rename` в `CodexCommands`;
- лимиты: корзины `rateLimitsByLimitId` -> `model_scoped`, фильтр уведомлений по `limitId` (A2), кольцо «Spending limit» со своим индикатором;
- экран настроек Codex: `CodexConfig`, `CodexConfigDesk` (config/read, configRequirements/read, experimentalFeature/list, config/batchWrite с явным путём и версией, перечитывание после записи, доверие проекту upsert'ом);
- per-turn: `sandbox_workspace_write` и `model_reasoning_summary` из конфига (A9);
- `./gradlew test`: 1396 тестов, 1 ожидаемое падение (экраны меню в статистике).

Доделано позже в тот же день:
- экран настроек Codex в панели (`CodexConfig.tsx`, блок «This project» с доверием), строка в ленте с двумя причинами (`untrusted`, `account`), объяснение на экране входа (`heldBackBy`, баг A7), порядок слоёв и доверие в `CodexSettings` (A8), подпись Default из конфига (A11);
- вопросы с кнопками и ответы в истории из файла треда (A12), превью треда без контекста редактора;
- восстановление вкладок: тред запоминается только после первого хода (`CodexSession.hasHistory`);
- сценарии: смена роли головы через `thread/inject_items` (живьём: `thread/resume` инструкции не меняет), `content_block_start` и `message_delta` в переводчике;
- `/side` как псевдоним `/btw`, тексты панели под Codex во всех 10 языках, заставка ACX;
- судья «чужих цифр» лимитов удалён (у Codex нет общего кэша, он мешал слиянию дублей), эфемерные треды названий отпускаются;
- статистика: плагинная часть под `acx`, PRIVACY.md, песочница шлёт на localhost:8082; сервис в репозитории оригинала разделяет плагины (не закоммичено);
- свой релей: код, скрипты, документация (не развёрнут);
- бренд: plugin.xml, AccBundle, CHANGELOG 0.2.0, версия 0.2.0, заметки в CLAUDE.md.

Проверено: `./gradlew test` - 1421 тест, 0 падений; живой `CodexSessionLiveTest` на gpt-5.6-luna - прошёл; панель - `tsc` чисто, 1561 тест; сервис статистики - 81 тест; релей - 84 теста. Живьём на Codex 0.152: эфемерный форк посреди хода, `inject_items` в эфемерный тред и после resume, переименование незагруженного треда, формат `request_user_input` в файле треда, склейка превью, запись конфига и доверия.

Не проверено живьём: продолжение хода после Stop-хука (`hookPrompt`) - хуков на машине нет; `forced_chatgpt_workspace_id` (обрабатывается так же, как `forced_login_method`).

За владельцем:
1. Коммиты - в форке их нет вовсе; сервис статистики в репозитории оригинала тоже не закоммичен.
2. Деплой сервиса статистики (перед ним снимок базы; после - `/v1/info` должен показать `"products": ["acc","acx"]`).
3. Деплой релея по `relay/README.md`, раздел «First deploy»: DNS `relay-codex.mzpizote.com`, ключи VAPID, приложение в Coolify, `APP_UUID` в `scripts/relay-deploy.sh`.

## A. Баги форка, которые есть уже сейчас

1. **Страница истории для телефона меряется в символах.** `CodexHistory.PHONE_PAGE_CHARS = 150 * 1024` через `.length` - до ~300 КБ кириллицы при кадре релея 256 КБ, «load earlier» молчит. Взвешивать через `utf8Bytes`, бюджет ~128 КБ байт (как `MAX_PHONE_PAGE_BYTES` в a89f3b0).
2. **Уведомление лимитов по дополнительной корзине затирает основные кольца.** Обработчик `account/rateLimits/updated` в `CodexSession` не смотрит на `limitId`: корзина `premium` с окнами перепишет 5ч и неделю, а её `rateLimitReachedType` покажется как основной лимит. `null`/`codex` - как сейчас, иначе основные кольца не трогать и перечитать `account/rateLimits/read`.
3. **Ложная строка «выбрана X, отвечает Y» на вкладке Default.** `modelFamily('default') = ''`. Фикс - aba6de4 (на коде форка 4 из 5 его тестов падают).
4. **Ящик аккаунта можно подменить и потерять.** `CodexAccounts.completeSignIn` находит запись по `idOf(email, org)` и `discard`-ит её ящик, не спросив, кто внутри. Кнопка входа на заглушке запускает `codex login` в ящик текущего аккаунта: вошёл другой аккаунт B - запись A держит B; следующий «Add account» как A удалит ящик с B. Фикс - блок аккаунтов (раздел C).
5. **Экран аккаунтов не верит свежему ответу** - тот же баг, что 875354a чинит в оригинале (`defaultWho = hub.auth.lastStatus`, `logout` обнуляет только у себя).
6. **Дубль аккаунта не ловится.** Вход в «Add account» тем же ChatGPT-аккаунтом, что в `~/.codex/auth.json`, проходит. А `UsageProbes.trust` с `borrowable` выбрасывает честные цифры двух ящиков одной подписки (общего кэша у Codex нет - защиту убрать).
7. **Репозиторий молча «разлогинивает» аккаунт.** Доверенный `.codex/config.toml` с `forced_login_method = "chatgpt"` даёт `account/read -> account: null` в этой папке (замерено), хотя ключ в ящике есть: экран входа, «No stored credential», повторный вход не помогает, объяснения нет. Так же, вероятно, `forced_chatgpt_workspace_id`.
8. **Конфиг проекта читается без проверки доверия и в неверном порядке.** `CodexSettings.sources` и `PermissionDefaultMode.of` читают `.codex/config.toml` проекта всегда и ставят `/etc/codex/config.toml` выше всех. У Codex слой проекта грузится только у доверенного, порядок project > user > system. Читать через `config/read` (через `CodexCatalog`).
9. **Панель каждым ходом перебивает часть config.toml.** `PermissionModes.sandboxPolicy` жёстко шлёт `networkAccess=false` и пустой `writableRoots`, `turn/start` - `summary: "auto"`. `sandbox_workspace_write.network_access`, `writable_roots`, `model_reasoning_summary` из конфига в панели не работают.
10. **Лимиты: каждый проект сам по себе.** Push `account/rateLimits/updated` идёт в `usage.noteLive` и рассылается только своему проекту; остальные проекты проигрывают общий `claim` и стоят. Фикс - c085ec2 под Codex.
11. **Подпись «Default» может врать.** Codex на пустой модели и `auto` усилии берёт `model`/`model_reasoning_effort` из config.toml (здесь `gpt-5.6-sol` и `max`), а панель подписывает по `isDefault` из `model/list` и «auto». Из ответа `thread/start`/`thread/resume` читается только `model`, не `reasoningEffort`.
12. **Вопрос с кнопками и ответ на него пропадают из истории.** `CodexReplay.lines` не рисует `request_user_input` вовсе (это запрос сервера, не `ThreadItem`), и дописывает `result` в конец даже хода, оборванного на вопросе.
13. **Хвосты бренда.** `plugin.xml` (описания двух действий редактора: «Claude panel»), `AccBundle.properties` (`webview.unsupported.text`: «Claude panel»; ключи `claude.notFound.*` - проверить, используются ли, и переписать или убрать). Код `"noClaude"` в `ScenarioDesk` - внутренний, но стоит переименовать вместе с панелью.

## B. Переносится почти как есть (общий код)

Пометка: файлы форка тут совпадают с 0.12.13 (с поправкой на `amazingcodex`, `acx.*`, `Codex*`), правки только из-за порядка и i18n.

| Коммит | Что | Заметки |
|---|---|---|
| 0afb41a | Индикаторы выключаются по одному | Ключ `acx.indicators.hidden`. У форка своё кольцо «Spending limit» - отдельный переключатель `spending`. `modelWeek` - только если делаем лимит модели (C). Тексты подсказок без Fable. `StatusBar.tsx` сливать один раз за индикаторы, лимит модели и сжатие строки. |
| 0afb41a | Строка под полем сжимается ступенями на узкой панели | `composerFit.ts`, хуки `use*Fit` дословно. |
| 0afb41a | Сценарии перетаскиваются, `.order.json` | `ScenarioStore(claudeHome)` -> `codexHome` через `CodexHome.of`; `@dnd-kit/*` в `package.json` + lock; тесты стора на временном `codexHome`; не потерять фильтр файлов с точкой. |
| 0afb41a | Курсор «рука» на macOS | `WebviewHost`. |
| 0c653eb | Стоп очереди снимается, когда упавший прогон подхватили | `QueueRules`, `ScenarioDesk`. |
| aba6de4 | Модель вкладки не считается выбором | Тест про алиас `opus[1m]` переписать на id Codex или убрать. |
| 7c21d25 | Обрезанный отчёт фонового агента не в ленте | К Codex не относится (история не режется), но дёшево держать общий код одинаковым. |
| b18a156 | Светлая тема, свой размер текста, тема телефона | `acx.theme`/`acx.textSize`. CSS-переменные `--acc-*` не переименовывать. |
| 3781ea1 | Телефон: сценарии с карточки проекта, прогон сверху вниз, таблицы прокручиваются, выравнивание колонок | Слилось чисто. |
| a89f3b0 | Телефон: лента с середины, подгрузка назад, переспрос через 8 с, дозвон, `RemoteAgent.fitted` | Каркас `SessionJournal` (`Tail.truncated`, `goneThrough`) брать - на него опираются следующие коммиты. `JournalStrands.of` - см. раздел E. Байтовая страница - баг A1. |
| 78ab438 | Заставка и картинка пустой панели | Имя «Amazing Codex GUI». Штрихи монограммы - ACX (F4). |
| 2192d65 | Метка PR и бургер не съезжают | |
| cc8d51c | Метка прогона сжимается, ссылка названа тем, на что ведёт | `scenario/AnswerLabel.kt`. |
| e8d5553 | Сообщение из очереди можно править; «+» отдаёт клавиатуру полю | |
| b00e387, e8b8bd5, e93f908 | Вкладка, позвавшая звуком, светится; открытая тоже; клик гасит | `calledAway` из `CodexPanel.playAlert`. Поводы звука Codex уже даёт через диалект. |
| e8b8bd5 | Телефон после перезагрузки снова получает факты (идущие прогоны); длинные слова переносятся | `DeviceSessions`, `RemoteAgent.forgetFacts`. |
| 44cbdbb | Отступы карточек на экране задач телефона | |
| 01e7239 | Главный поток сценария доделывает карточку | Движок, `TakeOver.kt`, редактор, телефон - как есть. `TAKE_OVER_BRIEFING`: «Amazing Codex panel». `ScenarioAuthor` форка переписан - поле `onGiveUp` вносить в промпт руками. Живая проверка: `thread/resume` подменяет `developerInstructions`. |
| 294f3c8 | Сообщение с телефона держится до подтверждения IDE, без дублей | `ArrivedMessages.kt`, `SessionCommands.takeOnce` (без `withEditor`, если e8d5553 ещё не перенесён), `arrived` в хабе, `DiagnosticsLog.PHONE`, абзац в PRIVACY.md. **Приоритет - совместимость с живыми телефонами.** |
| c6bab43 | Фото с телефона частями в полном размере | `RemoteParts.kt`, маршрут `part`, `CAP_PARTS`. Картинки в Codex уже уходят data URL - адаптации не нужно. После 294f3c8. |

## C. Переносится, но движок переделать под Codex

### Восстановление вкладок после перезапуска (b18a156)
- `TabMemory`, `DraftImages`, `draftMemory.ts`, `RestoreTabs.tsx`, хаб (`restoreTabs`, `showTab`, `asleepUntilSeen`, `wakeRestored`...) - механически. Восстановленная непоказанная вкладка = `CodexSession` без сервера, то же, что после `IdleSleep`; `showTab -> wake -> thread/resume` - путь уже есть.
- Папка `amazing-codex/tabs`, не `amazing-claude-code/tabs` (иначе два плагина в одной IDE затирают вкладки друг друга).
- Id треда у Codex есть с `thread/start`, а rollout - только после первого хода: запоминать `conversationId` только у треда, который уже что-то сказал; проверять наличие одним `thread/list` на всё восстановление, а не обходом `~/.codex/sessions` на вкладку.
- Бонус: шкалу контекста восстановленной вкладки брать из rollout (`CodexHistory.lastTokenUsage`) без подъёма процесса.

### Переименование вкладки и /rename (1dc6b72)
- Панель (`TabNameField`, ранги `TITLE_USER`, `resumedTitle`, `SessionRegistry`, `SessionSnapshot`, `SessionTitle`) - как есть; `rename` в свой `panelCommands`.
- Запись - родное имя треда Codex `thread/name/set {threadId, name}` (sqlite + `session_index.jsonl`; это же видит `codex resume`). Живой процесс - свой сервер; процесса нет - попробовать через `CodexCatalog` (возможна ошибка «thread not loaded» - тогда имя долгом до подъёма, `thread/resume` в каталоге не делать из-за writer lock); треда ещё нет - после `thread/start` и повторить после первого хода.
- У Codex одно поле имени, автоназвание `CodexTitles` пишет туда же. Чтобы отличать имя человека: свой журнал автоназваний (id треда -> автоимя); имя, равное записи журнала, - модельное, иначе человеческое.
- `titleWanted` - «ранг ниже LLM»; в колбэке `requestTitle` перепроверять `titleWanted()` перед `thread/name/set`; `thread/name/updated` с чужим именем считать `TITLE_USER`.
- `/rename` с телефона - в `CodexCommands.parse` -> `hub.nameSession`. `titleSource` в манифест поиска, поднять `FORMAT`.
- Проверить, что Codex не переписывает `session_index.jsonl` целиком (в ящиках это symlink).

### Экран настроек Codex по /config (1dc6b72)
- Компонент панели взять с адаптацией (CodexConfig, свои группы, типы bool/int/list); IDE-сторону написать заново.
- Чтение: `config/read {cwd, includeLayers: true}` - итог, `origins[key].name.type` (user/project/system/mdm/...), `layers[].disabledReason`. Допустимое: перечисления схемы + `configRequirements/read` (запрещённое организацией - недоступно с пометкой). Экспериментальное: `experimentalFeature/list`, запись `features.<name>`.
- Запись: `config/batchWrite {edits, filePath, expectedVersion, reloadUserConfig: true}`; `okOverridden` -> «записано, но перебивает X». **`filePath` явно на настоящий `~/.codex/config.toml`** - в ящике аккаунта это symlink, атомарная запись может заменить ссылку файлом.
- `/config ключ=значение` - в `CodexCommands`, тем же batchWrite.
- Что показывать: model, model_reasoning_effort (умолчание для Default/auto), approval_policy, sandbox_mode (от них режим новой вкладки), sandbox_workspace_write.network_access, web_search, model_reasoning_summary, model_verbosity, service_tier (из `serviceTiers` модели), personality, approvals_reviewer, model_auto_compact_token_limit, review_model, экспериментальные. Не показывать: developer_instructions, mcp_servers, projects.*, провайдеры, forced_login_*.
- **Сначала** убрать жёсткие оверрайды (баг A9), иначе экран врёт.
- Живые разговоры подхватят изменение при следующем подъёме процесса - или разослать перезагрузку (решить).

### Модель и усилие новой вкладки одной логикой (1dc6b72)
- `StartingChoice`, `announceNewTabDefaults`, `sendNewTabDefaults` - с адаптацией; сброс усилия при смене модели перенести из `pickModel` панели в IDE.
- Четвёртый источник у Codex - config.toml (с профилем и проектом): разрешать пустой конец через `config/read`, подписывать «Default - <модель> из config.toml», запускать по-прежнему без модели. Читать `reasoningEffort` из ответа `thread/start`/`thread/resume`. Уровни моделей хранить в `CodexAccounts.catalogues`, прижимать усилие в IDE. Рассылать при изменении config.toml.

### Лимиты одной картиной на IDE (c085ec2)
- `AccountUsage` как есть + `Extra.resets` форка; `noteLive` -> `AccountUsage.fold` (один работающий разговор обновляет кольца во всех проектах); фильтр по `limitId` (баг A2). Подтверждать чьи цифры - по `accountId` ответа. После 875354a.

### Недельный лимит модели (0afb41a)
- У Codex это не «неделя модели», а дополнительные корзины `rateLimitsByLimitId` (`limitId`, `limitName`, `primary`, `secondary`). Здесь видны только `codex` и `premium`, без имён и окон (business-место) - кольцо не нарисуется, это правильно.
- `CodexShapes.usage` строит `model_scoped` из каждой корзины кроме `codex` с окном (`display_name` = `limitName` или `limitId`); передавать весь ответ в `CodexSession.requestUsage` и `CodexOneShot.control("get_usage")`. Как подписывать `premium` - вопрос владельцу. Ветка `burning === 'model'` у Codex не сработает.

### Разговоры прогонов сценариев не в истории и поиске (0c653eb)
- `ScenarioConversations.kt` (книга id), `RunStore.conversations`, правки движка и `releasedRole` - как есть.
- `CodexHistory.list` листать `thread/list` по `nextCursor`, пока не набрано 40 видимых (с потолком страниц); фильтр после одной страницы даст «пару строк после ночи прогонов».
- `SearchIndex.transcriptsOf` сравнивать по `CodexHistory.threadIdOf(file)` (имя файла `rollout-<время>-<uuid>.jsonl`).
- `threadSource`, разделы, `thread/archive` рассмотрены - хуже книги. В терминальном `codex resume` нити прогонов останутся видны.

### Открытый файл и выделение едут с сообщением (e8d5553)
- `EditorContext.kt`, хаб, панель, `EditorChip`, `ShareEditor`, настройка `acx.shareEditor` - как есть.
- У Codex есть родной формат контекста IDE (его `/ide` в терминале): «# Context from my IDE setup:», «## Active file:», «## Active selection of the file:», «## Open tabs:», «## My request for Codex:». Слать в нём - модель на нём обучена.
- Доставка: второй элемент `{type: "text"}` в `input` у `turn/start` и `turn/steer`, после текста человека (`CodexSession.inputOf`; протянуть через `sendPrompt`, `deliver`, `startTurn`, `steer`, запасной список `afterTurn`).
- Обратный перевод для истории: `CodexDialect.userTextOf` / `userPrompt` превращают этот элемент в блок `<system-reminder>` со словами, которые читает `editorOfBlocks` - панель не меняется. Добавить заголовок в `TranscriptText.CONTEXT_BLOCK`.
- i18n: «the way Claude Code does it» -> Codex. Проверить живьём превью в `thread/list` при двух text-элементах и `turn/steer`.

### Голова сценария читает все концовки хода (40a7f19) и живая строка абзацами (1252c87)
- `TurnEndings`, `HeadTalk`, `LiveWords`, `Glance.tsx`, движок - как есть. Но в форке без правки переводчика они ничего не дадут:
- у Codex тоже есть Stop-хук, возвращающий агента в работу (`stop_hook_active`, `decision: block`, предмет хода `hookPrompt`). В `CodexStream`: на `item/started hookPrompt` выпускать `stream_event` с `message_delta`, `stop_reason: end_turn` (опционально и после `agentMessage` с `phase: "final_answer"`);
- на `item/started agentMessage` выпускать `stream_event` `content_block_start` типа text - иначе реплики живой строки склеиваются.
- Живой тест со Stop-хуком: продолжение после block идёт тем же ходом?

### Вопрос с кнопками и ответ из истории (5a5f7e1)
- Панель (`revivedAsk`, `addReplayedAnswers`, `AskPanel`, `steering: running`) - как есть.
- `CodexReplay`: восстанавливать вызов `request_user_input` (ожидаемо `response_item` `function_call` + `function_call_output` в rollout) как tool_use `AskUserQuestion` и tool_result с `toolUseResult.answers`; ход, оборванный на вопросе, не закрывать `result`. Сначала живой замер формы записей.
- Отказ API из-за temperature: в `CodexStream.turnCompleted` передавать `CodexErrors.httpStatus`, добавить `badRequest -> 400`. Низкий приоритет; кнопку вести не на «Settings sources» (шлюз у Codex только в пользовательском конфиге).

### Аккаунты одним блоком (875354a + fc1088f-C + b00e387 + 58fadd2)
- `LatestAnswer.kt` как есть; `ProjectAuth`/`AccountDesk` с `CodexAuth`. Ответ «залогинен ли» у Codex зависит от папки проекта (баг A7) - каждый проект спрашивает сам, чужой ответ не раздавать.
- Дубль: сравнивать `AccountIdentity.current()` и `ofDrawer(storeDir)` прямо из credential (проба процессом, окно свежести, `Probed` не нужны). Сливать, только если обоим ящикам недавно удался `account/rateLimits/read` - иначе можно удалить единственный рабочий credential. Слияние не рвёт идущий ход (b00e387: `sameAccount`, ход доживает).
- Не заменять ящик по догадке: `Holds` чтением `auth.json` старого ящика (тот же/пусто -> `renew`, другой -> `refile`, не прочитать -> не трогать); непрозрачные id с `key`; `refileMislabelled`; убрать мёртвые `before`/`Unsettled`/`insist`. Повторный вход в тот же аккаунт сохраняет имя, модель и усилие.
- Дыры личности у Codex: keyring-режим (нет `auth.json`, пустая org -> личное место и workspace с одной почтой сливаются - считать `Unknown`); API-ключ опознаётся по 4 последним символам - нужен хэш всего ключа. Удалить неиспользуемые папки проб `usageProbeDirectory`.
- Не нужно: опрос имени через `initialize` с одноразовой папкой, `expire`, TTL.
- Строка «репозиторий перебил аккаунт» (fc1088f-B) в Codex-версии: панельная часть (`OutrankedItem`, `OutrankedRow`, `accountOutranked`) как есть; источник - `config/read` по слою проекта (`forced_login_method`, `forced_chatgpt_workspace_id`) против типа credential ящика; в `ProjectAuth` объяснять вместо экрана входа (баг A7). Проверить, учитывает ли Codex `OPENAI_BASE_URL` из окружения IDE.

## D. У Codex своё - делаем по-кодексовски

### /btw (ef91932) -> родной `/side` Codex
- В терминальном Codex 0.152 есть `/side` (в списке команд рядом стоит и `btw`): «start a side conversation in an ephemeral fork». Это фича TUI (`tui/src/app/side.rs`), не метод app-server, но собрана из тех же кирпичей.
- Как устроено у Codex: эфемерный форк треда; граница «Side conversation boundary...» (история до неё - только справка, не продолжать ничего оттуда, субагенты запрещены, не менять файлы, git, права, конфиг без явной просьбы) и инструкция «You are in a side conversation, not the main thread...»; инструменты доступны по текущим правам; один боковой разговор за раз; нельзя до первого сообщения в треде; нельзя переименовать; правка прошлых сообщений недоступна; закрывается Ctrl+C, пока открыт - видно состояние основного треда («parent needs approval», «parent finished»...).
- Как у оригинала: `control_request side_question` в живой процесс CLI, ответ без инструментов, нить уточнений держит панель.
- Рецепт терминала Codex по строкам бинаря: эфемерный `thread/fork` -> `thread/inject_items` с текстом границы («thread/inject_items failed during TUI side conversation setup») -> обычные `turn/start` в боковой тред -> закрытие `thread/unsubscribe`. Через app-server отдельного метода нет. Терминал показывает статус основного треда, пока открыт боковой - значит, форк посреди идущего хода Codex делает сам.
- **Решение для форка:** панель и телефон берём как есть (карточка над полем; протокол `sideQuestion`/`sideAnswer`/`sideProgress` - общий язык панели, живые телефоны уже на нём), а движок - по рецепту Codex, но **новый форк на каждый вопрос**: нить держит клиент (присылает прошлые обмены в `history`), а забывает её молча (на /clear, при смене разговора), и телефон с релея сигнала «забудь нить» не пошлёт - постоянный форк на вкладку IDE не смогла бы вовремя выбросить.
  1. `thread/fork` треда вкладки в её же процессе (как `CodexTitles`): `ephemeral: true`, `excludeTurns: true` (обязателен для эфемерного форка), `approvalPolicy: "never"`, `sandbox: "read-only"`, `config` с выключенными MCP (`mcp_servers.<имя>.enabled = false`) и субагентами (`features.multi_agent`).
  2. `thread/inject_items`: текст границы дословно как у Codex `/side` + прошлые обмены из `history` парами «вопрос / ответ».
  3. `turn/start` с вопросом и `summary: "none"`.
  4. События -> `sideProgress` / `sideAnswer`: `turn/started` -> started, `error willRetry` -> api_retry, `agentMessage` -> текст, `model/rerouted` -> notice, `turn/completed` -> answered/empty/failed/cancelled; в конце всегда `thread/unsubscribe` (у `CodexTitles` та же утечка - закрыть заодно).
  5. Отмена - `turn/interrupt` бокового треда; таймаут 660 с - interrupt и `failed/timeout`; остановка, падение, рестарт (MCP, модель, аккаунт), сон вкладки -> всем висящим `failed/ended`; спящую вкладку будить через `awake`.
  6. Вкладка без сообщений: эфемерный `thread/start` без истории (у Codex `/side` там недоступен, у оригинала отвечает «из пустого разговора»).
  7. Диспетчер чужих тредов в `CodexSession.notification`/`serverRequest`: заголовок или вопрос сбоку.
- Инструменты: как у родного `/side` - чтение файлов разрешено (запись закрыта read-only и `never`), MCP и субагенты выключены (MCP песочница не держит, подтвердить в карточке негде). Тексты i18n «without tools» поправить. Принимать и `/btw`, и `/side`.
- Из `SideQuestion.kt` оригинала брать `Exchange`, `Answer`, `Reason`, `historyOf`, `answerJson`, `progressJson` и лимиты; разбор форм CLI выбросить; `SideQuestionTest` переписать на уведомления Codex.
- Цена: у форка новый id, кэш промпта основного разговора, вероятно, не переиспользуется - вопрос в длинном разговоре дорогой.
- Живьём проверить: форк посреди идущего хода (если откажет - `lastTurnId` последнего завершённого хода), выключение MCP и инструментов через `config` на форке, `inject_items` на эфемерном треде.

### Источники настроек (fc1088f-A) -> не переносим, у Codex иначе
- Флага слоёв у Codex нет и не нужно: слой проекта грузится только у доверенного проекта, а `model_provider`, `model_providers.*`, `openai_base_url`, `chatgpt_base_url`, `profile` из проектного конфига Codex выбрасывает сам (замерено). Своя беда Codex - `forced_login_method` (баг A7, строка «перебил аккаунт» в C).
- По желанию: статус доверия проекта и что задаёт его слой (`config/read`, только имена). Переключатель доверия пишет `projects."<path>".trust_level` в общий конфиг и действует в терминале - решение владельца. У самого Codex для этого есть `/debug-config`.

## E. Не переносим

- Ложное «exit code 137» при плановом рестарте (fc1088f-D) - в форке закрыто устройством `AppServer` (`stopRequested` на экземпляр).
- `JournalStrands.of` (a89f3b0) - распознаёт `parent_tool_use_id` и `workflow_progress` Claude; у Codex потока отчётов флота нет, события субагентов отсекаются по треду. Вызов оставить со `strand = null` или заглушкой.
- Из 58fadd2 - опрос имени аккаунта через `initialize`, `expire`, TTL, `dropProbeFolder`: корня бага в форке нет.
- Нарезка CLAUDE.md оригинала на `.claude/rules` (0afb41a) - смысл новых заметок дописать в CLAUDE.md форка и `docs/panel-notes.md`.

## F. Решения владельца

Решено 2026-10-02:

1. **Свой релей для форка.** Код уже в `relay/` форка. Отдельное приложение в Coolify со своим доменом, раздаёт телефонный клиент форка (`dist-mobile` форка, свой манифест и иконки, страница `/privacy` форка), свои ключи web push. В плагине - `RemoteAgent.DEFAULT_RELAY`, снять заглушку в `scripts/relay-dev.sh`. Уже спаренные телефоны после переезда спариваются заново (новый QR). После этого телефонные правки форка доходят до телефонов, и IDE форка больше не обязана держать протокол в ногу с клиентом оригинала.
2. **Статистика - да, в общем сервисе `usage.mzpizote.com`, данные разделены по плагинам.** Отдельного сервиса не заводим.
   - **Сервис живёт в репозитории оригинала** (`../amazing-claude-code/usage-service`) - правка там, это исключение из правила «оригинал не трогать», только для `usage-service`.
   - В отчёт - поле продукта (`acc` / `acx`, по белому списку; отчёт без поля = `acc`, так присылают уже вышедшие версии ACC). Поле `plugin` в базе - это версия, не продукт: нужна новая колонка в `installs` и `days` (миграция как у `DAY_FIELDS`).
   - Удаление по id машины - только в своём продукте (выключение в Codex не трогает данные ACC).
   - Белые списки фич, команд, моделей - свои у каждого продукта (`features.ts`, `features.test.ts` сверяется с `UsageFeatures.kt` своего плагина).
   - Админка: переключатель «Claude Code / Codex» (вкладки), каждая со своими графиками и подписями; подписи сервиса («Claude account», «Claude Code plugin») - по продукту.
   - В плагине форка: адрес тот же, свойство `acx.usage.url`, под Codex - семейства моделей (id из `model/list`, остальное «Other»), `CodexCommands.BUILT_IN` (+ `/prompts:*` и скиллы как custom), версия CLI без префикса `codex-cli`, фичи без design_login/claude_config/setting_sources/stop_task и без включения-выключения плагинов, настройки форка (`acx.*`, режимы форка), тексты.
   - PRIVACY.md форка переписать (сейчас обещает «no analytics, no telemetry»): опрос один раз, список полей; ссылка на политику - страница своего релея.
   - Чей ключ: тот же `USAGE_KEY` (не аутентификация) - продукт определяется полем, а не ключом.
3. **«Leave a tip» на Ko-fi - переносим с той же ссылкой** (ko-fi.com/mzpizote), вместе с `.github/FUNDING.yml`. Слить с правками форка в `Thanks.tsx` (там свои ссылки на репозиторий и маркетплейс).
4. **Свой знак - сделано 2026-10-02.** Монограмма ACX в стиле знака ACC (та же сетка 20x20, толщины, лучи), «X» вместо второй «C» и стоит в стороне от её конца, плашка индиго (`#7C83FF` -> `#5A55E6`, рамка `#E2E3FF`, знак `#F5F6FF` -> `#CCD3FF`). Заменены: `pluginIcon.svg` (градиенты `acxBg`/`acxMark`), `icons/toolWindow.svg` и `toolWindow_dark.svg`, логотип в `stats/poster.ts`, `assets/logo.png` и `logo-512.png`, иконки телефона в `webview/mobile-assets/` (перегенерированы). `scripts/mobile-icons.py` больше не ищет знак по цвету: чернила меряются по прозрачности на отрисовке без поля, имя градиента поля - константа `FIELD`. Свой знак - ещё и требование лицензии оригинала (имя и логотип форку не передаются). При переносе 78ab438: в `Splash.tsx` штрихи `LETTERS` - галка и перекладина A, дуга C, два штриха X (`M13.3 11.6 18.4 17.0`, `M18.4 11.6 13.3 17.0`); комментарии про «coral plate» в `Welcome.tsx` поправить.
5. **Корзина лимитов без имени:** подпись - `limitName`, если есть; иначе id с заглавной буквы («Premium»); рисовать только корзину с окном (на business-месте окон нет - ничего не рисуется).
6. **Доверие проекту:** не переключатель в настройках, а как делает сам Codex в терминале при первом запуске в папке. Если у проекта есть свои настройки Codex (`.codex/` с конфигом, хуками, MCP) и он не доверен - один раз строка в ленте «Codex не читает настройки этого проекта, пока ему не доверяют» с кнопками «Доверять» / «Не сейчас»; статус и отзыв доверия - на экране /config. Запись - тот же `projects."<path>".trust_level`, что пишет терминал Codex (у владельца там уже 11 проектов).
7. **Полка сценариев песочницы - вне git**, как в оригинале (07a7e46). **Сделано 2026-10-02:** путь в `.gitignore`, `smoke.json` удалён, CLAUDE.md форка и `docs/panel-notes.md` поправлены.

## Порядок работ

1. Хронологически по оригиналу (меньше всего конфликтов), с Codex-работой внутри каждого шага: 0afb41a -> 0c653eb -> aba6de4 -> fc1088f (без экрана источников; аккаунты - в блок) -> 5a5f7e1 -> 7c21d25 -> b18a156 -> 875354a -> 3781ea1 -> 1dc6b72 -> c085ec2 -> a89f3b0 -> 78ab438 -> 2192d65 -> cc8d51c -> e8d5553 -> b00e387 -> e8b8bd5 -> 01e7239 -> 58fadd2 (блок аккаунтов) -> e93f908 -> 44cbdbb -> 40a7f19 -> 1252c87 -> 294f3c8 -> ef91932 (`/side`) -> c6bab43.
2. Если нужен быстрый промежуточный выпуск ради живых телефонов - сначала 294f3c8, c6bab43 и `/btw` (без `withEditor` и `UsageFeatures`), плюс баги A1-A3.
3. Баги A4-A13 закрываются по ходу соответствующих шагов (указаны в C).
4. CHANGELOG форка - своими словами под Codex, без «as in the terminal and VS Code».

## Живые проверки (нужен `codex login`)

- `thread/fork ephemeral` посреди идущего хода; оверрайды MCP и инструментов на форке.
- `thread/resume` подменяет `developerInstructions` существующего треда (подхват карточки главным потоком).
- `thread/name/set` на незагруженном треде и на треде до первого хода.
- Stop-хук с `block`: продолжение тем же ходом, `hookPrompt` в основном треде.
- Форма записи `request_user_input` и ответа в rollout; `turn/start` после `thread/resume` над висящим вопросом.
- Превью `thread/list` и `turn/steer` при двух text-элементах во входе.
- Учитывает ли `isDefault` в `model/list` модель из config.toml; учитывает ли Codex `OPENAI_BASE_URL` из окружения.
- `forced_chatgpt_workspace_id` в проектном конфиге ведёт себя как `forced_login_method`.
