/** Compare opaque CSS colours by value: the editor persists hex defaults as rgba. */
export function pivotColorKey(value: string) {
  const color = value.trim().replace(/\s/g, '').toUpperCase()
  const rgb = color.match(/^RGBA?\((\d+),(\d+),(\d+)(?:,([\d.]+))?\)$/)
  if (rgb && (rgb[4] === undefined || Number(rgb[4]) === 1)) {
    const channels = rgb.slice(1, 4).map(Number)
    if (channels.every(channel => channel <= 255))
      return (
        '#' +
        channels
          .map(channel => channel.toString(16).padStart(2, '0'))
          .join('')
          .toUpperCase()
      )
  }
  if (/^#[\dA-F]{8}$/.test(color) && color.endsWith('FF')) return color.slice(0, 7)
  if (/^#[\dA-F]{3}$/.test(color))
    return (
      '#' +
      color
        .slice(1)
        .split('')
        .map(channel => channel + channel)
        .join('')
    )
  return color
}

/** One-time upgrade of the old built-in pivot appearance. User edits made after
 * this upgrade are authoritative, even when they equal an old default. */
export function migratePivotAppearance(chart: any) {
  if (chart?.type !== 'table-pivot' || !chart.customAttr) return
  const serialized = typeof chart.customAttr === 'string'
  const attr = serialized ? JSON.parse(chart.customAttr) : chart.customAttr
  const basic = (attr.basicStyle ||= {})
  if (basic.pivotAppearanceVersion === 4) return
  const header = (attr.tableHeader ||= {})
  const cell = (attr.tableCell ||= {})
  const replace = (obj, key, previous, next) => {
    const value = obj[key]
    if (
      value == null ||
      previous.some(old =>
        typeof old === 'string'
          ? typeof value === 'string' && pivotColorKey(value) === pivotColorKey(old)
          : value === old
      )
    )
      obj[key] = next
  }
  if (basic.pivotAppearanceVersion !== 3) {
    // Version 2 completes the preview palette without reapplying typography to
    // charts upgraded to version 1 and subsequently edited by the user.
    if (!basic.pivotAppearanceVersion) {
      // Populate split legacy row/corner settings before recolouring them.
      for (const prefix of ['Col', 'Corner']) {
        for (const [suffix, base] of [
          ['BgColor', 'BgColor'],
          ['FontColor', 'FontColor']
        ]) {
          header[`tableHeader${prefix}${suffix}`] ??= header[`tableHeader${base}`]
        }
        header[`tableTitle${prefix}FontSize`] ??= header.tableTitleFontSize
        header[`tableHeader${prefix}Align`] ??= header.tableHeaderAlign
        header[`is${prefix}Bolder`] ??= header.isBolder
        header[`is${prefix}Italic`] ??= header.isItalic
      }
      replace(basic, 'tableBorderColor', ['#CCCCCC', '#E6E7E4'], '#E7ECF2')
      replace(header, 'tableHeaderBgColor', ['#F5F6F8', '#F5F6F7'], '#F5F7FA')
      replace(header, 'tableHeaderCornerBgColor', ['#F5F6F8', '#F5F6F7'], '#F5F7FA')
      replace(header, 'tableHeaderColBgColor', ['#F5F6F8', '#F5F6F7'], '#FAFBFD')
      for (const key of ['tableHeaderFontColor', 'tableHeaderCornerFontColor'])
        replace(header, key, ['#1F2329', '#646A73'], '#374151')
      replace(header, 'tableHeaderColFontColor', ['#1F2329', '#646A73'], '#475569')
      for (const key of ['tableTitleFontSize', 'tableTitleColFontSize', 'tableTitleCornerFontSize'])
        replace(header, key, [13], 14)
      replace(header, 'tableTitleHeight', [36], 40)
      replace(header, 'isColBolder', [true], false)
      replace(header, 'showVerticalBorder', [true], false)
      replace(cell, 'tableItemFontSize', [13], 14)
      replace(cell, 'tableItemHeight', [34, 36], 38)
      replace(cell, 'tableFontColor', ['#1F2329'], '#263448')
      replace(cell, 'tableItemBgColor', [], '#FFFFFF')
      replace(cell, 'tableItemSubBgColor', ['#FAFBFC'], '#FAFBFD')
      replace(cell, 'showVerticalBorder', [true], false)
    }
    // Version 3 repairs persisted rgba defaults missed by earlier migrations.
    replace(header, 'tableHeaderBgColor', ['#F5F6F8', '#F5F6F7'], '#F5F7FA')
    replace(header, 'tableHeaderCornerBgColor', ['#F5F6F8', '#F5F6F7'], '#F5F7FA')
    replace(header, 'tableHeaderColBgColor', ['#F5F6F8', '#F5F6F7'], '#FAFBFD')
    replace(basic, 'tableBorderColor', ['#E7ECF2', '#CCCCCC', '#E6E7E4'], '#E9EDF3')
    replace(header, 'tableHeaderFontColor', ['#374151', '#1F2329', '#646A73'], '#65728A')
    replace(header, 'tableHeaderCornerFontColor', ['#374151', '#1F2329', '#646A73'], '#65728A')
    replace(header, 'tableHeaderColFontColor', ['#475569', '#1F2329', '#646A73'], '#6B788E')
    replace(cell, 'tableFontColor', ['#263448', '#1F2329'], '#37465F')
  }
  // Use scheme B's white / pale blue-grey stripes with the light header.
  // Upgrade the white alternate background from version 3 as well, while
  // preserving custom colours and an explicitly disabled stripe setting.
  replace(cell, 'tableItemSubBgColor', ['#FAFBFC', '#FAFBFD', '#FFFFFF'], '#FBFCFE')
  cell.enableTableCrossBG ??= true
  basic.pivotAppearanceVersion = 4
  if (serialized) chart.customAttr = JSON.stringify(attr)
}

/** Accent totals only on the built-in light palette. Conditional formatting and
 * custom backgrounds remain authoritative; merged placeholders stay invisible. */
export function pivotTotalBackground(background: any, meta: any, hasBackgroundConditions = false) {
  if (!meta.isTotals || meta.isMergedCell || hasBackgroundConditions) return background
  const color = background.backgroundColor?.replace(/\s/g, '').toUpperCase()
  if (['#FFF', '#FFFFFF', '#FAFBFD', '#FBFCFE'].includes(color))
    return { ...background, backgroundColor: '#EDF3FB' }
  const rgba = color?.match(/^RGBA?\((255,255,255|250,251,253|251,252,254)(,[\d.]+)?\)$/)
  return rgba
    ? { ...background, backgroundColor: `rgba(237,243,251${rgba[2] || ',1'})` }
    : background
}

/** Apply typography to the S2 theme; sizes are already scaled before this step. */
export function applyPivotAppearance(theme: any, chart: any) {
  const attr =
    typeof chart.customAttr === 'string' ? JSON.parse(chart.customAttr) : chart.customAttr
  const header = attr.tableHeader || {}
  const cell = attr.tableCell || {}
  const fallbackFont = '"Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", sans-serif'
  const customFont = chart.fontFamily && chart.fontFamily !== 'inherit'
  const styles = ['text', 'bolderText', 'measureText', 'seriesText']
  for (const kind of ['cornerCell', 'rowCell', 'colCell', 'dataCell']) {
    const current = (theme[kind] ||= {})
    for (const style of styles) {
      if (!current[style]) continue
      if (!customFont) current[style].fontFamily = fallbackFont
      const bold =
        kind === 'rowCell'
          ? header.isColBolder
          : kind === 'cornerCell'
          ? header.isCornerBolder
          : kind === 'colCell'
          ? header.isBolder
          : cell.isBolder
      const emphasize = bold ?? (kind === 'cornerCell' || kind === 'colCell')
      current[style].fontWeight = style === 'bolderText' ? 600 : emphasize ? 600 : 400
    }
    if (current.cell) {
      // Do not reinstate borders disabled in existing style configuration.
      if (current.cell.horizontalBorderWidth !== 0) current.cell.horizontalBorderWidth = 1
      if (current.cell.verticalBorderWidth !== 0) current.cell.verticalBorderWidth = 1
    }
  }
  if (theme.rowCell?.text) theme.rowCell.text.textBaseline = 'middle'
  return theme
}
