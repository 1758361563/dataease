import crypto from 'crypto'

const tasks = new Map()

export function createTask(input) {
  const task = {
    taskId: crypto.randomUUID(),
    status: 'PENDING',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    input,
    pngPath: null,
    error: null,
    meta: null
  }
  tasks.set(task.taskId, task)
  return task
}

export function updateTask(taskId, patch) {
  const task = tasks.get(taskId)
  if (!task) return null
  Object.assign(task, patch, { updatedAt: new Date().toISOString() })
  return task
}

export function getTask(taskId) {
  return tasks.get(taskId) || null
}

export function publicTask(task) {
  if (!task) return null
  return {
    taskId: task.taskId,
    status: task.status,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
    error: task.error,
    pngPath: task.pngPath,
    meta: task.meta
  }
}

export function sweepExpired(ttlMs, now = Date.now()) {
  for (const [id, task] of tasks) {
    if (now - Date.parse(task.createdAt) > ttlMs) tasks.delete(id)
  }
}
