import { getTenantAccessToken } from './feishu.mjs'
import { CONFIG } from './config.mjs'

const FEISHU_API = 'https://open.feishu.cn/open-apis'
const CACHE_TTL_MS = 10 * 60 * 1000
const MAX_MATCHES = 20

let cache = { loadedAt: 0, employees: [] }

function isActive(user) {
  const status = user?.status || {}
  if (status.is_resigned === true || status.is_exited === true || status.is_unjoin === true) return false
  if (status.is_frozen === true) return false
  return status.is_activated !== false
}

function usableUserId(userId) {
  const id = String(userId || '')
  return id.length > 0 && !id.startsWith('ou_') && !id.startsWith('oc_')
}

export function filterEmployeesByName(employees, query) {
  const needle = String(query || '').trim().toLowerCase()
  if (!needle) return []
  const hits = employees.filter(row => String(row.name || '').toLowerCase().includes(needle))
  hits.sort((a, b) => {
    const nameCmp = String(a.name).localeCompare(String(b.name), 'zh')
    if (nameCmp !== 0) return nameCmp
    return String(a.departments.join(',')).localeCompare(String(b.departments.join(',')), 'zh')
  })
  return hits.slice(0, MAX_MATCHES)
}

export function publicEmployee(row) {
  return {
    name: row.name,
    userId: row.userId,
    departments: row.departments || [],
    userIdTail: String(row.userId || '').slice(-4)
  }
}

async function feishuGet(token, path) {
  const res = await fetch(`${FEISHU_API}${path}`, {
    signal: AbortSignal.timeout(20000),
    headers: { Authorization: `Bearer ${token}` }
  })
  const json = await res.json()
  if (res.status !== 200 || json.code !== 0) {
    const err = new Error(`feishu ${path} http=${res.status} code=${json.code} msg=${json.msg}`)
    err.http = res.status
    err.code = json.code
    err.msg = json.msg
    err.status = 502
    if (json.code === 99991672 || json.code === 41050 || json.code === 99991401) {
      err.missingScope = 'contact:contact:readonly_as_app 或通讯录数据范围不足'
    } else if (json.code === 99991663) {
      err.missingScope = '当前接口需要 user_access_token，已有应用通讯录权限不够调用该接口'
    }
    throw err
  }
  return json.data || {}
}

async function listAll(token, pathBuilder) {
  const items = []
  let pageToken = ''
  for (let i = 0; i < 40; i++) {
    const path = pathBuilder(pageToken)
    const data = await feishuGet(token, path)
    items.push(...(data.items || []))
    if (!data.has_more || !data.page_token) break
    pageToken = data.page_token
  }
  return items
}

async function loadIndex(token) {
  const departments = await listAll(
    token,
    pageToken =>
      `/contact/v3/departments/0/children?fetch_child=true&page_size=50&department_id_type=department_id${
        pageToken ? `&page_token=${encodeURIComponent(pageToken)}` : ''
      }`
  )
  const deptName = new Map()
  const deptIds = new Set(['0'])
  for (const dept of departments) {
    const id = String(dept.department_id || dept.open_department_id || '')
    if (id) {
      deptIds.add(id)
      deptName.set(id, dept.name || id)
    }
  }
  deptName.set('0', '根部门')

  const byUserId = new Map()
  for (const departmentId of deptIds) {
    const users = await listAll(
      token,
      pageToken =>
        `/contact/v3/users/find_by_department?department_id=${encodeURIComponent(
          departmentId
        )}&department_id_type=department_id&user_id_type=user_id&page_size=50${
          pageToken ? `&page_token=${encodeURIComponent(pageToken)}` : ''
        }`
    )
    for (const user of users) {
      if (!isActive(user) || !usableUserId(user.user_id) || !String(user.name || '').trim()) continue
      const departmentsForUser = (user.department_ids || [])
        .map(id => deptName.get(String(id)))
        .filter(Boolean)
      const existing = byUserId.get(user.user_id)
      if (existing) {
        const merged = new Set([...existing.departments, ...departmentsForUser])
        existing.departments = [...merged]
        continue
      }
      byUserId.set(user.user_id, {
        name: String(user.name).trim(),
        userId: user.user_id,
        departments: departmentsForUser
      })
    }
  }
  return [...byUserId.values()]
}

export async function searchEmployees(query, { forceRefresh = false } = {}) {
  const needle = String(query || '').trim()
  if (needle.length < 1) {
    const err = new Error('query is required')
    err.status = 400
    throw err
  }
  const now = Date.now()
  if (forceRefresh || !cache.employees.length || now - cache.loadedAt > CACHE_TTL_MS) {
    const auth = await getTenantAccessToken(CONFIG.feishuAppId, CONFIG.feishuAppSecret)
    cache = { loadedAt: now, employees: await loadIndex(auth.token) }
  }
  return filterEmployeesByName(cache.employees, needle).map(publicEmployee)
}

export async function resolveEmployee(userId, { forceRefresh = false } = {}) {
  const id = String(userId || '').trim()
  if (!usableUserId(id)) {
    const err = new Error('userId is invalid')
    err.status = 400
    throw err
  }
  const now = Date.now()
  if (forceRefresh || !cache.employees.length || now - cache.loadedAt > CACHE_TTL_MS) {
    const auth = await getTenantAccessToken(CONFIG.feishuAppId, CONFIG.feishuAppSecret)
    cache = { loadedAt: now, employees: await loadIndex(auth.token) }
  }
  const found = cache.employees.find(row => row.userId === id)
  if (!found) {
    const err = new Error('employee not found in authorized contacts')
    err.status = 404
    throw err
  }
  return publicEmployee(found)
}

export function resetEmployeeCache() {
  cache = { loadedAt: 0, employees: [] }
}
