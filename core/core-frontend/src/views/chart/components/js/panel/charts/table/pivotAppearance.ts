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

export const TABLE_STYLE_PRESETS = [
  {
    value: 'light',
    label: '清爽浅色',
    header: '#F5F7FA',
    headerText: '#334155',
    row: '#F8FAFC',
    rowText: '#334155',
    text: '#1F2937',
    stripe: '#FAFBFC',
    border: '#E8ECF1'
  },
  {
    value: 'indigo',
    label: '靛蓝表头',
    header: '#47578C',
    headerText: '#FFFFFF',
    row: '#F5F7FC',
    rowText: '#334155',
    text: '#1F2937',
    stripe: '#FCFCFE',
    border: '#E7EBF2'
  },
  {
    value: 'slate',
    label: '深色表头',
    header: '#334155',
    headerText: '#FFFFFF',
    row: '#F8FAFC',
    rowText: '#334155',
    text: '#1F2937',
    stripe: '#FAFBFC',
    border: '#E8ECF1'
  }
]

/** Change appearance only: keep formatting, conditional rules, layout and totals intact. */
export function applyTableStylePreset(chart: any, preset: string) {
  const palette = TABLE_STYLE_PRESETS.find(item => item.value === preset)
  if (!palette || !['table-pivot', 'table-info', 'table-normal'].includes(chart?.type)) return
  const serialized = typeof chart.customAttr === 'string'
  const attr = serialized ? JSON.parse(chart.customAttr) : chart.customAttr
  const basic = (attr.basicStyle ||= {})
  const header = (attr.tableHeader ||= {})
  const cell = (attr.tableCell ||= {})
  Object.assign(basic, {
    tableStylePreset: preset,
    tableStyleVersion: 3,
    tableBorderColor: palette.border
  })
  Object.assign(header, {
    tableHeaderBgColor: palette.header,
    tableHeaderCornerBgColor: palette.header,
    tableHeaderColBgColor: palette.row,
    tableHeaderFontColor: palette.headerText,
    tableHeaderCornerFontColor: palette.headerText,
    tableHeaderColFontColor: palette.rowText,
    tableTitleFontSize: 15,
    tableTitleColFontSize: 15,
    tableTitleCornerFontSize: 15,
    isBolder: true,
    isCornerBolder: true,
    isColBolder: false,
    showVerticalBorder: true,
    showHorizonBorder: true
  })
  Object.assign(cell, {
    tableItemBgColor: '#FFFFFF',
    tableItemSubBgColor: palette.stripe,
    tableFontColor: palette.text,
    enableTableCrossBG: true,
    tableItemFontSize: 15,
    isBolder: false,
    showVerticalBorder: true,
    showHorizonBorder: true
  })
  if (serialized) chart.customAttr = JSON.stringify(attr)
  applyTableDensity(chart, basic.tableDensity || 'standard')
}

export const TABLE_DENSITIES = [
  { value: 'compact', label: '紧凑 · 24px', height: 24 },
  { value: 'standard', label: '标准 · 28px', height: 28 },
  { value: 'comfortable', label: '舒适 · 32px', height: 32 }
]

export function applyTableDensity(chart: any, density: string) {
  const option = TABLE_DENSITIES.find(item => item.value === density)
  if (!option) return
  const serialized = typeof chart.customAttr === 'string'
  const attr = serialized ? JSON.parse(chart.customAttr) : chart.customAttr
  attr.basicStyle.tableDensity = density
  attr.tableHeader.tableTitleHeight = option.height
  attr.tableCell.tableItemHeight = option.height
  if (serialized) chart.customAttr = JSON.stringify(attr)
}

export function currentTableDensity(chart: any) {
  const attr =
    typeof chart.customAttr === 'string' ? JSON.parse(chart.customAttr) : chart.customAttr
  return (
    TABLE_DENSITIES.find(
      item =>
        attr.tableHeader?.tableTitleHeight === item.height &&
        attr.tableCell?.tableItemHeight === item.height
    )?.value || 'custom'
  )
}

/** Adjust only the rendering copy, after canvas scaling, never the saved chart. */
export function normalizeTableReadability(chart: any, source = chart, scale = 1) {
  if (!['table-pivot', 'table-info', 'table-normal'].includes(chart?.type)) return
  const { tableHeader: header, tableCell: cell } = chart.customAttr
  for (const key of ['tableTitleFontSize', 'tableTitleColFontSize', 'tableTitleCornerFontSize']) {
    if (Number.isFinite(header?.[key])) header[key] = Math.max(13, Math.round(header[key]))
  }
  if (Number.isFinite(cell?.tableItemFontSize))
    cell.tableItemFontSize = Math.max(13, Math.round(cell.tableItemFontSize))
  // Density is a screen-space choice. Do not collapse all three presets to the
  // same minimum row height when a wide dashboard is scaled down.
  const density = TABLE_DENSITIES.find(item => item.value === currentTableDensity(source))
  if (density) {
    header.tableTitleHeight = Math.round(density.height * Math.max(1, scale))
    cell.tableItemHeight = header.tableTitleHeight
  }
  if (Number.isFinite(header?.tableTitleHeight))
    header.tableTitleHeight = Math.max(
      Math.round(header.tableTitleHeight),
      Math.max(
        header.tableTitleFontSize || 13,
        header.tableTitleColFontSize || 13,
        header.tableTitleCornerFontSize || 13
      ) + 8
    )
  if (Number.isFinite(cell?.tableItemHeight))
    cell.tableItemHeight = Math.max(
      Math.round(cell.tableItemHeight),
      (cell.tableItemFontSize || 13) + 8
    )
  if (!chart.fontFamily || chart.fontFamily === 'inherit')
    chart.fontFamily = '"Segoe UI", "Microsoft YaHei UI", "PingFang SC", Arial, sans-serif'
}

/** Give new, imported and legacy tables one default; later field-level edits remain authoritative. */
export function ensureTableStyle(chart: any) {
  if (!['table-pivot', 'table-info', 'table-normal'].includes(chart?.type) || !chart.customAttr)
    return
  const attr =
    typeof chart.customAttr === 'string' ? JSON.parse(chart.customAttr) : chart.customAttr
  if (attr.basicStyle?.tableStyleVersion === 3) return
  if (attr.basicStyle?.tableStyleVersion === 2) {
    const previousHeight = { compact: 32, standard: 40, comfortable: 48 }[
      attr.basicStyle.tableDensity
    ]
    const density = TABLE_DENSITIES.find(item => item.value === attr.basicStyle.tableDensity)
    if (
      density &&
      attr.tableHeader?.tableTitleHeight === previousHeight &&
      attr.tableCell?.tableItemHeight === previousHeight
    ) {
      attr.tableHeader.tableTitleHeight = density.height
      attr.tableCell.tableItemHeight = density.height
    }
    attr.basicStyle.tableStyleVersion = 3
    if (typeof chart.customAttr === 'string') chart.customAttr = JSON.stringify(attr)
    return
  }
  if (attr.basicStyle?.tableStyleVersion === 1) {
    const palette =
      TABLE_STYLE_PRESETS.find(item => item.value === attr.basicStyle.tableStylePreset) ||
      TABLE_STYLE_PRESETS[0]
    const previous = {
      light: {
        header: '#F5F7FA',
        headerText: '#65728A',
        row: '#FAFBFD',
        rowText: '#6B788E',
        text: '#37465F',
        stripe: '#FBFCFE',
        border: '#DDE3EC'
      },
      indigo: {
        header: '#4F5DA5',
        headerText: '#FFFFFF',
        row: '#F0F3FD',
        rowText: '#37465F',
        text: '#37465F',
        stripe: '#F6F7FC',
        border: '#DDE2EE'
      },
      slate: {
        header: '#334155',
        headerText: '#FFFFFF',
        row: '#F1F5F9',
        rowText: '#475569',
        text: '#334155',
        stripe: '#F8FAFC',
        border: '#D8E0E9'
      }
    }[palette.value]
    for (const [section, mappings] of [
      [attr.basicStyle, { tableBorderColor: 'border' }],
      [
        attr.tableHeader,
        {
          tableHeaderBgColor: 'header',
          tableHeaderCornerBgColor: 'header',
          tableHeaderColBgColor: 'row',
          tableHeaderFontColor: 'headerText',
          tableHeaderCornerFontColor: 'headerText',
          tableHeaderColFontColor: 'rowText'
        }
      ],
      [attr.tableCell, { tableFontColor: 'text', tableItemSubBgColor: 'stripe' }]
    ] as const) {
      for (const [key, color] of Object.entries(mappings)) {
        if (
          typeof section?.[key] === 'string' &&
          pivotColorKey(section[key]) === pivotColorKey(previous[color])
        )
          section[key] = palette[color]
      }
    }
    for (const key of ['tableTitleFontSize', 'tableTitleColFontSize', 'tableTitleCornerFontSize']) {
      if (attr.tableHeader[key] === 14) attr.tableHeader[key] = 15
    }
    if (attr.tableCell.tableItemFontSize === 14) attr.tableCell.tableItemFontSize = 15
    if (attr.tableHeader.tableTitleHeight === 40) attr.tableHeader.tableTitleHeight = 32
    if (attr.tableCell.tableItemHeight === 38) attr.tableCell.tableItemHeight = 32
    attr.basicStyle.tableDensity ||= 'comfortable'
    attr.basicStyle.tableStyleVersion = 3
    if (typeof chart.customAttr === 'string') chart.customAttr = JSON.stringify(attr)
    return
  }
  applyTableStylePreset(chart, 'light')
}

export function currentTableStylePreset(chart: any) {
  const attr =
    typeof chart.customAttr === 'string' ? JSON.parse(chart.customAttr) : chart.customAttr
  const header = attr.tableHeader || {}
  const cell = attr.tableCell || {}
  const matches = (value, expected) =>
    typeof value === 'string' && pivotColorKey(value) === pivotColorKey(expected)
  return (
    TABLE_STYLE_PRESETS.find(
      palette =>
        matches(header.tableHeaderBgColor, palette.header) &&
        matches(header.tableHeaderCornerBgColor, palette.header) &&
        matches(header.tableHeaderColBgColor, palette.row) &&
        matches(header.tableHeaderFontColor, palette.headerText) &&
        matches(header.tableHeaderCornerFontColor, palette.headerText) &&
        matches(header.tableHeaderColFontColor, palette.rowText) &&
        matches(attr.basicStyle?.tableBorderColor, palette.border) &&
        matches(cell.tableFontColor, palette.text) &&
        matches(cell.tableItemBgColor, '#FFFFFF') &&
        matches(cell.tableItemSubBgColor, palette.stripe)
    )?.value || 'custom'
  )
}

/** One-time upgrade of the old built-in pivot appearance. User edits made after
 * this upgrade are authoritative, even when they equal an old default. */
export function migratePivotAppearance(chart: any) {
  if (chart?.type !== 'table-pivot' || !chart.customAttr) return
  const serialized = typeof chart.customAttr === 'string'
  const attr = serialized ? JSON.parse(chart.customAttr) : chart.customAttr
  const basic = (attr.basicStyle ||= {})
  if (basic.tableStyleVersion >= 1) {
    basic.pivotAppearanceVersion = 4
    if (serialized) chart.customAttr = JSON.stringify(attr)
    return
  }
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
      current[style].textBaseline = 'middle'
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
