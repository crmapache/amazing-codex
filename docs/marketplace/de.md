# Amazing Codex GUI

**OpenAI Codex als Chat-Panel in deiner JetBrains-IDE.** Karten statt Terminal-Rücklauf, Dateien,
auf die du zeigst, statt Pfaden, die du tippst - und dein Code direkt daneben.

Es steuert die Codex-CLI, die ohnehin auf deinem Rechner liegt: deine ChatGPT-Anmeldung oder dein
API-Schlüssel, Modelle, Konfiguration, MCP-Server, Skills und deine eigenen Prompts kommen
unverändert mit. Kein Proxy dazwischen, kein Konto bei uns.

🌐 [English](en.md) | [简体中文](zh.md) | [Русский](ru.md) | [Українська](uk.md) | [Español](es.md) | [Português (Brasil)](pt.md) | **Deutsch** | [Français](fr.md) | [日本語](ja.md) | [한국어](ko.md)

## Warum dieses hier

- **Eine Arbeitsrunde, einmal geschrieben und für dich ausgeführt.** Szenarien: ein paar Karten,
  jede ein eigener Codex-Thread - implementieren, überprüfen, beheben, die Tests laufen lassen -
  in Etappen, die sich mehr als einmal wiederholen können, mit einem Hauptstrang, der sie
  durchläuft und bewertet, was jede gefunden hat. Starte per Taste, drei gleichzeitig gegen drei
  Tickets, oder per Uhrzeit, jeden Wochentag um neun, mit im Voraus beantworteten Fragen.
  Beschreibe die Runde in einem Satz, und Codex liest das Projekt und erstellt das Formular.
- **Das ganze Panel von deinem Handy aus, nicht nur eine "Ja"-Taste.** Beantworte eine
  Freigabeanfrage oder einen Plan, öffne ein geschlossenes Projekt, lies das gestrige Gespräch,
  verzweige, ändere das Modell und den Aufwand, wechsle das Konto, verfolge einen laufenden
  Szenario-Lauf und gib ihn frei. Standardmäßig aus, per QR-Code gekoppelt, Ende-zu-Ende
  verschlüsselt über ein Relay, das kein Wort mitlesen kann, mit einem Tipp widerrufbar.
- **Mehrere Codex-Konten, mit einem Klick gewechselt.** Arbeit und Privates auf einem Rechner,
  ohne dich von einem der beiden abzumelden: jedes behält seine eigene Anmeldung, während Verlauf,
  Konfiguration und Skills gemeinsam bleiben. Jede Zeile zeigt, was von den Limits dieses Kontos
  übrig ist, und Select verschiebt jedes offene Gespräch darauf.
- **Suche über jedes Gespräch des Projekts.** Präfixe, Tippfehler, Wortstämme, Phrasen in
  Anführungszeichen; dieses Gespräch oder alle, mit einem Sprung direkt zur Nachricht in ihrem
  Gespräch. Wenn Worte nicht reichen, beschreibe, wonach du suchst, und Codex liest die Gespräche
  für dich.
- **Alles, was es tut, steht auf dem Bildschirm.** Jeder Befehl mit seiner Dauer, jede Änderung
  als offener Diff mit echten Zeilennummern, der Plan, der abgehakt wird, Websuchen,
  MCP-Werkzeugaufrufe, und was der Zug an Tokens gekostet hat. Eine wiederholte Anfrage oder ein
  aufgebrauchtes Limit ist eine Karte mit dem Grund und dem Countdown, kein Schweigen.
- **Niemand antwortet für dich, und nichts geht verloren.** Eine Freigabeanfrage, ein Plan oder
  eine Frage warten, so lange es dauert - kein Timeout, kein automatisches Weitermachen. Gespräche
  laufen weiter, auch wenn das Panel eingeklappt oder das Projekt gewechselt wird, und Nachrichten,
  die während eines Zuges geschrieben werden, fließen entweder direkt in ihn ein oder warten in
  einer Warteschlange, die die IDE führt.
- **Android Studio inklusive**, dazu jede JetBrains-IDE ab 2026.1.

## Außerdem im Panel

- **Auf Dateien zeigen statt sie tippen.** Zieh eine hinein, tippe `@`, um sie auszuwählen, füge
  einen Screenshot oder ein langes Log ein - jede landet als Chip, bei dem man sich nicht
  vertippen kann.
- **Code geht mit seiner Adresse raus.** Markiere die Zeilen, "Send to Amazing Codex GUI" - und
  der Agent liest die echte Datei ringsum statt eines Schnipsels ohne Zusammenhang.
- **Pfade öffnen Dateien.** Ein Pfad an beliebiger Stelle im Gespräch - im Kopf einer Karte, einer
  Antwort, einem Fehler, deiner eigenen Nachricht - öffnet die Datei im Editor an der Zeile, die
  er nennt; eine Änderung öffnet sich direkt bei der Änderung selbst.
- **Jeder Teil einer Antwort ist ein Griff.** Zitiere ihn in deine nächste Nachricht, verzweige das
  Gespräch genau ab dieser Stelle, hefte bis zu drei Nachrichten über dem Gespräch an, oder hol
  eine gesendete Nachricht zurück ins Feld, um sie zu korrigieren und erneut zu senden.
- **Modell, Denkaufwand und Freigabemodus wechseln mitten im Gespräch**, je Tab und ohne
  irgendetwas neu zu starten: jedes Mal fragen, automatisch, nur Lesen, Plan, oder voller Zugriff.
  Das Aufwand-Menü bietet genau die Stufen, die das gewählte Modell hat.
- **MCP-Server und Plugins** haben eigene Ansichten: welcher Server läuft, welcher eine Anmeldung
  will, welcher abgestürzt ist und warum.
- **Verlauf** der bisherigen Gespräche dieses Projekts, auch der im Terminal begonnenen, vom Ende
  her geöffnet und bei Bedarf seitenweise weiter zurückgeladen.
- **Codex' eigene Befehle** - `/compact`, `/review`, `/init`, `/new`, deine eigenen Prompts und
  Skills - in den Vorschlägen des Feldes.
- **Fragen am Rand** mit `/side` oder `/btw`: fragen Sie, während Codex arbeitet - die Antwort
  kommt als Karte über dem Eingabefeld, und das Gespräch sieht sie nie.
- **Die Einstellungen von Codex selbst** auf einem eigenen Bildschirm (`/config`): was in Ihrer
  config.toml steht und woher jeder Wert kommt, und einem Projekt mit einem Klick vertrauen.
- **`!` führt einen Befehl in deiner eigenen Shell aus**, und die Ausgabe reist mit deiner
  nächsten Nachricht mit, ohne einen Zug oder eine Freigabeanfrage zu kosten.
- **Prompt verbessern** - der Funke schreibt deinen Entwurf in einem eigenen Lauf um, ohne Kontext
  des Gesprächs zu kosten, und eine Taste holt deine eigenen Worte zurück.
- **Spracheingabe** mit deinem eigenen Deepgram-Schlüssel: einen Hotkey halten, auch aus dem
  Editor heraus.
- **Klangsignale** für die Momente, die einen verdienen - und nur, wenn du nicht ohnehin hinsiehst.
- **Statistiken** zu Stunden, Gewohnheiten und Errungenschaften, als Bild teilbar.
- **Zehn Sprachen**, standardmäßig der IDE folgend.
- **Deine ungespeicherten Puffer** werden vor einem Zug geschrieben, und Dateien, die der Agent
  geändert hat, liest die IDE sofort neu ein.
- **Ein Seitenpanel, kein Editor-Tab**, an jedem Rand des Fensters; Zahlen wählen eine Option aus,
  Shift+Tab schaltet den Modus durch, Escape stoppt den Zug.

## Datenschutz und Transparenz

- **Alles läuft auf deinem Rechner.** Kein Proxy, kein Server von uns dazwischen. Deine
  Codex-Anmeldung gehört der CLI: Das Plugin schickt sie nie irgendwohin und sucht auch keine
  API-Schlüssel auf deiner Platte.
- **Nichts verlässt den Rechner ohne Ihr Ja.** Kein Konto, keine heimliche Analyse. Anonyme
  Nutzungsstatistik bleibt aus, bis Sie auf der Karte, die einmal fragt, auf Erlauben drücken:
  nur Zählwerte, nie Code, Nachrichten oder Dateinamen. Ist sie aus und der Fernzugriff ebenso,
  verlässt nur ein Feedback den Rechner, das Sie selbst schreiben und senden - und ein Knopf
  zeigt vorher den genauen Text.
- **Deine Regeln bleiben deine.** Codex wendet deine Konfiguration an, seine eigene Sandbox und
  seine eigene Freigaberichtlinie; der Modus auf dem Bildschirm ist genau die Richtlinie, mit der
  der Thread läuft, und das Plugin startet nie einen Thread in einem laxeren Modus.
- **Quellcode einsehbar** auf GitHub unter der Elastic License 2.0, und die
  [Datenschutzerklärung](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md) führt
  alles auf, was den Rechner verlassen kann.

## Voraussetzungen

Installierte und angemeldete Codex-CLI (`npm install -g @openai/codex`) sowie eine beliebige
JetBrains-IDE ab 2026.1, Android Studio eingeschlossen. Android Studio bringt keinen eigenen
eingebetteten Browser mit, deshalb bietet die IDE an, das Browser-Plugin von JetBrains zusammen
mit diesem zu installieren.

## Links

- [Quellcode](https://github.com/crmapache/amazing-codex)
- [Fehler melden oder Funktion wünschen](https://github.com/crmapache/amazing-codex/issues), oder
  das Formular direkt im Panel benutzen
- [Datenschutzerklärung](https://github.com/crmapache/amazing-codex/blob/main/PRIVACY.md)
