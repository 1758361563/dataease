<script lang="ts" setup>
import ScentBrand from './ScentBrand.vue'
import icon_left_outlined from '@/assets/svg/icon_left_outlined.svg'
import { ElHeader } from 'element-plus-secondary'
import { useRouter } from 'vue-router_2'
import AccountOperator from '@/layout/components/AccountOperator.vue'
import { useI18n } from '@/hooks/web/useI18n'
import { isDesktop } from '@/utils/ModelUtil'
const { push } = useRouter()
const { t } = useI18n()
const desktop = isDesktop()
withDefaults(
  defineProps<{
    title: string
  }>(),
  {}
)
const backToMain = () => {
  push('/panel/index')
}
</script>

<template>
  <el-header class="header-flex system-header scent-navbar">
    <ScentBrand @click="backToMain" />
    <el-divider direction="vertical" />
    <span class="system">{{ title || t('commons.system_setting') }}</span>
    <div class="operate-setting">
      <span @click="backToMain" class="work-bar flex-align-center">
        <el-icon>
          <Icon name="icon_left_outlined"><icon_left_outlined class="svg-icon" /></Icon>
        </el-icon>
        <span class="work">返回仪表板</span>
      </span>

      <AccountOperator v-if="!desktop" />
    </div>
  </el-header>
</template>
