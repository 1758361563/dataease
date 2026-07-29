/**
 * Shared display-name helper for indicator cards.
 * Never fall back to originName / dataeaseName (technical keys).
 */
export function indicatorDisplayName(
  quota:
    | {
        chartShowName?: string
        name?: string
      }
    | null
    | undefined
): string {
  if (!quota) return ''
  const show = (quota.chartShowName || '').trim()
  if (show) return show
  return (quota.name || '').trim()
}
