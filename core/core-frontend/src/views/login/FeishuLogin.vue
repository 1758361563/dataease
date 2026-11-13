<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import request from '@/config/axios'
import router from '@/router'
import { useUserStoreWithOut } from '@/store/modules/user'
import { cleanPlatformFlag } from '@/utils/utils'

const enabled = ref(false)
const active = ref(false)
const loading = ref(false)
const error = ref('')
const expired = ref(false)
const emit = defineEmits<{ (event: 'active', value: boolean): void }>()
const container = ref<HTMLElement>()
let listener: ((event: MessageEvent) => void) | undefined
let timer: ReturnType<typeof setTimeout>
let generation = 0
const storageKey = 'company.feishu.login'
const scripts = new Map<string, Promise<void>>()
function loadScript(url: string): Promise<void> {
  if (!scripts.has(url))
    scripts.set(
      url,
      new Promise((resolve, reject) => {
        const script = document.createElement('script')
        const timeout = setTimeout(() => {
          scripts.delete(url)
          script.remove()
          reject(new Error('飞书组件加载超时，请重试'))
        }, 15000)
        script.src = url
        script.onload = () => {
          clearTimeout(timeout)
          resolve()
        }
        script.onerror = () => {
          clearTimeout(timeout)
          scripts.delete(url)
          script.remove()
          reject(new Error('飞书组件加载失败，请重试'))
        }
        document.head.appendChild(script)
      })
    )
  return scripts.get(url) || Promise.reject(new Error('飞书组件加载失败'))
}
function cleanup() {
  generation++
  clearTimeout(timer)
  if (listener) window.removeEventListener('message', listener)
  listener = undefined
}
function select(value: boolean) {
  active.value = value
  emit('active', value)
  if (value) start()
  else {
    cleanup()
    loading.value = false
  }
}
function safePath(value: unknown) {
  return typeof value === 'string' &&
    /^\/(?!\/)/.test(value) &&
    !/[\\\r\n]/.test(value) &&
    !value.startsWith('/login')
    ? value
    : '/panel/index'
}
async function complete(code: string, state: string) {
  const current = generation
  const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
  sessionStorage.removeItem(storageKey)
  if (!saved || saved.state !== state || saved.expiresAt < Date.now())
    throw new Error('登录请求已过期，请重新扫码')
  const result = await request.post({
    url: '/login/feishu/complete',
    data: { code, state, verifier: saved.verifier }
  })
  if (current !== generation) return
  if (!result.data?.token) throw new Error('登录未完成，请重试')
  cleanPlatformFlag()
  const user = useUserStoreWithOut()
  user.setToken(result.data.token)
  user.setExp(result.data.exp)
  user.setTime(Date.now())
  await router.replace({ path: safePath(saved.redirect) })
}
function qrRedirect(
  event: MessageEvent,
  qr: { matchOrigin: (origin: string) => boolean; matchData: (data: unknown) => boolean },
  frame: HTMLIFrameElement | null | undefined,
  authorizeUrl: string
) {
  if (
    !['https://passport.feishu.cn', 'https://accounts.feishu.cn'].includes(event.origin) ||
    !qr.matchOrigin(event.origin) ||
    !qr.matchData(event.data) ||
    !frame ||
    event.source !== frame.contentWindow ||
    typeof event.data?.tmp_code !== 'string' ||
    !event.data.tmp_code ||
    event.data.tmp_code.length > 2048
  )
    return null
  const url = new URL(authorizeUrl)
  url.searchParams.set('tmp_code', event.data.tmp_code)
  return url.toString()
}
async function start() {
  cleanup()
  const current = generation
  loading.value = true
  error.value = ''
  expired.value = false
  try {
    const inClient = /Feishu|Lark/i.test(navigator.userAgent)
    const bytes = crypto.getRandomValues(new Uint8Array(32))
    const verifier = btoa(String.fromCharCode(...bytes))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
    const result = await request.post({
      url: '/login/feishu/start',
      data: { verifier, mode: inClient ? 'CLIENT' : 'QR' }
    })
    if (current !== generation) return
    const attempt = result.data
    if (!attempt?.state) throw new Error('无法创建登录请求')
    sessionStorage.setItem(
      storageKey,
      JSON.stringify({
        ...attempt,
        verifier,
        redirect: safePath(router.currentRoute.value.query.redirect)
      })
    )
    timer = setTimeout(() => {
      expired.value = true
      loading.value = false
      cleanup()
    }, Math.max(0, attempt.expiresAt - Date.now()))
    if (inClient) {
      await loadScript('https://lf1-cdn-tos.bytegoofy.com/goofy/lark/op/h5-js-sdk-1.5.42.js')
      if (current !== generation) return
      const tt = (window as any).tt
      const code = await new Promise<string>((resolve, reject) => {
        const fail = () => reject(new Error('飞书授权未完成，请重试'))
        const success = (res: { code: string }) => resolve(res.code)
        const fallback = () =>
          tt?.requestAuthCode ? tt.requestAuthCode({ appId: attempt.appId, success, fail }) : fail()
        if (tt?.requestAccess)
          tt.requestAccess({
            appID: attempt.appId,
            scopeList: [],
            success,
            fail: (reason: { errno?: number }) => (reason?.errno === 103 ? fallback() : fail())
          })
        else fallback()
      })
      if (current === generation) await complete(code, attempt.state)
    } else {
      await loadScript(
        'https://lf-package-cn.feishucdn.com/obj/feishu-static/lark/passport/qrcode/LarkSSOSDKWebQRCode-1.0.3.js'
      )
      if (current !== generation) return
      await nextTick()
      if (container.value) container.value.replaceChildren()
      const qr = (window as any).QRLogin({
        id: 'company-feishu-qr',
        goto: attempt.authorizeUrl,
        width: '280',
        height: '280',
        style: 'border:0;display:block;'
      })
      listener = (event: MessageEvent) => {
        if (current !== generation) return
        const redirect = qrRedirect(
          event,
          qr,
          container.value?.querySelector('iframe'),
          attempt.authorizeUrl
        )
        if (redirect) window.location.assign(redirect)
      }
      window.addEventListener('message', listener)
    }
  } catch (e) {
    if (current === generation)
      error.value = e instanceof Error ? e.message : '飞书登录失败，请重试'
  } finally {
    if (current === generation) loading.value = false
  }
}
onMounted(async () => {
  const query = router.currentRoute.value.query
  try {
    enabled.value = (await request.get({ url: '/login/feishu/config' })).data?.enabled === true
    if (query.feishu_code || query.feishu_error) {
      active.value = true
      emit('active', true)
      loading.value = true
      const code = String(query.feishu_code || ''),
        state = String(query.feishu_state || '')
      await router.replace({ path: '/login', query: { feishu_return: '1' } })
      if (!code) throw new Error('飞书授权已取消，请重试')
      await complete(code, state)
    } else if (enabled.value && (query.feishu === '1' || /Feishu|Lark/i.test(navigator.userAgent)))
      select(true)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '飞书登录失败，请重试'
  } finally {
    if (query.feishu_code || query.feishu_error) loading.value = false
  }
})
onBeforeUnmount(cleanup)
</script>
<template>
  <div v-if="enabled || active" class="feishu-login">
    <div class="tabs">
      <button type="button" :class="{ selected: !active }" @click="select(false)">账号密码</button
      ><button type="button" :class="{ selected: active }" @click="select(true)">飞书登录</button>
    </div>
    <div v-if="active" class="qr-panel" v-loading="loading">
      <div v-show="!error && !expired" id="company-feishu-qr" ref="container" />
      <p v-if="error || expired" role="alert">{{ error || '二维码已过期' }}</p>
      <p v-else>使用飞书扫码，或在飞书客户端中授权登录</p>
      <el-button v-if="!loading" text @click="start">{{
        error || expired ? '重新登录' : '刷新二维码'
      }}</el-button>
    </div>
  </div>
</template>
<style scoped>
.tabs {
  display: flex;
  gap: 24px;
  margin-bottom: 24px;
  border-bottom: 1px solid #e8ecf2;
}

.tabs button {
  padding: 10px 0;
  font-size: 15px;
  color: #8791a3;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.tabs button.selected {
  color: #536db4;
  box-shadow: 0 2px #536db4;
}

.qr-panel {
  display: flex;
  min-height: 320px;
  text-align: center;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}

.qr-panel p {
  max-width: 300px;
  font-size: 13px;
  line-height: 1.6;
  color: #7b879b;
}
</style>
