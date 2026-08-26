import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'

/**
 * Company dashboard layout grid schema.
 *
 * Canonical runtime/save format is official DataEase 72×36.
 * Company A5 saved 36×18 assets as layoutGridVersion 1 (or missing).
 *
 * One entry: adaptDashboardLayoutGrid() on canvas load.
 * Do not ×2 in Shape / VQuery / DeTabs. Do not touch mobile mx/my —
 * mobileMatrixCount was never coarsened.
 */
export const LAYOUT_GRID_VERSION_LEGACY = 1
export const LAYOUT_GRID_VERSION_CANONICAL = 2
export const LEGACY_MATRIX = { x: 36, y: 18 }
export const CANONICAL_MATRIX = { x: 72, y: 36 }

type LayoutItem = {
  component?: string
  x?: number
  y?: number
  sizeX?: number
  sizeY?: number
  propValue?: unknown
}

type DashboardStyle = {
  layoutGridVersion?: number
}

export type LayoutGridAdaptResult = {
  converted: boolean
  fromVersion: number
  toVersion: number
  skippedOverflow: boolean
}

let lastAdaptResult: LayoutGridAdaptResult | null = null

export function getLastLayoutGridAdaptResult() {
  return lastAdaptResult
}

function scaleLegacyPcBox(item: LayoutItem) {
  const x = Number(item.x)
  const y = Number(item.y)
  const sizeX = Number(item.sizeX)
  const sizeY = Number(item.sizeY)
  if (!Number.isFinite(x) || !Number.isFinite(sizeX)) return
  item.x = (x - 1) * 2 + 1
  item.y = Number.isFinite(y) ? (y - 1) * 2 + 1 : 1
  item.sizeX = sizeX * 2
  item.sizeY = Number.isFinite(sizeY) ? sizeY * 2 : sizeY
}

export function walkLayoutItems(list: unknown, visit: (item: LayoutItem) => void) {
  if (!Array.isArray(list)) return
  for (const raw of list) {
    if (!raw || typeof raw !== 'object') continue
    const item = raw as LayoutItem
    visit(item)
    if (item.component === 'DeTabs' && Array.isArray(item.propValue)) {
      for (const tab of item.propValue as Array<{ componentData?: unknown }>) {
        walkLayoutItems(tab?.componentData, visit)
      }
    } else if (item.component === 'Group' && Array.isArray(item.propValue)) {
      walkLayoutItems(item.propValue, visit)
    }
  }
}

function exceedsLegacyGrid(list: unknown): boolean {
  // Horizontal only. Vertical overflow past 18 is normal — the canvas
  // scrolls (this test board's DeTabs is sizeY=19 on the old 36×18 grid).
  // Canonical 72-col data is identified by any item wider than 36.
  let overflow = false
  walkLayoutItems(list, item => {
    const right = Number(item.x) + Number(item.sizeX) - 1
    if (right > LEGACY_MATRIX.x) overflow = true
  })
  return overflow
}

export function dumpLayoutCoords(list: unknown) {
  const rows: Array<{
    component?: string
    x?: number
    y?: number
    sizeX?: number
    sizeY?: number
  }> = []
  walkLayoutItems(list, item => {
    rows.push({
      component: item.component,
      x: item.x,
      y: item.y,
      sizeX: item.sizeX,
      sizeY: item.sizeY
    })
  })
  return rows
}

export function stampCanonicalLayoutGridVersion(
  canvasStyle: { dashboard?: DashboardStyle } | null | undefined
) {
  if (canvasStyle?.dashboard) {
    canvasStyle.dashboard.layoutGridVersion = LAYOUT_GRID_VERSION_CANONICAL
  }
}

/**
 * Load-time adapter. Idempotent: v2 is a no-op; overflow (already 72×36
 * without a version stamp) stamps v2 and does not ×2.
 */
export function adaptDashboardLayoutGrid(
  canvasStyle: { dashboard?: DashboardStyle } | null | undefined,
  componentData: unknown,
  dvType?: string
): LayoutGridAdaptResult {
  const canonical = {
    converted: false,
    fromVersion: LAYOUT_GRID_VERSION_CANONICAL,
    toVersion: LAYOUT_GRID_VERSION_CANONICAL,
    skippedOverflow: false
  }
  if (dvType && dvType !== 'dashboard') {
    lastAdaptResult = canonical
    return canonical
  }
  if (!canvasStyle?.dashboard) {
    lastAdaptResult = canonical
    return canonical
  }

  const dash = canvasStyle.dashboard
  const rawVersion = Number(dash.layoutGridVersion)
  const version = Number.isFinite(rawVersion) ? rawVersion : LAYOUT_GRID_VERSION_LEGACY
  if (version === LAYOUT_GRID_VERSION_CANONICAL) {
    lastAdaptResult = { ...canonical, fromVersion: version }
    return lastAdaptResult
  }

  if (exceedsLegacyGrid(componentData)) {
    // Already 72-col geometry (e.g. official template). Do not ×2.
    // Do not stamp v2 here — stamping without converting locked the
    // sizeY=19 false positive. Save path stamps v2 once coords are canonical.
    lastAdaptResult = {
      converted: false,
      fromVersion: version,
      toVersion: version,
      skippedOverflow: true
    }
    return lastAdaptResult
  }

  walkLayoutItems(componentData, scaleLegacyPcBox)
  dash.layoutGridVersion = LAYOUT_GRID_VERSION_CANONICAL
  lastAdaptResult = {
    converted: true,
    fromVersion: version,
    toVersion: LAYOUT_GRID_VERSION_CANONICAL,
    skippedOverflow: false
  }
  return lastAdaptResult
}

if (typeof window !== 'undefined') {
  const w = window as unknown as {
    __BI_dumpLayout?: () => unknown
  }
  w.__BI_dumpLayout = () => {
    const store = dvMainStoreWithOut()
    const out = {
      layoutGridVersion: store.canvasStyleData?.dashboard?.layoutGridVersion,
      pcMatrixCount: {
        x: store.pcMatrixCount?.x,
        y: store.pcMatrixCount?.y
      },
      lastAdapt: lastAdaptResult,
      rows: dumpLayoutCoords(store.componentData)
    }
    // eslint-disable-next-line no-console
    console.info('[BI layout dump]', JSON.stringify(out, null, 2))
    return out
  }
}
