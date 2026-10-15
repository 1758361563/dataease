import { onBeforeUnmount, onMounted, ref } from 'vue'

/** Field hints are passive: dismiss on interaction and resume only on a fresh, idle hover. */
export function useFieldNameTooltip() {
  const fieldNameTooltip = ref<{ onClose?: () => void; hide?: () => void }>()
  const fieldNameTooltipBlocked = ref(false)
  const dismissFieldNameTooltip = () => {
    fieldNameTooltipBlocked.value = true
    // Cancel a pending delayed open as well as an already visible tooltip.
    fieldNameTooltip.value?.onClose?.()
    fieldNameTooltip.value?.hide?.()
  }
  const resumeFieldNameTooltip = (event: MouseEvent) => {
    if (event.buttons === 0) fieldNameTooltipBlocked.value = false
  }
  const events = ['pointerdown', 'mousedown', 'dragstart'] as const
  onMounted(() => {
    events.forEach(event => document.addEventListener(event, dismissFieldNameTooltip, true))
  })
  onBeforeUnmount(() => {
    events.forEach(event => document.removeEventListener(event, dismissFieldNameTooltip, true))
  })
  return {
    fieldNameTooltip,
    fieldNameTooltipBlocked,
    dismissFieldNameTooltip,
    resumeFieldNameTooltip
  }
}
