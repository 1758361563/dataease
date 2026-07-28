/**
 * Tab insert-check orchestration (company, extracted from Shape.vue step 5).
 *
 * Per-drag-instance state (candidate/exit timers + the floating "放入「标签」"
 * overlay) that walks sibling DeTabs shapes on every pointer move during a
 * main-canvas drag and decides: not near any tab / hovering a tab's edge
 * (reorder candidate) / hovering a tab's center long enough to commit to
 * insert. Pure orchestration — the actual hit-testing math lives in
 * `tabDropZone.ts`.
 *
 * One instance is created per `Shape.vue` (not a singleton like the drag
 * session), since the overlay is rendered per-shape via `<TabDragOverlay>`.
 */

import { reactive, computed, type ComputedRef, type Ref } from 'vue'
import {
  TAB_CANDIDATE_MS,
  TAB_EXIT_MS,
  TAB_EXIT_PAD_PX,
  canMoveIntoTab,
  getTabContentRect,
  hitTabZone
} from '@/custom/company-bi/composables/tabDropZone'

export interface UseTabInsertCheckDeps {
  dvMainStore: {
    componentData: any[]
    setTabCollisionActiveId: (id: string | null) => void
    setTabMoveInActiveId: (id: string | null) => void
    setTabDragIntent: (intent: string | null) => void
    clearTabDragState: () => void
  }
  element: Ref<any>
  parentNode: Ref<HTMLElement | null>
  isTabMoveCheck: Ref<boolean>
  dashboardActive: ComputedRef<boolean>
  active: ComputedRef<boolean>
  /** static per-mount: whether this element currently sits inside a Tab (see checkJoinTab) */
  pJoinTab: boolean
  tabDragIntent: Ref<string | null>
  tabCollisionActiveId: Ref<string | null>
  tabMoveInActiveId: Ref<string | null>
}

export function useTabInsertCheck(deps: UseTabInsertCheckDeps) {
  const {
    dvMainStore,
    element,
    parentNode,
    isTabMoveCheck,
    dashboardActive,
    active,
    pJoinTab,
    tabDragIntent,
    tabCollisionActiveId,
    tabMoveInActiveId
  } = deps

  let tabCandidateTimer: ReturnType<typeof setTimeout> | null = null
  let tabExitTimer: ReturnType<typeof setTimeout> | null = null
  let tabCandidateTargetId: string | null = null

  const tabOverlay = reactive({
    visible: false,
    x: 0,
    y: 0,
    title: '',
    subtitle: '放入当前页签'
  })

  const tabInsertSource = computed(
    () =>
      active.value &&
      tabDragIntent.value === 'insert' &&
      tabMoveInActiveId.value &&
      element.value?.id
  )

  const clearTabIntentTimers = () => {
    if (tabCandidateTimer) {
      clearTimeout(tabCandidateTimer)
      tabCandidateTimer = null
    }
    if (tabExitTimer) {
      clearTimeout(tabExitTimer)
      tabExitTimer = null
    }
    tabCandidateTargetId = null
  }

  const resetTabDragUi = () => {
    clearTabIntentTimers()
    tabOverlay.visible = false
    dvMainStore.clearTabDragState()
  }

  const tabMoveInCheck = async (clientX?: number, clientY?: number) => {
    if (
      !dashboardActive.value ||
      !pJoinTab ||
      !isTabMoveCheck.value ||
      !canMoveIntoTab(element.value) ||
      !parentNode.value ||
      clientX == null ||
      clientY == null
    ) {
      return
    }

    const px = clientX
    const py = clientY

    if (tabDragIntent.value === 'insert' && tabOverlay.visible) {
      tabOverlay.x = px
      tabOverlay.y = py
    }

    const nodes = Array.from(parentNode.value.childNodes) as HTMLElement[]
    let hitTabId: string | null = null
    let hitZone: 'center' | 'edge-top' | 'edge-bottom' | 'outside' = 'outside'
    let hitTabLabel = '当前页签'

    for (const item of nodes) {
      if (
        !item?.className ||
        typeof item.className !== 'string' ||
        !item.className.split(' ').includes('shape') ||
        item.getAttribute('component-type') !== 'DeTabs' ||
        item.getAttribute('tab-is-check') === 'false'
      ) {
        continue
      }
      const componentId = item.getAttribute('component-id')
      if (!componentId || componentId === element.value.id) continue

      const contentRect = getTabContentRect(item)
      if (!contentRect) continue

      const engaged =
        tabCollisionActiveId.value === componentId || tabMoveInActiveId.value === componentId
      const strictZone = hitTabZone(px, py, contentRect, { exitPad: 0 })
      const paddedZone = engaged
        ? hitTabZone(px, py, contentRect, { exitPad: TAB_EXIT_PAD_PX })
        : strictZone

      if (paddedZone === 'outside') continue

      hitTabId = componentId
      if (engaged && strictZone === 'outside' && paddedZone !== 'outside') {
        hitZone = 'center'
      } else {
        hitZone = strictZone === 'outside' ? paddedZone : strictZone
      }

      const tabComp = dvMainStore.componentData.find(c => c.id === componentId)
      const activePage = tabComp?.propValue?.find(p => p.name === tabComp?.editableTabsValue)
      hitTabLabel = activePage?.title || tabComp?.label || '当前页签'
      break
    }

    if (hitZone === 'center' && hitTabId) {
      if (tabExitTimer) {
        clearTimeout(tabExitTimer)
        tabExitTimer = null
      }

      if (tabMoveInActiveId.value === hitTabId) {
        tabOverlay.visible = true
        tabOverlay.x = px
        tabOverlay.y = py
        tabOverlay.title = element.value.label || element.value.name || '组件'
        tabOverlay.subtitle = `放入「${hitTabLabel}」`
        return
      }

      if (tabCollisionActiveId.value !== hitTabId) {
        dvMainStore.setTabCollisionActiveId(hitTabId)
        dvMainStore.setTabDragIntent('candidate')
      }

      if (tabCandidateTargetId !== hitTabId) {
        if (tabCandidateTimer) clearTimeout(tabCandidateTimer)
        tabCandidateTargetId = hitTabId
        const confirmId = hitTabId
        const confirmLabel = hitTabLabel
        tabCandidateTimer = setTimeout(() => {
          dvMainStore.setTabMoveInActiveId(confirmId)
          dvMainStore.setTabCollisionActiveId(confirmId)
          dvMainStore.setTabDragIntent('insert')
          tabOverlay.visible = true
          tabOverlay.title = element.value.label || element.value.name || '组件'
          tabOverlay.subtitle = `放入「${confirmLabel}」`
          tabCandidateTimer = null
        }, TAB_CANDIDATE_MS)
      }
      return
    }

    if (tabCollisionActiveId.value || tabMoveInActiveId.value) {
      if (tabCandidateTimer) {
        clearTimeout(tabCandidateTimer)
        tabCandidateTimer = null
        tabCandidateTargetId = null
      }
      if (!tabExitTimer) {
        tabExitTimer = setTimeout(() => {
          resetTabDragUi()
          tabExitTimer = null
        }, TAB_EXIT_MS)
      }
    } else {
      clearTabIntentTimers()
    }
  }

  return {
    tabOverlay,
    tabInsertSource,
    tabMoveInCheck,
    resetTabDragUi,
    clearTabIntentTimers
  }
}
