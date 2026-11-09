import type { DataUpdateTask, UpdateSchedulePlan } from '@/api/company/dataUpdate'
import type { UpdateGroup } from '@/api/company/dataUpdateSetup'

export interface ScheduleRow {
  key: string
  time: string
  task: DataUpdateTask
  group?: UpdateGroup
  dependent: boolean
  effective: boolean
}

export function scheduleRows(
  tasks: DataUpdateTask[],
  groups: UpdateGroup[],
  plans: { tasks: UpdateSchedulePlan[]; groups: UpdateSchedulePlan[] }
): ScheduleRow[] {
  const rows: ScheduleRow[] = []
  const members = new Set(groups.flatMap(group => group.tasks))
  const byId = new Map(tasks.map(task => [task.definition.id, task]))
  for (const plan of plans.tasks) {
    const root = byId.get(plan.id)
    if (!root || members.has(plan.id)) continue
    const chain = [root]
    const visited = new Set([plan.id])
    for (let i = 0; i < chain.length; i++) {
      for (const task of tasks) {
        const d = task.definition
        if (
          !d.id ||
          visited.has(d.id) ||
          members.has(d.id) ||
          !d.enabled ||
          d.trigger !== 'DEPENDENCY'
        )
          continue
        if (d.dependencies.includes(chain[i].definition.id || '')) {
          visited.add(d.id)
          chain.push(task)
        }
      }
    }
    for (const time of plan.times.values)
      chain.forEach((task, index) =>
        rows.push({
          key: `${plan.id}/${time}/${task.definition.id}`,
          time,
          task,
          dependent: index > 0,
          effective: plan.effective && (index === 0 || task.effective)
        })
      )
  }
  for (const plan of plans.groups) {
    const group = groups.find(group => group.id === plan.id)
    if (!group) continue
    // A group owns its members' schedules, including members with their individual switch off.
    const remaining = group.tasks
      .map(id => byId.get(id))
      .filter((task): task is DataUpdateTask => !!task)
    const ordered: DataUpdateTask[] = []
    while (remaining.length) {
      const index = remaining.findIndex(task =>
        task.definition.dependencies.every(
          id => !remaining.some(other => other.definition.id === id)
        )
      )
      ordered.push(...remaining.splice(index < 0 ? 0 : index, 1))
    }
    for (const time of plan.times.values)
      ordered.forEach(task =>
        rows.push({
          key: `group/${plan.id}/${time}/${task.definition.id}`,
          time,
          task,
          group,
          dependent: task.definition.dependencies.length > 0,
          effective: plan.effective
        })
      )
  }
  return rows.sort((a, b) => a.time.localeCompare(b.time))
}

export function scheduleStrategy(task: DataUpdateTask): string {
  const d = task.definition
  if (d.mode === 'SCRIPT') return '脚本控制'
  if (d.strategy === 'FULL') return '全量替换'
  if (d.strategy === 'UPSERT') return '主键更新'
  return `窗口覆盖 · ${d.windowDays} 天`
}
