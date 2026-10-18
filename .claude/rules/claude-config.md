---
paths:
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexConfig.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexConfigDesk.kt"
  - "src/test/kotlin/io/github/crmapache/amazingcodex/codex/CodexConfigTest.kt"
  - "webview/src/components/CodexConfig.tsx"
---

## Настройки Codex: экран, который открывает `/config`

Пришло в оригинал отзывом: в терминале `/config` открывает экран, где настройки меняются на месте, а панель
выдавала простыню справки. В форке экран свой с самого начала: оригинал читал справку CLI и его файлы, а
`codex app-server` отвечает на вопросы о конфиге сам. Сводка - CLAUDE.md, «Настройки Codex и доверие проекту».

`/config` без аргументов - команда панели (`panelCommands` в `catalog.ts`, правило в `localCommand`): открывает
экран «Codex settings» в настройках бокового меню (экран `codexConfig`, `CodexConfig.tsx`). С `ключ=значение` его
разбирает IDE (`CodexCommands`) и пишет одну настройку той же дорогой, что экран.

### Что чьё

- **Список, значения и откуда каждое - от Codex** (`CodexConfigDesk.send` через общий процесс каталога,
  `CodexCatalog`): `config/read {cwd, includeLayers}` даёт настройки в силе для папки проекта, `origins` (файл
  человека, `.codex/config.toml` проекта, политика организации) и слои, у слоя проекта - почему он не загружен;
  `configRequirements/read` - что разрешила организация (варианты сужаются по спискам `allowed*`);
  `experimentalFeature/list` - переключатели стадий `beta` и `experimental` строками `features.<имя>`. TOML руками
  панель не читает: так и показывают значение, которое проект перебивает, как будто оно в силе.
- **Что на экране, решает `CodexConfig.SPECS`** (чистые функции, `CodexConfigTest`): сначала то, что меняет работу
  (`model_reasoning_summary`, `web_search`, `sandbox_workspace_write.network_access`, `service_tier` и т.д.), потом
  то, что решает только терминал и вкладка на «Default» (`model`, `model_reasoning_effort`, `approval_policy`,
  `sandbox_mode` - в панели их решают чипы MODEL и MODE), потом экспериментальные. Нарочно нет инструкций
  разработчика (у панели свой брифинг), MCP-серверов (свой экран), проектов и доверия (свой блок), провайдеров и
  принудительного входа (дело аккаунта).
- **Запись - `config/batchWrite` с явным путём к настоящему `~/.codex/config.toml` и `expectedVersion`**
  (`CodexConfigDesk.change`). В ящике добавленного аккаунта этот файл - символьная ссылка, и запись, заменившая
  ссылку файлом, отделила бы настройки аккаунта от всех остальных. Пишется только ключ из списка
  (`CodexConfig.writable`) и только значение, которое ему подходит (`wireValue`: переключатель - булево, число -
  число, пустое - удалить ключ и вернуться к умолчанию Codex).
- **Ответу «ok» не верят** (замерено на 0.152): Codex отвечает `ok` и на значение, которое слой проекта перебивает.
  Поэтому после записи конфиг читается снова, и перебитое значение помечается `overridden`.
- **Перебитое не притворяется изменяемым** (`CodexConfig.lockedBy`): если настройку держит проект или политика, ряд
  неактивен и говорит, кто держит.
- **Умолчание не угадывается**: значения нет в ответе - ряд не отмечает ни одного варианта.

### Доверие проекту

- Слой проекта (`.codex/config.toml`, хуки, exec policies) Codex грузит только у доверенного проекта
  (`[projects."<путь>"] trust_level`). Терминал Codex спрашивает это при первом запуске в папке, панель не
  спрашивала никогда. Теперь блок «This project» наверху экрана (`CodexConfigDesk.trust`, кнопка Trust / Stop
  trusting, запись upsert'ом объекта `projects`: путь с точками ломал бы ключ) и строка в ленте раз на разговор,
  если у проекта есть свои настройки, а доверия нет (`CodexSessionHub.checkProjectSettings`, `accountOutranked` с
  `reason: untrusted`).
- Доверенный проект может требовать способ входа или рабочее пространство (`forced_login_method`,
  `forced_chatgpt_workspace_id`, `CodexConfig.demands`): тогда Codex считает вход аккаунта отсутствующим в этой
  папке, и панель говорит это строкой в ленте (`reason: account`) и на экране входа (`heldBackBy`).

### Поведение экрана

- Одно изменение за раз (`changing` в IDE): две записи сразу прочли бы одну версию файла, и вторую Codex отклонил бы.
- Изменение машинное, как файл: его говорят экранам всех открытых проектов, а живые разговоры всех проектов
  поднимаются заново над своими тредами между ходами (`CodexSessions.restartAll`): многое Codex читает при старте
  процесса. Идущий ход не прерывается.
- То, что панель шлёт каждым ходом из конфига (`sandboxPolicy` с `[sandbox_workspace_write]`, `summary`), читает
  `CodexConfigDesk.workspaceWrite` с кэшем на 5 минут, греется на старте процесса.
- Нет исполняемого файла Codex - экран говорит это (`noCli`), а не пустой список.
- С телефона закрыто (`askCodexConfig`, `setCodexConfig`, `setProjectTrust` в `RemoteCommands.DENIED`): машинные
  настройки, и для терминала тоже.
- Проверить без IDE: харнесс, `/config` в поле или «Settings» → «Codex settings»; плеер отвечает на
  `askCodexConfig`, `setCodexConfig` и `setProjectTrust` (`player.ts`).
- **Настройки «чекпоинтов» у Codex нет**: откат кода в панели доступен всегда (rewind.md). Механизм оригинала
  (`checkpoints` -> переменная запуска CLI) в форк не переносился.
