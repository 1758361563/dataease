export const SHANGHAI = 'Asia/Shanghai'
const OFFSET_MS = 8 * 60 * 60 * 1000

export function shanghaiWall(nowMs) {
  const shifted = new Date(Number(nowMs) + OFFSET_MS)
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    weekday: shifted.getUTCDay()
  }
}

export function shanghaiWallToMs(year, month, day, hour, minute) {
  return Date.UTC(year, month - 1, day, hour, minute, 0, 0) - OFFSET_MS
}

function pad(n) {
  return String(n).padStart(2, '0')
}

export function parseSchedule(raw) {
  let obj = raw
  if (typeof raw === 'string') {
    try {
      obj = JSON.parse(raw)
    } catch {
      obj = {}
    }
  }
  obj = obj && typeof obj === 'object' ? obj : {}
  const kind = String(obj.kind || 'manual').toLowerCase()
  const [hourRaw, minuteRaw] = String(obj.time || '09:00').split(':')
  const hour = Number(hourRaw)
  const minute = Number(minuteRaw)
  const safeHour = Number.isInteger(hour) && hour >= 0 && hour <= 23 ? hour : 9
  const safeMinute = Number.isInteger(minute) && minute >= 0 && minute <= 59 ? minute : 0
  let dayOfWeek = Number(obj.dayOfWeek)
  if (!Number.isInteger(dayOfWeek) || dayOfWeek < 1 || dayOfWeek > 7) dayOfWeek = 1
  let dayOfMonth = Number(obj.dayOfMonth)
  if (!Number.isInteger(dayOfMonth) || dayOfMonth < 1 || dayOfMonth > 31) dayOfMonth = 1
  return {
    kind,
    autoRun: obj.autoRun === true,
    timezone: obj.timezone || SHANGHAI,
    time: `${pad(safeHour)}:${pad(safeMinute)}`,
    hour: safeHour,
    minute: safeMinute,
    dayOfWeek,
    dayOfMonth
  }
}

function isoWeekday(jsDow) {
  return jsDow === 0 ? 7 : jsDow
}

export function currentDueSlot(nowMs, scheduleInput) {
  const schedule = parseSchedule(scheduleInput)
  if (!schedule.autoRun) return null
  if (!['daily', 'weekly', 'monthly'].includes(schedule.kind)) return null
  const wall = shanghaiWall(nowMs)
  if (schedule.kind === 'weekly' && isoWeekday(wall.weekday) !== schedule.dayOfWeek) return null
  if (schedule.kind === 'monthly' && wall.day !== schedule.dayOfMonth) return null
  const slot = shanghaiWallToMs(wall.year, wall.month, wall.day, schedule.hour, schedule.minute)
  if (Number(nowMs) < slot) return null
  return slot
}

export function freshnessDecision(sub) {
  const raw = sub?.freshness_json || sub?.freshnessJson
  if (raw == null || String(raw).trim() === '' || String(raw) === '{}' || String(raw) === 'null') {
    return { checked: false, blocked: false, reason: 'no freshness config; not pretending a check passed' }
  }
  return {
    checked: false,
    blocked: true,
    reason: 'freshness check has no reliable data source yet; refusing to pretend it passed'
  }
}
