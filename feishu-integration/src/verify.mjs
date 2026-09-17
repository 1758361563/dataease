import fs from 'fs'
import path from 'path'
import { CONFIG, TMP_DIR, ensureDirs, requireScreenshotPassword } from './config.mjs'
import { captureCard } from './capture.mjs'
import { closeBrowser } from './session.mjs'

function pickCompareFilter(queryFields) {
  const names = (queryFields || []).map(item => item.name || item.label)
  const channel = names.find(name => /渠道/.test(name || ''))
  if (channel) {
    return {
      defaultLabel: '全渠道/默认',
      filter: { name: channel, value: '京东' }
    }
  }
  const series = names.find(name => /系列/.test(name || ''))
  if (series) {
    return {
      defaultLabel: '全系列/默认',
      filter: { name: series, value: '凉白开' }
    }
  }
  const zero = names.find(name => /零元/.test(name || ''))
  if (zero) {
    return {
      defaultLabel: '默认',
      filter: { name: zero, value: '是' }
    }
  }
  return null
}

async function main() {
  requireScreenshotPassword()
  ensureDirs()
  const dashboardId = CONFIG.defaultDashboardId
  const chartId = CONFIG.defaultChartId
  const report = {
    dashboardId,
    chartId,
    startedAt: new Date().toISOString(),
    defaultShot: null,
    filteredShot: null,
    comparison: null,
    error: null
  }

  try {
    console.log('[verify] capturing default / 全渠道')
    const defaultShot = await captureCard({ dashboardId, chartId, filters: null, taskId: 'verify-default' })
    report.defaultShot = defaultShot.meta
    fs.copyFileSync(defaultShot.pngPath, path.join(TMP_DIR, 'verify_default.png'))

    const chosen = pickCompareFilter(defaultShot.meta.queryFields)
    if (!chosen) {
      report.comparison = {
        filterExperiment: 'SKIPPED',
        reason: 'No usable query field on this card',
        availableFields: defaultShot.meta.queryFields
      }
    } else {
      console.log(`[verify] capturing filtered ${chosen.filter.name}=${chosen.filter.value}`)
      const filteredShot = await captureCard({
        dashboardId,
        chartId,
        filters: [chosen.filter],
        taskId: 'verify-filtered'
      })
      report.filteredShot = filteredShot.meta
      fs.copyFileSync(filteredShot.pngPath, path.join(TMP_DIR, 'verify_filtered.png'))
      const defaultHash = defaultShot.meta.getData?.dataHash
      const filteredHash = filteredShot.meta.getData?.dataHash
      const pngChanged = defaultShot.meta.pngHash !== filteredShot.meta.pngHash
      const dataChanged = Boolean(defaultHash && filteredHash && defaultHash !== filteredHash)
      report.comparison = {
        filterExperiment: dataChanged || pngChanged ? 'PASS' : 'FAIL',
        defaultLabel: chosen.defaultLabel,
        requestedFilter: chosen.filter,
        pngChanged,
        dataChanged,
        defaultPngHash: defaultShot.meta.pngHash,
        filteredPngHash: filteredShot.meta.pngHash,
        defaultDataHash: defaultHash,
        filteredDataHash: filteredHash,
        defaultFilter: defaultShot.meta.getData?.filter,
        filteredFilter: filteredShot.meta.getData?.filter,
        defaultClip: defaultShot.meta.clip,
        filteredClip: filteredShot.meta.clip
      }
    }
  } catch (err) {
    report.error = err.message || String(err)
  } finally {
    report.finishedAt = new Date().toISOString()
    const out = path.join(TMP_DIR, 'verify_1b.json')
    fs.writeFileSync(out, JSON.stringify(report, null, 2))
    console.log(`[verify] wrote ${out}`)
    console.log(JSON.stringify(report.comparison || { error: report.error }, null, 2))
    await closeBrowser()
  }

  if (report.error) process.exit(1)
  if (report.comparison?.filterExperiment === 'FAIL') process.exit(2)
}

main()
