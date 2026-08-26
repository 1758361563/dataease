import { ShorthandMode } from '@/Types'

/** Physical min/max from the old 36×18 caps (2 / 8) after restoring 72×36. */
export const VQUERY_MIN_SIZE_Y = 4
export const VQUERY_MAX_SIZE_Y = 16

/**
 * How many grid rows a VQuery card needs so wrapped conditions aren't clipped.
 * Snap up to whole cells: e.g. 2.1 cells of content → 3, not a hardcoded +1.
 */
export function sizeYToFitQueryContent(opts: {
  contentHeight: number
  cellHeight: number
  chromePx: number
  minSizeY?: number
  maxSizeY?: number
}): number {
  const minSizeY = opts.minSizeY ?? VQUERY_MIN_SIZE_Y
  const maxSizeY = opts.maxSizeY ?? VQUERY_MAX_SIZE_Y
  if (opts.contentHeight <= 0 || opts.cellHeight <= 0) return minSizeY
  const raw = Math.ceil((opts.contentHeight + opts.chromePx) / opts.cellHeight)
  return Math.min(maxSizeY, Math.max(minSizeY, raw))
}

/** Vertical chrome outside the condition row (shape gap + card inner padding + slack). */
export function queryCardChromePx(opts: {
  gap: number
  innerPadding?: number | { mode?: string; top?: number; bottom?: number }
  slack?: number
}): number {
  const slack = opts.slack ?? 4
  let padTop = 0
  let padBottom = 0
  const p = opts.innerPadding
  if (typeof p === 'number') {
    padTop = padBottom = p
  } else if (p) {
    padTop = p.top ?? 0
    const perEdge = p.mode === ShorthandMode.PerEdge || p.mode === 'per_edge'
    padBottom = perEdge ? p.bottom ?? padTop : padTop
  }
  return Math.max(0, opts.gap) * 2 + padTop + padBottom + slack
}
