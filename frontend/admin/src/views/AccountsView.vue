<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi, dateTime } from '../api'
import { can } from '../session'

type Account = {
  id:number;username:string;displayName:string;status:string;linkedUserId?:number
  mustChangePassword:boolean;failedAttempts:number;lockedUntil?:string;lastLoginAt?:string;roles:string[]
}
type Role = {code:string;name:string;builtin:boolean;permissions:string[]}
const rows = ref<Account[]>([])
const roles = ref<Role[]>([])
const permissions = ref<string[]>([])
const showCreate = ref(false)
const showRoles = ref(false)
const assigning = ref<Account>()
const error = ref('')
const busy = ref(false)
const form = reactive({username:'',displayName:'',temporaryPassword:'',linkedUserId:'',roles:[] as string[],currentPassword:'',reason:'创建运营账号'})
const roleForm = reactive({code:'',name:'',permissions:[] as string[],currentPassword:'',reason:''})
const assignment = reactive({roles:[] as string[],currentPassword:'',reason:''})
const canManageRoles = computed(() => can('admin:role:manage'))

async function load() {
  error.value = ''
  try {
    rows.value = await adminApi<Account[]>('/accounts')
    if (canManageRoles.value) {
      ;[roles.value, permissions.value] = await Promise.all([
        adminApi<Role[]>('/roles'),
        adminApi<string[]>('/permissions')
      ])
    } else {
      roles.value = []
      permissions.value = []
    }
  } catch (e) { error.value = (e as Error).message }
}

async function create() {
  busy.value = true
  try {
    await adminApi('/accounts', {
      method:'POST',
      body:JSON.stringify({...form,linkedUserId:form.linkedUserId ? Number(form.linkedUserId) : null})
    })
    showCreate.value = false
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}

function sensitive() {
  const currentPassword = prompt('请输入当前管理员密码（二次校验）') || ''
  const reason = prompt('请输入操作原因') || ''
  return {currentPassword,reason}
}

async function setStatus(row: Account) {
  const status = prompt('新状态：ACTIVE / DISABLED', row.status) || ''
  const auth = sensitive()
  if (!status || !auth.currentPassword || !auth.reason) return
  await adminApi(`/accounts/${row.id}/status`, {method:'PUT',body:JSON.stringify({...auth,status})})
  await load()
}

async function reset(row: Account) {
  const temporaryPassword = prompt('新的临时密码（至少12位，含字母和数字）') || ''
  const auth = sensitive()
  if (!temporaryPassword || !auth.currentPassword || !auth.reason) return
  await adminApi(`/accounts/${row.id}/reset-password`, {method:'POST',body:JSON.stringify({...auth,temporaryPassword})})
  await load()
}

async function unlock(row: Account) {
  const auth = sensitive()
  if (!auth.currentPassword || !auth.reason) return
  await adminApi(`/accounts/${row.id}/unlock`, {method:'POST',body:JSON.stringify(auth)})
  await load()
}

function openAssignment(row: Account) {
  assigning.value = row
  assignment.roles = [...row.roles]
  assignment.currentPassword = ''
  assignment.reason = ''
}

async function setRoles() {
  if (!assigning.value) return
  await adminApi(`/accounts/${assigning.value.id}/roles`, {method:'PUT',body:JSON.stringify(assignment)})
  assigning.value = undefined
  await load()
}

async function link(row: Account) {
  const value = prompt('关联商城会员ID，留空表示取消关联', row.linkedUserId?.toString() || '')
  const auth = sensitive()
  if (value === null || !auth.currentPassword || !auth.reason) return
  await adminApi(`/accounts/${row.id}/linked-user`, {method:'PUT',body:JSON.stringify({...auth,linkedUserId:value ? Number(value) : null})})
  await load()
}

function editRole(role?: Role) {
  Object.assign(roleForm, role
    ? {code:role.code,name:role.name,permissions:[...role.permissions],currentPassword:'',reason:''}
    : {code:'',name:'',permissions:[],currentPassword:'',reason:''})
  showRoles.value = true
}

async function saveRole() {
  busy.value = true
  try {
    await adminApi('/roles', {method:'POST',body:JSON.stringify(roleForm)})
    showRoles.value = false
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally { busy.value = false }
}

async function removeRole(role: Role) {
  const auth = sensitive()
  if (!auth.currentPassword || !auth.reason) return
  if (!confirm(`确认删除自定义角色 ${role.name}（${role.code}）？`)) return
  try {
    await adminApi(`/roles/${encodeURIComponent(role.code)}`, {
      method:'DELETE',
      body:JSON.stringify(auth)
    })
    await load()
  } catch (e) { error.value = (e as Error).message }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title"><div><h1>后台账号与 RBAC</h1><p>账号、角色、权限与商城会员身份相互隔离；敏感操作要求二次验证。</p></div><div v-if="canManageRoles" class="head-actions"><button class="secondary" @click="editRole()">新建自定义角色</button><button class="primary" @click="showCreate = true">创建账号</button></div></div>
    <p v-if="error" class="error">{{ error }}</p>
    <section v-if="canManageRoles" class="role-cards">
      <article v-for="role in roles" :key="role.code" class="card">
        <div><b>{{ role.name }}</b><span class="tag" :class="{green:role.builtin}">{{ role.builtin ? '内置' : '自定义' }}</span></div>
        <small>{{ role.code }}</small><p>{{ role.permissions.join('、') }}</p>
        <div v-if="!role.builtin" class="role-actions"><button class="secondary" @click="editRole(role)">编辑权限</button><button class="danger" @click="removeRole(role)">删除</button></div>
      </article>
    </section>
    <div class="card table-wrap"><table><thead><tr><th>账号</th><th>状态</th><th>角色</th><th>关联会员</th><th>安全状态</th><th>最近登录</th><th>操作</th></tr></thead><tbody>
      <tr v-for="row in rows" :key="row.id">
        <td><b>{{ row.displayName }}</b><br /><small>{{ row.username }} · #{{ row.id }}</small></td>
        <td><span class="tag" :class="{green:row.status === 'ACTIVE'}">{{ row.status }}</span></td>
        <td>{{ row.roles.join('、') }}</td><td>{{ row.linkedUserId ? `#${row.linkedUserId}` : '未关联' }}</td>
        <td>{{ row.mustChangePassword ? '待改密' : '正常' }} / 失败 {{ row.failedAttempts }}<small v-if="row.lockedUntil"><br />锁定至 {{ dateTime(row.lockedUntil) }}</small></td>
        <td>{{ dateTime(row.lastLoginAt) }}</td>
        <td class="actions">
          <button v-if="canManageRoles" class="secondary" @click="openAssignment(row)">角色</button><button class="secondary" @click="link(row)">关联</button>
          <button class="secondary" @click="reset(row)">重置密码</button>
          <button v-if="row.failedAttempts > 0 || row.lockedUntil" class="primary" @click="unlock(row)">解锁</button>
          <button class="danger" @click="setStatus(row)">状态</button>
        </td>
      </tr>
    </tbody></table></div>

    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false"><form class="modal card" @submit.prevent="create">
      <h2>创建后台账号</h2>
      <div class="field"><label>用户名</label><input v-model="form.username" required /></div>
      <div class="field"><label>显示名称</label><input v-model="form.displayName" required /></div>
      <div class="field"><label>临时密码</label><input v-model="form.temporaryPassword" minlength="12" type="password" required /></div>
      <div class="field"><label>关联会员ID（可选）</label><input v-model="form.linkedUserId" type="number" /></div>
      <div class="check-grid"><label v-for="role in roles" :key="role.code"><input v-model="form.roles" :value="role.code" type="checkbox" />{{ role.name }}</label></div>
      <div class="field"><label>当前管理员密码</label><input v-model="form.currentPassword" type="password" required /></div>
      <div class="field"><label>原因</label><input v-model="form.reason" required /></div>
      <div class="modal-actions"><button type="button" class="secondary" @click="showCreate = false">取消</button><button class="primary" :disabled="busy">创建</button></div>
    </form></div>

    <div v-if="assigning" class="modal-mask" @click.self="assigning = undefined"><form class="modal card" @submit.prevent="setRoles">
      <h2>分配角色 · {{ assigning.displayName }}</h2>
      <div class="check-grid"><label v-for="role in roles" :key="role.code"><input v-model="assignment.roles" :value="role.code" type="checkbox" />{{ role.name }}<small>{{ role.code }}</small></label></div>
      <div class="field"><label>当前管理员密码</label><input v-model="assignment.currentPassword" type="password" required /></div>
      <div class="field"><label>原因</label><input v-model="assignment.reason" required /></div>
      <div class="modal-actions"><button type="button" class="secondary" @click="assigning = undefined">取消</button><button class="primary">保存角色</button></div>
    </form></div>

    <div v-if="showRoles" class="modal-mask" @click.self="showRoles = false"><form class="modal role-modal card" @submit.prevent="saveRole">
      <h2>{{ roles.some(role => role.code === roleForm.code) ? '编辑自定义角色' : '新建自定义角色' }}</h2>
      <div class="field"><label>角色编码</label><input v-model="roleForm.code" pattern="[A-Z][A-Z0-9_]{2,63}" required :readonly="roles.some(role => role.code === roleForm.code)" /></div>
      <div class="field"><label>角色名称</label><input v-model="roleForm.name" required /></div>
      <div class="check-grid permission-grid"><label v-for="permission in permissions" :key="permission"><input v-model="roleForm.permissions" :value="permission" type="checkbox" />{{ permission }}</label></div>
      <div class="field"><label>当前管理员密码</label><input v-model="roleForm.currentPassword" type="password" required /></div>
      <div class="field"><label>原因</label><input v-model="roleForm.reason" required /></div>
      <div class="modal-actions"><button type="button" class="secondary" @click="showRoles = false">取消</button><button class="primary" :disabled="busy">保存角色</button></div>
    </form></div>
  </div>
</template>

<style scoped>
.head-actions,.actions{display:flex;gap:6px}.role-cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:10px;margin-bottom:14px}.role-cards article{padding:15px}.role-cards article>div{display:flex;justify-content:space-between;gap:8px}.role-cards small{color:var(--muted)}.role-cards p{min-height:40px;color:var(--muted);font-size:12px;line-height:1.6}
.role-actions{display:flex;gap:6px}
.modal .field{margin-top:12px}.check-grid{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin:14px 0}.check-grid label{padding:8px;background:#f5f7f5;border-radius:8px;font-size:13px}.check-grid input{margin-right:7px}.check-grid small{display:block;margin:3px 0 0 22px;color:var(--muted)}.role-modal{width:min(760px,100%);max-height:92vh;overflow:auto}.permission-grid{grid-template-columns:repeat(3,1fr)}
@media(max-width:700px){.permission-grid,.check-grid{grid-template-columns:1fr}.actions{flex-wrap:wrap}}
</style>
