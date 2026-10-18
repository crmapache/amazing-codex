import type { Dict } from '../i18n/en'
import type { ScenarioRunStep } from '../protocol'

/**
 * Where a row of a run stands in its stage, and how long a stage is, in words - once for both screens that draw
 * a run, the desk and the phone (the same reason as moves.ts: written in each, the two would name one pass two
 * ways on the first change).
 *
 * Two kinds of stage, and they are placed differently on purpose. A stage that goes round a number of times is
 * placed by its pass ("pass 2 of up to 3"). A stage that runs once per item of a list is placed by its item
 * ("item 3 of 7") - its passes are as many as its list, and the list, not a ceiling, is what the person wants to
 * read off it (see Stage.each on the IDE's side).
 */

/** What a step row of the timeline knows about its stage (see TimelineRow). */
export interface StepPlace {
  passes: number
  untilDone: boolean
  perItem: boolean
}

/** What a stage heading knows about its stage (see TimelineRow). */
export interface StageSize {
  passes: number
  untilDone: boolean
  /** For a stage over a list: its length, null until the main thread has given it. */
  items?: number | null
}

/** The chip on a step row: its pass, its item, or nothing for a stage that runs once. */
export const stepPlace = (t: Dict, step: ScenarioRunStep, place: StepPlace): string => {
  if (place.perItem) {
    const item = step.item
    if (!item) return t.scenarios.run.listPending
    if (item.count === 0) return t.scenarios.run.listEmpty
    if (item.again) return t.scenarios.run.secondGo
    return t.scenarios.run.itemOf(item.index, item.count)
  }

  if (place.passes <= 1) return ''
  return place.untilDone ? t.scenarios.run.passOfUpTo(step.pass, place.passes) : t.scenarios.run.passOf(step.pass, place.passes)
}

/** What a stage heading says about its size: how long its list is, or how many times it goes round. */
export const stageSize = (t: Dict, stage: StageSize): string => {
  if (stage.items !== undefined) {
    if (stage.items === null) return t.scenarios.run.listAheadShort
    return stage.items === 0 ? t.scenarios.run.listEmptyShort : t.scenarios.run.itemsShort(stage.items)
  }

  if (stage.passes <= 1) return ''
  return stage.untilDone ? t.scenarios.editor.roundsUntilShort(stage.passes) : t.scenarios.editor.roundsShort(stage.passes)
}

/** The item a row is for, by the name the main thread gave it - empty for every row that is not for one. */
export const itemLabel = (step: ScenarioRunStep): string => (step.item && step.item.count > 0 ? step.item.label : '')
