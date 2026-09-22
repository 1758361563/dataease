import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
export const ROOT_DIR = path.resolve(__dirname, '..')
export const TMP_DIR = path.join(ROOT_DIR, 'tmp')
export const SHOT_DIR = path.join(TMP_DIR, 'screenshots')

function loadDotEnv() {
  const envPath = path.join(ROOT_DIR, '.env')
  if (!fs.existsSync(envPath)) return
  for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) continue
    const eq = trimmed.indexOf('=')
    if (eq < 1) continue
    const key = trimmed.slice(0, eq).trim()
    let value = trimmed.slice(eq + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    if (process.env[key] === undefined) process.env[key] = value
  }
}

loadDotEnv()

export const LOG_DIR = process.env.FEISHU_LOG_DIR
  ? path.resolve(process.env.FEISHU_LOG_DIR)
  : path.join(ROOT_DIR, 'log')

export const CONFIG = {
  baseUrl: (process.env.DE_BASE_URL || 'http://localhost:8080').replace(/\/$/, ''),
  user: process.env.DE_SCREENSHOT_USER || process.env.DE_P0_USER || 'admin',
  password: process.env.DE_SCREENSHOT_PASSWORD || process.env.DE_P0_PWD || '',
  port: Number(process.env.FEISHU_SHOT_PORT || 8091),
  defaultDashboardId: process.env.DE_VERIFY_DASHBOARD_ID || '1292912367851671552',
  defaultChartId: process.env.DE_VERIFY_CHART_ID || '7501169547546726400',
  viewport: { width: 1920, height: 1200 },
  taskTtlMs: 30 * 60 * 1000,
  chartWaitMs: 90_000,
  feishuAppId: process.env.FEISHU_APP_ID || '',
  feishuAppSecret: process.env.FEISHU_APP_SECRET || '',
  feishuTestChatId: process.env.FEISHU_TEST_CHAT_ID || '',
  feishuTestUserId: process.env.FEISHU_TEST_USER_ID || '',
  internalToken: process.env.FEISHU_INTERNAL_TOKEN || process.env.COMPANY_FEISHU_INTERNAL_TOKEN || '',
  mysql: {
    dockerContainer: process.env.DE_MYSQL_DOCKER || 'mysql-de',
    host: process.env.DE_MYSQL_HOST || '',
    port: Number(process.env.DE_MYSQL_PORT || 3306),
    user: process.env.DE_MYSQL_USER || 'root',
    password: process.env.DE_MYSQL_PASSWORD || process.env.MYSQL_ROOT_PASSWORD || '',
    database: process.env.DE_MYSQL_DATABASE || 'dataease'
  },
  dryRun: parseDryRunFlag(process.env.COMPANY_FEISHU_DRY_RUN ?? process.env.FEISHU_DRY_RUN),
  schedulerIntervalMs: Number(process.env.FEISHU_SCHEDULER_INTERVAL_MS || 30_000)
}

function parseDryRunFlag(raw) {
  if (raw === undefined || raw === null || String(raw).trim() === '') return true
  return !['0', 'false', 'FALSE', 'no', 'off'].includes(String(raw).trim())
}

export function ensureDirs() {
  fs.mkdirSync(SHOT_DIR, { recursive: true })
}

export function requireScreenshotPassword() {
  if (!CONFIG.password) {
    throw new Error(
      'Missing DE_SCREENSHOT_PASSWORD (or DE_P0_PWD). Put it in feishu-integration/.env; do not commit the file.'
    )
  }
}
