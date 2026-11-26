// The display an agent's windows go to, so its checks do not jump in front of a person's work
// (scripts/agent-sandbox.sh).
//
//   osascript -l JavaScript scripts/aside-display.js
//     "x y width height" of the visible area (no menu bar, no dock) of a display the mouse is NOT on - the
//     laptop's built-in screen first, any other one after it - in the coordinates Java's AWT uses: the
//     origin at the top left corner of the main display, y growing downwards. Prints nothing and exits 1
//     when there is only one display: then there is nowhere aside to go.
//
//   osascript -l JavaScript scripts/aside-display.js front x y width height
//     "pid bundle-id mouse-inside" of the front app, and whether the mouse is inside those bounds (AWT
//     coordinates, as above) - one call for each tick of the focus guard.
//
// AppKit counts from the bottom left corner of the main display with y growing upwards, hence the flips.

ObjC.import('AppKit')

function run(argv) {
  const screens = ObjC.unwrap($.NSScreen.screens)
  const mainHeight = screens[0].frame.size.height
  const mouse = $.NSEvent.mouseLocation

  if (argv[0] === 'front') {
    const [x, y, w, h] = argv.slice(1).map(Number)
    const bottom = mainHeight - y - h
    // The menu bar and the dock of that display count as on it.
    const inside = mouse.x >= x && mouse.x < x + w && mouse.y >= bottom - 80 && mouse.y < bottom + h + 40
    const app = $.NSWorkspace.sharedWorkspace.frontmostApplication
    return [app.processIdentifier, ObjC.unwrap(app.bundleIdentifier) || '-', inside].join(' ')
  }

  if (screens.length < 2) {
    $.exit(1)
  }

  const holdsMouse = (s) =>
    mouse.x >= s.frame.origin.x &&
    mouse.x < s.frame.origin.x + s.frame.size.width &&
    mouse.y >= s.frame.origin.y &&
    mouse.y < s.frame.origin.y + s.frame.size.height
  const builtIn = (s) => {
    const id = ObjC.unwrap(s.deviceDescription.objectForKey('NSScreenNumber'))
    return $.CGDisplayIsBuiltin(id) !== 0
  }

  const free = screens.filter((s) => !holdsMouse(s))
  const target = free.find(builtIn) ?? free[0]
  if (!target) {
    $.exit(1)
  }

  const v = target.visibleFrame
  const top = mainHeight - (v.origin.y + v.size.height)
  return [v.origin.x, top, v.size.width, v.size.height].map(Math.round).join(' ')
}
