import crypto from 'crypto'
import fs from 'fs'
import path from 'path'
import { CONFIG, SHOT_DIR, ensureDirs } from './config.mjs'
import { recordServiceEvent } from './service-log.mjs'
import { invalidateSession, withPage } from './session.mjs'

function normalizeFilters(filters) {
  if (!filters) return []
  if (Array.isArray(filters)) {
    return filters
      .map(item => {
        if (!item) return null
        if (typeof item === 'string') return { name: item, value: item }
        return { name: String(item.name || item.field || ''), value: String(item.value ?? '') }
      })
      .filter(item => item && item.name)
  }
  return Object.entries(filters).map(([name, value]) => ({ name, value: String(value ?? '') }))
}

function summarizeGetData(payload) {
  if (!payload) return null
  const req = payload.req || {}
  const ext = req.chartExtRequest || {}
  const body = payload.body || {}
  const data = body.data || body
  const tableRow = data?.tableRow || data?.data || data?.sourceData || null
  let rowCount = null
  if (Array.isArray(tableRow)) rowCount = tableRow.length
  else if (tableRow && Array.isArray(tableRow.data)) rowCount = tableRow.data.length
  const dataHash = crypto
    .createHash('sha256')
    .update(JSON.stringify(data || body))
    .digest('hex')
    .slice(0, 16)
  return {
    httpStatus: payload.status,
    code: body.code,
    msg: body.msg || null,
    title: req.title || null,
    type: req.type || null,
    filter: ext.filter || [],
    outerParamsFilters: ext.outerParamsFilters || [],
    rowCount,
    dataHash
  }
}

class ChartDataCollector {
  constructor(page, chartId) {
    this.page = page
    this.chartId = String(chartId)
    this.hits = []
    this.onResponse = async response => {
      try {
        if (!response.url().includes('chartData/getData')) return
        if (response.request().method() !== 'POST') return
        let req = {}
        try {
          req = JSON.parse(response.request().postData() || '{}')
        } catch {
          req = {}
        }
        this.hits.push({ id: req.id, url: response.url(), status: response.status(), title: req.title })
        if (String(req.id) !== this.chartId) return
        let body = null
        try {
          body = await response.json()
        } catch {
          body = null
        }
        this.last = { status: response.status(), req, body, at: Date.now() }
      } catch {
        /* ignore collector errors */
      }
    }
    page.on('response', this.onResponse)
  }

  seenIds() {
    return this.hits.map(item => `${item.id}:${item.status}`)
  }

  async waitNext(timeoutMs, afterTs = 0) {
    const start = Date.now()
    while (Date.now() - start < timeoutMs) {
      if (this.last && this.last.at > afterTs) return this.last
      await this.page.waitForTimeout(200)
    }
    throw new Error(
      `Timed out waiting for chartData/getData of chartId=${this.chartId}; seen=${JSON.stringify(this.seenIds())}`
    )
  }

  stop() {
    this.page.off('response', this.onResponse)
  }
}

async function discoverQueryFields(page) {
  return page.evaluate(() => {
    const fields = []
    const roots = [...document.querySelectorAll('.v-query')]
    for (const el of roots) {
      const rect = el.getBoundingClientRect()
      if (rect.width < 2 || rect.height < 2) continue
      let inst = el.__vueParentComponent
      while (inst) {
        const propValue = inst.props?.element?.propValue
        if (Array.isArray(propValue)) {
          for (const item of propValue) {
            if (item?.visible === false) continue
            fields.push({
              id: item.id,
              name: item.name,
              displayType: item.displayType,
              multiple: Boolean(item.multiple),
              selectValue: item.selectValue ?? null,
              required: Boolean(item.required)
            })
          }
          break
        }
        inst = inst.parent
      }
    }
    if (fields.length) return fields
    return [...document.querySelectorAll('.query-item')].map(item => {
      const label = (item.querySelector('.label-wrapper-text')?.innerText || '').trim()
      return { name: label, label, selectValue: null }
    })
  })
}

async function applyFiltersViaVue(page, filters) {
  return page.evaluate(filters => {
    const applied = []
    const roots = [...document.querySelectorAll('.v-query')]
    for (const el of roots) {
      const rect = el.getBoundingClientRect()
      if (rect.width < 2 || rect.height < 2) continue
      let inst = el.__vueParentComponent
      while (inst) {
        const propValue = inst.props?.element?.propValue
        if (Array.isArray(propValue)) {
          for (const filter of filters) {
            const field = propValue.find(item => {
              const name = item?.name || ''
              return name.includes(filter.name) || filter.name.includes(name)
            })
            if (!field) continue
            field.defaultValueCheck = true
            if (field.multiple || Number(field.displayType) === 7) {
              field.selectValue = [filter.value]
              field.defaultValue = [filter.value]
            } else {
              field.selectValue = filter.value
              field.defaultValue = filter.value
            }
            applied.push({ name: field.name, value: filter.value, id: field.id })
          }
          const selectRoots = [...el.querySelectorAll('.query-select')]
          for (const selectEl of selectRoots) {
            let child = selectEl.__vueParentComponent
            while (child && child !== inst) {
              if (typeof child.setupState?.handleValueChange === 'function') {
                try {
                  child.setupState.handleValueChange()
                } catch {
                  /* handleValueChange may close over unexposed helpers; 查询 click is the fallback */
                }
                break
              }
              child = child.parent
            }
          }
          break
        }
        inst = inst.parent
      }
    }
    const btn = [...document.querySelectorAll('.query-button button, .query-button .ed-button, button')].find(el =>
      /查询|搜索/.test(el.textContent || '')
    )
    if (btn) btn.click()
    return { applied, clickedQuery: Boolean(btn) }
  }, filters)
}

async function triggerChartQuery(page, chartId) {
  return page.evaluate(id => {
    const wrap = document.getElementById(`wrapper-outer-id-${id}`)
    if (!wrap) return { ok: false, reason: 'wrapper-missing' }
    const nodes = [wrap, ...wrap.querySelectorAll('*')]
    for (const el of nodes) {
      const inst = el.__vueParentComponent
      const st = inst?.setupState
      if (st && typeof st.queryDataFromSelect === 'function') {
        st.queryDataFromSelect(false)
        return { ok: true, via: 'queryDataFromSelect' }
      }
      if (st && typeof st.queryData === 'function') {
        st.queryData(false)
        return { ok: true, via: 'queryData' }
      }
    }
    return { ok: false, reason: 'chart-query-not-found' }
  }, chartId)
}

async function applyFilters(page, filters, chartId) {
  const available = await discoverQueryFields(page)
  if (!filters.length) {
    return { applied: [], available, mode: 'default' }
  }

  const vueResult = await applyFiltersViaVue(page, filters)
  if (vueResult.applied.length) {
    if (!vueResult.clickedQuery) {
      const queryBtn = page.getByRole('button', { name: /查询|搜索/ })
      if (await queryBtn.count()) {
        await queryBtn.first().click({ force: true })
      }
    }
    const triggered = await triggerChartQuery(page, chartId)
    return { applied: vueResult.applied, available, mode: 'vue-selectValue', triggered }
  }

  const items = page.locator('.query-item').locator('visible=true')
  const applied = []
  for (const filter of filters) {
    const count = await items.count()
    let matchedIndex = -1
    for (let i = 0; i < count; i++) {
      const text = ((await items.nth(i).locator('.label-wrapper-text').innerText().catch(() => '')) || '').trim()
      if (text.includes(filter.name) || filter.name.includes(text)) {
        matchedIndex = i
        break
      }
    }
    if (matchedIndex < 0) {
      throw new Error(`Filter field "${filter.name}" not found. Available: ${JSON.stringify(available)}`)
    }
    const item = items.nth(matchedIndex)
    await item.locator('.query-select').click({ force: true })
    await page.waitForTimeout(800)
    const dropdown = page.locator('.ed-select-dropdown:visible, .el-select-dropdown:visible, .ed-popper:visible')
    const option = dropdown.locator('.ed-select-dropdown__item, .ed-option, .el-select-dropdown__item').filter({
      hasText: filter.value
    })
    if (!(await option.count())) {
      const shown = await dropdown.locator('.ed-select-dropdown__item, .ed-option').allInnerTexts()
      throw new Error(
        `Filter value "${filter.value}" not found for "${filter.name}". Options: ${JSON.stringify(shown.slice(0, 40))}`
      )
    }
    await option.first().click({ force: true })
    applied.push({ name: filter.name, value: filter.value })
    await page.waitForTimeout(200)
  }

  const queryBtn = page.getByRole('button', { name: /查询|搜索/ })
  if (await queryBtn.count()) {
    await queryBtn.first().click({ force: true })
  }
  return { applied, available, mode: 'ui' }
}

async function waitUntilChartReady(page, chartId) {
  const wrapperSel = `#wrapper-outer-id-${chartId}`
  await page.waitForSelector(wrapperSel, { timeout: CONFIG.chartWaitMs })
  const s2 = page.locator(`${wrapperSel} .antv-s2-container, ${wrapperSel} .s2-container`)
  if (await s2.count()) {
    await s2.first().waitFor({ state: 'visible', timeout: 30_000 })
  }
  await page.waitForFunction(
    id => {
      const wrap = document.getElementById(`wrapper-outer-id-${id}`)
      if (!wrap) return false
      return !wrap.querySelector('.ed-loading-mask, .el-loading-mask')
    },
    chartId,
    { timeout: 30_000 }
  )
  await page.waitForTimeout(600)
}

async function inspectClip(page, chartId) {
  return page.evaluate(id => {
    const wrap = document.getElementById(`wrapper-outer-id-${id}`)
    if (!wrap) return { found: false }
    const rect = wrap.getBoundingClientRect()
    const s2 = wrap.querySelector('.antv-s2-container, .s2-container, canvas')
    const s2Rect = s2 ? s2.getBoundingClientRect() : null
    const tab = wrap.closest('.el-tabs, .ed-tabs, .de-tabs')
    const queryBar = wrap.querySelector('.v-query, .query-fields-container')
    const nearbyQuery = wrap.parentElement?.querySelector?.('.v-query, .query-fields-container')
    const vScroll = wrap.querySelector('[class*="vertical-scroll"], .antv-s2-scrollbar-thumb-vertical')
    const hScroll = wrap.querySelector('[class*="horizontal-scroll"], .antv-s2-scrollbar-thumb-horizontal')
    return {
      found: true,
      wrapper: { width: Math.round(rect.width), height: Math.round(rect.height), top: Math.round(rect.top), left: Math.round(rect.left) },
      s2: s2Rect
        ? {
            width: Math.round(s2Rect.width),
            height: Math.round(s2Rect.height),
            scrollWidth: s2.scrollWidth,
            scrollHeight: s2.scrollHeight,
            clientWidth: s2.clientWidth,
            clientHeight: s2.clientHeight
          }
        : null,
      croppedX: Boolean(hScroll) || (s2 ? s2.scrollWidth > s2.clientWidth + 4 : false),
      croppedY: Boolean(vScroll) || (s2 ? s2.scrollHeight > s2.clientHeight + 4 : false),
      hasVerticalScrollbar: Boolean(vScroll),
      hasHorizontalScrollbar: Boolean(hScroll),
      hasQueryBarInsideCard: Boolean(queryBar),
      hasNearbyQueryBar: Boolean(nearbyQuery),
      insideTab: Boolean(tab),
      sampleTexts: [...wrap.querySelectorAll('.antv-s2-cell, .s2-cell, td, .antv-s2-data-cell')]
        .slice(0, 24)
        .map(el => (el.innerText || '').trim())
        .filter(Boolean)
    }
  }, chartId)
}

function isAuthFailure(err) {
  const msg = String(err?.message || '')
  return err?.code === 'SCREENSHOT_AUTH' || /screenshot session|de_v2_user\.token|\/login/i.test(msg)
}

export async function captureCard(input) {
  try {
    return await captureCardOnce(input)
  } catch (err) {
    if (!input?._authRetried && isAuthFailure(err)) {
      recordServiceEvent({ area: 'screenshot', message: `session expired; re-login once: ${err.message}` })
      await invalidateSession()
      return captureCardOnce({ ...input, _authRetried: true })
    }
    recordServiceEvent({ area: 'screenshot', message: err.message, code: err.code || null })
    throw err
  }
}

async function captureCardOnce(input) {
  ensureDirs()
  const dashboardId = String(input.dashboardId || '')
  const chartId = String(input.chartId || '')
  if (!dashboardId || !chartId) {
    throw new Error('dashboardId and chartId are required')
  }
  const filters = normalizeFilters(input.filters)
  const started = Date.now()
  const pngPath = path.join(SHOT_DIR, `${input.taskId || crypto.randomUUID()}.png`)

  return withPage(async page => {
    page.setDefaultTimeout(CONFIG.chartWaitMs)
    const collector = new ChartDataCollector(page, chartId)
    try {
      const url = `${CONFIG.baseUrl}/#/panel/index?dvId=${encodeURIComponent(dashboardId)}`
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60_000 })
      if (/\/login/.test(page.url())) {
        const expired = new Error('screenshot session is not logged in')
        expired.code = 'SCREENSHOT_AUTH'
        throw expired
      }
      let getData
      try {
        getData = await collector.waitNext(CONFIG.chartWaitMs)
      } catch (err) {
        await page.screenshot({ path: path.join(SHOT_DIR, `debug-load-${chartId}.png`), fullPage: true }).catch(() => {})
        throw err
      }
      if (getData.status !== 200 || (getData.body && getData.body.code && getData.body.code !== 0)) {
        throw new Error(
          `chartData/getData failed status=${getData.status} code=${getData.body?.code} msg=${getData.body?.msg || ''}`
        )
      }

      await waitUntilChartReady(page, chartId)
      const queryFields = await discoverQueryFields(page)

      let filterResult = { applied: [], available: queryFields, mode: 'default' }
      if (filters.length) {
        const before = Date.now()
        try {
          filterResult = await applyFilters(page, filters, chartId)
          getData = await collector.waitNext(CONFIG.chartWaitMs, before)
        } catch (err) {
          await page.screenshot({ path: path.join(SHOT_DIR, `debug-filter-${chartId}.png`), fullPage: true }).catch(() => {})
          throw err
        }
        if (getData.status !== 200) {
          throw new Error(`Filtered chartData/getData failed status=${getData.status}`)
        }
        await waitUntilChartReady(page, chartId)
      }

    await page.addStyleTag({
      content: `
        .bar-main, .bar-base-icon, .canvas-opt-bar, .preview-icon-area,
        .ed-aside, .dv-aside, .left-sidebar { visibility: hidden !important; }
      `
    })

    const wrapper = page.locator(`#wrapper-outer-id-${chartId}`)
    if (!(await wrapper.count())) {
      throw new Error(`Card wrapper #wrapper-outer-id-${chartId} not found`)
    }
    await wrapper.screenshot({ path: pngPath, type: 'png' })
    const clip = await inspectClip(page, chartId)
    const stat = fs.statSync(pngPath)
    const pngHash = crypto.createHash('sha256').update(fs.readFileSync(pngPath)).digest('hex').slice(0, 16)

    return {
      pngPath,
      meta: {
        dashboardId,
        chartId,
        durationMs: Date.now() - started,
        auth: 'ui-login-memory-session',
        url,
        pngBytes: stat.size,
        pngHash,
        queryFields: filterResult.available,
        filtersRequested: filters,
        filtersApplied: filterResult.applied,
        filterMode: filterResult.mode,
        getData: summarizeGetData(getData),
        clip
      }
    }
    } finally {
      collector.stop()
    }
  })
}
