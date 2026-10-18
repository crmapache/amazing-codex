import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { kindOfPath, PICTURE_EXTENSIONS } from './reference'

/**
 * Which files are pictures is the IDE's to say (PictureFormats.kt): the chooser names a picked file `img` by it,
 * the preview reads a file by it, the paste folder writes under it. The page needs its own copy - a dropped
 * file and a conversation read back are named here (see kindOfPath) - and this holds the copy to the list,
 * both ways. Four lists had drifted apart: a pasted BMP previewed, and came back from the history as a file.
 */
const kotlinExtensions = (): string[] => {
  const source = readFileSync(
    resolve(__dirname, '../../../src/main/kotlin/io/github/crmapache/amazingcodex/webview/PictureFormats.kt'),
    'utf8',
  )
  const map = /val MEDIA_TYPES[^=]*=\s*mapOf\(([\s\S]*?)\n\s*\)/.exec(source)?.[1] ?? ''
  return [...map.matchAll(/"([a-z0-9]+)" to "image\//g)].map((match) => match[1] ?? '')
}

describe('picture formats', () => {
  it("are the IDE's list, no more and no fewer", () => {
    const kotlin = kotlinExtensions()
    expect(kotlin.length).toBeGreaterThan(5)
    expect([...PICTURE_EXTENSIONS].sort()).toEqual([...kotlin].sort())
  })

  it('name a path a picture by its extension, in any case', () => {
    expect(kindOfPath('design/sheet.BMP')).toBe('img')
    expect(kindOfPath('shots/a.jpeg')).toBe('img')
    expect(kindOfPath('docs/scan.tif')).toBe('file')
  })
})
