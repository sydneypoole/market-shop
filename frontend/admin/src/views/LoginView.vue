<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api'
import { clearAdminSession, firstAllowedPath, loadAdminSession } from '../session'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function login() {
  busy.value = true
  error.value = ''
  try {
    await adminApi('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    clearAdminSession()
    await loadAdminSession(true)
    const redirect = typeof router.currentRoute.value.query.redirect === 'string'
      ? router.currentRoute.value.query.redirect
      : firstAllowedPath()
    await router.replace(redirect)
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login">
    <section class="login-art">
      <div class="mark">拾</div>
      <div><span>MARKET OPERATIONS</span><h1>让每一笔线下订单，<br />都有清晰的去向。</h1><p>审核、发货、会员任务与积分账本在同一条可追溯链路中完成。</p></div>
      <small>敏感操作实行职责分离与审计留痕</small>
    </section>
    <section class="login-form">
      <form class="card" @submit.prevent="login">
        <span class="overline">STAFF ACCESS</span>
        <h2>运营后台登录</h2>
        <p>后台身份与商城用户身份完全隔离。</p>
        <div class="field"><label>用户名</label><input v-model="username" required autocomplete="username" /></div>
        <div class="field"><label>密码</label><input v-model="password" required type="password" autocomplete="current-password" /></div>
        <p v-if="error" class="error">{{ error }}</p>
        <button class="primary" :disabled="busy">{{ busy ? '正在验证…' : '安全登录' }}</button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.login { min-height: 100vh; display: grid; grid-template-columns: 1.1fr .9fr; color: white; background: var(--ink); }
.login-art { display: flex; flex-direction: column; justify-content: space-between; padding: 52px 8vw; background: radial-gradient(circle at 90% 10%, #c95340 0 0, transparent 36%), linear-gradient(135deg, #1d2a25, #31594e); }
.mark { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 14px; background: var(--coral); font: 700 26px serif; }
.login-art span { color: #e9a78a; font-size: 12px; letter-spacing: .18em; }
.login-art h1 { font: 700 clamp(42px, 5vw, 68px)/1.2 "Songti SC", serif; margin: 18px 0; }
.login-art p { max-width: 600px; color: rgba(255,255,255,.65); line-height: 1.8; }
.login-art small { opacity: .45; }
.login-form { display: grid; place-items: center; padding: 30px; background: #f2f4f1; }
.login-form form { width: min(420px, 100%); padding: 36px; color: var(--ink); }
.overline { color: var(--coral); letter-spacing: .15em; font-size: 11px; font-weight: 800; }
.login-form h2 { font: 700 30px serif; margin: 10px 0; }
.login-form form > p { color: var(--muted); margin-bottom: 26px; }
.login-form .field { margin-top: 16px; }
.login-form .primary { width: 100%; min-height: 46px; margin-top: 20px; }
@media (max-width: 720px) {
  .login { grid-template-columns: 1fr; }
  .login-art { min-height: 320px; padding: 28px 22px; }
  .login-art h1 { font-size: 38px; }
  .login-art small { display: none; }
  .login-form { padding: 16px 12px 40px; }
  .login-form form { margin-top: -38px; z-index: 2; padding: 26px 20px; }
}
</style>
