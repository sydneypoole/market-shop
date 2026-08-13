<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, adminErrorMessage, dateTime, isConflictError } from '../api'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import FilterBar from '../components/admin/FilterBar.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import { accountStatusLabel, accountStatusOptions, permissionLabel, roleLabel } from '../localization'
import { adminSession, can } from '../session'
import { notifySuccess } from '../toast'

type Account = {
  id: number; username: string; displayName: string; status: string; linkedUserId?: number
  mustChangePassword: boolean; failedAttempts: number; lockedUntil?: string; lastLoginAt?: string; roles: string[]
}
type Role = { code: string; name: string; builtin: boolean; permissions: string[] }
type SensitiveKind = 'status' | 'reset' | 'unlock' | 'roles' | 'link' | 'delete-role'

const route = useRoute()
const router = useRouter()
const rows = ref<Account[]>([])
const roles = ref<Role[]>([])
const permissions = ref<string[]>([])
const pageLoading = ref(true)
const listError = ref('')
const section = ref<'accounts' | 'roles'>('accounts')
const draftKeyword = ref('')
const appliedKeyword = ref('')
const canManageRoles = computed(() => can('admin:role:manage'))
const isSuperAdmin = computed(() => adminSession.current?.roles.includes('SUPER_ADMIN') ?? false)
const visibleRows = computed(() => {
  const keyword = appliedKeyword.value.trim().toLowerCase()
  return keyword ? rows.value.filter(row => `${row.username} ${row.displayName} ${row.id}`.toLowerCase().includes(keyword)) : rows.value
})

const createOpen = ref(false)
const createSubmitting = ref(false)
const createError = ref('')
const createForm = reactive({ username: '', displayName: '', temporaryPassword: '', linkedUserId: '', roles: [] as string[], currentPassword: '', reason: '创建运营账号' })

const roleOpen = ref(false)
const editingRole = ref<Role>()
const roleSubmitting = ref(false)
const roleError = ref('')
const roleForm = reactive({ code: '', name: '', permissions: [] as string[], currentPassword: '', reason: '' })

const sensitiveKind = ref<SensitiveKind>()
const sensitiveAccount = ref<Account>()
const sensitiveRole = ref<Role>()
const sensitiveReason = ref('')
const currentPassword = ref('')
const temporaryPassword = ref('')
const targetStatus = ref('ACTIVE')
const linkedUserId = ref('')
const assignedRoles = ref<string[]>([])
const sensitiveError = ref('')
const sensitiveSubmitting = ref(false)

const sensitiveTitle = computed(() => {
  const titles: Record<SensitiveKind, string> = {
    status: '调整后台账号状态', reset: '重置后台账号密码', unlock: '解锁后台账号',
    roles: '调整后台账号角色', link: '调整关联商城会员', 'delete-role': '删除自定义角色'
  }
  return sensitiveKind.value ? titles[sensitiveKind.value] : '敏感操作确认'
})
const sensitiveTargetLabel = computed(() => sensitiveAccount.value
  ? `${sensitiveAccount.value.displayName}（${sensitiveAccount.value.username}）`
  : sensitiveRole.value ? `${sensitiveRole.value.name}（${sensitiveRole.value.code}）` : '当前对象')
const sensitiveImpact = computed(() => {
  if (sensitiveKind.value === 'status') return '账号状态变化会立即影响后台访问能力，并写入不可变审计日志。'
  if (sensitiveKind.value === 'reset') return '原密码将失效；账号下次登录必须使用新临时密码并完成改密。'
  if (sensitiveKind.value === 'unlock') return '清除登录失败锁定后，该账号可立即重新尝试登录。'
  if (sensitiveKind.value === 'roles') return '角色变化会改变后台可见页面与操作权限；后端仍会逐请求鉴权。'
  if (sensitiveKind.value === 'link') return '后台身份与商城身份仍相互隔离；关联关系仅供受控业务使用。'
  return '删除角色后不可恢复；已分配给账号的自定义角色不能删除。'
})

async function load() {
  pageLoading.value = true
  listError.value = ''
  try {
    rows.value = await adminApi<Account[]>('/accounts')
    if (canManageRoles.value) {
      ;[roles.value, permissions.value] = await Promise.all([
        adminApi<Role[]>('/roles'), adminApi<string[]>('/permissions')
      ])
    } else {
      roles.value = []
      permissions.value = []
    }
  } catch (cause) { listError.value = adminErrorMessage(cause) }
  finally { pageLoading.value = false }
}

function clearCreateSecrets() {
  createForm.temporaryPassword = ''
  createForm.currentPassword = ''
}
function resetCreate() {
  Object.assign(createForm, { username: '', displayName: '', temporaryPassword: '', linkedUserId: '', roles: [], currentPassword: '', reason: '创建运营账号' })
  createError.value = ''
}
function openCreate() { resetCreate(); createOpen.value = true }
function closeCreate() { createOpen.value = false; clearCreateSecrets(); resetCreate() }

async function createAccount() {
  if (createSubmitting.value) return
  createSubmitting.value = true
  createError.value = ''
  try {
    await adminApi('/accounts', {
      method: 'POST',
      body: JSON.stringify({ ...createForm, linkedUserId: createForm.linkedUserId ? Number(createForm.linkedUserId) : null })
    })
    closeCreate()
    await load()
    notifySuccess('后台账号已创建', '临时密码已从页面状态清除。')
  } catch (cause) { createError.value = adminErrorMessage(cause) }
  finally { createSubmitting.value = false }
}

function openRole(role?: Role) {
  editingRole.value = role
  Object.assign(roleForm, role
    ? { code: role.code, name: role.name, permissions: [...role.permissions], currentPassword: '', reason: '' }
    : { code: '', name: '', permissions: [], currentPassword: '', reason: '' })
  roleError.value = ''
  roleOpen.value = true
}
function closeRole() {
  roleOpen.value = false
  roleForm.currentPassword = ''
  roleForm.reason = ''
  editingRole.value = undefined
}
async function saveRole() {
  if (roleSubmitting.value) return
  roleSubmitting.value = true
  roleError.value = ''
  try {
    await adminApi('/roles', { method: 'POST', body: JSON.stringify(roleForm) })
    closeRole()
    await load()
    notifySuccess('自定义角色已保存')
  } catch (cause) { roleError.value = adminErrorMessage(cause) }
  finally { roleSubmitting.value = false }
}

function clearSensitive() {
  currentPassword.value = ''
  temporaryPassword.value = ''
  sensitiveReason.value = ''
  targetStatus.value = 'ACTIVE'
  linkedUserId.value = ''
  assignedRoles.value = []
  sensitiveError.value = ''
}
function openSensitive(kind: SensitiveKind, target: Account | Role) {
  clearSensitive()
  sensitiveAccount.value = undefined
  sensitiveRole.value = undefined
  sensitiveKind.value = kind
  if ('username' in target) {
    sensitiveAccount.value = target
    targetStatus.value = target.status
    linkedUserId.value = target.linkedUserId?.toString() || ''
    assignedRoles.value = [...target.roles]
  } else sensitiveRole.value = target
}
function closeSensitive() {
  sensitiveKind.value = undefined
  sensitiveAccount.value = undefined
  sensitiveRole.value = undefined
  clearSensitive()
}

async function submitSensitive() {
  const kind = sensitiveKind.value
  const account = sensitiveAccount.value
  if (!kind || sensitiveSubmitting.value) return
  sensitiveSubmitting.value = true
  sensitiveError.value = ''
  const auth = { currentPassword: currentPassword.value, reason: sensitiveReason.value.trim() }
  try {
    if (kind === 'status' && account) {
      await adminApi(`/accounts/${account.id}/status`, { method: 'PUT', body: JSON.stringify({ ...auth, status: targetStatus.value }) })
    } else if (kind === 'reset' && account) {
      await adminApi(`/accounts/${account.id}/reset-password`, { method: 'POST', body: JSON.stringify({ ...auth, temporaryPassword: temporaryPassword.value }) })
    } else if (kind === 'unlock' && account) {
      await adminApi(`/accounts/${account.id}/unlock`, { method: 'POST', body: JSON.stringify(auth) })
    } else if (kind === 'roles' && account) {
      await adminApi(`/accounts/${account.id}/roles`, { method: 'PUT', body: JSON.stringify({ ...auth, roles: assignedRoles.value }) })
    } else if (kind === 'link' && account) {
      await adminApi(`/accounts/${account.id}/linked-user`, { method: 'PUT', body: JSON.stringify({ ...auth, linkedUserId: linkedUserId.value ? Number(linkedUserId.value) : null }) })
    } else if (kind === 'delete-role' && sensitiveRole.value) {
      await adminApi(`/roles/${encodeURIComponent(sensitiveRole.value.code)}`, { method: 'DELETE', body: JSON.stringify(auth) })
    }
    const successTitle: Record<SensitiveKind, string> = {
      status: '账号状态已更新', reset: '临时密码已重置', unlock: '账号已解锁', roles: '账号角色已更新', link: '关联会员已更新', 'delete-role': '自定义角色已删除'
    }
    notifySuccess(successTitle[kind])
    closeSensitive()
    await load()
  } catch (cause) {
    sensitiveError.value = adminErrorMessage(cause)
    if (isConflictError(cause)) await load()
  } finally { sensitiveSubmitting.value = false }
}

function readRouteState() {
  const keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  draftKeyword.value = keyword
  appliedKeyword.value = keyword
  section.value = route.query.section === 'roles' && canManageRoles.value ? 'roles' : 'accounts'
}

function accountRouteQuery() {
  return {
    ...(appliedKeyword.value ? { keyword: appliedKeyword.value } : {}),
    ...(section.value === 'roles' ? { section: 'roles' } : {})
  }
}

async function navigateApplied() {
  const target = { path: '/accounts', query: accountRouteQuery() }
  if (router.resolve(target).fullPath !== route.fullPath) await router.push(target)
}

async function applyKeyword() {
  appliedKeyword.value = draftKeyword.value
  await navigateApplied()
}

async function resetKeyword() {
  draftKeyword.value = ''
  appliedKeyword.value = ''
  await navigateApplied()
}

async function selectSection(value: 'accounts' | 'roles') {
  section.value = value === 'roles' && canManageRoles.value ? 'roles' : 'accounts'
  await navigateApplied()
}

watch(() => route.fullPath, () => { if (route.path === '/accounts') readRouteState() })
onMounted(() => { readRouteState(); void load() })
</script>

<template>
  <div>
    <PageHeader title="后台账号与权限" description="账号、角色、权限与商城会员身份相互隔离；敏感操作统一要求原因和当前密码再认证。"><template #actions><button v-if="canManageRoles && isSuperAdmin" class="secondary" type="button" @click="selectSection('roles'); openRole()">新建自定义角色</button><button class="primary" type="button" :disabled="!isSuperAdmin" :title="isSuperAdmin ? '' : '仅超级管理员可创建后台账号'" @click="openCreate">创建账号</button></template></PageHeader>
    <div class="section-tabs" role="tablist" aria-label="账号权限分区"><button type="button" role="tab" :aria-selected="section === 'accounts'" :class="{ active: section === 'accounts' }" @click="selectSection('accounts')">后台账号</button><button v-if="canManageRoles" type="button" role="tab" :aria-selected="section === 'roles'" :class="{ active: section === 'roles' }" @click="selectSection('roles')">角色与权限</button></div>

    <template v-if="section === 'accounts'">
      <FilterBar :busy="pageLoading" :applied-summary="appliedKeyword ? [`关键词：${appliedKeyword}`] : ['全部后台账号']" @apply="applyKeyword" @reset="resetKeyword"><label class="field"><span>账号关键词</span><input v-model="draftKeyword" placeholder="用户名 / 显示名称 / 编号" /></label></FilterBar>
      <InlineAlert v-if="!isSuperAdmin" tone="info" title="当前为只读账号管理视图" message="敏感账号变更由超级管理员执行；后端仍会对每次请求重复鉴权。" />
      <TableFrame :loading="pageLoading" :error="listError" :empty="!visibleRows.length" empty-title="暂无后台账号" label="后台账号列表" @retry="load"><table class="responsive-table"><thead><tr><th>账号</th><th>状态</th><th>角色</th><th>关联会员</th><th>安全状态</th><th>最近登录</th><th>操作</th></tr></thead><tbody><tr v-for="row in visibleRows" :key="row.id"><td data-label="账号"><b>{{ row.displayName }}</b><br /><small>{{ row.username }} · #{{ row.id }}</small></td><td data-label="状态"><StatusTag :tone="row.status === 'ACTIVE' ? 'success' : 'danger'" :label="accountStatusLabel(row.status)" /></td><td data-label="角色">{{ row.roles.map(code => roleLabel(code, roles)).join('、') || '未分配' }}</td><td data-label="关联会员">{{ row.linkedUserId ? `#${row.linkedUserId}` : '未关联' }}</td><td data-label="安全状态">{{ row.mustChangePassword ? '待改密' : '正常' }} / 失败 {{ row.failedAttempts }}<small v-if="row.lockedUntil"><br />锁定至 {{ dateTime(row.lockedUntil) }}</small></td><td data-label="最近登录">{{ dateTime(row.lastLoginAt) }}</td><td class="actions" data-label="操作"><template v-if="isSuperAdmin"><button v-if="canManageRoles" class="secondary" type="button" @click="openSensitive('roles', row)">角色</button><button class="secondary" type="button" @click="openSensitive('link', row)">关联</button><button class="secondary" type="button" @click="openSensitive('reset', row)">重置密码</button><button v-if="row.failedAttempts > 0 || row.lockedUntil" class="primary" type="button" @click="openSensitive('unlock', row)">解锁</button><button class="danger" type="button" @click="openSensitive('status', row)">状态</button></template><span v-else class="muted">仅查看</span></td></tr></tbody></table></TableFrame>
    </template>

    <template v-else>
      <TableFrame :loading="pageLoading" :error="listError" :empty="!roles.length" empty-title="暂无角色" label="角色与权限列表" @retry="load"><div class="role-cards"><article v-for="role in roles" :key="role.code" class="card"><div><b>{{ role.name }}</b><StatusTag :tone="role.builtin ? 'info' : 'neutral'" :label="role.builtin ? '内置' : '自定义'" /></div><small>{{ role.code }}</small><p>{{ role.permissions.map(permissionLabel).join('、') }}</p><div v-if="!role.builtin && isSuperAdmin" class="role-actions"><button class="secondary" type="button" @click="openRole(role)">编辑权限</button><button class="danger" type="button" @click="openSensitive('delete-role', role)">删除</button></div></article></div></TableFrame>
    </template>

    <BaseDialog :model-value="createOpen" title="创建后台账号" description="新账号使用临时密码，首次登录必须改密；关闭后所有密码字段立即清空。" :submitting="createSubmitting" @update:model-value="value => { if (!value) closeCreate() }"><form id="create-account-form" class="dialog-form" @submit.prevent="createAccount"><label class="field"><span>用户名</span><input v-model="createForm.username" required autocomplete="off" /></label><label class="field"><span>显示名称</span><input v-model="createForm.displayName" required /></label><label class="field"><span>临时密码</span><input v-model="createForm.temporaryPassword" minlength="12" type="password" autocomplete="new-password" required /></label><label class="field"><span>关联会员编号（可选）</span><input v-model="createForm.linkedUserId" type="number" min="1" /></label><div v-if="roles.length" class="check-grid"><label v-for="role in roles" :key="role.code"><input v-model="createForm.roles" :value="role.code" type="checkbox" />{{ role.name }}</label></div><label class="field"><span>当前管理员密码</span><input v-model="createForm.currentPassword" type="password" autocomplete="current-password" required /></label><label class="field"><span>操作原因</span><input v-model="createForm.reason" required /></label><InlineAlert v-if="createError" title="账号未创建" :message="createError" /></form><template #footer><button class="secondary" type="button" autofocus :disabled="createSubmitting" @click="closeCreate">取消</button><button class="primary" form="create-account-form" :disabled="createSubmitting">{{ createSubmitting ? '创建中…' : '创建账号' }}</button></template></BaseDialog>

    <BaseDialog :model-value="roleOpen" :title="editingRole ? '编辑自定义角色' : '新建自定义角色'" description="内置角色不可修改；自定义角色保存需要当前密码再认证。" width="min(780px, calc(100vw - 32px))" :submitting="roleSubmitting" @update:model-value="value => { if (!value) closeRole() }"><form id="role-form" class="dialog-form" @submit.prevent="saveRole"><label class="field"><span>角色编码</span><input v-model="roleForm.code" pattern="[A-Z][A-Z0-9_]{2,63}" required :readonly="Boolean(editingRole)" /></label><label class="field"><span>角色名称</span><input v-model="roleForm.name" required /></label><div class="check-grid permission-grid"><label v-for="permission in permissions" :key="permission"><input v-model="roleForm.permissions" :value="permission" type="checkbox" />{{ permissionLabel(permission) }}<small>{{ permission }}</small></label></div><label class="field"><span>当前管理员密码</span><input v-model="roleForm.currentPassword" type="password" autocomplete="current-password" required /></label><label class="field"><span>操作原因</span><input v-model="roleForm.reason" required /></label><InlineAlert v-if="roleError" title="角色未保存" :message="roleError" /></form><template #footer><button class="secondary" type="button" autofocus :disabled="roleSubmitting" @click="closeRole">取消</button><button class="primary" form="role-form" :disabled="roleSubmitting">{{ roleSubmitting ? '保存中…' : '保存角色' }}</button></template></BaseDialog>

    <BusinessActionDialog :model-value="Boolean(sensitiveKind)" :title="sensitiveTitle" :target="sensitiveTargetLabel" :impact="sensitiveImpact" :current-state="sensitiveKind === 'status' ? accountStatusLabel(sensitiveAccount?.status) : ''" :next-state="sensitiveKind === 'status' ? accountStatusLabel(targetStatus) : ''" v-model:reason="sensitiveReason" v-model:password="currentPassword" requires-password :danger="sensitiveKind === 'status' || sensitiveKind === 'delete-role'" :confirm-label="sensitiveKind === 'delete-role' ? '确认删除角色' : '确认提交'" :submitting="sensitiveSubmitting" :submit-disabled="sensitiveKind === 'reset' && temporaryPassword.length < 12" :error="sensitiveError" @update:model-value="value => { if (!value) closeSensitive() }" @submit="submitSensitive"><label v-if="sensitiveKind === 'status'" class="field"><span>目标状态</span><select v-model="targetStatus"><option v-for="option in accountStatusOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label v-if="sensitiveKind === 'reset'" class="field"><span>新临时密码</span><input v-model="temporaryPassword" type="password" autocomplete="new-password" minlength="12" required /></label><label v-if="sensitiveKind === 'link'" class="field"><span>商城会员编号（留空取消关联）</span><input v-model="linkedUserId" type="number" min="1" /></label><div v-if="sensitiveKind === 'roles'" class="check-grid"><label v-for="role in roles" :key="role.code"><input v-model="assignedRoles" :value="role.code" type="checkbox" />{{ role.name }}</label></div></BusinessActionDialog>
  </div>
</template>

<style scoped>
.section-tabs{display:flex;width:max-content;margin-bottom:16px;border-bottom:1px solid var(--color-border)}.section-tabs button{position:relative;min-height:39px;padding:0 15px;border:0;background:transparent;color:var(--color-text-muted)}.section-tabs button.active{color:var(--color-brand);font-weight:750}.section-tabs button.active:after{content:'';position:absolute;right:10px;bottom:-1px;left:10px;height:2px;background:var(--color-brand)}.actions{display:flex;flex-wrap:wrap;gap:6px}.muted{color:var(--color-text-muted)}.role-cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:0;padding:0}.role-cards article{padding:16px 18px;border:0;border-right:1px solid var(--color-border);border-bottom:1px solid var(--color-border);border-radius:0;box-shadow:none}.role-cards article>div{display:flex;justify-content:space-between;gap:8px}.role-cards small{color:var(--color-text-muted)}.role-cards p{min-height:40px;color:var(--color-text-muted);font-size:12px;line-height:1.6}.role-actions{display:flex;gap:6px;padding-top:10px;border-top:1px solid var(--color-border)}.dialog-form{display:grid;gap:12px}.check-grid{display:grid;grid-template-columns:1fr 1fr;gap:0;border:1px solid var(--color-border);border-radius:var(--radius-md);overflow:hidden}.check-grid label{padding:9px 10px;border-right:1px solid var(--color-border);border-bottom:1px solid var(--color-border);font-size:13px}.check-grid input{margin-right:7px}.check-grid small{display:block;margin:3px 0 0 22px;color:var(--color-text-muted)}.permission-grid{grid-template-columns:repeat(3,minmax(0,1fr))}
@media(max-width:700px){.permission-grid,.check-grid{grid-template-columns:1fr}.section-tabs{width:100%}.section-tabs button{flex:1}.role-cards{grid-template-columns:1fr}.role-cards article{border-right:0}}
</style>
