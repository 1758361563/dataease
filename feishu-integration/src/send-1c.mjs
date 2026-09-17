import fs from 'fs'
import path from 'path'
import { chromium } from 'playwright'
import { CONFIG, TMP_DIR, ensureDirs } from './config.mjs'
import { captureCard } from './capture.mjs'
import { closeBrowser } from './session.mjs'
import { getTenantAccessToken, sendText, sendImage, uploadImage } from './feishu.mjs'

function stamp() {
  return new Date().toISOString()
}

async function writeStaticPng(filePath) {
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 720, height: 240 } })
  await page.setContent(`<!doctype html><html><body style="margin:0;background:#1f2329;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center">
    <div>
      <div style="font-size:22px;font-weight:700">新 BI Phase 1C 静态测试图</div>
      <div style="margin-top:12px;opacity:.85">气味数据机器人开发测试 · 不是观远正式日报</div>
      <div style="margin-top:8px;font-size:12px;opacity:.7">${stamp()}</div>
    </div>
  </body></html>`)
  await page.screenshot({ path: filePath, type: 'png' })
  await browser.close()
}

function requireConfig() {
  const missing = []
  if (!CONFIG.feishuAppId) missing.push('FEISHU_APP_ID')
  if (!CONFIG.feishuAppSecret) missing.push('FEISHU_APP_SECRET')
  if (!CONFIG.feishuTestChatId) missing.push('FEISHU_TEST_CHAT_ID')
  if (!CONFIG.feishuTestUserId) missing.push('FEISHU_TEST_USER_ID')
  if (missing.length) {
    throw new Error(`Missing env: ${missing.join(', ')}`)
  }
}

async function main() {
  ensureDirs()
  requireConfig()
  const report = {
    startedAt: stamp(),
    configCheck: {
      feishuAppIdLoaded: Boolean(CONFIG.feishuAppId),
      feishuAppSecretLoaded: Boolean(CONFIG.feishuAppSecret),
      feishuTestChatIdLoaded: Boolean(CONFIG.feishuTestChatId),
      feishuTestUserIdLoaded: Boolean(CONFIG.feishuTestUserId),
      feishuTestUserIdLen: CONFIG.feishuTestUserId.length,
      stillReadsOpenId: Boolean(process.env.FEISHU_TEST_OPEN_ID),
      receiveIdTypes: { chat: 'chat_id', user: 'user_id' }
    },
    steps: {}
  }

  let token
  try {
    const auth = await getTenantAccessToken(CONFIG.feishuAppId, CONFIG.feishuAppSecret)
    token = auth.token
    report.steps.auth = { result: 'PASS', at: stamp(), ...auth.meta }
  } catch (err) {
    report.steps.auth = { result: 'FAIL', at: stamp(), error: err.message, detail: err.detail || null }
    finish(report, 1)
    return
  }

  const chatText = await sendText({
    token,
    receiveIdType: 'chat_id',
    receiveId: CONFIG.feishuTestChatId,
    text: `[新BI开发测试] Phase 1C 群聊文本 ${stamp()}。本条由气味数据应用发送，不是观远正式日报，请忽略。`
  })
  report.steps.chatText = { result: chatText.ok ? 'PASS' : 'FAIL', at: stamp(), ...chatText }

  const staticPath = path.join(TMP_DIR, 'phase1c_static.png')
  await writeStaticPng(staticPath)
  const staticUpload = await uploadImage({ token, filePath: staticPath, filename: 'phase1c_static.png' })
  let chatStatic = { result: 'FAIL', at: stamp(), upload: staticUpload }
  if (staticUpload.ok) {
    const sent = await sendImage({
      token,
      receiveIdType: 'chat_id',
      receiveId: CONFIG.feishuTestChatId,
      imageKey: staticUpload.imageKey
    })
    chatStatic = { result: sent.ok ? 'PASS' : 'FAIL', at: stamp(), upload: staticUpload, send: sent }
  }
  report.steps.chatStaticImage = chatStatic

  let pngPath = path.join(TMP_DIR, 'verify_default.png')
  let captureMeta = null
  if (!fs.existsSync(pngPath) || fs.statSync(pngPath).size < 1000) {
    const shot = await captureCard({
      dashboardId: CONFIG.defaultDashboardId,
      chartId: CONFIG.defaultChartId,
      filters: null,
      taskId: 'phase1c-card'
    })
    pngPath = shot.pngPath
    captureMeta = { pngHash: shot.meta.pngHash, pngBytes: shot.meta.pngBytes, chartId: shot.meta.chartId }
    fs.copyFileSync(pngPath, path.join(TMP_DIR, 'phase1c_card.png'))
    await closeBrowser()
  } else {
    captureMeta = { reused: 'tmp/verify_default.png', pngBytes: fs.statSync(pngPath).size }
  }
  const cardUpload = await uploadImage({ token, filePath: pngPath, filename: 'de_card.png' })
  let chatCard = { result: 'FAIL', at: stamp(), capture: captureMeta, upload: cardUpload }
  if (cardUpload.ok) {
    const sent = await sendImage({
      token,
      receiveIdType: 'chat_id',
      receiveId: CONFIG.feishuTestChatId,
      imageKey: cardUpload.imageKey
    })
    chatCard = { result: sent.ok ? 'PASS' : 'FAIL', at: stamp(), capture: captureMeta, upload: cardUpload, send: sent }
  }
  report.steps.chatCardImage = chatCard

  const dmText = await sendText({
    token,
    receiveIdType: 'user_id',
    receiveId: CONFIG.feishuTestUserId,
    text: `[新BI开发测试] Phase 1C 个人文本 ${stamp()}。本条由气味数据应用发给 FEISHU_TEST_USER_ID，用于验证观远「飞书账号」字段。不是观远正式日报。`
  })
  report.steps.dmText = { result: dmText.ok ? 'PASS' : 'FAIL', at: stamp(), ...dmText }

  let dmImage = { result: 'SKIPPED', at: stamp(), reason: 'personal text failed; image not attempted' }
  if (dmText.ok) {
    const img = await sendImage({
      token,
      receiveIdType: 'user_id',
      receiveId: CONFIG.feishuTestUserId,
      imageKey: (cardUpload.ok && cardUpload.imageKey) || (staticUpload.ok && staticUpload.imageKey)
    })
    dmImage = { result: img.ok ? 'PASS' : 'FAIL', at: stamp(), ...img }
  }
  report.steps.dmImage = dmImage

  finish(report, 0)
}

function finish(report, code) {
  report.finishedAt = stamp()
  const out = path.join(TMP_DIR, 'phase1c_report.json')
  fs.writeFileSync(out, JSON.stringify(report, null, 2))
  console.log(`[phase1c] wrote ${out}`)
  const rows = [
    ['auth', report.steps.auth?.result],
    ['chatText', report.steps.chatText?.result],
    ['chatStaticImage', report.steps.chatStaticImage?.result],
    ['chatCardImage', report.steps.chatCardImage?.result],
    ['dmText', report.steps.dmText?.result],
    ['dmImage', report.steps.dmImage?.result]
  ]
  for (const [name, result] of rows) {
    console.log(`${name}=${result || 'n/a'}`)
  }
  process.exit(code)
}

main().catch(async err => {
  console.error('[phase1c] ERROR', err.message)
  await closeBrowser().catch(() => {})
  process.exit(1)
})
