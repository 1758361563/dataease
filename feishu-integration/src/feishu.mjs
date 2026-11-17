const FEISHU_API = 'https://open.feishu.cn/open-apis'

export function feishuErrorMessage(err) {
  const operation = err?.feishuOperation || '飞书请求'
  if (err?.name === 'TimeoutError' || err?.cause?.code === 'UND_ERR_CONNECT_TIMEOUT') {
    return `${operation}超时，请检查 BI 执行器到 open.feishu.cn 的网络连接后重试`
  }
  if (err?.name === 'AbortError') return `${operation}已取消，请重试`
  if (err?.name === 'TypeError' && err?.message === 'fetch failed') {
    return `${operation}连接失败，请检查 BI 执行器的网络、DNS 和代理配置`
  }
  const code = err?.http ? err.code : err?.detail?.code
  if (code != null && Number.isInteger(Number(code))) return `飞书接口调用失败，错误码 ${code}`
  return null
}

// Only used for credential acquisition and read-only contact requests.
export async function readFeishuJson(path, options, operation) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetch(`${FEISHU_API}${path}`, {
        ...options,
        signal: AbortSignal.timeout(20000)
      })
      return await readJson(res)
    } catch (err) {
      err.feishuOperation = operation
      const retryable = err.name === 'TimeoutError' ||
        (err.name === 'TypeError' && err.message === 'fetch failed')
      if (!retryable || attempt === 1) throw err
      await new Promise(resolve => setTimeout(resolve, 300))
    }
  }
}

function redactToken(token) {
  if (!token) return '(empty)'
  return `${token.slice(0, 6)}…(len=${token.length})`
}

async function readJson(res) {
  const text = await res.text()
  try {
    return { http: res.status, json: JSON.parse(text) }
  } catch {
    return { http: res.status, json: { code: -1, msg: text.slice(0, 300) } }
  }
}

export async function getTenantAccessToken(appId, appSecret) {
  const { http, json } = await readFeishuJson('/auth/v3/tenant_access_token/internal', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ app_id: appId, app_secret: appSecret })
  }, '获取飞书应用凭证')
  const token = json.tenant_access_token
  if (!token) {
    const err = new Error(`tenant_access_token failed http=${http} code=${json.code} msg=${json.msg}`)
    err.detail = { http, code: json.code, msg: json.msg }
    throw err
  }
  return {
    token,
    expire: json.expire,
    meta: { http, code: json.code ?? 0, tokenRedacted: redactToken(token), expire: json.expire }
  }
}

export async function sendMessage({ token, receiveIdType, receiveId, msgType, content }) {
  const url = `${FEISHU_API}/im/v1/messages?receive_id_type=${encodeURIComponent(receiveIdType)}`
  const res = await fetch(url, {
    signal: AbortSignal.timeout(20000),
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      receive_id: receiveId,
      msg_type: msgType,
      content: typeof content === 'string' ? content : JSON.stringify(content)
    })
  })
  const { http, json } = await readJson(res)
  return {
    ok: http === 200 && json.code === 0,
    http,
    code: json.code,
    msg: json.msg,
    messageId: json.data?.message_id || null,
    receiveIdType,
    receiveIdRedacted: receiveId ? `${String(receiveId).slice(0, 4)}…(len=${String(receiveId).length})` : ''
  }
}

export async function uploadImage({ token, filePath, filename }) {
  const fs = await import('fs')
  const buf = fs.readFileSync(filePath)
  const form = new FormData()
  form.append('image_type', 'message')
  form.append('image', new Blob([buf], { type: 'image/png' }), filename || 'image.png')
  const res = await fetch(`${FEISHU_API}/im/v1/images`, {
    signal: AbortSignal.timeout(30000),
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form
  })
  const { http, json } = await readJson(res)
  return {
    ok: http === 200 && json.code === 0,
    http,
    code: json.code,
    msg: json.msg,
    imageKey: json.data?.image_key || null,
    bytes: buf.length
  }
}

export function sendText(opts) {
  return sendMessage({
    ...opts,
    msgType: 'text',
    content: { text: opts.text }
  })
}

export function sendImage(opts) {
  return sendMessage({
    ...opts,
    msgType: 'image',
    content: { image_key: opts.imageKey }
  })
}
