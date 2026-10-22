<script lang="ts" setup>
import userImg from '@/assets/svg/scent/user-round.svg'
import chevronDown from '@/assets/svg/scent/chevron-down.svg'
import chevronRight from '@/assets/svg/scent/chevron-right.svg'
import settingsIcon from '@/assets/svg/scent/settings-2.svg'
import keyIcon from '@/assets/svg/scent/key-round.svg'
import infoIcon from '@/assets/svg/scent/info.svg'
import languagesIcon from '@/assets/svg/scent/languages.svg'
import logoutIcon from '@/assets/svg/scent/log-out.svg'
import { NAV_MENU_TIMING } from './navMenu'
import { computed, ref, watch } from 'vue'
import { useUserStoreWithOut } from '@/store/modules/user'
import { logoutApi } from '@/api/login'
import { logoutHandler } from '@/utils/logout'
import { XpackComponent } from '@/components/plugin'
import { useI18n } from '@/hooks/web/useI18n'
import { useEmitt } from '@/hooks/web/useEmitt'
import AboutPage from '@/views/about/index.vue'
import LangSelector from './LangSelector.vue'
import router from '@/router'
import { useCache } from '@/hooks/web/useCache'
import { useAppearanceStoreWithOut } from '@/store/modules/appearance'
const appearanceStore = useAppearanceStoreWithOut()
const { wsCache } = useCache()
const userStore = useUserStoreWithOut()
const { t } = useI18n()

interface LinkItem {
  id: number
  label: string
  link?: string
  method?: string
}
const linkList = ref([{ id: 5, label: t('common.about'), method: 'toAbout' }] as LinkItem[])
if (!appearanceStore.getShowAbout) {
  linkList.value.splice(0, 1)
}

const inPlatformClient = computed(() => !!wsCache.get('de-platform-client'))

const logout = async () => {
  closeAccount()
  await logoutApi()
  logoutHandler()
}

const linkLoaded = items => {
  items.forEach(item => linkList.value.push(item))
  linkList.value.sort(compare('id'))
}
const xpackLinkLoaded = items => {
  let len = linkList.value.length
  while (len--) {
    if (linkList.value[len]?.id === 2 && linkList.value[len]?.link === '/modify-pwd/index') {
      linkList.value.splice(len, 1)
    }
  }
  items.forEach(item => linkList.value.push(item))
  if (inPlatformClient.value) {
    len = linkList.value.length
    while (len--) {
      if (linkList.value[len]?.id === 2) {
        linkList.value.splice(len, 1)
      }
    }
  }
  linkList.value.sort(compare('id'))
}

const compare = (property: string) => {
  return (a, b) => a[property] - b[property]
}

const toAbout = () => {
  useEmitt().emitter.emit('open-about-dialog')
}

const executeMethod = (item: LinkItem) => {
  closeAccount()
  if (item?.method) {
    toAbout()
  }

  if (item.link) {
    router.push(item.link)
  }
}

const name = computed(() => userStore.getName)
const uid = computed(() => userStore.getUid)

const accountMenu = ref()
const closeAccount = () => {
  accountMenu.value?.close('account-language')
  accountMenu.value?.close('account')
}
watch(() => router.currentRoute.value.fullPath, closeAccount)
const accountLinkIcon = (item: LinkItem) =>
  item.method === 'toAbout' ? infoIcon : item.link?.includes('modify-pwd') ? keyIcon : settingsIcon

if (uid.value === '1') {
  linkLoaded([{ id: 4, link: '/sys-setting/parameter', label: t('commons.system_setting') }])
  const desktop = wsCache.get('app.desktop')
  if (!desktop) {
    linkLoaded([{ id: 2, link: '/modify-pwd/index', label: t('user.change_password') }])
  }
}
</script>

<template>
  <el-menu
    ref="accountMenu"
    mode="horizontal"
    :ellipsis="false"
    menu-trigger="hover"
    class="scent-account-menu"
    close-on-click-outside
    v-bind="NAV_MENU_TIMING"
    @select="closeAccount"
  >
    <el-sub-menu
      index="account"
      popper-class="scent-nav-popper scent-account-popper"
      v-bind="NAV_MENU_TIMING"
      :expand-close-icon="chevronDown"
      :expand-open-icon="chevronDown"
    >
      <template #title>
        <div class="top-info-container" :aria-label="name">
          <el-icon class="main-color"><userImg /></el-icon>
          <span class="uname-span">{{ name }}</span>
        </div>
      </template>
      <li class="scent-account-identity" role="presentation">
        <span>{{ name }}</span
        ><small>{{ 'ID: ' + uid }}</small>
      </li>
      <el-menu-item
        v-for="link in linkList"
        :key="link.id"
        :index="'account-link-' + link.id"
        @click="executeMethod(link)"
      >
        <component :is="accountLinkIcon(link)" /><span>{{ link.label }}</span>
      </el-menu-item>
      <el-sub-menu
        index="account-language"
        popper-class="scent-nav-popper scent-language-popper"
        v-bind="NAV_MENU_TIMING"
        :expand-close-icon="chevronRight"
        :expand-open-icon="chevronRight"
      >
        <template #title
          ><languagesIcon /><span>{{ t('commons.language') }}</span></template
        >
        <li class="scent-language-options" role="presentation"><LangSelector /></li>
      </el-sub-menu>
      <el-menu-item
        v-if="!inPlatformClient"
        index="account-logout"
        class="scent-account-logout"
        @click="logout"
      >
        <logoutIcon /><span>{{ t('common.exit_system') }}</span>
      </el-menu-item>
    </el-sub-menu>
  </el-menu>
  <AboutPage />
  <XpackComponent jsname="dWNlbnRlci1oYW5kbGVy" @loaded="xpackLinkLoaded" />
</template>
