/** Keep standard controls readable beside the unscaled dashboard parameter bar.
 * Explicit compact sizes below the default and non-dashboard/mobile views keep
 * their existing scaling. This is a render-only adjustment. */
export function readableQuerySize(
  value: number | string,
  baseline: number,
  scale = 1,
  enabled = true
) {
  const numeric = Number(value)
  const size = Number.isFinite(numeric) && numeric > 0 ? numeric : baseline
  if (!enabled || size < baseline || !Number.isFinite(scale) || scale <= 0) return size
  return Math.max(size, baseline / Math.min(scale, 1))
}

/** Upgrade only the old built-in filter defaults; later style edits win. */
export function migrateQueryAppearance(style: any, fields: any[] = []) {
  if (!style || style.queryAppearanceVersion === 1) return
  const replace = (key, previous, next) => {
    const current = style[key]
    const normalize = value =>
      typeof value === 'string' ? value.replace(/\s/g, '').toLowerCase() : value
    if (current == null || previous.some(value => normalize(value) === normalize(current)))
      style[key] = next
  }
  replace('fontSize', ['16', 16], '12')
  replace('placeholderSize', [16, '16'], 13)
  replace('queryConditionHeight', [36, '36'], 32)
  replace('queryConditionWidth', [227, '227'], 180)
  replace('nameboxSpacing', [8, '8'], 6)
  replace('queryConditionSpacing', [16, '16'], 18)
  replace('labelColor', ['', '#1f2329', 'rgb(31,35,41)', 'rgba(31,35,41,1)'], '#64748B')
  replace('text', ['', '#1f2329', 'rgb(31,35,41)', 'rgba(31,35,41,1)'], '#36465D')
  replace('borderColor', [''], '#E2E8F0')
  for (const field of fields) {
    if (Number(field.queryConditionWidth) === 227) field.queryConditionWidth = 180
  }
  style.queryAppearanceVersion = 1
}
