package io.github.crmapache.amazingcodex.codex

/**
 * Codex's service tiers, as far as the panel's fast mode needs them (see CodexSession.fastMode).
 *
 * The switch asks for one tier, [FAST] - "Fast" in Codex's catalogue (`serviceTiers` of `model/list`) and `/fast`
 * in its terminal - or for [STANDARD] to turn it off. What the mark shows is a little wider (see [isFast]): it
 * answers "does this conversation run at a faster speed right now", whoever put it there.
 */
internal object ServiceTiers {

    const val FAST = "priority"

    const val STANDARD = "default"

    /** The catalogue's older word for the same tier (`additionalSpeedTiers`, deprecated in favour of `serviceTiers`). */
    const val LEGACY_FAST = "fast"

    /** Faster still - "The fastest available responses for latency-sensitive work" (Codex 0.163), on some models. */
    const val ULTRAFAST = "ultrafast"

    /**
     * Whether a tier Codex says a thread runs on is a fast one: the switch's own, its older name, and the one above
     * it, which a person may set in Codex's config. Not "anything but the standard one": `flex` is slower and cheaper
     * (Codex knows it - `flexUnavailable` is one of its errors), and a fast mark over it would be a lie.
     */
    fun isFast(tier: String?): Boolean = tier == FAST || tier == LEGACY_FAST || tier == ULTRAFAST
}
