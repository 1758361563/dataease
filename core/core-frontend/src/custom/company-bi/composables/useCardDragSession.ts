/**
 * Card-level drag/resize session bridge (company, extracted from Shape.vue step 5).
 *
 * Decides whether a given Shape instance is eligible for the company
 * Overlay+Placeholder session (`biBoardSessionOk`) and, if so, seeds the
 * shared `dashboardDragSession` singleton from this card's current DOM rect
 * and grid position (`startBiBoardSession`). One instance is created per
 * `Shape.vue`; the underlying session itself stays a singleton because only
 * one card can be dragged/resized at a time.
 */

import { computed, type ComputedRef, type Ref } from 'vue'
import { isMainCanvas } from '@/utils/canvasUtils'
import { dashboardDragSession } from '@/custom/company-bi/composables/useDashboardDragSession'

export interface UseCardDragSessionDeps {
  dvInfo: Ref<{ type: string }>
  canvasId: Ref<string>
  element: Ref<any>
  areaData: Ref<{ components: any[] }>
  shapeInnerRef: Ref<HTMLElement | null>
  parentNode: Ref<HTMLElement | null>
  baseCellInfo: Ref<{ baseWidth: number; baseHeight: number; curGap: number }>
  pcMatrixCount: Ref<{ x: number; y: number }>
}

export function useCardDragSession(deps: UseCardDragSessionDeps) {
  const {
    dvInfo,
    canvasId,
    element,
    areaData,
    shapeInnerRef,
    parentNode,
    baseCellInfo,
    pcMatrixCount
  } = deps

  /** company P0: main-canvas single card uses follow overlay session */
  const biBoardSessionOk = () => {
    return (
      dvInfo.value.type === 'dashboard' &&
      isMainCanvas(canvasId.value) &&
      element.value.component !== 'GroupArea' &&
      !['DeTabs', 'Group'].includes(element.value.component) &&
      !(areaData.value.components?.length > 0)
    )
  }

  const biSourceGhost: ComputedRef<boolean> = computed(
    () =>
      dashboardDragSession.rawView.active &&
      dashboardDragSession.rawView.componentId === element.value.id &&
      dashboardDragSession.rawView.mode === 'drag' &&
      dashboardDragSession.rawView.sourceDisplay === 'ghost'
  )

  const startBiBoardSession = (
    mode: 'drag' | 'resize',
    e: MouseEvent,
    resizeHandle?: string,
    pointerOffsetOverride?: { x: number; y: number }
  ) => {
    const shapeRect = shapeInnerRef.value?.getBoundingClientRect?.() || {
      left: e.clientX,
      top: e.clientY,
      width: element.value.style.width,
      height: element.value.style.height
    }
    const editorEl = parentNode.value as HTMLElement
    const contentRect = editorEl?.getBoundingClientRect?.() || shapeRect
    const cellW = baseCellInfo.value.baseWidth || 1
    const cellH = baseCellInfo.value.baseHeight || 1
    const pointerOffset = pointerOffsetOverride || {
      x: e.clientX - shapeRect.left,
      y: e.clientY - shapeRect.top
    }
    dashboardDragSession.start({
      mode,
      componentId: element.value.id,
      canvasId: canvasId.value,
      label: element.value.label || element.value.name || '',
      pointerOffset,
      resizeHandle,
      origin: {
        x: element.value.x,
        y: element.value.y,
        sizeX: element.value.sizeX,
        sizeY: element.value.sizeY,
        pixel: {
          left: cellW * (element.value.x - 1),
          top: cellH * (element.value.y - 1),
          width: shapeRect.width,
          height: shapeRect.height
        }
      },
      matrix: {
        cellWidth: cellW,
        cellHeight: cellH,
        gap: baseCellInfo.value.curGap || 0,
        cols: pcMatrixCount.value.x,
        contentClientRect: {
          left: contentRect.left,
          top: contentRect.top,
          width: contentRect.width,
          height: contentRect.height
        },
        scrollTop: 0,
        scrollLeft: 0
      }
    })
    if (mode === 'drag') {
      dashboardDragSession.move(e.clientX, e.clientY)
    }
  }

  return {
    biBoardSessionOk,
    startBiBoardSession,
    biSourceGhost
  }
}
