/**
 * Opt-in density patch for the currently open dashboard (editor store).
 *
 * Does NOT run on load. Does NOT migrate saved assets.
 * Only rewrites the old company defaults:
 *   gapSize 8 → 4
 *   innerPadding 12 → 8 (number or uniform { top: 12 })
 *
 * Editor console: __BI_applyDensityPhase1() then 保存.
 */
import { dvMainStoreWithOut } from '@/store/modules/data-visualization/dvMain'
import { snapshotStoreWithOut } from '@/store/modules/data-visualization/snapshot'
import { ShorthandMode } from '@/Types'

const OLD_GAP = 8
const NEW_GAP = 4
const OLD_PAD = 12
const NEW_PAD = 8

function padSnapshot(padding: unknown) {
  if (padding == null) return padding
  if (typeof padding === 'number') return padding
  if (typeof padding === 'object') {
    const p = padding as {
      mode?: string
      top?: number
      right?: number
      left?: number
      bottom?: number
    }
    return { mode: p.mode, top: p.top, right: p.right, left: p.left, bottom: p.bottom }
  }
  return String(padding)
}

function patchPaddingRef(holder: { innerPadding?: unknown } | undefined): boolean {
  if (!holder || typeof holder !== 'object') return false
  const padding = holder.innerPadding
  if (typeof padding === 'number') {
    if (padding !== OLD_PAD) return false
    holder.innerPadding = {
      mode: ShorthandMode.Uniform,
      top: NEW_PAD
    }
    return true
  }
  if (!padding || typeof padding !== 'object') return false
  const p = padding as { top?: number; right?: number; left?: number; bottom?: number }
  const top = Number(p.top)
  if (top !== OLD_PAD) return false
  p.top = NEW_PAD
  if (Number(p.right) === OLD_PAD) p.right = NEW_PAD
  if (Number(p.left) === OLD_PAD) p.left = NEW_PAD
  if (Number(p.bottom) === OLD_PAD) p.bottom = NEW_PAD
  return true
}

function walkComponents(list: unknown, tally: { innerPaddingPatched: number }, samples: unknown[]) {
  if (!Array.isArray(list)) return
  for (const item of list) {
    if (!item || typeof item !== 'object') continue
    const rec = item as {
      id?: string
      component?: string
      commonBackground?: { innerPadding?: unknown }
      titleBackground?: {
        active?: { innerPadding?: unknown }
        inActive?: { innerPadding?: unknown }
      }
      propValue?: Array<{ componentData?: unknown }>
    }
    if (samples.length < 8) {
      samples.push({
        id: rec.id,
        component: rec.component,
        pad: padSnapshot(rec.commonBackground?.innerPadding)
      })
    }
    if (patchPaddingRef(rec.commonBackground)) tally.innerPaddingPatched += 1
    if (patchPaddingRef(rec.titleBackground?.active)) tally.innerPaddingPatched += 1
    if (patchPaddingRef(rec.titleBackground?.inActive)) tally.innerPaddingPatched += 1
    if (rec.component === 'DeTabs' && Array.isArray(rec.propValue)) {
      for (const tab of rec.propValue) {
        walkComponents(tab?.componentData, tally, samples)
      }
    }
  }
}

export function applyDashboardDensityPhase1() {
  const dvMainStore = dvMainStoreWithOut()
  const snapshotStore = snapshotStoreWithOut()
  const dash = dvMainStore.canvasStyleData?.dashboard
  const samples: unknown[] = []
  const tally = { innerPaddingPatched: 0 }
  const before = dumpDashboardDensity(true)

  if (dash && dash.gapSize === OLD_GAP) {
    dash.gap = 'yes'
    dash.gapMode = 'custom'
    dash.gapSize = NEW_GAP
  }

  if (patchPaddingRef(dvMainStore.canvasStyleData?.component?.chartCommonStyle)) {
    tally.innerPaddingPatched += 1
  }

  walkComponents(dvMainStore.componentData, tally, samples)

  const after = dumpDashboardDensity(true)
  snapshotStore.recordSnapshotCache('applyDashboardDensityPhase1')
  const result = {
    innerPaddingPatched: tally.innerPaddingPatched,
    before,
    after
  }
  // eslint-disable-next-line no-console
  console.info('[BI density phase1]', JSON.stringify(result, null, 2))
  return result
}

/** Read-only dump: what the store actually holds (no mutation). */
export function dumpDashboardDensity(silent = false) {
  const dvMainStore = dvMainStoreWithOut()
  const rows: unknown[] = []
  const visit = (list: unknown, lane: string) => {
    if (!Array.isArray(list)) return
    for (const item of list) {
      if (!item || typeof item !== 'object') continue
      const rec = item as {
        id?: string
        component?: string
        commonBackground?: { innerPadding?: unknown }
        propValue?: Array<{ title?: string; componentData?: unknown }>
      }
      rows.push({
        lane,
        component: rec.component,
        id: rec.id,
        pad: JSON.stringify(padSnapshot(rec.commonBackground?.innerPadding))
      })
      if (rec.component === 'DeTabs' && Array.isArray(rec.propValue)) {
        for (const tab of rec.propValue) {
          visit(tab?.componentData, `tab:${tab?.title}`)
        }
      }
    }
  }
  visit(dvMainStore.componentData, 'main')
  const out = {
    gap: dvMainStore.canvasStyleData?.dashboard?.gap,
    gapMode: dvMainStore.canvasStyleData?.dashboard?.gapMode,
    gapSize: dvMainStore.canvasStyleData?.dashboard?.gapSize,
    chartPad: JSON.stringify(
      padSnapshot(dvMainStore.canvasStyleData?.component?.chartCommonStyle?.innerPadding)
    ),
    rows
  }
  if (!silent) {
    // eslint-disable-next-line no-console
    console.info('[BI density dump]', JSON.stringify(out, null, 2))
  }
  return out
}

if (typeof window !== 'undefined') {
  const w = window as unknown as {
    __BI_applyDensityPhase1?: () => unknown
    __BI_dumpDensity?: () => unknown
  }
  w.__BI_applyDensityPhase1 = applyDashboardDensityPhase1
  w.__BI_dumpDensity = dumpDashboardDensity
}
