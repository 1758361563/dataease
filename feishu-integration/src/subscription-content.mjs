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

export function dashboardDetailLink(baseUrl, dashboardId) {
  if (!String(baseUrl || '').trim()) return null
  if (!/^\d+$/.test(String(dashboardId))) throw new Error('无效的仪表板标识')
  const url = new URL(String(baseUrl).trim())
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) throw new Error('BI 访问地址无效')
  url.search = ''
  url.pathname = url.pathname.replace(/\/+$/, '') + '/'
  url.hash = '/panel/index?dvId=' + dashboardId
  return url.href
}

export function buildSubscriptionMessage({ title, body, images, link }) {
  const rows = []
  const text = content => ({ tag: 'div', text: { tag: 'plain_text', content } })
  if (body) rows.push(text(body))
  for (const image of images) {
    if (!image.imageKey) throw new Error('截图尚未上传完成')
    rows.push(text(image.name || '卡片'))
    rows.push({ tag: 'img', img_key: image.imageKey, alt: { tag: 'plain_text', content: image.name || '卡片' }, mode: 'fit_horizontal' })
  }
  if (link) rows.push({ tag: 'hr' }, { tag: 'action', actions: [
    { tag: 'button', text: { tag: 'plain_text', content: '查看详情' }, type: 'default', url: link }
  ] })
  const content = { config: { wide_screen_mode: true }, header: { title: { tag: 'plain_text', content: title || '数据订阅' } }, elements: rows }
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

// Runs inside the authenticated browser; match web-storage-cache's nested JSON format.
export async function requestExecutionPermission(id) {
  const pathname = location.pathname.replace('mobile.html', '')
  const prefix = pathname.match(/^\/([^/]+)/)?.[1]
  const raw = localStorage.getItem((prefix ? prefix + '_' : 'de_v2_') + 'user.token')
  let token = raw
  try {
    const cached = JSON.parse(raw)
    token = cached && typeof cached === 'object' ? cached.v : cached
    if (typeof token === 'string') {
      try { token = JSON.parse(token) } catch { /* Plain token from older cache formats. */ }
    }
  } catch { /* Plain token from older cache formats. */ }
  if (typeof token !== 'string' || !token.trim()) {
    return { ok: false, code: 401, message: '截图账号登录已失效，请检查截图账号配置' }
  }
  const endpoint = '/company/bi/feishu/content/execution/' + id
  const options = { headers: { 'X-DE-TOKEN': token } }
  let response = await fetch('de2api' + endpoint, options)
  const isHtml = res => (res.headers.get('content-type') || '').includes('text/html')
  // Vite development proxies /api; production and preview serve /de2api.
  // Retry only SPA fallback HTML, never a JSON authorization denial.
  if (response.ok && isHtml(response)) response = await fetch('api' + endpoint, options)
  if (isHtml(response)) {
    return { ok: false, code: 'API_ROUTE_MISMATCH', message: '订阅权限接口返回了网页，请检查 BI 访问地址的 API 代理配置' }
  }
  const body = await response.json().catch(() => ({}))
  const code = body.code ?? response.status
  const ok = response.ok && body.code === 0 && body.data?.allowed === true
  let message = body.msg
  if (!ok && !message) {
    if ([401, 40101, 20001].includes(Number(code))) message = '截图账号登录已失效，请检查截图账号配置'
    else if (response.status === 404) message = '订阅权限校验接口不存在，请更新本地后端运行包'
    else if (response.status === 403) message = '截图账号无订阅执行权限，请检查账号权限'
    else message = `订阅权限校验失败（HTTP ${response.status}，状态码 ${code}）`
  }
  return { ok, code, message }
}

export async function authorizeSubscription(id) {
  if (!/^\d+$/.test(String(id))) throw new Error('无效的订阅标识')
  for (let attempt = 0; attempt < 2; attempt++) {
    const result = await withPage(async page => {
      await page.goto(CONFIG.baseUrl, { waitUntil: 'domcontentloaded' })
      return page.evaluate(requestExecutionPermission, String(id))
    })
    if (result.ok) return
    if (attempt === 0 && [401, 40101, 20001].includes(Number(result.code))) { await invalidateSession(); continue }
    throw new Error(result.message || '订阅权限校验失败，请检查截图账号与订阅配置者权限')
  }
}

export function previewFailure(stage, error) {
  const message = String(error?.message || '')
  const safe = /^(图表加载超时，请缩小筛选范围后重试|图表请求失败|图表响应无效|无法打开选中的卡片，请检查卡片是否仍在仪表板中|部分筛选条件无法应用，请重新选择筛选条件|仪表板截图过长，请改用指定卡片订阅|截图过大，请减少所选内容|截图区域不可见，请检查仪表板布局)$/
  let reason = safe.test(message) ? message : '执行失败，请查看飞书执行器诊断日志'
  if (error?.name === 'TimeoutError' || /Timeout \d+ms exceeded/i.test(message)) reason = '等待超时，请检查页面是否能正常加载'
  else if (/ERR_CONNECTION_REFUSED|ERR_NAME_NOT_RESOLVED|ENOTFOUND/.test(message)) reason = '无法访问 BI 页面，请检查截图访问地址与网络'
  else if (/Executable doesn't exist/.test(message)) reason = '截图浏览器未安装，请重新构建本地运行环境'
  return '预览失败（' + stage + '）：' + reason
}

export async function captureSubscription(input) {
  ensureDirs()
  const content = contentDefinition(input)
  const dashboardId = String(input.dashboardId || input.dashboard_id)
  if (!/^\d+$/.test(dashboardId)) throw new Error('请选择仪表板')
  const filters = resolveFilters(input.filters || [], input.reference || Date.now())
  const images = []
  let stage = '登录截图账号'
  const targets = content.mode === 'DASHBOARD' ? [null] : content.chartIds
  try {
    await withPage(async page => {
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
      stage = '打开仪表板'
      await page.goto(`${CONFIG.baseUrl}/#/panel/index?dvId=${encodeURIComponent(dashboardId)}`, { waitUntil: 'domcontentloaded' })
      stage = '等待仪表板画布'
      await page.waitForSelector('.canvas-container', { timeout: CONFIG.chartWaitMs })
      stage = '加载图表数据'
      await ready()
      await page.addStyleTag({ content: '.bar-main,.bar-base-icon,.canvas-opt-bar,.preview-icon-area,.ed-tooltip__popper{visibility:hidden!important}' })
      for (const chartId of targets) {
        stage = chartId ? '打开所选卡片' : '定位仪表板'
        const wrapper = chartId ? await revealCard(page, chartId) : page.locator('.canvas-container').first()
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
        await ready()
        if (filters.length) {
          stage = '应用筛选条件'
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
        stage = '读取卡片标题'
        const name = chartId ? content.cardNames?.find(c => c.id === chartId)?.name || await wrapper.locator('.chart-title').first().innerText({ timeout: 1500 }).catch(() => '卡片') : content.dashboardName || '仪表板'
        stage = '调整截图布局'
        await prepareScreenshot(wrapper, Boolean(chartId))
        // Older frontends still resize on a 500 ms debounce.
        await page.waitForTimeout(550)
        await ready()
        const box = await wrapper.boundingBox()
        if (!box || box.width < 1 || box.height < 1 || !(await wrapper.isVisible())) throw new Error('截图区域不可见，请检查仪表板布局')
        if (box.height > 16000) throw new Error('仪表板截图过长，请改用指定卡片订阅')
        stage = '生成图片'
        const parts = await captureScreenshotParts(wrapper)
        for (const [index, part] of parts.entries()) {
          const pngPath = path.join(SHOT_DIR, `subscription-${crypto.randomUUID()}.png`)
          const image = { chartId, name: parts.length > 1 ? `${name}（${index + 1}/${parts.length}）` : name, pngPath, status: 'CAPTURED' }
          images.push(image)
          fs.writeFileSync(pngPath, part)
          if (fs.statSync(pngPath).size > 9 * 1024 * 1024) throw new Error('截图过大，请减少所选内容')
        }
        await restoreScreenshot(wrapper)
      }
    })
  return { images, filters }
  } catch (error) {
    for (const image of images) fs.rmSync(image.pngPath, { force: true })
    error.previewStage = stage
    error.publicMessage = previewFailure(stage, error)
    throw error
  }
}

export function screenshotSegments(height) {
  if (!Number.isFinite(height) || height <= 0 || height > 16000) throw new Error('截图高度无效')
  const parts = []
  for (let y = 0; y < height; y += 2000) parts.push({ y, height: Math.min(2000, height - y) })
  return parts
}

export async function captureScreenshotParts(wrapper) {
  // Locator screenshots handle fixed elements, nested scrollports and document offsets.
  // Split the resulting pixels, never the live page's potentially moving coordinates.
  const png = await wrapper.screenshot({ type: 'png', scale: 'device', timeout: 30000 })
  const height = png.readUInt32BE(20)
  const width = png.readUInt32BE(16)
  const maxHeight = 4000
  if (height <= maxHeight) return [png]
  const chunks = await wrapper.page().evaluate(async ({ data, width, height, maxHeight }) => {
    const img = new Image()
    img.src = 'data:image/png;base64,' + data
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = width
    const chunks = []
    for (let y = 0; y < height; y += maxHeight) {
      canvas.height = Math.min(maxHeight, height - y)
      canvas.getContext('2d').drawImage(img, 0, y, width, canvas.height, 0, 0, width, canvas.height)
      chunks.push(canvas.toDataURL('image/png').split(',')[1])
    }
    return chunks
  }, { data: png.toString('base64'), width, height, maxHeight })
  return chunks.map(chunk => Buffer.from(chunk, 'base64'))
}

export async function prepareScreenshot(wrapper, card) {
  await wrapper.evaluate(async (el, card) => {
    if (!el.__subscriptionLayout) {
      const saved = []
      for (let node = el; node && node !== document.body; node = node.parentElement) {
        saved.push({ node, style: node.getAttribute('style'), scrollTop: node.scrollTop, scrollLeft: node.scrollLeft })
      }
      el.__subscriptionLayout = saved
    }
    const rect = el.getBoundingClientRect()
    if (rect.width < 1 || rect.height < 1) throw new Error('截图区域不可见，请检查仪表板布局')
    if (card) {
      // Keep the live Vue node in its parent: tab/layout ownership and inherited
      // theme styles must survive the screenshot resize.
      for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        parent.style.setProperty('transform', 'none', 'important')
        parent.style.setProperty('overflow', 'visible', 'important')
      }
      for (const [key, value] of Object.entries({ position: 'fixed', left: '0', top: '0', width: '1680px', height: '980px', transform: 'none', 'z-index': '9999', background: '#fff' })) {
        el.style.setProperty(key, value, 'important')
      }
    } else {
      const height = Math.ceil(Math.max(rect.height, el.scrollHeight))
      // A flex child with percentage height collapses if ancestor heights become
      // auto. Expand only this scrollport; preserve the containing layout.
      el.style.setProperty('flex', 'none', 'important')
      el.style.setProperty('height', height + 'px', 'important')
      el.style.setProperty('min-height', height + 'px', 'important')
      el.style.setProperty('max-height', 'none', 'important')
      el.style.setProperty('overflow', 'visible', 'important')
      el.scrollTop = 0
      for (let parent = el.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        parent.style.setProperty('overflow', 'visible', 'important')
      }
    }
    window.dispatchEvent(new Event('resize'))
    if (card) {
      const visited = new Set()
      for (const node of el.querySelectorAll('*')) {
        for (let instance = node.__vueParentComponent; instance; instance = instance.parent) {
          if (visited.has(instance)) continue
          visited.add(instance)
          const prepare = instance.exposed?.prepareSubscriptionScreenshot
          if (typeof prepare !== 'function') continue
          const measurement = await prepare()
          const container = document.getElementById(measurement?.containerId)
          if (container && measurement.contentHeight > 0) {
            const top = container.getBoundingClientRect().top - el.getBoundingClientRect().top
            const height = Math.max(160, Math.min(980, Math.ceil(top + measurement.contentHeight + 32)))
            el.style.setProperty('height', height + 'px', 'important')
          }
        }
      }
      window.dispatchEvent(new Event('resize'))
    }
  }, card)
}

export async function restoreScreenshot(wrapper) {
  await wrapper.evaluate(el => {
    for (const { node, style, scrollTop, scrollLeft } of el.__subscriptionLayout || []) {
      if (style === null) node.removeAttribute('style')
      else node.setAttribute('style', style)
      node.scrollTop = scrollTop
      node.scrollLeft = scrollLeft
    }
    delete el.__subscriptionLayout
    window.dispatchEvent(new Event('resize'))
  })
}
