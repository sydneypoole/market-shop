<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi } from './api'
import { adminSession, can, clearAdminSession } from './session'

const route = useRoute()
const router = useRouter()
const admin = computed(() => adminSession.current)
const publicPage = computed(() => Boolean(route.meta.public))
const menuOpen = ref(false)
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const passwordError = ref('')

async function logout() {
  await adminApi('/auth/logout', { method: 'POST' })
  clearAdminSession()
  await router.replace('/login')
}

async function changePassword() {
  passwordError.value = ''
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  try {
    await adminApi('/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({
        currentPassword: passwordForm.value.currentPassword,
        newPassword: passwordForm.value.newPassword
      })
    })
    if (admin.value) admin.value.mustChangePassword = false
    passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
  } catch (e) { passwordError.value = (e as Error).message }
}
</script>

<template>
  <RouterView v-if="publicPage" />
  <div v-else class="admin-shell">
    <aside :class="{ open: menuOpen }">
      <div class="admin-brand"><b>拾</b><span>拾光优选<small>运营控制台</small></span></div>
      <nav @click="menuOpen = false">
        <RouterLink v-if="can('order:read')" to="/"><i>⌂</i>业务概览</RouterLink>
        <RouterLink v-if="can('order:read')" to="/orders"><i>≡</i>订单审核</RouterLink>
        <RouterLink v-if="can('catalog:read')" to="/catalog"><i>◇</i>商品与库存</RouterLink>
        <RouterLink v-if="can('rule:publish')" to="/rules"><i>⌘</i>规则版本</RouterLink>
        <RouterLink v-if="can('aftersale:review')" to="/after-sales"><i>↩</i>售后处理</RouterLink>
        <RouterLink v-if="can('member:read')" to="/members"><i>◎</i>会员管理</RouterLink>
        <RouterLink v-if="can('content:write')" to="/content"><i>▤</i>内容运营</RouterLink>
        <RouterLink v-if="can('storefront:template:manage')" to="/templates"><i>▦</i>商城模板</RouterLink>
        <RouterLink v-if="can('admin:account:manage')" to="/accounts"><i>⚿</i>账号权限</RouterLink>
        <RouterLink v-if="can('audit:read')" to="/audit"><i>◉</i>审计日志</RouterLink>
        <RouterLink v-if="can('system:setting:manage')" to="/settings"><i>⚙</i>系统配置</RouterLink>
      </nav>
      <div class="safety">安全边界<br /><small>无在线支付 · 无积分提现<br />奖励关系深度固定 1 层</small></div>
    </aside>
    <div v-if="menuOpen" class="backdrop" @click="menuOpen = false"></div>
    <section class="workspace">
      <header>
        <button class="menu-button" @click="menuOpen = true">☰</button>
        <div><span>商城环境</span><b>本地演示</b></div>
        <div class="admin-user"><span>{{ admin?.displayName || '后台用户' }}<small>{{ admin?.username }}</small></span><button @click="logout">退出</button></div>
      </header>
      <main><RouterView /></main>
    </section>
    <div v-if="admin?.mustChangePassword" class="modal-mask forced">
      <form class="modal card" @submit.prevent="changePassword">
        <h2>首次登录必须修改密码</h2>
        <p>新密码需为 12–72 位，并同时包含字母和数字。改密前其他后台功能保持锁定。</p>
        <div class="field"><label>当前临时密码</label><input v-model="passwordForm.currentPassword" type="password" required /></div>
        <div class="field"><label>新密码</label><input v-model="passwordForm.newPassword" type="password" minlength="12" required /></div>
        <div class="field"><label>确认新密码</label><input v-model="passwordForm.confirmPassword" type="password" minlength="12" required /></div>
        <p v-if="passwordError" class="error">{{ passwordError }}</p>
        <button class="primary">修改密码并解锁后台</button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.forced{z-index:100}.forced .field{margin-top:13px}.forced .primary{width:100%;margin-top:16px}
</style>
