export function compactTooltip(tooltip: any, fontSize = 14) {
  if (!tooltip) return false
  const size = Math.max(14, Number(fontSize) || 14)
  return {
    ...tooltip,
    itemTpl:
      '<li class="g2-tooltip-list-item" data-index={index}><span class="g2-tooltip-marker" style="background:{color}"></span><span class="g2-tooltip-name">{name}</span><span class="g2-tooltip-value">{value}</span></li>',
    domStyles: {
      ...tooltip.domStyles,
      'g2-tooltip': {
        ...tooltip.domStyles?.['g2-tooltip'],
        fontSize: `${size}px`,
        minWidth: '0',
        width: 'max-content',
        maxWidth: 'min(320px, calc(100vw - 24px))',
        padding: '8px 10px',
        lineHeight: '1.4'
      },
      'g2-tooltip-title': {
        fontSize: `${size + 2}px`,
        fontWeight: '500',
        lineHeight: '1.35',
        marginBottom: '5px',
        whiteSpace: 'normal',
        overflowWrap: 'anywhere'
      },
      'g2-tooltip-list': { margin: '0', padding: '0' },
      'g2-tooltip-list-item': {
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'flex-start',
        columnGap: '6px',
        fontSize: `${size}px`,
        lineHeight: '1.4',
        margin: '3px 0'
      },
      'g2-tooltip-marker': { flexShrink: '0', margin: '0', width: '8px', height: '8px' },
      'g2-tooltip-name': { fontSize: `${size}px`, lineHeight: '1.4', whiteSpace: 'nowrap' },
      'g2-tooltip-value': {
        fontSize: `${size}px`,
        lineHeight: '1.4',
        marginLeft: '0',
        flex: '0 1 auto',
        textAlign: 'left',
        overflowWrap: 'anywhere'
      }
    }
  }
}
