<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { adminApi, adminErrorMessage, dateTime } from '../api'
import TemplatePreview from '../components/TemplatePreview.vue'
import BaseDialog from '../components/admin/BaseDialog.vue'
import BusinessActionDialog from '../components/admin/BusinessActionDialog.vue'
import InlineAlert from '../components/admin/InlineAlert.vue'
import PageHeader from '../components/admin/PageHeader.vue'
import TableFrame from '../components/admin/TableFrame.vue'
import { notifySuccess } from '../toast'

type PresetType = 'EDITORIAL' | 'VIBRANT' | 'MINIMAL'
type TemplateRow = {
  id: number
  code: string
  name: string
  presetType: PresetType
  status: string
  active: boolean
  designTokensJson: string
  layoutJson: string
  version: number
  publishedAt?: string
  updatedAt?: string
}
type Tokens = {
  primary: string
  accent: string
  canvas: string
  surface: string
  ink: string
  muted: string
  radius: string
  headingFont: 'serif' | 'sans'
}
type SectionSettings = Record<string, unknown>
type Section = { id: string; type: string; enabled: boolean; settings: SectionSettings }
type Editor = { row: TemplateRow; name: string; tokens: Tokens; sections: Section[] }

const rows = ref<TemplateRow[]>([])
const loading = ref(true)
const editor = ref<Editor>()
const editorBaseline = ref('')
const discardEditorOpen = ref(false)
const selectedSectionId = ref('')
const device = ref<'desktop' | 'mobile'>('desktop')
const mobilePanel = ref<'settings' | 'preview'>('settings')
const busy = ref('')
const error = ref('')
const createOpen = ref(false)
const createForm = ref<{ name: string; presetType: PresetType; sourceId?: number }>({
  name: '我的商城模板',
  presetType: 'EDITORIAL'
})
const confirmAction = ref<{ type: 'publish' | 'archive'; row: TemplateRow }>()
const confirmReason = ref('')
let resolvePendingLeave: ((allow: boolean) => void) | undefined

const presets: ReadonlyArray<{ value: PresetType; name: string; description: string }> = [
  { value: 'EDITORIAL', name: '序章 · 编辑甄选', description: '杂志编排、非对称留白和温暖的品牌叙事。' },
  { value: 'VIBRANT', name: '好物热场 · 活力市集', description: '高对比色块、紧凑信息和强烈活动氛围。' },
  { value: 'MINIMAL', name: '留白 · 极简精品', description: '克制网格、精细层级和沉浸式产品陈列。' }
]
const sectionCatalog = [
  { type: 'ANNOUNCEMENT', label: '商城公告' },
  { type: 'HERO', label: '主视觉' },
  { type: 'CATEGORY_NAV', label: '分类导航' },
  { type: 'PRODUCT_COLLECTION', label: '商品集合' },
  { type: 'CONTENT_STORY', label: '内容故事' },
  { type: 'SERVICE_BENEFITS', label: '服务权益' },
  { type: 'QUICK_LINKS', label: '快捷入口' }
] as const

const selectedSection = computed(() =>
  editor.value?.sections.find(section => section.id === selectedSectionId.value)
)
const editorSignature = computed(() => editor.value ? JSON.stringify({
  name: editor.value.name,
  tokens: editor.value.tokens,
  sections: editor.value.sections
}) : '')
const editorDirty = computed(() => Boolean(editor.value) && editorSignature.value !== editorBaseline.value)

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function parseUnknown(value: string): unknown {
  try { return JSON.parse(value) as unknown } catch { return undefined }
}

function stringField(value: Record<string, unknown>, key: string, fallback: string) {
  return typeof value[key] === 'string' ? value[key] : fallback
}

function parseTokens(value: string): Tokens {
  const parsed = parseUnknown(value)
  const source = isObject(parsed) ? parsed : {}
  return {
    primary: stringField(source, 'primary', '#173F35'),
    accent: stringField(source, 'accent', '#C75B45'),
    canvas: stringField(source, 'canvas', '#F4F0E8'),
    surface: stringField(source, 'surface', '#FFFEFA'),
    ink: stringField(source, 'ink', '#17201C'),
    muted: stringField(source, 'muted', '#707970'),
    radius: stringField(source, 'radius', '24px'),
    headingFont: source.headingFont === 'sans' ? 'sans' : 'serif'
  }
}

function parseSections(value: string): Section[] {
  const parsed = parseUnknown(value)
  if (!isObject(parsed) || !Array.isArray(parsed.sections)) return []
  return parsed.sections.flatMap((item): Section[] => {
    if (!isObject(item) || typeof item.id !== 'string' || typeof item.type !== 'string' || !isObject(item.settings)) {
      return []
    }
    return [{ id: item.id, type: item.type, enabled: item.enabled !== false, settings: { ...item.settings } }]
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try { rows.value = await adminApi<TemplateRow[]>('/storefront/templates') }
  catch (cause) { error.value = adminErrorMessage(cause, '模板列表加载失败') }
  finally { loading.value = false }
}

function openEditor(row: TemplateRow) {
  if (row.active) {
    openDuplicate(row)
    return
  }
  editor.value = {
    row,
    name: row.name,
    tokens: parseTokens(row.designTokensJson),
    sections: parseSections(row.layoutJson)
  }
  selectedSectionId.value = editor.value.sections[0]?.id || ''
  device.value = 'desktop'
  mobilePanel.value = 'settings'
  editorBaseline.value = editorSignature.value
  error.value = ''
}

function closeEditor() {
  editor.value = undefined
  editorBaseline.value = ''
  discardEditorOpen.value = false
}

function requestCloseEditor() {
  if (busy.value) return
  if (editorDirty.value) discardEditorOpen.value = true
  else closeEditor()
}

function continueEditing() {
  discardEditorOpen.value = false
  resolvePendingLeave?.(false)
  resolvePendingLeave = undefined
}

function discardEditorChanges() {
  const resolve = resolvePendingLeave
  resolvePendingLeave = undefined
  closeEditor()
  resolve?.(true)
}

function openCreate() {
  createForm.value = { name: '我的商城模板', presetType: 'EDITORIAL' }
  createOpen.value = true
}

function openDuplicate(row: TemplateRow) {
  createForm.value = {
    name: `${row.name} · 副本`,
    presetType: row.presetType,
    sourceId: row.id
  }
  createOpen.value = true
}

async function createTemplate() {
  if (busy.value) return
  busy.value = 'create'
  error.value = ''
  try {
    const result = createForm.value.sourceId
      ? await adminApi<TemplateRow>(`/storefront/templates/${createForm.value.sourceId}/duplicate`, {
          method: 'POST',
          body: JSON.stringify({ name: createForm.value.name })
        })
      : await adminApi<TemplateRow>('/storefront/templates', {
          method: 'POST',
          body: JSON.stringify({
            name: createForm.value.name,
            presetType: createForm.value.presetType
          })
        })
    createOpen.value = false
    await load()
    openEditor(result)
    notifySuccess('模板草稿已创建')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '模板创建失败' }
  finally { busy.value = '' }
}

async function save() {
  if (!editor.value || busy.value) return
  busy.value = 'save'
  error.value = ''
  try {
    const updated = await adminApi<TemplateRow>(`/storefront/templates/${editor.value.row.id}`, {
      method: 'PUT',
      body: JSON.stringify({
        name: editor.value.name,
        designTokensJson: JSON.stringify(editor.value.tokens),
        layoutJson: JSON.stringify({ schemaVersion: 1, sections: editor.value.sections }),
        expectedVersion: editor.value.row.version
      })
    })
    await load()
    openEditor(updated)
    notifySuccess('模板草稿已保存')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '模板保存失败' }
  finally { busy.value = '' }
}

async function executeConfirmed() {
  const action = confirmAction.value
  if (!action || busy.value) return
  busy.value = action.type
  error.value = ''
  try {
    if (action.type === 'publish') {
      await adminApi(`/storefront/templates/${action.row.id}/publish`, {
        method: 'POST',
        body: JSON.stringify({ expectedVersion: action.row.version, reason: confirmReason.value.trim() })
      })
    } else {
      await adminApi(`/storefront/templates/${action.row.id}`, {
        method: 'DELETE',
        body: JSON.stringify({ expectedVersion: action.row.version, reason: confirmReason.value.trim() })
      })
    }
    confirmAction.value = undefined
    confirmReason.value = ''
    closeEditor()
    await load()
    notifySuccess(action.type === 'publish' ? '商城模板已发布' : '商城模板已归档')
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '模板操作失败' }
  finally { busy.value = '' }
}

function addSection(type: string) {
  if (!editor.value || editor.value.sections.length >= 24) return
  const section: Section = {
    id: `${type.toLowerCase()}-${Date.now()}`,
    type,
    enabled: true,
    settings: defaultSettings(type)
  }
  editor.value.sections.push(section)
  selectedSectionId.value = section.id
}

function handleAddSection(event: Event) {
  const target = event.target as HTMLSelectElement
  if (target.value) addSection(target.value)
  target.value = ''
}

function defaultSettings(type: string): SectionSettings {
  if (type === 'HERO') return {
    eyebrow: 'CURATED FOR YOU', title: '认真挑选，让日常值得期待。',
    description: '从一件真正好用的物品开始。', primaryLabel: '浏览精选',
    primaryLink: '#products', contentType: 'BANNER'
  }
  if (type === 'PRODUCT_COLLECTION') return {
    eyebrow: 'THE COLLECTION', title: '本期精选', description: '价格、规格与库存均实时同步。',
    limit: 8, columns: 4, scene: 'ALL'
  }
  if (type === 'SERVICE_BENEFITS') return { items: ['精选商品', '透明规格', '完整履约', '售后可查'] }
  if (type === 'CONTENT_STORY') return { contentType: 'HELP', layout: 'split' }
  if (type === 'ANNOUNCEMENT') return { limit: 3, style: 'ticker' }
  return { title: type === 'CATEGORY_NAV' ? '按分类探索' : '快捷入口' }
}

function moveSection(index: number, direction: -1 | 1) {
  if (!editor.value) return
  const target = index + direction
  if (target < 0 || target >= editor.value.sections.length) return
  const [section] = editor.value.sections.splice(index, 1)
  if (!section) return
  editor.value.sections.splice(target, 0, section)
}

function removeSection(sectionId: string) {
  if (!editor.value || editor.value.sections.length <= 1) return
  editor.value.sections = editor.value.sections.filter(section => section.id !== sectionId)
  selectedSectionId.value = editor.value.sections[0]?.id || ''
}

function sectionLabel(type: string) {
  return sectionCatalog.find(item => item.type === type)?.label || '未知区块'
}

function presetLabel(type: string) {
  return presets.find(item => item.value === type)?.name || '自定义模板'
}

function statusLabel(row: TemplateRow) {
  if (row.active) return '当前生效'
  if (row.status === 'DRAFT') return '草稿'
  if (row.status === 'PUBLISHED') return '已发布'
  if (row.status === 'ARCHIVED') return '已归档'
  return '未知状态'
}

function textSetting(section: Section, key: string, fallback = '') {
  const value = section.settings[key]
  return typeof value === 'string' ? value : fallback
}

function numberSetting(section: Section, key: string, fallback: number) {
  const value = section.settings[key]
  return typeof value === 'number' ? value : fallback
}

function setText(section: Section, key: string, event: Event) {
  section.settings[key] = (event.target as HTMLInputElement).value
}

function setNumber(section: Section, key: string, event: Event) {
  section.settings[key] = Number((event.target as HTMLInputElement).value)
}

function benefitText(section: Section) {
  const value = section.settings.items
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string').join('\n')
    : ''
}

function setBenefits(section: Section, event: Event) {
  section.settings.items = (event.target as HTMLTextAreaElement).value
    .split('\n')
    .map(item => item.trim())
    .filter(Boolean)
    .slice(0, 12)
}

function onBeforeWindowUnload(event: BeforeUnloadEvent) {
  if (!editorDirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

onBeforeRouteLeave(() => {
  if (!editorDirty.value) return true
  discardEditorOpen.value = true
  return new Promise<boolean>(resolve => {
    resolvePendingLeave?.(false)
    resolvePendingLeave = resolve
  })
})

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeWindowUnload)
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeWindowUnload)
  resolvePendingLeave?.(false)
  resolvePendingLeave = undefined
})
</script>

<template>
  <div>
    <PageHeader title="商城模板" description="维护多个 PC / H5 自适应模板，草稿保存并预览确认后再发布到商城。">
      <template #actions><button class="primary" type="button" @click="openCreate">新建模板</button></template>
    </PageHeader>
    <InlineAlert v-if="error" title="模板操作未完成" :message="error" retryable @retry="load" />

    <TableFrame :loading="loading" :error="loading ? '' : error" :empty="!rows.length" empty-title="暂无商城模板" empty-text="创建第一个模板草稿后开始设计。" label="商城模板列表" @retry="load">
    <div class="template-grid">
      <article v-for="row in rows" :key="row.id" class="template-card card" :class="{ active: row.active }">
        <div class="template-cover" :class="`cover-${row.presetType.toLowerCase()}`">
          <span>{{ row.presetType === 'VIBRANT' ? 'GO' : row.presetType === 'MINIMAL' ? '01' : '拾' }}</span>
          <i>{{ presetLabel(row.presetType) }}</i>
        </div>
        <div class="template-copy">
          <div class="template-title"><div><span class="tag" :class="{ green: row.active }">{{ statusLabel(row) }}</span><h2>{{ row.name }}</h2><small>{{ row.code }}</small></div><b>v{{ row.version }}</b></div>
          <p>{{ presetLabel(row.presetType) }} · 更新于 {{ dateTime(row.updatedAt) }}</p>
          <div class="template-actions">
            <button v-if="!row.active && row.status !== 'ARCHIVED'" class="primary" type="button" @click="openEditor(row)">设计模板</button>
            <button class="secondary" type="button" @click="openDuplicate(row)">复制</button>
            <button v-if="!row.active && row.status !== 'ARCHIVED'" class="secondary" type="button" @click="confirmAction = { type: 'publish', row }">发布</button>
            <button v-if="!row.active && row.status !== 'ARCHIVED'" class="danger" type="button" @click="confirmAction = { type: 'archive', row }">归档</button>
          </div>
        </div>
      </article>
    </div>
    </TableFrame>

    <div v-if="editor" class="designer-mask">
      <section class="designer">
        <header class="designer-header">
          <div><button type="button" class="back" @click="requestCloseEditor">← 返回模板列表</button><h2>{{ editor.name }}</h2><span :class="{ unsaved: editorDirty }">{{ editorDirty ? '有未保存改动' : '草稿已保存' }}</span></div>
          <div class="device-switch"><button :class="{ active: device === 'desktop' }" type="button" @click="device = 'desktop'">PC 预览</button><button :class="{ active: device === 'mobile' }" type="button" @click="device = 'mobile'">H5 预览</button></div>
          <button class="primary" type="button" :disabled="Boolean(busy) || !editorDirty" @click="save">{{ busy === 'save' ? '保存中…' : '保存草稿' }}</button>
          <div class="mobile-panel-switch"><button type="button" :class="{ active: mobilePanel === 'settings' }" @click="mobilePanel = 'settings'">设置</button><button type="button" :class="{ active: mobilePanel === 'preview' }" @click="mobilePanel = 'preview'">预览</button></div>
        </header>
        <div v-if="error" class="designer-feedback"><InlineAlert title="模板草稿操作未完成" :message="error" /></div>
        <div class="designer-body">
          <aside class="designer-sidebar" :class="{ 'mobile-active': mobilePanel === 'settings' }">
            <div class="editor-group">
              <h3>基础信息</h3>
              <div class="field"><label>模板名称</label><input v-model="editor.name" maxlength="120" /></div>
            </div>
            <div class="editor-group">
              <h3>全局风格</h3>
              <div class="color-grid">
                <label><span>主色</span><input v-model="editor.tokens.primary" type="color" /></label>
                <label><span>强调色</span><input v-model="editor.tokens.accent" type="color" /></label>
                <label><span>画布</span><input v-model="editor.tokens.canvas" type="color" /></label>
                <label><span>卡片</span><input v-model="editor.tokens.surface" type="color" /></label>
                <label><span>正文</span><input v-model="editor.tokens.ink" type="color" /></label>
                <label><span>辅助文字</span><input v-model="editor.tokens.muted" type="color" /></label>
              </div>
              <div class="row-fields"><div class="field"><label>圆角</label><select v-model="editor.tokens.radius"><option value="0px">直角</option><option value="4px">轻微</option><option value="16px">柔和</option><option value="24px">圆润</option><option value="36px">大圆角</option></select></div><div class="field"><label>标题字体</label><select v-model="editor.tokens.headingFont"><option value="serif">宋体 / 编辑感</option><option value="sans">黑体 / 现代感</option></select></div></div>
            </div>
            <div class="editor-group sections-group">
              <div class="group-title"><h3>页面区块</h3><select aria-label="添加区块" @change="handleAddSection"><option value="">＋ 添加</option><option v-for="item in sectionCatalog" :key="item.type" :value="item.type">{{ item.label }}</option></select></div>
              <div
                v-for="(section, index) in editor.sections"
                :key="section.id"
                class="section-item"
                :class="{ selected: section.id === selectedSectionId }"
              >
                <button type="button" class="section-select" @click="selectedSectionId = section.id"><i>{{ String(index + 1).padStart(2, '0') }}</i><b>{{ sectionLabel(section.type) }}</b></button>
                <span class="section-controls">
                  <button type="button" aria-label="上移" :disabled="index === 0" @click="moveSection(index, -1)">↑</button>
                  <button type="button" aria-label="下移" :disabled="index === editor.sections.length - 1" @click="moveSection(index, 1)">↓</button>
                  <label><input v-model="section.enabled" type="checkbox" /><span class="sr-only">启用{{ sectionLabel(section.type) }}</span></label>
                </span>
              </div>
            </div>
            <div v-if="selectedSection" class="editor-group section-settings">
              <div class="group-title"><h3>{{ sectionLabel(selectedSection.type) }}设置</h3><button type="button" :disabled="editor.sections.length <= 1" @click="removeSection(selectedSection.id)">删除区块</button></div>
              <template v-if="selectedSection.type === 'HERO'">
                <div class="field"><label>眉标题</label><input :value="textSetting(selectedSection, 'eyebrow')" @input="setText(selectedSection, 'eyebrow', $event)" /></div>
                <div class="field"><label>主标题</label><textarea :value="textSetting(selectedSection, 'title')" @input="setText(selectedSection, 'title', $event)" /></div>
                <div class="field"><label>描述</label><textarea :value="textSetting(selectedSection, 'description')" @input="setText(selectedSection, 'description', $event)" /></div>
                <div class="field"><label>按钮文字</label><input :value="textSetting(selectedSection, 'primaryLabel')" @input="setText(selectedSection, 'primaryLabel', $event)" /></div>
                <div class="field"><label>按钮链接</label><input :value="textSetting(selectedSection, 'primaryLink')" @input="setText(selectedSection, 'primaryLink', $event)" /></div>
              </template>
              <template v-else-if="selectedSection.type === 'PRODUCT_COLLECTION'">
                <div class="field"><label>眉标题</label><input :value="textSetting(selectedSection, 'eyebrow')" @input="setText(selectedSection, 'eyebrow', $event)" /></div>
                <div class="field"><label>标题</label><input :value="textSetting(selectedSection, 'title')" @input="setText(selectedSection, 'title', $event)" /></div>
                <div class="field"><label>描述</label><textarea :value="textSetting(selectedSection, 'description')" @input="setText(selectedSection, 'description', $event)" /></div>
                <div class="row-fields"><div class="field"><label>首屏数量</label><input type="number" min="1" max="24" :value="numberSetting(selectedSection, 'limit', 8)" @input="setNumber(selectedSection, 'limit', $event)" /></div><div class="field"><label>PC 列数</label><select :value="numberSetting(selectedSection, 'columns', 4)" @change="setNumber(selectedSection, 'columns', $event)"><option :value="2">2 列</option><option :value="3">3 列</option><option :value="4">4 列</option></select></div></div>
                <div class="field"><label>销售场景</label><select :value="textSetting(selectedSection, 'scene', 'ALL')" @change="setText(selectedSection, 'scene', $event)"><option value="ALL">全部商品</option><option value="UPGRADE">成长精选</option><option value="REPURCHASE">品质复购</option></select></div>
              </template>
              <template v-else-if="selectedSection.type === 'SERVICE_BENEFITS'">
                <div class="field"><label>权益文案（每行一项）</label><textarea :value="benefitText(selectedSection)" @input="setBenefits(selectedSection, $event)" /></div>
              </template>
              <template v-else-if="selectedSection.type === 'CONTENT_STORY'">
                <div class="field"><label>内容类型</label><select :value="textSetting(selectedSection, 'contentType', 'HELP')" @change="setText(selectedSection, 'contentType', $event)"><option value="HELP">帮助内容</option><option value="BANNER">首页横幅</option><option value="ANNOUNCEMENT">商城公告</option></select></div>
              </template>
              <template v-else-if="selectedSection.type === 'ANNOUNCEMENT'">
                <div class="field"><label>显示条数</label><input type="number" min="1" max="10" :value="numberSetting(selectedSection, 'limit', 3)" @input="setNumber(selectedSection, 'limit', $event)" /></div>
              </template>
              <template v-else>
                <div class="field"><label>区块标题</label><input :value="textSetting(selectedSection, 'title')" @input="setText(selectedSection, 'title', $event)" /></div>
              </template>
            </div>
          </aside>
          <main class="preview-column" :class="{ 'mobile-active': mobilePanel === 'preview' }">
            <div class="preview-meta"><span>实时预览</span><b>{{ device === 'desktop' ? '1440 × 自适应' : '390 × 自适应' }}</b></div>
            <TemplatePreview :preset="editor.row.presetType" :tokens="editor.tokens" :sections="editor.sections" :device="device" />
          </main>
        </div>
      </section>
    </div>

    <BaseDialog v-model="createOpen" :title="createForm.sourceId ? '复制模板' : '新建商城模板'" description="模板创建后先进入草稿状态，不会影响当前商城。" width="min(680px, calc(100vw - 32px))" :submitting="busy === 'create'">
      <form id="create-template-form" class="create-modal" @submit.prevent="createTemplate">
        <div class="field"><label>模板名称</label><input v-model="createForm.name" required maxlength="120" /></div>
        <template v-if="!createForm.sourceId">
          <label v-for="preset in presets" :key="preset.value" class="preset-option" :class="{ active: createForm.presetType === preset.value }">
            <input v-model="createForm.presetType" type="radio" :value="preset.value" />
            <span :class="`mini-${preset.value.toLowerCase()}`"><b>{{ preset.value === 'VIBRANT' ? 'GO' : preset.value === 'MINIMAL' ? '01' : '拾' }}</b></span>
            <div><b>{{ preset.name }}</b><small>{{ preset.description }}</small></div>
          </label>
        </template>
      </form>
      <template #footer="{ close }"><button type="button" class="secondary" autofocus :disabled="Boolean(busy)" @click="close">取消</button><button class="primary" form="create-template-form" :disabled="Boolean(busy)">{{ busy === 'create' ? '创建中…' : '创建草稿' }}</button></template>
    </BaseDialog>

    <BusinessActionDialog
      :model-value="Boolean(confirmAction)"
      :title="confirmAction?.type === 'publish' ? '发布商城模板' : '归档商城模板'"
      :target="confirmAction?.row.name || '当前模板'"
      :impact="confirmAction?.type === 'publish' ? '发布后该模板将立即成为线上唯一生效模板，原模板会保留为已发布版本。' : '归档后模板不能再次编辑或发布，但历史记录继续保留。'"
      :current-state="confirmAction ? statusLabel(confirmAction.row) : ''"
      :next-state="confirmAction?.type === 'publish' ? '当前生效' : '已归档'"
      v-model:reason="confirmReason"
      :danger="confirmAction?.type === 'archive'"
      :confirm-label="confirmAction?.type === 'publish' ? '确认发布' : '确认归档'"
      :submitting="Boolean(busy)"
      :error="error"
      @update:model-value="value => { if (!value) { confirmAction = undefined; confirmReason = '' } }"
      @submit="executeConfirmed"
    />

    <BaseDialog v-model="discardEditorOpen" title="放弃未保存的模板改动？" description="返回列表或离开当前页面后，本地未保存的设计改动将丢失。" :show-default-footer="false" @update:model-value="value => { if (!value) continueEditing() }"><p class="discard-copy">服务端最近一次已保存草稿不受影响。</p><template #footer><button class="secondary" type="button" autofocus @click="continueEditing">继续编辑</button><button class="danger solid" type="button" @click="discardEditorChanges">放弃改动</button></template></BaseDialog>
  </div>
</template>

<style scoped>
.template-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(340px,1fr));gap:16px}.template-card{overflow:hidden}.template-card.active{border-color:#7ba493;box-shadow:0 14px 38px #315d5018}.template-cover{position:relative;height:200px;display:grid;place-items:center;overflow:hidden;background:linear-gradient(140deg,#dbcab6,#315d50)}.template-cover::before,.template-cover::after{content:"";position:absolute;border-radius:50%;border:1px solid #ffffff55}.template-cover::before{width:240px;height:240px;right:-60px;top:-90px}.template-cover::after{width:170px;height:170px;left:-50px;bottom:-90px}.template-cover>span{position:relative;z-index:2;color:white;font:700 86px serif;opacity:.9}.template-cover>i{position:absolute;left:18px;bottom:14px;color:#ffffffc5;font-size:10px;font-style:normal;letter-spacing:.14em}.cover-vibrant{background:#f7f42e;border-bottom:2px solid #111}.cover-vibrant>span{color:#111;font-family:sans-serif;font-weight:900;text-shadow:7px 7px 0 #ff5a36}.cover-minimal{background:linear-gradient(135deg,#f4f4f1,#c4c4bf)}.cover-minimal>span{color:#1c1c1c;font-family:sans-serif;font-weight:300}.cover-minimal>i{color:#333}.template-copy{padding:19px}.template-title{display:flex;justify-content:space-between;gap:14px}.template-title h2{margin:9px 0 3px;font:650 22px serif}.template-title small,.template-copy>p{color:var(--muted);font-size:11px}.template-title>b{color:var(--muted);font-size:11px}.template-actions{display:flex;flex-wrap:wrap;gap:7px;margin-top:17px}
.designer-mask{position:fixed;inset:0;z-index:80;background:#eef0ed}.designer{height:100dvh;display:flex;flex-direction:column}.designer-header{flex:0 0 72px;display:grid;grid-template-columns:1fr auto auto;align-items:center;gap:18px;padding:0 22px;border-bottom:1px solid var(--line);background:white}.designer-feedback{flex:0 0 auto;padding:0 16px;background:white}.designer-feedback .inline-alert{margin:10px 0}.designer-header>div:first-child{display:flex;align-items:center;gap:16px}.designer-header h2{margin:0;font:650 18px serif}.designer-header span{color:var(--muted);font-size:11px}.designer-header span.unsaved{color:var(--color-warning);font-weight:750}.back{padding:0;border:0;background:transparent;color:var(--green);font-weight:750}.device-switch,.mobile-panel-switch{display:flex;padding:3px;border-radius:9px;background:#e9eeeb}.device-switch button,.mobile-panel-switch button{min-height:34px;padding:0 12px;border:0;border-radius:7px;background:transparent;font-size:11px}.device-switch button.active,.mobile-panel-switch button.active{background:white;box-shadow:0 2px 7px #00000012}.mobile-panel-switch{display:none}.designer-body{min-height:0;flex:1 1 auto;display:grid;grid-template-columns:360px 1fr}.designer-sidebar{overflow:auto;padding:16px;border-right:1px solid var(--line);background:#f7f8f6}.editor-group{padding:16px;margin-bottom:12px;border:1px solid var(--line);border-radius:12px;background:white}.editor-group h3{margin:0 0 14px;font:650 15px serif}.editor-group .field+.field{margin-top:11px}.color-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.color-grid label{display:grid;gap:5px;color:var(--muted);font-size:10px}.color-grid input{width:100%;height:36px;padding:2px;border:1px solid var(--line);border-radius:7px;background:white}.row-fields{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:11px}.group-title{display:flex;align-items:center;justify-content:space-between;gap:10px}.group-title select{max-width:110px;padding:5px;border:1px solid var(--line);border-radius:7px}.section-item{width:100%;display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:6px;padding:7px;color:var(--ink);border:1px solid transparent;border-radius:8px;background:#f3f5f2}.section-item.selected{border-color:var(--green);background:#eaf2ed}.section-select{min-width:0;display:flex;align-items:center;gap:9px;flex:1;padding:3px;border:0;background:transparent;text-align:left}.section-select i{color:var(--muted);font-size:9px;font-style:normal}.section-select b{font-size:11px}.section-controls{display:flex;align-items:center;gap:5px}.section-controls button{display:grid;place-items:center;width:26px;height:26px;padding:0;border:0;border-radius:5px;background:white;font-size:10px}.section-controls label{display:grid;place-items:center;width:26px;height:26px}.section-settings textarea{min-height:74px;resize:vertical}.section-settings .group-title button{padding:0;color:#ad3d30;border:0;background:transparent;font-size:10px}.preview-column{min-width:0;overflow:auto;padding:16px 22px 30px}.preview-meta{display:flex;justify-content:space-between;margin-bottom:10px;color:var(--muted);font-size:10px}
.modal-title{display:flex;justify-content:space-between;gap:12px;align-items:start}.modal-title h2,.modal-title p{margin:0}.modal-title p{margin-top:5px;color:var(--muted);font-size:12px}.create-modal{width:min(680px,100%);max-height:92vh;overflow:auto}.create-modal>.field{margin:18px 0}.preset-option{display:grid;grid-template-columns:auto 90px 1fr;align-items:center;gap:13px;margin-top:9px;padding:10px;border:1px solid var(--line);border-radius:11px;cursor:pointer}.preset-option.active{border-color:var(--green);background:#edf4f0}.preset-option>input{accent-color:var(--green)}.preset-option>span{height:62px;display:grid;place-items:center;border-radius:7px;background:linear-gradient(135deg,#d7c7b5,#315d50);color:white}.preset-option .mini-vibrant{color:#111;background:#f7f42e}.preset-option .mini-vibrant b{text-shadow:3px 3px 0 #ff5a36}.preset-option .mini-minimal{color:#111;background:#e6e6e2}.preset-option>div b,.preset-option>div small{display:block}.preset-option>div small{margin-top:5px;color:var(--muted);line-height:1.5}.confirm-modal{text-align:center}.confirm-icon{display:grid;place-items:center;width:56px;height:56px;margin:0 auto 16px;color:white;border-radius:50%;background:var(--green);font-size:24px}.confirm-modal p{color:var(--muted);line-height:1.7}.confirm-modal .modal-actions{justify-content:center}
.discard-copy{color:var(--color-text-muted);line-height:1.7}
@media(max-width:900px){.designer-header{flex-basis:auto;grid-template-columns:1fr auto;gap:9px;padding:10px 12px}.designer-header>div:first-child{grid-column:1/-1;flex-wrap:wrap}.designer-header>div:first-child span{display:inline}.mobile-panel-switch{display:flex}.designer-body{display:block;min-height:0}.designer-sidebar,.preview-column{display:none;height:100%;overflow:auto}.designer-sidebar.mobile-active,.preview-column.mobile-active{display:block}.preview-column{padding:10px}.template-grid{grid-template-columns:1fr}.device-switch{display:flex}}
@media(max-width:520px){.designer-header{grid-template-columns:1fr}.designer-header>div:first-child{grid-column:1}.device-switch,.mobile-panel-switch{width:100%}.device-switch button,.mobile-panel-switch button{flex:1}}
</style>
