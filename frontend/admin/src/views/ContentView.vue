<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi, adminErrorMessage } from '../api'
import AssetPicker from '../components/AssetPicker.vue'
import RichTextEditor from '../components/RichTextEditor.vue'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import StatusTag from '../components/admin/StatusTag.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import { contentStatusLabel, contentTypeLabel, contentTypeOptions } from '../localization'
import { notifySuccess } from '../toast'

type Content = {
  id: number; contentType: string; title: string; summary?: string; coverUrl?: string
  targetUrl?: string; bodyHtml?: string; status: string; sortOrder: number
}
type ActionKind = 'publish' | 'offline' | 'delete'

const rows = ref<Content[]>([])
const pageLoading = ref(true)
const listError = ref('')
const editing = ref<Content>()
const editorOpen = ref(false)
const preview = ref(false)
const editorError = ref('')
const saving = ref(false)
const bodyImageUploading = ref(false)
const baseline = ref('')
const discardOpen = ref(false)
const form = reactive({ contentType: 'BANNER', title: '', summary: '', coverUrl: '', targetUrl: '', bodyHtml: '', status: 'DRAFT', sortOrder: 0 })

const actionKind = ref<ActionKind>()
const actionTarget = ref<Content>()
const actionReason = ref('')
const actionError = ref('')
const actionSubmitting = ref(false)

const serializedForm = computed(() => JSON.stringify(form))
const dirty = computed(() => editorOpen.value && serializedForm.value !== baseline.value)

async function load() {
  pageLoading.value = true
  listError.value = ''
  try { rows.value = await adminApi<Content[]>('/catalog/contents') }
  catch (cause) { listError.value = adminErrorMessage(cause) }
  finally { pageLoading.value = false }
}

function open(row?: Content) {
  if (row?.status === 'PUBLISHED') {
    openAction('offline', row)
    return
  }
  editing.value = row
  Object.assign(form, row || { contentType: 'BANNER', title: '', summary: '', coverUrl: '', targetUrl: '', bodyHtml: '', status: 'DRAFT', sortOrder: 0 })
  preview.value = false
  bodyImageUploading.value = false
  editorError.value = ''
  baseline.value = JSON.stringify(form)
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
  editing.value = undefined
  bodyImageUploading.value = false
  editorError.value = ''
  baseline.value = ''
}

function requestEditorClose() {
  if (saving.value || bodyImageUploading.value) return
  if (dirty.value) discardOpen.value = true
  else closeEditor()
}

async function saveDraft() {
  if (saving.value || bodyImageUploading.value) return
  if (editing.value?.status === 'PUBLISHED') {
    editorError.value = '已发布内容须先通过列表中的显式下线确认，才能继续编辑草稿。'
    return
  }
  saving.value = true
  editorError.value = ''
  try {
    const payload = { ...form, status: 'DRAFT' }
    const saved = await adminApi<Content>(`/catalog/contents${editing.value ? `/${editing.value.id}` : ''}`, {
      method: editing.value ? 'PUT' : 'POST', body: JSON.stringify(payload)
    })
    Object.assign(form, saved)
    editing.value = saved
    baseline.value = JSON.stringify(form)
    notifySuccess('内容草稿已保存', '保存草稿不会自动发布到商城。')
    await load()
  } catch (cause) { editorError.value = adminErrorMessage(cause) }
  finally { saving.value = false }
}

function openAction(kind: ActionKind, row: Content) {
  actionKind.value = kind
  actionTarget.value = row
  actionReason.value = ''
  actionError.value = ''
}
function closeAction() { actionKind.value = undefined; actionTarget.value = undefined; actionReason.value = ''; actionError.value = '' }

async function submitAction() {
  const row = actionTarget.value
  const kind = actionKind.value
  if (!row || !kind || actionSubmitting.value) return
  actionSubmitting.value = true
  actionError.value = ''
  try {
    if (kind === 'delete') {
      await adminApi(`/catalog/contents/${row.id}`, { method: 'DELETE', body: JSON.stringify({ reason: actionReason.value.trim() }) })
    } else {
      await adminApi(`/catalog/contents/${row.id}`, {
        method: 'PUT', body: JSON.stringify({ ...row, status: kind === 'publish' ? 'PUBLISHED' : 'OFFLINE', reason: actionReason.value.trim() })
      })
    }
    notifySuccess(kind === 'publish' ? '内容已发布' : kind === 'offline' ? '内容已下线' : '内容已删除')
    closeAction()
    await load()
  } catch (cause) { actionError.value = adminErrorMessage(cause) }
  finally { actionSubmitting.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="内容运营" description="内容先保存为草稿并安全预览，发布、下线与删除作为独立确认动作。"><template #actions><button class="primary" type="button" @click="open()">新增内容草稿</button></template></PageHeader>
    <TableFrame :loading="pageLoading" :error="listError" :empty="!rows.length" empty-title="暂无内容" empty-text="创建第一条内容草稿后再预览与发布。" label="内容列表" @retry="load"><table class="responsive-table"><thead><tr><th>排序</th><th>封面</th><th>类型</th><th>标题</th><th>摘要</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="row.id"><td data-label="排序">{{ row.sortOrder }}</td><td data-label="封面"><img v-if="row.coverUrl" :src="row.coverUrl" :alt="row.title" /></td><td data-label="类型">{{ contentTypeLabel(row.contentType) }}</td><td data-label="标题"><b>{{ row.title }}</b></td><td data-label="摘要">{{ row.summary || '未填写' }}</td><td data-label="状态"><StatusTag :tone="row.status === 'PUBLISHED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'neutral'" :label="contentStatusLabel(row.status)" /></td><td class="actions" data-label="操作"><button v-if="row.status !== 'PUBLISHED'" class="primary" type="button" @click="open(row)">编辑草稿</button><button v-if="row.status !== 'PUBLISHED'" class="secondary" type="button" @click="openAction('publish', row)">发布</button><button v-if="row.status === 'PUBLISHED'" class="secondary" type="button" @click="openAction('offline', row)">下线后再编辑</button><button class="danger" type="button" @click="openAction('delete', row)">删除</button></td></tr></tbody></table></TableFrame>

    <BaseDialog :model-value="editorOpen" :title="editing ? '编辑内容草稿' : '新增内容草稿'" description="编辑内容不会自动影响商城；请显式保存草稿，再从列表执行发布。" width="min(900px, calc(100vw - 32px))" :submitting="saving || bodyImageUploading" :dirty="dirty" @update:model-value="value => { if (!value) closeEditor() }" @close-blocked="discardOpen = true"><form id="content-form" class="content-form" @submit.prevent="saveDraft"><div class="draft-banner"><StatusTag :tone="dirty ? 'warning' : 'success'" :label="dirty ? '有未保存改动' : '草稿已保存'" /><span>当前编辑状态：{{ contentStatusLabel(form.status) }}</span></div><div class="row"><label class="field"><span>类型</span><select v-model="form.contentType"><option v-for="option in contentTypeOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label><label class="field"><span>排序</span><input v-model.number="form.sortOrder" type="number" /></label></div><label class="field"><span>标题</span><input v-model="form.title" required /></label><label class="field"><span>摘要</span><textarea v-model="form.summary" /></label><AssetPicker v-model="form.coverUrl" /><label class="field"><span>封面地址</span><input v-model="form.coverUrl" /></label><label class="field"><span>跳转地址</span><input v-model="form.targetUrl" placeholder="/rules 或 https://..." /></label><div class="body-head"><label>正文内容</label><button type="button" class="secondary" :disabled="bodyImageUploading" @click="preview = !preview">{{ preview ? '继续编辑' : '安全预览' }}</button></div><RichTextEditor v-if="!preview" v-model="form.bodyHtml" placeholder="输入公告、帮助说明或活动内容" @uploading-change="bodyImageUploading = $event" /><iframe v-else sandbox="" class="preview" :srcdoc="form.bodyHtml || '<p>暂无正文</p>'" title="内容预览"></iframe><InlineAlert v-if="editorError" title="草稿未保存" :message="editorError" /></form><template #footer><button class="secondary" type="button" :disabled="saving || bodyImageUploading" @click="requestEditorClose">返回列表</button><button class="primary" form="content-form" :disabled="saving || bodyImageUploading || !dirty">{{ bodyImageUploading ? '图片上传中…' : saving ? '保存中…' : '保存草稿' }}</button></template></BaseDialog>

    <BusinessActionDialog :model-value="Boolean(actionKind)" :title="actionKind === 'publish' ? '发布商城内容' : actionKind === 'offline' ? '下线商城内容' : '删除商城内容'" :target="actionTarget?.title || '当前内容'" :impact="actionKind === 'publish' ? '发布后该内容可能立即出现在商城配置引用的位置，请确认预览和跳转地址。' : actionKind === 'offline' ? '下线后商城不再展示该内容，内容记录仍保留以便后续编辑。' : `删除将移除该内容记录；当前状态为${contentStatusLabel(actionTarget?.status)}，请先确认模板或入口不再引用。`" :current-state="contentStatusLabel(actionTarget?.status)" :next-state="actionKind === 'publish' ? '已发布' : actionKind === 'offline' ? '已下线' : '已删除'" v-model:reason="actionReason" :danger="actionKind !== 'publish'" :confirm-label="actionKind === 'publish' ? '确认发布' : actionKind === 'offline' ? '确认下线' : '确认删除'" :submitting="actionSubmitting" :error="actionError" @update:model-value="value => { if (!value) closeAction() }" @submit="submitAction" />

    <BaseDialog v-model="discardOpen" title="放弃未保存的内容改动？" description="返回列表后，本次尚未保存的字段修改将丢失。" :show-default-footer="false"><p class="discard-copy">已保存的草稿不会受影响；仅放弃当前编辑器中的本地改动。</p><template #footer><button class="secondary" type="button" autofocus @click="discardOpen = false">继续编辑</button><button class="danger solid" type="button" @click="discardOpen = false; closeEditor()">放弃改动</button></template></BaseDialog>
  </div>
</template>

<style scoped>
td img{width:52px;height:38px;object-fit:cover;border-radius:var(--radius-sm);background:var(--color-surface-subtle)}.actions{display:flex;flex-wrap:wrap;gap:6px}.content-form{display:grid;gap:12px}.row{display:grid;grid-template-columns:minmax(0,1.5fr) minmax(120px,.5fr);gap:10px}.draft-banner{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:9px 0 11px;border-bottom:1px solid var(--color-border);color:var(--color-text-muted);font-size:12px}.body-head{display:flex;justify-content:space-between;align-items:center;padding-top:13px;border-top:1px solid var(--color-border);color:var(--color-text-muted);font-size:12px;font-weight:700}.preview{width:100%;height:280px;border:1px solid var(--color-border);border-radius:var(--radius-md);background:#fff}.discard-copy{color:var(--color-text-muted);line-height:1.7}
@media(max-width:600px){.row{grid-template-columns:1fr}.draft-banner{align-items:flex-start;flex-direction:column}.body-head{align-items:flex-start;gap:10px}}
</style>
