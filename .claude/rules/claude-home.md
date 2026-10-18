---
paths:
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexHome.kt"
  - "src/test/kotlin/io/github/crmapache/amazingcodex/codex/CodexHomeTest.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/HostOs.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexHistory.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexSettings.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexTokenUsage.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/CodexCommandHints.kt"
  - "src/main/kotlin/io/github/crmapache/amazingcodex/codex/PromptDelivery.kt"
---

## Где живёт Codex - вопрос к проекту, а не к машине IDE

Все читатели диска Codex (файлы тредов для истории, картинок и поиска, пользовательский слой конфига для режима
новой вкладки, личные промпты и скиллы для подсказки, расход токенов за день, полка личных сценариев)
спрашивают `CodexHome.of(project.basePath)`, а не `HostOs.configDirectory()` напрямую. Причина пришла в оригинал
отзывом с RubyMine на Windows и проектом внутри WSL: платформа поднимает агента внутри дистрибутива, тот пишет в
дом пользователя дистрибутива, а панель искала в доме Windows - не та машина, и молча: отсутствие папки с
разговорами - штатное состояние. У Codex это `~/.codex/sessions` внутри дистрибутива против `C:\Users\<имя>\.codex`.

- **Для обычного проекта ответ - ровно прежний** (`CodexHome.local`): `CODEX_HOME` из окружения IDE или
  `~/.codex` (`HostOs.configDirectory`), путь проекта рядом с каноническим (по нему `thread/list` фильтрует треды). WSL-классы не трогаются вовсе: до них дело
  доходит только на Windows и только для пути, начинающегося с `//` или `\\`.
- **Для WSL-проекта** - дом пользователя дистрибутива (`WSLDistribution.getUserHome`, платформа кэширует),
  его `CODEX_HOME` из окружения login-shell (`WSLDistribution.getEnvironment`; однопеременный
  `getEnvironmentVariable` помечен Internal, и верификатор на нём падает), иначе `~/.codex`, политика -
  `/etc/codex`, Linux-путь проекта, а файлы читаются через
  ту же шару, откуда открыт проект (`WslPath.wslRoot` + Linux-путь). Сами читатели не меняются - меняются
  корень и имя.
- **Пути, напечатанные Codex, переводятся тем же местом** (`CodexHome.hostPath`): путь внутри WSL - Linux-путь, и
  папку в нём иначе не открыть.
- **Первый ответ для WSL стоит процесса `wsl.exe`**, поэтому хаб греет его при открытии проекта
  (`CodexHome.warmUp`), ответ кэшируется по дистрибутиву, а неудача не кэшируется дольше минуты. С EDT
  спрашивать нельзя: платформа там отвечает null, не блокируя поток, и резолвер честно откатывается к
  локальному ответу с заметкой в `DiagnosticsLog` (без путей и имён - буфер уезжает наружу с отчётом).
- **Арифметика путей вынесена в чистую функцию** (`CodexHome.inWsl`) и держится `CodexHomeTest`: живьём
  на маке WSL не проверить, а ломается это молча.
- Что здесь НЕ решено и сделано осознанно: поиск исполняемого файла, его версия без рабочего каталога и чипы
  файлов с UNC-путями в сообщении агенту - это отдельная история про запуск в WSL, а не про чтение его диска.
  Добавленные аккаунты в WSL-проекте закрыты вовсе (`Capability.WSL`, accounts.md).
