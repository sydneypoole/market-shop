<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, safeRedirect } from '../api'

const route = useRoute()
const router = useRouter()
const inviteCode = ref(String(route.query.inviteCode || 'BOOTSTRAP2026'))
const nickname = ref('微信演示用户')
const error = ref('')
const busy = ref(false)
const busyWechat = ref<'H5' | 'WEB'>()
const sessionMessage = computed(() => {
  switch (String(route.query.reason || '')) {
    case 'session-expired': return '登录状态已失效，请重新登录后继续。'
    case 'login-required': return '该页面需要登录，请先完成微信登录。'
    case 'session-check-failed': return '暂时无法确认登录状态，请重新登录或稍后重试。'
    default: return ''
  }
})

async function devLogin() {
  busy.value = true
  error.value = ''
  try {
    await api('/auth/dev-login', {
      method: 'POST',
      body: JSON.stringify({
        openId: `local-${Date.now()}`,
        nickname: nickname.value,
        inviteCode: inviteCode.value
      })
    })
    await router.replace(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function wechat(scene: 'H5' | 'WEB') {
  if (busyWechat.value) return
  busyWechat.value = scene
  error.value = ''
  try {
    const result = await api<{ authorizationUrl: string }>('/auth/wechat/authorize?' + new URLSearchParams({
      scene,
      inviteCode: inviteCode.value,
      redirectUri: `${location.origin}${safeRedirect(route.query.redirect)}`
    }))
    location.href = result.authorizationUrl
  } catch (e) {
    error.value = (e as Error).message
    busyWechat.value = undefined
  }
}
</script>

<template>
  <div class="login-page">
    <section class="login-story">
      <RouterLink class="login-brand" to="/"><b>拾</b>拾光优选</RouterLink>
      <div>
        <span class="eyebrow">Trusted Commerce</span>
        <h1>每一次连接，<br />都从一份信任开始。</h1>
        <p>首次注册需要朋友的邀请码。直属关系一旦绑定不可自行修改，所有任务只计算一层直属关系。</p>
      </div>
      <small>线下确认 · 平台审核 · 安心履约</small>
    </section>
    <section class="login-panel">
      <div class="login-card card">
        <span class="eyebrow">Welcome</span>
        <h2>微信登录 / 注册</h2>
        <p class="muted">系统会根据当前设备选择公众号授权或网页扫码。</p>
        <p v-if="sessionMessage" class="session-message" role="status">{{ sessionMessage }}</p>
        <div class="field">
          <label for="invite">邀请码（首次注册必填）</label>
          <input id="invite" v-model="inviteCode" autocomplete="off" placeholder="输入朋友的邀请码" />
        </div>
        <button class="wechat" :disabled="Boolean(busyWechat)" type="button" @click="wechat('H5')">
          {{ busyWechat === 'H5' ? '正在跳转微信…' : '微信 H5 授权登录' }}
        </button>
        <button class="secondary wide" :disabled="Boolean(busyWechat)" type="button" @click="wechat('WEB')">
          {{ busyWechat === 'WEB' ? '正在打开二维码…' : '电脑端微信扫码登录' }}
        </button>
        <details>
          <summary>本地开发演示入口</summary>
          <div class="field">
            <label for="nickname">演示昵称</label>
            <input id="nickname" v-model="nickname" />
          </div>
          <button class="primary wide" :disabled="busy" type="button" @click="devLogin">
            {{ busy ? '正在创建会话…' : '进入本地演示' }}
          </button>
        </details>
        <p v-if="error" class="error">{{ error }}</p>
        <p class="privacy">登录即表示同意：积分不可提现、不可转账、不可兑换现金；商城不提供在线支付。</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page { min-height: 100vh; display: grid; grid-template-columns: 1fr 1fr; background: var(--paper); }
.login-story { display: flex; flex-direction: column; justify-content: space-between; padding: 42px max(40px, 8vw); color: white; background: #294b41; overflow: hidden; position: relative; }
.login-story::after { content: ""; position: absolute; width: 480px; height: 480px; border: 90px solid rgba(244,93,72,.75); border-radius: 50%; right: -250px; bottom: -230px; }
.login-brand { font-weight: 800; letter-spacing: .1em; }
.login-brand b { display: inline-grid; place-items: center; width: 36px; height: 36px; margin-right: 10px; background: var(--coral); border-radius: 11px; }
.login-story h1 { font: 700 clamp(44px, 5vw, 70px)/1.18 "Songti SC", serif; margin: 18px 0; }
.login-story p { max-width: 520px; opacity: .72; line-height: 1.8; }
.login-panel { display: grid; place-items: center; padding: 30px; background: #f8f5ee; }
.login-card { width: min(460px, 100%); padding: 36px; }
.login-card h2 { font: 700 32px "Songti SC", serif; margin: 10px 0; }
.login-card .field { margin: 24px 0 14px; }
.wechat, .wide { width: 100%; min-height: 48px; border: 0; border-radius: 12px; font-weight: 800; margin-top: 10px; }
.wechat { color: white; background: #1aad19; }
details { border-top: 1px solid var(--line); margin-top: 22px; padding-top: 18px; }
summary { color: var(--muted); cursor: pointer; font-size: 13px; }
details .field { margin: 16px 0 4px; }
.privacy { color: #8f8680; font-size: 12px; line-height: 1.6; margin-top: 22px; }
.session-message { padding: 11px 12px; color: #79531c; background: #fff2d8; border-radius: 10px; }
.wechat:disabled { opacity: .55; cursor: not-allowed; }
@media (max-width: 760px) {
  .login-page { grid-template-columns: 1fr; }
  .login-story { min-height: 310px; padding: 28px 22px; }
  .login-story h1 { font-size: 40px; }
  .login-story small { display: none; }
  .login-panel { padding: 20px 14px 42px; }
  .login-card { padding: 25px 20px; margin-top: -34px; z-index: 2; }
}
</style>
