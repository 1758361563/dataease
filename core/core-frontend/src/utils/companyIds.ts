export const BOOTSTRAP_ADMIN_ID = '1'

export function keepStringId(value: unknown): string {
  if (value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value)) {
      throw new Error('snowflake id must stay string')
    }
    return String(value)
  }
  return String(value)
}

export function isUnsafeNumberId(raw: string): boolean {
  return raw !== String(Number(raw))
}

export const SNOWFLAKE_SAMPLE = '900000000000000010'
