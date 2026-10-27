import { createDecipheriv } from 'node:crypto'
import { CONFIG } from './config.mjs'
import { query } from './db.mjs'
import { resetEmployeeCache } from './contact.mjs'

export function decryptConnection(cipher, key, provider = 'FEISHU', connectionKey = 'default') {
  const [version, iv, payload] = String(cipher).split('.')
  if (version !== 'v1' || !iv || !payload) throw new Error('Unsupported integration secret format')
  const packed = Buffer.from(payload, 'base64')
  const decipher = createDecipheriv('aes-256-gcm', Buffer.from(key, 'base64'), Buffer.from(iv, 'base64'))
  decipher.setAAD(Buffer.from(`${provider}:${connectionKey}`))
  decipher.setAuthTag(packed.subarray(-16))
  return JSON.parse(Buffer.concat([decipher.update(packed.subarray(0, -16)), decipher.final()]).toString('utf8'))
}

let previous
export async function refreshManagedConnection() {
  const rows = await query(`SELECT config_json,secret_cipher FROM company_integration_connection
    WHERE provider=? AND connection_key=?`, ['FEISHU', 'default'])
  if (!rows.length) throw new Error('请先在集成中心配置飞书应用连接')
  const row = rows[0]
  const config = typeof row.config_json === 'string' ? JSON.parse(row.config_json) : row.config_json
  const secrets = decryptConnection(row.secret_cipher, process.env.COMPANY_BI_INTEGRATION_KEY)
  if (previous !== row.secret_cipher + row.config_json) {
    resetEmployeeCache()
    const { invalidateSession } = await import('./session.mjs')
    await invalidateSession()
    previous = row.secret_cipher + row.config_json
  }
  CONFIG.feishuAppId = config.appId || ''
  CONFIG.feishuAppSecret = secrets.appSecret || ''
  CONFIG.user = config.screenshotUser || ''
  CONFIG.password = secrets.screenshotPassword || ''
  // Managed BI executes the product workflow. Test isolation belongs to the test harness.
  CONFIG.dryRun = false
  process.env.BI_MANAGED_FEISHU_SECRET = CONFIG.feishuAppSecret
  return config
}
