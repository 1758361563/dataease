/** Shared by chart defaults without importing the chart rendering utilities. */
export function parseJson<T>(value: T | JSONString<T>): T {
  return typeof value === 'string' ? (JSON.parse(value) as T) : (value as T)
}
