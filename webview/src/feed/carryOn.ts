import type { PanelState } from './panelState'

/**
 * Whether the plan to carry this conversation on after a limit stands on screen now (see CarryOnRow).
 *
 * Not while a turn runs, however it began - a background agent reporting in starts one the feed never sent, and
 * the row stood over it promising a moment that was already work. Not once the moment has gone by either: the
 * IDE carries on at it or as soon as the tab is free, and a row still saying "at 03:00" at ten past is wrong in
 * both cases. The row next to it about the limit itself hides at its reset in the same way (see LimitRow).
 */
export const carryOnShown = (carryOn: PanelState['carryOn'], running: boolean, now: number): boolean =>
  carryOn !== undefined && !running && carryOn.at > now
