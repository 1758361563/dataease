type ExpressionField = { id?: string | number; name?: string }

/** Unknown saved IDs remain visible; saving requires every displayed reference to resolve. */
export function translateTotalFieldReferences(
  expression: string,
  fields: ExpressionField[],
  from: 'name' | 'id',
  to: 'name' | 'id',
  strict = from === 'name'
): string {
  return expression.replace(/\[([^\]]+)\]/g, (reference, key: string) => {
    const matches = fields.filter(
      field =>
        field[from] != null && String(field[from]) === key && field[to] != null && field[to] !== ''
    )
    const targets = [...new Set(matches.map(field => String(field[to])))]
    if (targets.length === 1) return `[${targets[0]}]`
    if (!strict) return reference
    if (targets.length > 1) throw new Error(`字段“${key}”存在重名，请先区分字段名称`)
    throw new Error(`字段“${key}”不存在或已移除，请从字段列表重新选择`)
  })
}
