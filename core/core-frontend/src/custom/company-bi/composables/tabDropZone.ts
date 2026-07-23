/**
 * Tab drop-zone geometry helpers (company).
 * Pointer-based center vs edge zones — replaces four-corner containment.
 */

export type TabZoneKind = 'center' | 'edge-top' | 'edge-bottom' | 'outside'

export const TAB_CANDIDATE_MS = 280
export const TAB_EXIT_MS = 100
export const TAB_HEADER_FALLBACK_PX = 46
export const TAB_EDGE_MIN_PX = 32
export const TAB_EDGE_MAX_PX = 56
export const TAB_EXIT_PAD_PX = 16

export function edgeBandHeight(contentHeight: number): number {
  return Math.min(TAB_EDGE_MAX_PX, Math.max(TAB_EDGE_MIN_PX, contentHeight * 0.12))
}

/** Content rect of a DeTabs shape: below the folder header strip. */
export function getTabContentRect(tabShapeEl: HTMLElement): DOMRect | null {
  const canvas = tabShapeEl.querySelector(
    '.tab-content-custom:not(.switch-hidden) .db-canvas, .tab-content-custom:not(.switch-hidden)'
  ) as HTMLElement | null
  if (canvas) {
    return canvas.getBoundingClientRect()
  }
  // fallback: shape-inner content below tabs header
  const head = tabShapeEl.querySelector('.custom-tabs-head') as HTMLElement | null
  if (head) {
    const full = head.getBoundingClientRect()
    const header =
      (head.querySelector('.ed-tabs__header') as HTMLElement | null)?.offsetHeight ||
      TAB_HEADER_FALLBACK_PX
    return new DOMRect(full.left, full.top + header, full.width, Math.max(0, full.height - header))
  }
  const full = tabShapeEl.getBoundingClientRect()
  if (!full.width || !full.height) return null
  return new DOMRect(
    full.left,
    full.top + TAB_HEADER_FALLBACK_PX,
    full.width,
    Math.max(0, full.height - TAB_HEADER_FALLBACK_PX)
  )
}

export function hitTabZone(
  clientX: number,
  clientY: number,
  contentRect: DOMRect,
  opts?: { exitPad?: number }
): TabZoneKind {
  const pad = opts?.exitPad ?? 0
  const left = contentRect.left - pad
  const right = contentRect.right + pad
  const top = contentRect.top - pad
  const bottom = contentRect.bottom + pad
  if (clientX < left || clientX > right || clientY < top || clientY > bottom) {
    return 'outside'
  }
  const band = edgeBandHeight(contentRect.height)
  // When using exit pad, still classify relative to original content (not padded)
  const y = clientY
  if (y < contentRect.top + band) return 'edge-top'
  if (y > contentRect.bottom - band) return 'edge-bottom'
  return 'center'
}

export function canMoveIntoTab(component: { component?: string } | null | undefined): boolean {
  if (!component?.component) return false
  const ignore = ['de-button', 'de-reset-button', 'DeTabs', 'GroupArea']
  return !ignore.includes(component.component)
}
