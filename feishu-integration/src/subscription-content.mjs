import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { CONFIG, SHOT_DIR, ensureDirs } from './config.mjs'
import { withPage, invalidateSession } from './session.mjs'
import { applyFiltersViaVue, normalizeFilters } from './capture.mjs'

export function contentDefinition(sub) {
  const content = sub.content_json ? JSON.parse(sub.content_json) : sub.content
  if (!content) return contentDefinition({ content: { version: 1, mode: 'CARDS', chartIds: [String(sub.chart_id || sub.chartId || '')] } })
  if (!['CARDS', 'DASHBOARD'].includes(content.mode)) throw new Error('无效的订阅内容类型')
  const ids = (content.chartIds || []).map(String)
  if (content.mode === 'CARDS' && (!ids.length || ids.length > 12 || new Set(ids).size !== ids.length))
    throw new Error('请选择 1 至 12 张不同的卡片')
  if (ids.some(id => !/^\d+$/.test(String(id)))) throw new Error('无效的卡片标识')
  return { ...content, chartIds: ids.map(String) }
}

export function resolveFilters(items, reference = Date.now()) {
  const date = new Date(Number(reference) + 8 * 3600000)
  const today = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate())
  const day = value => new Date(value).toISOString().slice(0, 10)
  if (!Array.isArray(items) || items.some(item => !item || !item.name)) throw new Error('筛选条件无效')
  return items.map(source => {
    const item = normalizeFilters([source])[0]
    const rule = source.dateRule
    if (!rule || rule === 'fixed') return item
    let start, end
    if (rule === 'yesterday') start = end = today - 86400000
    else if (rule === 'last7') { start = today - 7 * 86400000; end = today - 86400000 }
    else if (rule === 'monthToYesterday') {
      start = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1); end = today - 86400000
      if (end < start) start = Date.UTC(date.getUTCFullYear(), date.getUTCMonth() - 1, 1)
    } else throw new Error('不支持的相对日期规则')
    return { ...item, value: [day(start), day(end)] }
  })
}

export function buildSubscriptionMessage({ title, body, images, link }) {
  const rows = []
  if (body) rows.push([{ tag: 'text', text: body }])
  for (const image of images) {
    if (!image.imageKey) throw new Error('截图尚未上传完成')
    rows.push([{ tag: 'text', text: image.name || '卡片' }])
    rows.push([{ tag: 'img', image_key: image.imageKey }])
  }
  if (link) rows.push([{ tag: 'a', text: '打开仪表板', href: link }])
  const content = { zh_cn: { title: title || '数据订阅', content: rows } }
  if (Buffer.byteLength(JSON.stringify(content)) > 28000) throw new Error('消息内容过长，请减少正文或卡片数量')
  return content
}

async function revealCard(page, id) {
  const target = page.locator(`#wrapper-outer-id-${id}`)
  if (await target.isVisible()) return target
  const visited = new Set()
  for (let attempt = 0; attempt < 80; attempt++) {
    const tabs = page.locator('[role="tab"]:visible')
    let clicked = false
    for (let i = 0; i < await tabs.count(); i++) {
      const tab = tabs.nth(i)
      const key = await tab.evaluate(el => el.id || `${el.parentElement?.id}:${el.textContent}`)
      if (visited.has(key)) continue
      visited.add(key); await tab.click(); clicked = true
      await page.waitForTimeout(100)
      if (await target.isVisible()) return target
      break
    }
    if (!clicked) break
  }
  throw new Error('无法打开选中的卡片，请检查卡片是否仍在仪表板中')
}

export async function authorizeSubscription(id) {
  if (!/^\d+$/.test(String(id))) throw new Error('无效的订阅标识')
  for (let attempt = 0; attempt < 2; attempt++) {
    const result = await withPage(async page => {
      await page.goto(CONFIG.baseUrl, { waitUntil: 'domcontentloaded' })
      return page.evaluate(async id => {
        const prefix = location.pathname.match(/^\/([^/]+)/)?.[1]
        const raw = localStorage.getItem((prefix ? prefix + '_' : 'de_v2_') + 'user.token')
        let token
        try { const cached = JSON.parse(raw); token = cached?.v ?? cached } catch { token = raw }
        const response = await fetch('de2api/company/bi/feishu/content/execution/' + id, { headers: { 'X-DE-TOKEN': token || '' } })
        const body = await response.json().catch(() => ({}))
        return { ok: response.ok && body.code === 0 && body.data?.allowed === true, code: body.code || response.status, message: body.msg }
      }, String(id))
    })
    if (result.ok) return
    if (attempt === 0 && [401, 40101].includes(Number(result.code))) { await invalidateSession(); continue }
    throw new Error(result.message || '订阅权限校验失败，请检查截图账号与订阅配置者权限')
  }
}

export async function captureSubscription(input) {
  ensureDirs()
  const content = contentDefinition(input)
  const dashboardId = String(input.dashboardId || input.dashboard_id)
  if (!/^\d+$/.test(dashboardId)) throw new Error('请选择仪表板')
  const filters = resolveFilters(input.filters || [], input.reference || Date.now())
  const images = []
  const targets = content.mode === 'DASHBOARD' ? [null] : content.chartIds
  try {
  for (const chartId of targets) {
    const shot = await withPage(async page => {
      await page.setViewportSize({ width: 1680, height: 1050 })
      const pending = new Set(), failures = []
      let responses = 0, lastResponse = Date.now()
      const isData = req => req.url().includes('/chartData/getData')
      page.on('request', req => { if (isData(req)) pending.add(req) })
      page.on('requestfailed', req => { if (isData(req)) { pending.delete(req); failures.push('图表请求失败') } })
      page.on('response', async res => {
        if (!isData(res.request())) return
        try { const body = await res.json(); if (!res.ok() || (body.code && Number(body.code) !== 0)) failures.push(body.msg || '图表加载失败') }
        catch { failures.push('图表响应无效') }
        finally { responses++; pending.delete(res.request()); lastResponse = Date.now() }
      })
      const ready = async (after = -1) => {
        const deadline = Date.now() + CONFIG.chartWaitMs
        while (Date.now() < deadline) {
          if (failures.length) throw new Error(failures[0])
          if (responses > after && responses > 0 && !pending.size && Date.now() - lastResponse > 800) {
            await page.evaluate(() => document.fonts.ready)
            return
          }
          await page.waitForTimeout(100)
        }
        throw new Error('图表加载超时，请缩小筛选范围后重试')
      }
      await page.goto(`${CONFIG.baseUrl}/#/panel/index?dvId=${encodeURIComponent(dashboardId)}`, { waitUntil: 'domcontentloaded' })
      await page.waitForSelector('.canvas-container', { timeout: CONFIG.chartWaitMs })
      await ready()
      const wrapper = chartId ? await revealCard(page, chartId) : page.locator('.canvas-container').first()
      await ready()
      if (filters.length) {
        const before = responses
        const applied = await applyFiltersViaVue(page, filters)
        if (applied.applied.length !== filters.length) throw new Error('部分筛选条件无法应用，请重新选择筛选条件')
        if (!applied.clickedQuery) {
          // Existing query components expose the same query action used by their toolbar.
          await page.evaluate(() => {
            for (const el of document.querySelectorAll('.v-query')) {
              for (let instance = el.__vueParentComponent; instance; instance = instance.parent) {
                if (typeof instance.setupState?.queryData === 'function') { instance.setupState.queryData(); break }
              }
            }
          })
        }
        lastResponse = Date.now(); await ready(before)
      }
      const name = chartId ? content.cardNames?.find(c => c.id === chartId)?.name || await wrapper.locator('.chart-title').first().innerText().catch(() => '卡片') : content.dashboardName || '仪表板'
      await page.addStyleTag({ content: '.bar-main,.bar-base-icon,.canvas-opt-bar,.preview-icon-area,.ed-tooltip__popper{visibility:hidden!important}' })
      if (chartId) {
        await wrapper.evaluate(el => {
          const styles = getComputedStyle(el)
          for (const key of styles) if (key.startsWith('--')) el.style.setProperty(key, styles.getPropertyValue(key))
          document.body.append(el)
          Object.assign(el.style, { position: 'fixed', left: '0', top: '0', width: '1680px', height: '980px', transform: 'none', zIndex: '9999', background: '#fff' })
          window.dispatchEvent(new Event('resize'))
        })
      } else {
        await wrapper.evaluate(el => {
          for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
            parent.style.overflow = 'visible'; parent.style.maxHeight = 'none'; parent.style.height = 'auto'
          }
        })
      }
      await page.waitForTimeout(500)
      await ready()
      const box = await wrapper.boundingBox()
      if (!box || box.height > 16000) throw new Error('仪表板截图过长，请改用指定卡片订阅')
      const pngPath = path.join(SHOT_DIR, `subscription-${crypto.randomUUID()}.png`)
      await wrapper.screenshot({ path: pngPath, type: 'png' })
      if (fs.statSync(pngPath).size > 9 * 1024 * 1024) { fs.unlinkSync(pngPath); throw new Error('截图过大，请减少所选内容') }
      return { chartId, name, pngPath, status: 'CAPTURED' }
    })
    images.push(shot)
  }
  return { images, filters }
  } catch (error) {
    for (const image of images) fs.rmSync(image.pngPath, { force: true })
    throw error
  }
}
