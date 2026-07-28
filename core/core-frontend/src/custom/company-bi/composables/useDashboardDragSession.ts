/**
 * Dashboard drag/resize session (P0).
 * Overlay follows pointer in pixel space; placeholder snaps to grid;
 * formal x/y/size* commit only on end().
 */

import { reactive, readonly } from 'vue'

export type SessionMode = 'drag' | 'resize'

export interface SessionMatrix {
  cellWidth: number
  cellHeight: number
  gap: number
  cols: number
  contentClientRect: { left: number; top: number; width: number; height: number }
  scrollTop: number
  scrollLeft: number
}

export interface SessionOrigin {
  x: number
  y: number
  sizeX: number
  sizeY: number
  pixel: { left: number; top: number; width: number; height: number }
}

export interface SessionStartInput {
  mode: SessionMode
  componentId: string
  canvasId: string
  label?: string
  pointerOffset: { x: number; y: number }
  resizeHandle?: string
  origin: SessionOrigin
  matrix: SessionMatrix
}

export interface SessionPlaceholder {
  x: number
  y: number
  sizeX: number
  sizeY: number
}

export interface SessionOverlay {
  x: number
  y: number
  width: number
  height: number
}

export interface SessionView {
  active: boolean
  mode: SessionMode | null
  componentId: string | null
  label: string
  overlay: SessionOverlay | null
  placeholder: SessionPlaceholder | null
  neighborPreview: Record<string, { x: number; y: number }>
  sourceDisplay: 'ghost' | 'hidden' | 'solid'
}

export interface SessionCommit {
  componentId: string
  x: number
  y: number
  sizeX: number
  sizeY: number
  changed: boolean
}

const EXPAND_RATIO = 0.55
const SHRINK_RATIO = 0.55

function clamp(n: number, min: number, max: number) {
  return Math.max(min, Math.min(max, n))
}

function hystereticSize(raw: number, current: number, min = 1, max = 999): number {
  let size = current
  while (raw >= size + EXPAND_RATIO && size < max) {
    size += 1
  }
  while (raw <= size - SHRINK_RATIO && size > min) {
    size -= 1
  }
  return clamp(size, min, max)
}

function createSession() {
  const view = reactive<SessionView>({
    active: false,
    mode: null,
    componentId: null,
    label: '',
    overlay: null,
    placeholder: null,
    neighborPreview: {},
    sourceDisplay: 'ghost'
  })

  let input: SessionStartInput | null = null

  const reset = () => {
    input = null
    view.active = false
    view.mode = null
    view.componentId = null
    view.label = ''
    view.overlay = null
    view.placeholder = null
    view.neighborPreview = {}
    view.sourceDisplay = 'ghost'
  }

  const start = (next: SessionStartInput) => {
    input = next
    view.active = true
    view.mode = next.mode
    view.componentId = next.componentId
    view.label = next.label || ''
    view.sourceDisplay = 'ghost'
    view.neighborPreview = {}
    view.placeholder = {
      x: next.origin.x,
      y: next.origin.y,
      sizeX: next.origin.sizeX,
      sizeY: next.origin.sizeY
    }
    const { pixel } = next.origin
    const rect = next.matrix.contentClientRect
    view.overlay = {
      x: rect.left + pixel.left - next.matrix.scrollLeft,
      y: rect.top + pixel.top - next.matrix.scrollTop,
      width: pixel.width,
      height: pixel.height
    }
  }

  const moveDrag = (clientX: number, clientY: number) => {
    if (!input) return
    const { pointerOffset, matrix, origin } = input
    const overlayX = clientX - pointerOffset.x
    const overlayY = clientY - pointerOffset.y
    view.overlay = {
      x: overlayX,
      y: overlayY,
      width: origin.pixel.width,
      height: origin.pixel.height
    }

    const localX = overlayX - matrix.contentClientRect.left + matrix.scrollLeft
    const localY = overlayY - matrix.contentClientRect.top + matrix.scrollTop
    const maxX = Math.max(1, matrix.cols - origin.sizeX + 1)
    const candidateX = clamp(Math.floor(localX / matrix.cellWidth) + 1, 1, maxX)
    const candidateY = Math.max(1, Math.floor(localY / matrix.cellHeight) + 1)

    const ph = view.placeholder
    if (!ph || ph.x !== candidateX || ph.y !== candidateY) {
      view.placeholder = {
        x: candidateX,
        y: candidateY,
        sizeX: origin.sizeX,
        sizeY: origin.sizeY
      }
    }
  }

  const moveResize = (clientX: number, clientY: number, pixelBox?: SessionOrigin['pixel']) => {
    if (!input) return
    const { matrix, origin, resizeHandle } = input
    const handle = resizeHandle || 'rb'

    // SE / E / S: pin top-left. Never trust legacy style.left/top (official rb path
    // rewrites top via symmetricPoint and can jump the card upward into neighbors/Tab).
    const pinTopLeft = handle === 'rb' || handle === 'r' || handle === 'b'
    const left = pinTopLeft ? origin.pixel.left : pixelBox?.left ?? origin.pixel.left
    const top = pinTopLeft ? origin.pixel.top : pixelBox?.top ?? origin.pixel.top
    let width = pixelBox?.width ?? origin.pixel.width
    let height = pixelBox?.height ?? origin.pixel.height

    if (pinTopLeft) {
      const localX = clientX - matrix.contentClientRect.left + matrix.scrollLeft
      const localY = clientY - matrix.contentClientRect.top + matrix.scrollTop
      if (handle === 'rb' || handle === 'r') {
        width = Math.max(matrix.cellWidth * 0.5, localX - left)
      } else {
        width = origin.pixel.width
      }
      if (handle === 'rb' || handle === 'b') {
        height = Math.max(matrix.cellHeight * 0.5, localY - top)
      } else {
        height = origin.pixel.height
      }
    }

    view.overlay = {
      x: matrix.contentClientRect.left + left - matrix.scrollLeft,
      y: matrix.contentClientRect.top + top - matrix.scrollTop,
      width,
      height
    }

    const rawW = width / matrix.cellWidth
    const rawH = height / matrix.cellHeight
    const prev = view.placeholder || {
      x: origin.x,
      y: origin.y,
      sizeX: origin.sizeX,
      sizeY: origin.sizeY
    }
    const maxSizeX = pinTopLeft ? Math.max(1, matrix.cols - origin.x + 1) : matrix.cols
    const sizeX = hystereticSize(rawW, prev.sizeX, 1, maxSizeX)
    const sizeY = hystereticSize(rawH, prev.sizeY, 1, 999)
    const x = pinTopLeft
      ? origin.x
      : clamp(Math.floor(left / matrix.cellWidth) + 1, 1, Math.max(1, matrix.cols - sizeX + 1))
    const y = pinTopLeft ? origin.y : Math.max(1, Math.floor(top / matrix.cellHeight) + 1)

    if (prev.x !== x || prev.y !== y || prev.sizeX !== sizeX || prev.sizeY !== sizeY) {
      view.placeholder = { x, y, sizeX, sizeY }
    }
  }

  const move = (clientX: number, clientY: number, pixelBox?: SessionOrigin['pixel']) => {
    if (!input || !view.active) return
    if (input.mode === 'drag') {
      moveDrag(clientX, clientY)
    } else {
      moveResize(clientX, clientY, pixelBox)
    }
  }

  const buildCommit = (): SessionCommit | null => {
    if (!input || !view.placeholder) return null
    const ph = view.placeholder
    const changed =
      ph.x !== input.origin.x ||
      ph.y !== input.origin.y ||
      ph.sizeX !== input.origin.sizeX ||
      ph.sizeY !== input.origin.sizeY
    return {
      componentId: input.componentId,
      x: ph.x,
      y: ph.y,
      sizeX: ph.sizeX,
      sizeY: ph.sizeY,
      changed
    }
  }

  /** Commit snapshot then reset. Returns commit if layout should apply. */
  const end = (): SessionCommit | null => {
    const commit = buildCommit()
    reset()
    return commit?.changed ? commit : null
  }

  const cancel = () => {
    reset()
  }

  const isActiveFor = (componentId: string) => view.active && view.componentId === componentId

  return {
    view: readonly(view) as SessionView,
    /** mutable view for template binding without readonly unwrap issues */
    rawView: view,
    start,
    move,
    end,
    cancel,
    isActiveFor,
    getInput: () => input
  }
}

export const dashboardDragSession = createSession()

export function useDashboardDragSession() {
  return dashboardDragSession
}
