<script lang="ts" setup>
import ScentBrand from './ScentBrand.vue'
import logo_sqlbot from '@/assets/svg/logo_sqlbot.svg'
import msgNotice from '@/assets/svg/icon_notification_outlined.svg'
import dvPreviewDownload from '@/assets/svg/scent/download.svg'
import { computed, onMounted, ref } from 'vue'
import { usePermissionStore } from '@/store/modules/permission'
import { isExternal } from '@/utils/validate'
import { formatRoute } from '@/router/establish'
import HeaderMenuItem from './HeaderMenuItem.vue'
import { NAV_MENU_TIMING } from './navMenu'
import { useEmitt } from '@/hooks/web/useEmitt'
import { Icon } from '@/components/icon-custom'
import SystemCfg from './SystemCfg.vue'
import ToolboxCfg from './ToolboxCfg.vue'
import { useRouter, useRoute } from 'vue-router_2'
import AccountOperator from '@/layout/components/AccountOperator.vue'
import { isDesktop } from '@/utils/ModelUtil'
import { XpackComponent } from '@/components/plugin'
import DesktopSetting from './DesktopSetting.vue'
import request from '@/config/axios'

const { push } = useRouter()
const route = useRoute()
import { useCache } from '@/hooks/web/useCache'
import { useI18n } from '@/hooks/web/useI18n'
import { msgCountApi } from '@/api/msg'
const { wsCache } = useCache('localStorage')
const handleIconClick = () => {
  if (route.path === '/panel/index') return
  push('/panel/index')
}

const { t } = useI18n()

const handleSQLBotClick = () => {
  push('/sqlbot/index')
}
const sqlbotEnabled = ref(false)
const desktop = isDesktop()
const activeIndex = computed(() => {
  if (route.path.includes('system')) {
    return '/system/user'
  }
  return route.path
})

const permissionStore = usePermissionStore()
const downloadClick = params => {
  useEmitt().emitter.emit('data-export-center', params)
}
const routers: any[] = formatRoute(
  permissionStore.getRoutersNotHidden as AppCustomRouteRecordRaw[]
).filter(item => !/^\/(?:workbranch|screen)(?:\/|$)/.test(item.path))
const showSystem = ref(false)
const showMsg = ref(false)
const showToolbox = ref(false)
const handleSelect = (index: string) => {
  // 自定义事件
  if (isExternal(index)) {
    const openType = wsCache.get('open-backend') === '1' ? '_self' : '_blank'
    window.open(index, openType)
  } else {
    push(index)
  }
}
const initShowSystem = () => {
  showSystem.value = permissionStore.getRouters.some(route => route.path === '/system')
}
const initShowMsg = () => {
  showMsg.value = permissionStore.getRouters.some(route => route.path === '/msg')
}
const initShowToolbox = () => {
  showToolbox.value = permissionStore.getRouters.some(route => route.path === '/toolbox')
}

const msgNoticePush = () => {
  push('/msg/msg-fill')
}

const badgeCount = ref('0')
const loadSqlbotInfo = () => {
  const url = '/sysParameter/sqlbot'
  request.get({ url }).then(res => {
    if (res && res.data) {
      const { domain, id, enabled, valid } = res.data
      sqlbotEnabled.value = domain && id && enabled && valid
    }
  })
}
onMounted(() => {
  loadSqlbotInfo()
  initShowSystem()
  initShowToolbox()
  initShowMsg()

  msgCountApi().then(res => {
    badgeCount.value = (res?.data > 99 ? '99+' : res?.data) || '0'
  })
})
</script>

<template>
  <el-header class="header-flex scent-navbar">
    <ScentBrand @click="handleIconClick" />
    <el-menu
      :default-active="activeIndex"
      mode="horizontal"
      :ellipsis="false"
      menu-trigger="hover"
      close-on-click-outside
      v-bind="NAV_MENU_TIMING"
      @select="handleSelect"
      effect="light"
    >
      <HeaderMenuItem v-for="menu in routers" :key="menu.path" :menu="menu"></HeaderMenuItem>
    </el-menu>
    <div class="operate-setting" v-if="!desktop">
      <XpackComponent jsname="c3dpdGNoZXI=" />
      <el-tooltip offset="6" effect="dark" content="SQLBot" placement="bottom">
        <el-icon style="margin: 0 10px" class="ai-icon copilot-icon" v-if="sqlbotEnabled">
          <Icon name="copilot"><logo_sqlbot @click="handleSQLBotClick" class="svg-icon" /></Icon>
        </el-icon>
      </el-tooltip>

      <el-tooltip
        offset="10"
        effect="dark"
        :content="t('data_export.export_center')"
        placement="bottom"
      >
        <button
          type="button"
          class="scent-action-button"
          :aria-label="t('data_export.export_center')"
          @click="downloadClick"
        >
          <dvPreviewDownload />
        </button>
      </el-tooltip>

      <ToolboxCfg v-if="showToolbox" />
      <el-tooltip
        v-if="showMsg"
        effect="dark"
        offset="10"
        :content="$t('v_query.msg_center')"
        placement="bottom"
      >
        <el-badge
          style="margin-right: 10px"
          :hidden="[0, '0'].includes(badgeCount)"
          :value="badgeCount"
          class="ed-badge_custom"
        >
          <el-icon class="preview-download_icon">
            <Icon><msgNotice @click="msgNoticePush" class="svg-icon" /></Icon>
          </el-icon>
        </el-badge>
      </el-tooltip>

      <SystemCfg v-if="showSystem" />
      <AccountOperator />
    </div>
    <div v-else class="operate-setting">
      <desktop-setting />
    </div>
  </el-header>
</template>
