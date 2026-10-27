/**
 * Minimal read of formal ETL run results for subscription send gating.
 *
 * Source of truth when a subscription names tasks: `qiwei_new_bi.etl_run_log`
 * (same columns as etl_migration/scripts/etl_run_log.sql). The ETL runner
 * writes it. This service only reads. It never inserts rows, so a missing
 * table or missing success cannot be turned into a fake update.
 *
 * Local formal/run_log.jsonl is not treated as a shared source.
 */

export const UNCHECKED_LABEL = '数据更新状态未经校验'
export const ETL_HOLD_PREFIX = 'etl-hold: '

export function etlHoldSummary(reason) {
  return `${ETL_HOLD_PREFIX}${reason || ''}`.slice(0, 500)
}

export function isEtlHoldSummary(text) {
  return String(text || '').startsWith(ETL_HOLD_PREFIX)
}

const TASK_LIST_KEYS = ['etlTasks', 'etlNames', 'assetIds', 'taskIds']
const TASK_SCALAR_KEYS = ['etlName', 'assetId', 'taskId']

function asObject(raw) {
  if (raw == null) return null
  if (typeof raw === 'object') return raw
  const text = String(raw).trim()
  if (!text || text === 'null' || text === '{}') return null
  try {
    const parsed = JSON.parse(text)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

export function parseFreshnessBinding(raw) {
  const obj = asObject(raw) || {}
  const tasks = []
  for (const key of TASK_LIST_KEYS) {
    const list = obj[key]
    if (!Array.isArray(list)) continue
    for (const item of list) {
      const name = String(item ?? '').trim()
      if (name) tasks.push(name)
    }
  }
  for (const key of TASK_SCALAR_KEYS) {
    const name = String(obj[key] ?? '').trim()
    if (name) tasks.push(name)
  }
  const businessDate = String(obj.businessDate || obj.bizDate || '').trim()
  return {
    tasks: [...new Set(tasks)],
    businessDate: /^\d{4}-\d{2}-\d{2}$/.test(businessDate) ? businessDate : null
  }
}

function dateOnly(value) {
  if (value == null || value === '') return null
  const text = String(value).trim()
  const match = text.match(/^(\d{4}-\d{2}-\d{2})/)
  return match ? match[1] : null
}

export function coversBusinessDate(record, businessDate) {
  if (!businessDate) return true
  const start = dateOnly(record.window_start ?? record.windowStart)
  const end = dateOnly(record.window_end ?? record.windowEnd)
  if (!start || !end) return false
  return start <= businessDate && businessDate < end
}

function idRank(row) {
  const raw = row?.id
  if (raw == null || raw === '') return 0n
  try {
    return BigInt(String(raw))
  } catch {
    return 0n
  }
}

function latestByTask(records) {
  const sorted = [...(records || [])].sort((a, b) => {
    const ai = idRank(a)
    const bi = idRank(b)
    if (ai !== bi) return ai > bi ? -1 : 1
    return String(b.finished_at || b.finishedAt || '').localeCompare(String(a.finished_at || a.finishedAt || ''))
  })
  const latest = new Map()
  for (const row of sorted) {
    const name = String(row.etl_name || row.etlName || row.asset_id || row.assetId || '').trim()
    if (!name || latest.has(name)) continue
    latest.set(name, row)
  }
  return latest
}

function unchecked() {
  return {
    checked: false,
    blocked: false,
    label: UNCHECKED_LABEL,
    reason: UNCHECKED_LABEL,
    tasks: [],
    lastSuccessAt: null,
    businessWindow: null
  }
}

export function evaluateFreshness(binding, snapshot) {
  const tasks = binding?.tasks || []
  if (!tasks.length) return unchecked()
  if (!binding.businessDate) {
    return {
      checked: true,
      blocked: true,
      label: '已关联任务但未约定业务日',
      reason: `已关联 ETL 任务（${tasks.join('、')}），但未配置 businessDate，不能只凭成功状态发送`,
      tasks,
      lastSuccessAt: null,
      businessWindow: null
    }
  }
  if (!snapshot || snapshot.available === false) {
    return {
      checked: true,
      blocked: true,
      label: '已关联任务，正式运行记录不可用',
      reason: `已关联 ETL 任务（${tasks.join('、')}），但 etl_run_log 没有可用的正式运行记录，已阻止发送旧图`,
      tasks,
      lastSuccessAt: null,
      businessWindow: null
    }
  }
  const latest = latestByTask(snapshot.records)
  const problems = []
  const windows = []
  let lastSuccessAt = null
  for (const task of tasks) {
    const row = latest.get(task)
    if (!row) {
      problems.push(`${task} 尚无正式运行记录`)
      continue
    }
    const status = String(row.status || '').trim().toLowerCase()
    const finishedAt = row.finished_at || row.finishedAt || null
    if (status !== 'success') {
      problems.push(`${task} 最近状态=${status || 'unknown'}，未成功`)
      continue
    }
    if (binding.businessDate && !coversBusinessDate(row, binding.businessDate)) {
      const start = dateOnly(row.window_start ?? row.windowStart) || '?'
      const end = dateOnly(row.window_end ?? row.windowEnd) || '?'
      problems.push(`${task} 成功窗口 [${start}, ${end}) 未覆盖业务日 ${binding.businessDate}`)
      continue
    }
    windows.push({
      task,
      finishedAt,
      windowStart: dateOnly(row.window_start ?? row.windowStart),
      windowEnd: dateOnly(row.window_end ?? row.windowEnd)
    })
    if (finishedAt && (!lastSuccessAt || String(finishedAt) > String(lastSuccessAt))) {
      lastSuccessAt = finishedAt
    }
  }
  if (problems.length) {
    return {
      checked: true,
      blocked: true,
      label: '关联任务未成功或未覆盖约定业务日',
      reason: `已阻止发送旧图：${problems.join('；')}`.slice(0, 500),
      tasks,
      lastSuccessAt,
      businessWindow: windows
    }
  }
  const windowText = windows
    .map(item => `${item.task} [${item.windowStart || '?'}, ${item.windowEnd || '?'})`)
    .join('；')
  return {
    checked: true,
    blocked: false,
    label: '关联任务已成功',
    reason: `ETL 已成功${binding.businessDate ? `，覆盖业务日 ${binding.businessDate}` : ''}：${windowText}`.slice(0, 500),
    tasks,
    lastSuccessAt,
    businessWindow: windows
  }
}

function tableName() {
  const raw = String(process.env.ETL_RUN_LOG_TABLE ||
    (process.env.COMPANY_BI_EMBEDDED_WORKER === 'true' ? 'etl_run_log' : 'qiwei_new_bi.etl_run_log')).trim()
  // 飞书默认连 dataease 库。正式记录在 qiwei_new_bi.etl_run_log，
  // 用库名.表名读取，避免只看到本库里另一张同名表。
  if (/^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)?$/.test(raw)) return raw
  return 'etl_run_log'
}

export function isMissingRunLog(err) {
  const text = `${err?.code || ''} ${err?.errno || ''} ${err?.message || err || ''}`
  return /ER_NO_SUCH_TABLE|1146|doesn't exist|does not exist|不存在/i.test(text)
}

export async function readEtlRunLog(taskNames, queryFn) {
  const names = [...new Set((taskNames || []).map(name => String(name).trim()).filter(Boolean))]
  if (!names.length) return { available: true, records: [] }
  const query = queryFn || (await import('./db.mjs')).query
  const placeholders = names.map(() => '?').join(',')
  try {
    const records = await query(
      `SELECT id, etl_name, status, started_at,
              DATE_FORMAT(finished_at, '%Y-%m-%d %H:%i:%s') AS finished_at,
              DATE_FORMAT(window_start, '%Y-%m-%d') AS window_start,
              DATE_FORMAT(window_end, '%Y-%m-%d') AS window_end
         FROM ${tableName()}
        WHERE etl_name IN (${placeholders})
        ORDER BY id DESC`,
      names
    )
    return { available: true, records }
  } catch (err) {
    if (isMissingRunLog(err)) return { available: false, records: [] }
    throw err
  }
}

export async function freshnessDecision(sub, options = {}) {
  const binding = parseFreshnessBinding(sub?.freshness_json || sub?.freshnessJson)
  if (!binding.tasks.length) return unchecked()
  if (!binding.businessDate) return evaluateFreshness(binding, { available: true, records: [] })
  const read = options.readRuns || readEtlRunLog
  const snapshot = await read(binding.tasks)
  return evaluateFreshness(binding, snapshot)
}
