export interface NamedField {
  id: string | number
  name: string
  originName?: string
  description?: string
  extField?: number
}

export const fieldHelp = (field: Partial<NamedField>) => {
  const lines = [field.name || field.originName || '']
  if (
    (field.extField == null || field.extField === 0) &&
    field.originName &&
    field.originName !== field.name
  )
    lines.push(`原字段名：${field.originName}`)
  if (field.description) lines.push(`备注：${field.description}`)
  return lines.filter(Boolean).join('\n')
}

export const restoreCandidates = (fields: NamedField[]) =>
  fields
    .filter(field => field.extField === 0 && !!field.originName && field.name !== field.originName)
    .map(field => ({
      id: String(field.id),
      before: field.name,
      after: field.originName || '',
      description: field.description || '',
      fromComment: !!field.description && field.name === field.description
    }))

// Build a plan only. The editor applies it to the current draft after the user's selection.
export const planNameRestore = (fields: NamedField[], ids: string[]) => {
  const candidates = restoreCandidates(fields)
  const selected = new Set(ids)
  const changes = candidates.filter(field => selected.has(field.id))
  if (changes.length !== selected.size) throw new Error('字段已变化，请重新选择')
  const names = new Map(changes.map(field => [field.id, field.after]))
  for (const change of changes) {
    if (change.after.length > 100)
      throw new Error(`原字段名超过显示名称的 100 字限制：${change.after}`)
    if (
      fields.some(
        field =>
          String(field.id) !== change.id &&
          (names.get(String(field.id)) ?? field.name).trim() === change.after.trim()
      )
    )
      throw new Error(`恢复后显示名称重复：${change.after}，请调整选择或先修改重名字段`)
  }
  return changes
}
