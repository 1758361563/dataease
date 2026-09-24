import { DAILY_0100, SOURCE_798, ensure798Source, getSheetSourceByName, updateSheetSourceSchedule } from './sheet-source.mjs'
import { futureSlot, getQiwei, scheduleAt, slotDateTime } from './sheet-sync.mjs'
import { startScheduler } from './scheduler.mjs'

const ahead = futureSlot(2)
const plannedAt = slotDateTime(ahead.slot)
await ensure798Source()
await updateSheetSourceSchedule(SOURCE_798.sourceName, {
  enabled: true,
  scheduleJson: scheduleAt(ahead.hour, ahead.minute)
})
console.log(JSON.stringify({ waitingFor: plannedAt, schedule: scheduleAt(ahead.hour, ahead.minute) }))
const timer = startScheduler({ intervalMs: 15000 })

const db = await getQiwei()
const deadline = Date.now() + 4 * 60 * 1000
let success = null
while (Date.now() < deadline) {
  const [rows] = await db.execute(
    `SELECT id, status, \`rows\`, \`columns\`, error_summary
       FROM \`qiwei_new_bi\`.\`feishu_sheet_run\`
      WHERE source = ? AND planned_at = ?`,
    [SOURCE_798.sourceName, plannedAt]
  )
  success = rows.find(row => row.status === 'success')
  if (success || rows.some(row => row.status === 'failed')) {
    if (!success) {
      console.log(JSON.stringify({ failed: rows }))
      break
    }
    await new Promise(resolve => setTimeout(resolve, 20000))
    break
  }
  await new Promise(resolve => setTimeout(resolve, 5000))
}
const [after] = await db.execute(
  `SELECT status, COUNT(*) AS n FROM \`qiwei_new_bi\`.\`feishu_sheet_run\`
    WHERE source = ? AND planned_at = ? GROUP BY status`,
  [SOURCE_798.sourceName, plannedAt]
)
const [table] = await db.query(
  `SELECT COUNT(*) AS rows,
          DATE_FORMAT(MIN(\`销售日期\`), '%Y-%m-%d') AS dateMin,
          DATE_FORMAT(MAX(\`销售日期\`), '%Y-%m-%d') AS dateMax
     FROM \`qiwei_new_bi\`.\`798门店手工数据_SS\``
)
clearInterval(timer)
await updateSheetSourceSchedule(SOURCE_798.sourceName, { enabled: true, scheduleJson: DAILY_0100 })
const saved = await getSheetSourceByName(SOURCE_798.sourceName)
console.log(JSON.stringify({
  plannedAt,
  success: success ? { rows: success.rows, columns: success.columns } : null,
  slotCounts: after,
  table: table[0],
  restoredSchedule: saved.schedule_json,
  enabled: saved.enabled
}, null, 2))
if (!success || Number(after.find(row => row.status === 'success')?.n) !== 1) process.exit(1)
process.exit(0)
