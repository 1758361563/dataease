<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useAppearanceStoreWithOut } from '@/store/modules/appearance'

const appearance = useAppearanceStoreWithOut()
const imageFailed = ref(false)
const logo = computed(
  () => appearance.getNavigate || `${import.meta.env.BASE_URL}branding/company-logo.png`
)
watch(logo, () => (imageFailed.value = false))
</script>

<template>
  <button type="button" class="scent-brand" aria-label="气味数据，返回仪表板">
    <span v-if="!imageFailed" class="scent-brand-mark">
      <img :src="logo" alt="" @error="imageFailed = true" />
    </span>
    <span>气味数据</span>
  </button>
</template>
