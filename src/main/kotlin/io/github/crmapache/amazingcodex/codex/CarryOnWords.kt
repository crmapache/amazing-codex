package io.github.crmapache.amazingcodex.codex

/**
 * What the panel says into a conversation by itself, when the work in it stopped for a reason that was
 * nobody's (see CarryOns) - in the language the panel speaks.
 *
 * Each of the three roads leaves the agent standing with nothing to tell it to move. Choosing an account
 * interrupts the turn in flight, and Codex hands the agent that as `<turn_aborted>` - "the user interrupted
 * the previous turn on purpose": the agent reads the stop as the person's and waits. Nothing else carries it
 * on either: the process raised on the new account resumes the thread and starts no turn of its own
 * (measured on 0.163). A limit closes the turn with its refusal, and the next word in the
 * conversation would have to be somebody's. A process replaced under background agents takes them with it
 * without a word, and the agent goes on waiting for their reports. Before this, every such tab stood still
 * until somebody went through them one by one writing "carry on" - which is the complaint this exists for.
 * The words say what really happened and ask for the rest of the work.
 *
 * Written on this side rather than in the panel's dictionaries for the reason the push titles are (see
 * PushWords): nothing of the panel may be running when it is said - a switch reaches every project, a
 * phone's included, tool window or no tool window, and a limit resets at three in the morning. And in the
 * person's language rather than in English, because the agent answers in the language it was last spoken
 * to in, and a Russian conversation should not go on in English from here.
 *
 * The background sentence is the one a scenario's card is told after a stop (see
 * ScenarioEngine.CARD_CARRY_ON), and for the same reason: an interrupt stops the background helpers of the
 * turn, a new process does not inherit them, and an agent whose last words were "waiting for the reviewers"
 * would otherwise go on waiting for reports that are never coming. Codex's own note beside the stop says the
 * opposite of the account switch's case - "any running unified exec processes may still be running" - and the
 * switch takes the process that held them down, so the words say so.
 *
 * And they name Codex, which is what restarts under the conversation here: the agent is Codex's, and "Claude
 * Code was restarted" would be a sentence about a tool it has never been run by.
 */
internal object CarryOnWords {

    /**
     * The tag the words reach the agent in - see [forAgent].
     *
     * It is what tells the message apart once it is only a line in a transcript: the feed draws it as the
     * panel's rather than the person's (CARRY_ON in feed/build.ts), the search leaves it out (see
     * TranscriptText.personWords), and a tab still waiting for its name is not named after it (see
     * SessionTitle.describe). The words inside it are in any of ten languages; the tag is in one, and its
     * reason names the road (CarryOns.Reason.wire).
     */
    const val TAG = "panel-carry-on"

    /** What the panel says for [owed], in [language]. */
    fun of(owed: CarryOns.Owed, language: String): String = when (owed.reason) {
        CarryOns.Reason.ACCOUNT_SWITCH -> afterAccountSwitch(language)
        CarryOns.Reason.RESTART -> afterRestart(language)
        CarryOns.Reason.LIMIT -> {
            val limit = afterLimit(language, moved = owed.moved)
            if (owed.helpersLost) "$limit ${helpersLost(language)}" else limit
        }
    }

    fun afterAccountSwitch(language: String): String = when (language) {
        "zh-Hans" -> "上面的中断是自动切换到另一个账号造成的，不是让你停下。请从停下的地方继续。" +
            "后台运行的任务没能撑过这次切换，仍然需要的请重新启动。"
        "ru" -> "Прерывание выше - это автоматическое переключение на другой аккаунт, а не просьба остановиться. " +
            "Продолжай с того места, где остановился. Всё, что работало в фоне, переключение не пережило: " +
            "запусти заново то, что ещё нужно."
        "uk" -> "Переривання вище - це автоматичне перемикання на інший акаунт, а не прохання зупинитися. " +
            "Продовжуй з того місця, де зупинився. Усе, що працювало у фоні, перемикання не пережило: " +
            "запусти знову те, що ще потрібно."
        "es" -> "La interrupción de arriba fue un cambio automático a otra cuenta, no una petición de parar. " +
            "Sigue desde donde lo dejaste. Lo que tenías en segundo plano no sobrevivió al cambio: " +
            "vuelve a lanzar lo que todavía necesites."
        "pt-BR" -> "A interrupção acima foi uma troca automática para outra conta, não um pedido para parar. " +
            "Continue de onde parou. O que estava rodando em segundo plano não sobreviveu à troca: " +
            "inicie de novo o que ainda precisar."
        "de" -> "Die Unterbrechung oben war ein automatischer Wechsel zu einem anderen Konto, keine Bitte aufzuhören. " +
            "Mach dort weiter, wo du aufgehört hast. Was im Hintergrund lief, hat den Wechsel nicht überstanden: " +
            "Starte neu, was du noch brauchst."
        "fr" -> "L'interruption ci-dessus était un passage automatique à un autre compte, pas une demande d'arrêt. " +
            "Reprends là où tu t'es arrêté. Ce qui tournait en arrière-plan n'a pas survécu au changement : " +
            "relance ce dont tu as encore besoin."
        "ja" -> "上の中断は別のアカウントへの自動切り替えによるもので、停止の指示ではありません。" +
            "止まったところから続けてください。バックグラウンドで動いていたものは切り替えで止まったので、" +
            "まだ必要なものは起動し直してください。"
        "ko" -> "위의 중단은 다른 계정으로 자동 전환되면서 생긴 것이며, 멈추라는 요청이 아닙니다. " +
            "멈춘 곳에서 이어서 진행해 주세요. 백그라운드에서 돌던 작업은 전환을 견디지 못했으니, " +
            "아직 필요한 것은 다시 시작해 주세요."
        else -> "The interruption above was an automatic switch to another account, not a request to stop. " +
            "Carry on from where you stopped. Anything you had running in the background did not survive " +
            "the switch: start again whatever you still need."
    }

    /**
     * The limit that refused the turn has reset - or, [moved], the conversation has gone on to another account
     * since. The two are said apart because the agent is told what is true: a limit that has not reset at all
     * and an account that is not the one it was refused on are different news, and either one is all it needs.
     */
    fun afterLimit(language: String, moved: Boolean): String = when (language) {
        "zh-Hans" -> if (moved) "你因用量上限停下了，此后这个对话已经换到另一个账号。请从停下的地方继续。"
        else "让你停下的用量上限已经重置。请从停下的地方继续。"
        "ru" -> if (moved) "Ты остановился на лимите, а разговор с тех пор переехал на другой аккаунт. " +
            "Продолжай с того места, где остановился."
        else "Лимит, на котором ты остановился, сбросился. Продолжай с того места, где остановился."
        "uk" -> if (moved) "Ти зупинився на ліміті, а розмова відтоді переїхала на інший акаунт. " +
            "Продовжуй з того місця, де зупинився."
        else "Ліміт, на якому ти зупинився, скинувся. Продовжуй з того місця, де зупинився."
        "es" -> if (moved) "El límite de uso te detuvo, y esta conversación ha pasado desde entonces a otra cuenta. " +
            "Sigue desde donde lo dejaste."
        else "El límite de uso que te detuvo ya se ha reiniciado. Sigue desde donde lo dejaste."
        "pt-BR" -> if (moved) "O limite de uso te parou, e esta conversa passou desde então para outra conta. " +
            "Continue de onde parou."
        else "O limite de uso que te parou foi reiniciado. Continue de onde parou."
        "de" -> if (moved) "Das Nutzungslimit hat dich gestoppt, und dieses Gespräch läuft inzwischen auf einem " +
            "anderen Konto. Mach dort weiter, wo du aufgehört hast."
        else "Das Nutzungslimit, an dem du gestoppt hast, wurde zurückgesetzt. Mach dort weiter, wo du aufgehört hast."
        "fr" -> if (moved) "La limite d'utilisation t'a arrêté, et cette conversation est passée depuis sur un autre " +
            "compte. Reprends là où tu t'es arrêté."
        else "La limite d'utilisation qui t'a arrêté a été réinitialisée. Reprends là où tu t'es arrêté."
        "ja" -> if (moved) "利用上限で止まり、その後この会話は別のアカウントに移りました。止まったところから続けてください。"
        else "止まる原因になった利用上限がリセットされました。止まったところから続けてください。"
        "ko" -> if (moved) "사용 한도 때문에 멈췄고, 그 뒤로 이 대화는 다른 계정으로 옮겨졌습니다. 멈춘 곳에서 이어서 진행해 주세요."
        else "멈추게 했던 사용 한도가 초기화됐습니다. 멈춘 곳에서 이어서 진행해 주세요."
        else -> if (moved) "The usage limit stopped you, and this conversation has since moved to another account. " +
            "Carry on from where you stopped."
        else "The usage limit that stopped you has reset. Carry on from where you stopped."
    }

    /** The process under an idle conversation was replaced, and the agents working in its background died. */
    fun afterRestart(language: String): String = when (language) {
        "zh-Hans" -> "Codex 在这个对话下重启了，你放在后台运行的代理没能撑过这次重启。请重新启动仍然需要的代理，然后继续。"
        "ru" -> "Codex перезапустили под этим разговором, и агенты, которых ты запустил в фоне, этого не " +
            "пережили. Запусти заново тех, кто ещё нужен, и продолжай."
        "uk" -> "Codex перезапустили під цією розмовою, і агенти, яких ти запустив у фоні, цього не " +
            "пережили. Запусти знову тих, хто ще потрібен, і продовжуй."
        "es" -> "Codex se reinició bajo esta conversación, y los agentes que tenías trabajando en segundo " +
            "plano no lo sobrevivieron. Vuelve a lanzar los que todavía necesites y sigue."
        "pt-BR" -> "O Codex foi reiniciado por baixo desta conversa, e os agentes que você tinha rodando em " +
            "segundo plano não sobreviveram. Inicie de novo os que ainda precisar e continue."
        "de" -> "Codex wurde unter diesem Gespräch neu gestartet, und die Agenten, die du im Hintergrund " +
            "laufen hattest, haben das nicht überstanden. Starte neu, welche du noch brauchst, und mach weiter."
        "fr" -> "Codex a été redémarré sous cette conversation, et les agents que tu faisais tourner en " +
            "arrière-plan n'y ont pas survécu. Relance ceux dont tu as encore besoin, puis reprends."
        "ja" -> "この会話の下で Codex が再起動され、バックグラウンドで動かしていたエージェントは止まりました。" +
            "まだ必要なものを起動し直して、続けてください。"
        "ko" -> "이 대화 아래에서 Codex가 다시 시작되어, 백그라운드에서 돌리던 에이전트들이 멈췄습니다. " +
            "아직 필요한 것은 다시 시작하고 이어서 진행해 주세요."
        else -> "Codex was restarted under this conversation, and the agents you had working in the " +
            "background did not survive it. Start again whatever you still need of them, and carry on."
    }

    /** Said after a limit's words when the background was lost on the way as well. */
    fun helpersLost(language: String): String = when (language) {
        "zh-Hans" -> "后台运行的任务没能撑过 Codex 的重启，仍然需要的请重新启动。"
        "ru" -> "Всё, что работало в фоне, не пережило перезапуск Codex: запусти заново то, что ещё нужно."
        "uk" -> "Усе, що працювало у фоні, не пережило перезапуск Codex: запусти знову те, що ще потрібно."
        "es" -> "Lo que tenías en segundo plano no sobrevivió a un reinicio de Codex: vuelve a lanzar lo que " +
            "todavía necesites."
        "pt-BR" -> "O que estava rodando em segundo plano não sobreviveu a um reinício do Codex: inicie de novo " +
            "o que ainda precisar."
        "de" -> "Was im Hintergrund lief, hat einen Neustart von Codex nicht überstanden: Starte neu, was du " +
            "noch brauchst."
        "fr" -> "Ce qui tournait en arrière-plan n'a pas survécu à un redémarrage de Codex : relance ce dont " +
            "tu as encore besoin."
        "ja" -> "バックグラウンドで動いていたものは Codex の再起動で止まったので、まだ必要なものは起動し直してください。"
        "ko" -> "백그라운드에서 돌던 작업은 Codex 재시작을 견디지 못했으니, 아직 필요한 것은 다시 시작해 주세요."
        else -> "Anything you had running in the background did not survive a restart of Codex: start again " +
            "whatever you still need."
    }

    /** The words as the agent is sent them - inside the tag that names them, and the road, for every later reader. */
    fun forAgent(reason: CarryOns.Reason, words: String): String = "<$TAG reason=\"${reason.wire}\">$words</$TAG>"

    /** Every language these words exist in - the same ones the panel has dictionaries for. */
    val LANGUAGES: List<String> get() = IdeLanguage.SUPPORTED
}
