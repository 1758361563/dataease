import { ShorthandMode } from '@/Types'

/**
 * How many grid rows a VQuery card needs so wrapped conditions aren't clipped.
 * Snap up to whole cells: e.g. 2.1 cells of content → 3, not a hardcoded +1.
 */
export function sizeYToFitQueryContent(opts: {
  contentHeight: number
  cellHeight: number
  chromePx: number
  minSizeY?: number
}): number {
  const minSizeY = opts.minSizeY ?? 2
  if (opts.contentHeight <= 0 || opts.cellHeight <= 0) return minSizeY
  const raw = Math.ceil((opts.contentHeight + opts.chromePx) / opts.cellHeight)
  return Math.min(8, Math.max(minSizeY, raw))
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
