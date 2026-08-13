<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, adminErrorMessage } from '../api'
import AdminIcon from '../components/admin/AdminIcon.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import { clearAdminSession, firstAllowedPath, loadAdminSession, safeAdminRedirect } from '../session'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function login() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await adminApi('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    clearAdminSession()
    await loadAdminSession(true)
    const redirect = safeAdminRedirect(router.currentRoute.value.query.redirect, firstAllowedPath())
    password.value = ''
    await router.replace(redirect)
  } catch (cause) {
    error.value = adminErrorMessage(cause, '登录失败，请重试')
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => { password.value = '' })
</script>

<template>
  <main class="login">
    <section class="login-identity" aria-label="宏杉生物商城运营中心">
      <header class="login-brand">
        <img class="mark" src="/logo.png" alt="宏杉生物 Logo" />
        <span><b>宏杉生物 · 商城运营中心</b><small>线下商城运营工作台</small></span>
      </header>
      <div class="login-statement">
        <h1>把订单履约、会员关系与运营审计放在一处。</h1>
        <p>围绕真实业务状态完成审核、发货与会员管理，每一次敏感操作都可追溯。</p>
      </div>
      <footer class="login-boundary">
        <AdminIcon name="security" :size="20" weight="duotone" />
        <span><b>清晰的业务边界</b><small>无在线支付 · 无积分提现 · 关键操作留痕</small></span>
      </footer>
    </section>

    <section class="login-entry">
      <form class="login-card" aria-labelledby="login-title" @submit.prevent="login">
        <div class="login-card__head">
          <span>员工安全入口</span>
          <h2 id="login-title">登录运营后台</h2>
          <p>使用独立的后台管理员账号继续。</p>
        </div>
        <label class="field">
          <span>用户名</span>
          <input v-model.trim="username" required autocomplete="username" inputmode="text" :disabled="busy" />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="password" required type="password" autocomplete="current-password" :disabled="busy" />
        </label>
        <InlineAlert v-if="error" title="登录未完成" :message="error" />
        <button class="primary login-submit" :disabled="busy">
          <span>{{ busy ? '正在验证…' : '安全登录' }}</span>
          <AdminIcon v-if="!busy" name="arrow-right" :size="18" weight="bold" />
          <AdminIcon v-else name="loading" :size="18" weight="bold" />
        </button>
        <p class="login-isolation"><AdminIcon name="security" :size="16" />后台身份与商城会员身份完全隔离</p>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login {
  width: 100%;
  max-width: 100%;
  min-height: 100dvh;
  display: grid;
  grid-template-columns: minmax(420px, 1.08fr) minmax(420px, .92fr);
  background: var(--color-canvas);
}
.login-identity {
  position: relative;
  min-height: 100dvh;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(30px, 4vw, 64px);
  color: #f8f6fa;
  background: #211c25;
  isolation: isolate;
}
.login-identity::before {
  content: '';
  position: absolute;
  z-index: -1;
  inset: 0;
  opacity: .32;
  background-image:
    linear-gradient(rgba(255,255,255,.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,.035) 1px, transparent 1px);
  background-size: 38px 38px;
  mask-image: linear-gradient(to bottom right, black, transparent 72%);
}
.login-identity::after {
  content: '';
  position: absolute;
  z-index: -1;
  right: clamp(-120px, -6vw, -40px);
  bottom: clamp(-180px, -12vw, -80px);
  width: min(42vw, 540px);
  aspect-ratio: 1;
  border: 1px solid rgba(190, 150, 190, .2);
  border-radius: 50%;
  box-shadow: 0 0 0 70px rgba(190, 150, 190, .035), 0 0 0 140px rgba(190, 150, 190, .025);
}
.login-brand { display: flex; align-items: center; gap: 14px; }
.mark {
  width: 58px;
  height: 58px;
  display: block;
  flex: 0 0 58px;
  object-fit: contain;
  border: 1px solid rgba(255,255,255,.15);
  border-radius: var(--radius-md);
  background: #fff;
}
.login-brand b, .login-brand small { display: block; }
.login-brand b { font-size: 17px; letter-spacing: .04em; }
.login-brand small { margin-top: 3px; color: rgba(255,255,255,.55); font-size: 12px; }
.login-statement { max-width: 680px; padding: 10vh 0; }
.login-statement h1 {
  max-width: 12ch;
  margin: 18px 0 22px;
  font-size: clamp(42px, 5.2vw, 74px);
  font-weight: 720;
  line-height: 1.08;
  letter-spacing: -.055em;
}
.login-statement p { max-width: 560px; margin: 0; color: rgba(255,255,255,.62); font-size: 16px; line-height: 1.85; }
.login-boundary { display: flex; align-items: center; gap: 12px; color: rgba(255,255,255,.76); }
.login-boundary b, .login-boundary small { display: block; }
.login-boundary b { font-size: 13px; }
.login-boundary small { margin-top: 3px; color: rgba(255,255,255,.42); font-size: 11px; }
.login-entry { width: 100%; min-width: 0; display: flex; align-items: center; justify-content: center; padding: clamp(28px, 7vw, 104px); }
.login-card { width: 100%; max-width: 430px; min-width: 0; display: grid; gap: 18px; }
.login-card__head { margin-bottom: 12px; }
.login-card__head > span { color: var(--color-brand); font-size: 12px; font-weight: 800; letter-spacing: .12em; }
.login-card h2 { margin: 10px 0 8px; font-size: clamp(28px, 3vw, 36px); font-weight: 760; letter-spacing: -.035em; }
.login-card__head p { margin: 0; color: var(--color-text-muted); }
.login-card .field { min-width: 0; gap: 8px; }
.login-card .field > span { color: var(--color-text); font-size: 13px; }
.login-card .field input { width: 100%; max-width: 100%; min-height: 48px; padding-inline: 14px; }
.login-submit { width: 100%; max-width: 100%; min-height: 48px; display: inline-flex; align-items: center; justify-content: space-between; margin-top: 2px; padding-inline: 17px; }
.login-submit :deep(.admin-icon:last-child) { transition: transform .18s ease; }
.login-submit:hover:not(:disabled) :deep(.admin-icon:last-child) { transform: translateX(3px); }
.login-submit :deep(.admin-icon[name='loading']) { animation: spin .8s linear infinite; }
.login-isolation { display: flex; align-items: center; justify-content: center; gap: 7px; margin: 0; color: var(--color-text-muted); font-size: 12px; }
@media (max-width: 900px) {
  .login { width: 100%; min-width: 0; grid-template-columns: minmax(0, 1fr); }
  .login-identity, .login-entry { width: 100%; min-width: 0; }
  .login-identity { min-height: 310px; padding: 26px 24px 34px; }
  .login-statement { padding: 44px 0 20px; }
  .login-statement h1 { max-width: 15ch; margin-block: 13px; font-size: clamp(34px, 8vw, 48px); }
  .login-statement p { max-width: 640px; font-size: 14px; }
  .login-boundary { display: none; }
  .login-entry { padding: 42px 22px 56px; }
}
@media (max-width: 480px) {
  .login-identity { min-height: 270px; }
  .login-statement { padding-top: 30px; }
  .login-statement h1 { font-size: 32px; }
  .login-statement p { display: none; }
  .login-entry { padding-inline: 18px; }
  .login-card { width: 100%; max-width: 100%; }
}
@media (prefers-reduced-motion: reduce) {
  .login-submit :deep(.admin-icon:last-child) { transition: none; }
}
</style>
