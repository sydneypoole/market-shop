<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminErrorMessage } from './api'
import { adminNavigation, adminNavigationGroups } from './admin-navigation'
import BaseDialog from './components/admin/BaseDialog.vue'
import InlineAlert from './components/admin/InlineAlert.vue'
import ToastRegion from './components/admin/ToastRegion.vue'
import { adminSession, can, clearAdminSession } from './session'
import { notifyError, notifySuccess } from './toast'

const route = useRoute()
const router = useRouter()
const admin = computed(() => adminSession.current)
const publicPage = computed(() => Boolean(route.meta.public))
const menuOpen = ref(false)
const menuButton = ref<HTMLButtonElement>()
const sidebar = ref<HTMLElement>()
const logoutBusy = ref(false)
const changeBusy = ref(false)
const passwordForm = ref({ currentPassword: '', newPassword: '', confirmPassword: '' })
const passwordError = ref('')

const visibleGroups = computed(() => adminNavigationGroups.flatMap(group => {
  const items = adminNavigation.filter(item => item.group === group.id && can(item.permission))
  return items.length ? [{ ...group, items }] : []
}))

function clearPasswordForm() {
  passwordForm.value = { currentPassword: '', newPassword: '', confirmPassword: '' }
  passwordError.value = ''
}

async function openMenu() {
  menuOpen.value = true
  await nextTick()
  sidebar.value?.querySelector<HTMLElement>('a, button')?.focus()
}

function closeMenu(restoreFocus = true) {
  if (!menuOpen.value) return
  menuOpen.value = false
  if (restoreFocus) void nextTick(() => menuButton.value?.focus())
}

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && menuOpen.value) closeMenu()
}

function onSidebarKeydown(event: KeyboardEvent) {
  if (event.key !== 'Tab' || !menuOpen.value || !sidebar.value) return
  const controls = Array.from(sidebar.value.querySelectorAll<HTMLElement>('a, button:not([disabled])'))
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(() => route.fullPath, () => closeMenu(false))
watch(menuOpen, open => {
  document.body.classList.toggle('admin-menu-open', open)
}, { immediate: true })

window.addEventListener('keydown', onWindowKeydown)
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onWindowKeydown)
  document.body.classList.remove('admin-menu-open')
  clearPasswordForm()
})

async function logout() {
  if (logoutBusy.value) return
  logoutBusy.value = true
  try {
    await adminApi('/auth/logout', { method: 'POST' })
    clearAdminSession()
    clearPasswordForm()
    await router.replace('/login')
  } catch (cause) {
    notifyError('退出失败', adminErrorMessage(cause))
  } finally {
    logoutBusy.value = false
  }
}

async function changePassword() {
  if (changeBusy.value) return
  passwordError.value = ''
  if (passwordForm.value.newPassword !== passwordForm.value.confirmPassword) {
    passwordError.value = '两次输入的新密码不一致'
    return
  }
  changeBusy.value = true
  try {
    await adminApi('/auth/change-password', {
      method: 'POST',
      body: JSON.stringify({
        currentPassword: passwordForm.value.currentPassword,
        newPassword: passwordForm.value.newPassword
      })
    })
    if (admin.value) admin.value.mustChangePassword = false
    clearPasswordForm()
    notifySuccess('密码已更新', '后台功能现已解锁。')
  } catch (cause) {
    passwordError.value = adminErrorMessage(cause)
  } finally {
    changeBusy.value = false
  }
}
</script>

<template>
  <RouterView v-if="publicPage" />
  <div v-else class="admin-shell">
    <aside id="admin-sidebar" ref="sidebar" :class="{ open: menuOpen }" aria-label="后台主导航" @keydown="onSidebarKeydown">
      <div class="admin-brand">
        <b aria-hidden="true">拾</b>
        <span>拾光优选<small>运营控制台</small></span>
        <button type="button" class="sidebar-close" aria-label="关闭导航" @click="closeMenu()">×</button>
      </div>
      <nav>
        <section v-for="group in visibleGroups" :key="group.id" class="admin-nav-group">
          <span>{{ group.label }}</span>
          <RouterLink v-for="item in group.items" :key="item.name" :to="item.path">
            <i aria-hidden="true">{{ item.icon }}</i>{{ item.label }}
          </RouterLink>
        </section>
      </nav>
      <div class="safety">安全边界<br /><small>无在线支付 · 无积分提现<br />奖励关系深度固定 1 层</small></div>
    </aside>
    <div v-if="menuOpen" class="backdrop" aria-hidden="true" @click="closeMenu()"></div>
    <section class="workspace">
      <header>
        <button
          ref="menuButton"
          type="button"
          class="menu-button"
          aria-label="打开后台导航"
          aria-controls="admin-sidebar"
          :aria-expanded="menuOpen"
          @click="openMenu"
        >☰</button>
        <div class="environment-pill"><span>商城环境</span><b>运营工作台</b></div>
        <div class="admin-user">
          <span>{{ admin?.displayName || '后台用户' }}<small>{{ admin?.username }}</small></span>
          <button class="secondary compact" type="button" :disabled="logoutBusy" @click="logout">{{ logoutBusy ? '退出中…' : '退出' }}</button>
        </div>
      </header>
      <main><RouterView /></main>
    </section>

    <BaseDialog
      :model-value="Boolean(admin?.mustChangePassword)"
      title="首次登录必须修改密码"
      description="新密码需为 12–72 位，并同时包含字母和数字；完成前其他后台功能保持锁定。"
      persistent
      :submitting="changeBusy"
      :show-default-footer="false"
    >
      <form id="forced-password-form" class="forced-password-form" @submit.prevent="changePassword">
        <label class="field"><span>当前临时密码</span><input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" required /></label>
        <label class="field"><span>新密码</span><input v-model="passwordForm.newPassword" type="password" autocomplete="new-password" minlength="12" required /></label>
        <label class="field"><span>确认新密码</span><input v-model="passwordForm.confirmPassword" type="password" autocomplete="new-password" minlength="12" required /></label>
        <InlineAlert v-if="passwordError" title="密码修改失败" :message="passwordError" />
      </form>
      <template #footer>
        <button class="primary" form="forced-password-form" :disabled="changeBusy">{{ changeBusy ? '修改中…' : '修改密码并解锁后台' }}</button>
      </template>
    </BaseDialog>
    <ToastRegion />
  </div>
</template>

<style scoped>
.sidebar-close{display:none;margin-left:auto;width:34px;height:34px;color:#d9e5df;border:1px solid #425249;border-radius:8px;background:transparent;font-size:20px}
.forced-password-form{display:grid;gap:14px}
@media(max-width:1100px){.sidebar-close{display:block}}
</style>
