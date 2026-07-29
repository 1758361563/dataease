/**
 * company: Tab/Group inner canvas grid geometry.
 *
 * One coordinate system per Tab content root:
 *   cell = root.clientWidth / cols   (cols = 36)
 *   left = (x - 1 + TAB_INNER_GUTTER_COLS) * cell
 *
 * Do NOT reuse main-canvas bashMatrix here — that mixes two containers and
 * breaks when the side tree opens/closes.
 */

/** Explicit leading column gutter inside Tab content (responsive, not px). */
export const TAB_INNER_GUTTER_COLS = 1

export type DashboardGridItemStyle = {
  padding: string
  width: string
  height: string
  left: string
  top: string
}

/** Resolve cell from THIS Tab root only. rootWidth/Height must be the positioning root. */
export function resolveTabInnerCell(opts: {
  rootWidth: number
  rootHeight: number
  cols: number
  rows: number
  scaleWithWidth?: boolean
}): { cellWidth: number; cellHeight: number; colsThatFit: number } {
  const cellWidth = Math.max(opts.rootWidth, 1) / opts.cols
  const cellHeight = opts.scaleWithWidth
    ? cellWidth * 1.6
    : Math.max(opts.rootHeight, 1) / opts.rows
  return {
    cellWidth,
    cellHeight,
    colsThatFit: opts.rootWidth > 0 ? opts.rootWidth / cellWidth : opts.cols
  }
}

/**
 * Tab-inner item style: keep each component's own x, add gutter columns.
 * left = (x - 1 + gutterCols) * cell
 */
export function dashboardTabInnerItemStyle(
  item: { x: number; y: number; sizeX: number; sizeY: number },
  opts: {
    cellWidth: number
    cellHeight: number
    curGap: number
    gutterCols?: number
  }
): DashboardGridItemStyle {
  const gutterCols = opts.gutterCols ?? TAB_INNER_GUTTER_COLS
  const { cellWidth, cellHeight, curGap } = opts
  return {
    padding: curGap + 'px!important',
    width: cellWidth * item.sizeX + 'px',
    height: cellHeight * item.sizeY + 'px',
    left: cellWidth * (item.x - 1 + gutterCols) + 'px',
    top: cellHeight * (item.y - 1) + 'px'
  }
}

/** Main-canvas / non-Tab dashboard formula (no Tab gutter). */
export function dashboardGridItemStyle(
  item: { x: number; y: number; sizeX: number; sizeY: number },
  opts: { cellWidth: number; cellHeight: number; curGap: number }
): DashboardGridItemStyle {
  const { cellWidth, cellHeight, curGap } = opts
  return {
    padding: curGap + 'px!important',
    width: cellWidth * item.sizeX + 'px',
    height: cellHeight * item.sizeY + 'px',
    left: cellWidth * (item.x - 1) + 'px',
    top: cellHeight * (item.y - 1) + 'px'
  }
}

type CoordTracePayload = {
  lane: 'editor-DeCanvas' | 'preview-DePreview'
  canvasId: string
  componentId: string
  gridX: number
  gridY: number
  sizeX: number
  sizeY: number
  cols: number
  gutterCols: number
  cellWidth: number
  cellHeight: number
  styleLeftPx: number
  styleWidthPx: number
  rootWidth?: number
  colsThatFit?: number
  leftSlackPx?: number
  rightSlackPx?: number
  domLeft?: string
  domWidth?: string
  offsetParentIsRoot?: boolean
}

const tracedKeys = new Set<string>()

/** Temporary diagnosis — one sample per component×lane; reset via __BI_resetTabCoordTrace. */
export function traceTabInnerCoord(payload: CoordTracePayload, force = false) {
  if (typeof window === 'undefined') return
  const enabled =
    force ||
    (window as unknown as { __BI_TAB_COORD_TRACE__?: boolean }).__BI_TAB_COORD_TRACE__ !== false
  if (!enabled) return
  const key = `${payload.lane}:${payload.canvasId}:${payload.componentId}`
  if (!force && tracedKeys.has(key)) return
  tracedKeys.add(key)

  // eslint-disable-next-line no-console
  console.info('[BI tab-coord]', {
    ...payload,
    formula: 'left = cell * (gridX - 1 + gutterCols); cell = rootWidth / cols'
  })
}

export function resetTabInnerCoordTrace() {
  tracedKeys.clear()
}

if (typeof window !== 'undefined') {
  ;(window as unknown as { __BI_resetTabCoordTrace?: () => void }).__BI_resetTabCoordTrace =
    resetTabInnerCoordTrace
}
