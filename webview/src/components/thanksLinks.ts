/**
 * The addresses behind the heart - the menu carries only ids, the shell opens the address (see
 * App.openThanks). The feedback screen's two cards lead to two of them as well (see Feedback).
 *
 * A module of their own, with nothing imported, on purpose. Thanks.tsx borrows from StatusBar, StatusBar
 * draws the feedback bubble from Feedback.tsx, and Feedback.tsx needs these addresses when it loads: kept
 * in Thanks.tsx they closed a circle of imports, and whichever file the bundle happened to evaluate first
 * decided whether the panel started or died on "THANKS_LINKS before initialization".
 */
export const THANKS_LINKS = {
  github: 'https://github.com/crmapache/amazing-codex',
  /* The plugin's page in the marketplace, found by its name: this fork has no page of its own to link
     straight to until it is published, and a search is the address that works either way. */
  rate: 'https://plugins.jetbrains.com/search?search=Amazing%20Codex%20GUI',
  /* The author's page rather than a payment form: the amount, once or monthly, and the means of paying are
     chosen there, and nothing about money passes through the plugin. */
  tip: 'https://ko-fi.com/mzpizote',
} as const
