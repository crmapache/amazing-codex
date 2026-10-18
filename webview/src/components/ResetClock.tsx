/**
 * A small clock before the time a usage window has left until it resets (see the accounts screens).
 *
 * Drawn rather than a glyph: "◷" and "↻" are not in the panel's font and fall through to whatever the system
 * has, at a size and weight of their own beside figures set in ours (the same reason as EditorIcon). It is what
 * tells the time apart from the share above it - "35%" and "3h 31m" side by side read as one string.
 */
export const ResetClock = ({ size = 9 }: { size?: number }) => (
  <svg
    viewBox="0 0 12 12"
    width={size}
    height={size}
    aria-hidden="true"
    fill="none"
    stroke="currentColor"
    strokeWidth="1.3"
    strokeLinecap="round"
    strokeLinejoin="round"
  >
    <circle cx="6" cy="6" r="4.7" />
    <path d="M6 3.4V6l1.8 1.2" />
  </svg>
)
