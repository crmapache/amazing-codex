# Amazing Codex GUI

**OpenAI Codex como un panel de chat dentro de tu IDE de JetBrains.** Tarjetas en lugar del
desplazamiento de la terminal, archivos que señalas en lugar de rutas que escribes, y tu código
justo al lado.

Usa el propio CLI de Codex que ya tienes instalado, así que tu inicio de sesión de ChatGPT o tu
clave de API, los modelos, la configuración, los servidores MCP, las skills y tus propios prompts
personalizados vienen contigo. Sin proxy y sin ninguna cuenta nuestra.

🌐 [English](en.md) | [简体中文](zh.md) | [Русский](ru.md) | [Українська](uk.md) | **Español** | [Português (Brasil)](pt.md) | [Deutsch](de.md) | [Français](fr.md) | [日本語](ja.md) | [한국어](ko.md)

## Por qué este

- **Una ronda de trabajo, escrita una vez y ejecutada por ti.** Escenarios: unas pocas tarjetas,
  cada una un hilo de Codex propio - implementar, revisar, corregir, ejecutar las pruebas - en
  etapas que pueden repetirse más de una vez, con un hilo principal que las recorre y evalúa lo que
  encontró cada una. Ejecuta una con un botón, tres a la vez contra tres tickets, o de forma
  programada a las nueve de cada día laborable con sus preguntas ya respondidas de antemano.
  Describe la ronda en una frase y Codex lee el proyecto y escribe el formulario.
- **Todo el panel desde tu móvil, no solo un botón de «sí».** Contesta una petición de aprobación o
  un plan, abre un proyecto que está cerrado, lee la conversación de ayer, bifurca, cambia el
  modelo y el esfuerzo, cambia de cuenta, sigue la ejecución de un escenario y desbloquéalo.
  Desactivado por defecto, emparejado con un código QR, cifrado de extremo a extremo a través de
  un relay que no puede leer ni una palabra, revocable con un toque.
- **Varias cuentas de Codex, cambiadas con un clic.** Trabajo y personal en una sola máquina sin
  cerrar sesión en ninguna: cada una mantiene su propio inicio de sesión, mientras el historial, la
  configuración y las skills se comparten. Cada fila muestra lo que queda de los límites de esa
  cuenta, y Select mueve a ella cada conversación abierta.
- **Busca en todas las conversaciones del proyecto.** Prefijos, erratas, raíces de palabras, frases
  entre comillas; esta conversación o todas ellas, con un salto directo al mensaje dentro de su
  conversación. Cuando las palabras no bastan, describe lo que buscas y Codex lee las
  conversaciones por ti.
- **Todo lo que hace está en pantalla.** Cada comando con su duración, cada parche como un diff
  abierto con números de línea reales, el plan tachándose, búsquedas web, llamadas a herramientas
  MCP, y lo que costó el turno en tokens. Una petición reintentada o un límite agotado es una
  tarjeta con el motivo y la cuenta atrás, no silencio.
- **Nada responde por ti, y nada se pierde.** Una petición de aprobación, un plan o una pregunta
  esperan lo que haga falta: sin tiempo límite y sin continuación automática. Las conversaciones
  siguen adelante con el panel cerrado o el proyecto cambiado, y los mensajes escritos durante un
  turno se incorporan a él o esperan en una cola que conserva el IDE.
- **Android Studio incluido**, además de todos los IDE de JetBrains desde 2026.1.

## Además, en el panel

- **Señala los archivos en vez de escribirlos.** Arrastra uno, escribe `@` para elegirlo, pega una
  captura de pantalla o un registro largo - cada uno entra como una cápsula que no puedes teclear
  mal.
- **Envía el código con su dirección.** Selecciona las líneas, "Send to Amazing Codex GUI", y el
  agente lee el archivo real a su alrededor en vez de un fragmento sin contexto.
- **Las rutas abren archivos.** Una ruta en cualquier parte de la conversación - la cabecera de una
  tarjeta, una respuesta, un error, tu propio mensaje - abre el archivo en el editor en la línea
  que nombra; una edición se abre en el lugar de la propia edición.
- **Toma cualquier parte de una respuesta.** Cítala en tu siguiente mensaje, bifurca la
  conversación justo en ese punto, fija hasta tres mensajes encima de la conversación, o devuelve
  un mensaje ya enviado al campo para corregirlo y reenviarlo.
- **Modelo, esfuerzo de razonamiento y modo de aprobación cambian a mitad de conversación**, cada
  pestaña por su cuenta y sin reiniciar nada: preguntar cada vez, automático, solo lectura, plan, o
  acceso total. El menú de esfuerzo ofrece exactamente los niveles que tiene el modelo elegido.
- **Servidores MCP y plugins** en sus propias pantallas: qué servidor está activo, cuál necesita
  iniciar sesión, cuál se ha caído y por qué.
- **Historial** de las conversaciones anteriores de este proyecto, incluidas las que empezaron en
  la terminal, que se abren desde el final y cargan las páginas anteriores bajo demanda.
- **Los propios comandos de Codex** - `/compact`, `/review`, `/init`, `/new`, tus prompts
  personalizados y tus skills - en las sugerencias del campo.
- **Preguntas al margen** con `/side` o `/btw`: pregunta mientras Codex trabaja; la respuesta
  llega en una tarjeta sobre el campo y la conversación nunca la ve.
- **Los ajustes de Codex** en una pantalla propia (`/config`): qué dice tu config.toml y de
  dónde sale cada valor, y confiar en un proyecto con un clic.
- **`!` ejecuta un comando en tu propio shell**, y la salida viaja con tu siguiente mensaje, sin
  gastar un turno ni pedir permiso.
- **Mejorar el prompt**: la estrella reescribe tu borrador en una ejecución aparte, sin gastar el
  contexto de la conversación, y un botón devuelve tus propias palabras.
- **Dictado por voz** con tu propia clave de Deepgram: mantén pulsada una tecla, incluso desde el
  editor.
- **Avisos sonoros** para los momentos que lo merecen, y solo cuando no estás mirando ya.
- **Estadísticas** de horas, hábitos y logros, que puedes compartir como imagen.
- **Diez idiomas**, siguiendo tu IDE por defecto.
- **Tus búferes sin guardar** se escriben antes de un turno, y los archivos que el agente cambió se
  releen al instante.
- **Un panel lateral, no una pestaña del editor**, en cualquier borde de la ventana; los números
  eligen una opción, Shift+Tab recorre el modo, Escape detiene el turno.

## Privacidad y transparencia

- **Todo corre en tu máquina.** Sin proxy y sin ningún servidor nuestro por el medio. Tu inicio de
  sesión de Codex pertenece al CLI: el plugin nunca lo envía a ningún sitio ni va buscando claves
  de API por tu disco.
- **Nada sale sin tu permiso.** Sin cuenta y sin analíticas a tus espaldas. Las estadísticas
  de uso anónimas están apagadas hasta que pulsas Permitir en la tarjeta que pregunta una sola
  vez: solo recuentos, nunca código, mensajes ni nombres de archivo. Con ellas y el acceso
  remoto apagados, lo único que sale es un informe que tú escribes y envías - y un botón muestra
  antes su texto exacto.
- **Tus reglas siguen siendo tuyas.** Codex aplica tu configuración, su propio sandbox y su propia
  política de aprobación; el modo en pantalla es exactamente la política con la que corre el hilo,
  y el plugin nunca arranca un hilo en un modo más laxo.
- **Código disponible** en GitHub bajo la Elastic License 2.0, y la
  [política de privacidad](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md)
  enumera todo lo que puede salir de la máquina.

## Requisitos

Codex CLI instalado (`npm install -g @openai/codex`) y con sesión iniciada, y cualquier IDE de
JetBrains desde 2026.1, Android Studio incluido. Android Studio no trae navegador integrado
propio, así que el IDE te ofrecerá instalar el plugin de navegador de JetBrains junto a este.

## Enlaces

- [Código fuente](https://github.com/crmapache/amazing-codex)
- [Informar de un fallo o pedir una función](https://github.com/crmapache/amazing-codex/issues), o
  usa el formulario del propio panel
- [Política de privacidad](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md)
