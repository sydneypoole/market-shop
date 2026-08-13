<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, shallowRef, ref } from 'vue'
import { QuillEditor } from '@vueup/vue-quill'
import { adminErrorMessage } from '../api'
import {
  CATALOG_IMAGE_ACCEPT,
  catalogImageValidationMessage,
  uploadCatalogImage
} from '../catalog-assets'
import { notifySuccess } from '../toast'
import {
  normalizeImageWidth,
  normalizeRichTextImages,
  stableCatalogImagePath
} from '../rich-text-html'

type QuillSelection = { index: number; length: number }
type QuillSource = 'api' | 'silent' | 'user'
type RichTextBlot = { domNode?: Node }
type RichTextQuill = {
  clipboard: { dangerouslyPasteHTML: (html: string, source: QuillSource) => void }
  focus: () => void
  formatText: (
    index: number,
    length: number,
    formats: Record<string, string | false>,
    source: QuillSource
  ) => void
  getIndex: (blot: RichTextBlot) => number
  getLeaf: (index: number) => [RichTextBlot | null, number]
  getLength: () => number
  getSelection: (focus?: boolean) => QuillSelection | null
  insertEmbed: (index: number, type: string, value: string, source: QuillSource) => void
  root: HTMLDivElement
  scroll: { find: (node: Node, bubble?: boolean) => RichTextBlot | null }
  setSelection: (index: number, length: number, source: QuillSource) => void
}

type SelectionChange = { range: QuillSelection | null }

const IMAGE_WIDTH_PRESETS = [
  { label: '铺满正文', value: '100%' },
  { label: '宽版', value: '75%' },
  { label: '中版', value: '50%' },
  { label: '小图', value: '25%' }
] as const

const props = withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
}>(), {
  modelValue: '',
  placeholder: '请输入正文内容'
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  uploadingChange: [value: boolean]
}>()

const editor = shallowRef<RichTextQuill>()
const imageInput = ref<HTMLInputElement>()
const imageUploading = ref(false)
const imageError = ref('')
const imageSecurityWarning = ref('')
const selectedImageIndex = ref<number>()
const selectedImageWidth = ref('')
const customImageWidth = ref(50)
const sizePanel = ref<HTMLElement>()
let pendingImageIndex: number | undefined
let editorRoot: HTMLDivElement | undefined

const toolbar = {
  container: [
    [{ header: [2, 3, false] }],
    ['bold', 'italic', 'underline', 'strike'],
    [{ list: 'ordered' }, { list: 'bullet' }],
    ['blockquote', 'link', 'image'],
    ['clean']
  ],
  handlers: { image: openImageChooser }
}

const content = computed({
  // Keep the getter referentially transparent. Sanitizing through a fresh DOM on every
  // render would rewrite Quill's root while the operator is typing and clear its history.
  get: () => props.modelValue,
  set: (value: string) => {
    const result = normalizeRichTextImages(value)
    if (result.removedImages > 0) {
      imageSecurityWarning.value = `已移除 ${result.removedImages} 张非平台素材图片，请通过工具栏重新上传。`
    }
    const normalized = result.html.trim() === '<p><br></p>' ? '' : result.html
    emit('update:modelValue', normalized)
  }
})

const selectedImage = computed(() => selectedImageIndex.value !== undefined)
const selectedSizeLabel = computed(() => {
  if (!selectedImage.value) return ''
  return selectedImageWidth.value ? `当前宽度 ${selectedImageWidth.value}` : '当前为原始尺寸'
})

function onReady(instance: RichTextQuill) {
  editor.value = instance
  editorRoot = instance.root
  editorRoot.addEventListener('click', onEditorClick)
  editorRoot.addEventListener('keydown', onEditorKeydown)

  const normalized = normalizeRichTextImages(props.modelValue)
  if (normalized.removedImages > 0) {
    imageSecurityWarning.value = `已移除 ${normalized.removedImages} 张非平台素材图片，请通过工具栏重新上传。`
    instance.clipboard.dangerouslyPasteHTML(normalized.html, 'silent')
    emit('update:modelValue', normalized.html)
  }
}

onBeforeUnmount(() => {
  editorRoot?.removeEventListener('click', onEditorClick)
  editorRoot?.removeEventListener('keydown', onEditorKeydown)
})

function imageAt(index: number): { blot: RichTextBlot; element: HTMLImageElement; index: number } | undefined {
  const instance = editor.value
  if (!instance || index < 0 || index >= instance.getLength()) return undefined
  const [blot] = instance.getLeaf(index)
  const element = blot?.domNode
  if (!blot || !(element instanceof HTMLImageElement)) return undefined
  return { blot, element, index: instance.getIndex(blot) }
}

function selectImage(blot: RichTextBlot, element: HTMLImageElement, focusPanel = false) {
  const instance = editor.value
  if (!instance) return
  const src = stableCatalogImagePath(element.getAttribute('src'))
  if (!src) {
    imageError.value = '该图片不是平台素材，请删除后通过工具栏重新上传'
    clearSelectedImage()
    return
  }
  selectedImageIndex.value = instance.getIndex(blot)
  editorRoot?.querySelectorAll('img.is-size-selected').forEach(image => image.classList.remove('is-size-selected'))
  element.classList.add('is-size-selected')
  selectedImageWidth.value = normalizeImageWidth(element.getAttribute('width'))
  customImageWidth.value = Number.parseInt(selectedImageWidth.value, 10) || 50
  imageError.value = ''
  if (focusPanel) nextTick(() => sizePanel.value?.focus())
}

function clearSelectedImage() {
  editorRoot?.querySelectorAll('img.is-size-selected').forEach(image => image.classList.remove('is-size-selected'))
  selectedImageIndex.value = undefined
  selectedImageWidth.value = ''
}

function onEditorClick(event: MouseEvent) {
  const element = event.target instanceof HTMLImageElement ? event.target : undefined
  const blot = element ? editor.value?.scroll.find(element, true) : undefined
  if (element && blot) selectImage(blot, element)
}

function onSelectionChange(event: SelectionChange) {
  const range = event.range
  if (!range) return
  const direct = imageAt(range.index)
  const previous = range.length === 0 ? imageAt(range.index - 1) : undefined
  const match = direct || previous
  if (match) selectImage(match.blot, match.element)
  else clearSelectedImage()
}

function selectNearestImage(focusPanel = true) {
  const instance = editor.value
  if (!instance) return
  const selection = instance.getSelection()
  const candidates = [selection?.index ?? 0, (selection?.index ?? 0) - 1]
  for (const index of candidates) {
    const match = imageAt(index)
    if (match) {
      selectImage(match.blot, match.element, focusPanel)
      return
    }
  }
  imageError.value = '请先单击一张图片，或将光标移到图片旁边'
}

function onEditorKeydown(event: KeyboardEvent) {
  if (event.altKey && event.shiftKey && event.key.toLowerCase() === 'i') {
    event.preventDefault()
    selectNearestImage()
  }
}

function applyImageWidth(width: string) {
  const instance = editor.value
  const index = selectedImageIndex.value
  if (!instance || index === undefined || imageUploading.value) return
  const match = imageAt(index)
  if (!match) {
    clearSelectedImage()
    imageError.value = '图片已被删除，请重新选择'
    return
  }
  const normalized = normalizeImageWidth(width)
  instance.formatText(index, 1, { width: normalized || false, height: false }, 'user')
  selectedImageWidth.value = normalized
  instance.setSelection(index, 1, 'silent')
  imageError.value = ''
  notifySuccess(normalized ? `图片宽度已设为 ${normalized}` : '已恢复图片原始尺寸')
}

function applyCustomImageWidth() {
  const value = Number(customImageWidth.value)
  if (!Number.isInteger(value) || value < 10 || value > 100) {
    imageError.value = '自定义宽度请输入 10 到 100 的整数'
    return
  }
  applyImageWidth(`${value}%`)
}

function openImageChooser() {
  if (imageUploading.value) return
  const instance = editor.value
  if (!instance) {
    imageError.value = '编辑器尚未准备好，请稍后重试'
    return
  }
  const selection = instance.getSelection(true)
  pendingImageIndex = selection?.index ?? Math.max(0, instance.getLength() - 1)
  imageError.value = ''
  imageInput.value?.click()
}

async function uploadImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || imageUploading.value) return

  const validationMessage = catalogImageValidationMessage(file)
  if (validationMessage) {
    imageError.value = validationMessage
    input.value = ''
    return
  }

  imageUploading.value = true
  imageError.value = ''
  emit('uploadingChange', true)
  try {
    const asset = await uploadCatalogImage(file)
    const instance = editor.value
    if (!instance) {
      imageError.value = '图片已上传至素材库，但编辑器已关闭，请重新打开后插入'
      return
    }
    const lastInsertIndex = Math.max(0, instance.getLength() - 1)
    const index = Math.min(Math.max(0, pendingImageIndex ?? lastInsertIndex), lastInsertIndex)
    instance.insertEmbed(index, 'image', asset.url, 'user')
    instance.formatText(index, 1, { alt: asset.originalFilename.slice(0, 160), width: '100%', height: false }, 'user')
    instance.setSelection(index + 1, 0, 'silent')
    instance.focus()
    const inserted = imageAt(index)
    if (inserted) selectImage(inserted.blot, inserted.element)
    notifySuccess('图片已插入图文详情')
  } catch (cause) {
    imageError.value = adminErrorMessage(cause, '图片上传失败，请稍后重试')
  } finally {
    imageUploading.value = false
    pendingImageIndex = undefined
    input.value = ''
    emit('uploadingChange', false)
  }
}
</script>

<template>
  <div class="rich-text-editor" :class="{ 'is-uploading': imageUploading }">
    <QuillEditor
      v-model:content="content"
      content-type="html"
      theme="snow"
      :toolbar="toolbar"
      :placeholder="placeholder"
      @ready="onReady"
      @selection-change="onSelectionChange"
    />
    <input
      ref="imageInput"
      class="image-input"
      type="file"
      :accept="CATALOG_IMAGE_ACCEPT"
      :disabled="imageUploading"
      tabindex="-1"
      aria-hidden="true"
      @change="uploadImage"
    />
    <section class="image-workbench" aria-labelledby="image-size-title">
      <div class="image-workbench__intro">
        <div>
          <h4 id="image-size-title">图片显示尺寸</h4>
          <p v-if="selectedImage" class="selection-status" role="status">{{ selectedSizeLabel }}</p>
          <p v-else class="selection-status">单击正文图片后设置；键盘可按 Alt + Shift + I</p>
        </div>
        <button
          v-if="!selectedImage"
          type="button"
          class="locate-image"
          :disabled="imageUploading"
          @click="selectNearestImage()"
        >定位图片</button>
      </div>

      <div
        ref="sizePanel"
        class="image-size-controls"
        :class="{ 'is-disabled': !selectedImage }"
        tabindex="-1"
        aria-label="图片尺寸设置"
      >
        <div class="size-presets" role="group" aria-label="常用图片宽度">
          <button
            v-for="preset in IMAGE_WIDTH_PRESETS"
            :key="preset.value"
            type="button"
            class="size-preset"
            :class="{ 'is-active': selectedImageWidth === preset.value }"
            :aria-pressed="selectedImageWidth === preset.value"
            :disabled="!selectedImage || imageUploading"
            @click="applyImageWidth(preset.value)"
          >
            <span class="size-preset__mark" :style="{ width: preset.value }"></span>
            <span>{{ preset.label }}</span>
            <strong>{{ preset.value }}</strong>
          </button>
          <button
            type="button"
            class="size-preset size-preset--original"
            :class="{ 'is-active': selectedImage && !selectedImageWidth }"
            :aria-pressed="selectedImage && !selectedImageWidth"
            :disabled="!selectedImage || imageUploading"
            @click="applyImageWidth('')"
          >
            <span class="size-preset__mark"></span>
            <span>原始尺寸</span>
            <strong>自适应</strong>
          </button>
        </div>

        <form class="custom-size" @submit.prevent="applyCustomImageWidth">
          <label for="custom-image-width">自定义宽度</label>
          <div class="custom-size__field">
            <input
              id="custom-image-width"
              v-model.number="customImageWidth"
              type="number"
              inputmode="numeric"
              min="10"
              max="100"
              step="1"
              :disabled="!selectedImage || imageUploading"
              aria-describedby="custom-size-help"
            />
            <span aria-hidden="true">%</span>
          </div>
          <button type="submit" :disabled="!selectedImage || imageUploading">应用</button>
          <small id="custom-size-help">10 至 100，按正文宽度的百分比显示</small>
        </form>
      </div>
    </section>

    <div class="editor-help">
      <p v-if="imageUploading" role="status"><span class="state-spinner"></span>图片上传中，请稍候…</p>
      <p v-else>通过工具栏上传 JPG、PNG 或 WebP（单张不超过 10 MB）；尺寸修改可使用撤销恢复。</p>
      <p v-if="imageSecurityWarning" class="warning" role="status">{{ imageSecurityWarning }}</p>
      <p v-if="imageError" class="error" role="alert">{{ imageError }}</p>
    </div>
  </div>
</template>

<style scoped>
.rich-text-editor {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--color-surface);
  transition: border-color .16s ease, box-shadow .16s ease;
}
.rich-text-editor:focus-within {
  border-color: var(--green);
  box-shadow: var(--focus-ring);
}
.editor-help {
  padding: 10px 14px;
  color: var(--muted);
  border-top: 1px solid var(--line);
  background: var(--color-surface-subtle);
  font-size: 12px;
}
.editor-help p {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
}
.editor-help .error {
  margin-top: 5px;
  color: var(--color-danger);
}
.editor-help .warning {
  margin-top: 6px;
  color: var(--color-warning);
}
.image-input {
  display: none;
}
:deep(.ql-toolbar.ql-snow) {
  border: 0;
  border-bottom: 1px solid var(--line);
  padding: 10px 12px;
  background: var(--color-surface-subtle);
}
.is-uploading :deep(.ql-toolbar .ql-image) {
  pointer-events: none;
  cursor: progress;
  opacity: .45;
}
:deep(.ql-container.ql-snow) {
  border: 0;
  font-family: inherit;
  font-size: 14px;
}
:deep(.ql-editor) {
  min-height: 220px;
  line-height: 1.75;
}
:deep(.ql-editor img) {
  display: block;
  max-width: 100%;
  height: auto;
  margin: 12px auto;
  border: 2px solid transparent;
  border-radius: 10px;
  cursor: pointer;
  transition: border-color .14s ease, box-shadow .14s ease;
}
:deep(.ql-editor img:hover) {
  border-color: rgba(120, 71, 111, .28);
}
:deep(.ql-editor img::selection) {
  background: rgba(120, 71, 111, .14);
}
:deep(.ql-editor img.is-size-selected) {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(120, 71, 111, .16);
}
:deep(.ql-editor.ql-blank::before) {
  color: var(--color-text-muted);
  font-style: normal;
}
.image-workbench {
  padding: 14px;
  border-top: 1px solid var(--line);
  background: var(--color-surface);
}
.image-workbench__intro {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.image-workbench h4 {
  margin: 0;
  color: var(--ink);
  font-size: 13px;
  line-height: 1.4;
}
.selection-status {
  margin: 3px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
}
.locate-image,
.custom-size button {
  min-height: 34px;
  padding: 0 13px;
  border: 1px solid var(--color-border-strong);
  border-radius: 8px;
  background: var(--color-surface);
  color: var(--green);
  font: inherit;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
}
.image-size-controls {
  margin-top: 12px;
}
.size-presets {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}
.size-preset {
  display: grid;
  min-width: 0;
  min-height: 68px;
  padding: 9px 10px;
  grid-template-columns: 1fr auto;
  grid-template-rows: 7px auto;
  gap: 7px 6px;
  align-items: center;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--color-surface-subtle);
  color: var(--ink);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.size-preset__mark {
  display: block;
  max-width: 100%;
  height: 5px;
  grid-column: 1 / -1;
  border-radius: 4px;
  background: var(--color-border-strong);
}
.size-preset span:not(.size-preset__mark) {
  overflow: hidden;
  font-size: 12px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.size-preset strong {
  color: var(--muted);
  font-size: 11px;
  font-weight: 600;
}
.size-preset.is-active {
  border-color: var(--green);
  background: var(--color-brand-soft);
  box-shadow: inset 0 0 0 1px var(--green);
}
.size-preset.is-active .size-preset__mark { background: var(--green); }
.size-preset--original .size-preset__mark {
  width: 100%;
  background: repeating-linear-gradient(90deg, var(--color-border-strong) 0 5px, transparent 5px 8px);
}
.custom-size {
  display: grid;
  margin-top: 10px;
  grid-template-columns: auto 92px auto 1fr;
  gap: 8px 10px;
  align-items: center;
}
.custom-size > label {
  color: var(--ink);
  font-size: 12px;
  font-weight: 700;
}
.custom-size__field {
  display: flex;
  height: 36px;
  align-items: center;
  border: 1px solid var(--color-border-strong);
  border-radius: 8px;
  background: white;
}
.custom-size__field:focus-within {
  border-color: var(--green);
  box-shadow: var(--focus-ring);
}
.custom-size input {
  width: 100%;
  min-width: 0;
  height: 100%;
  padding: 0 4px 0 10px;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
}
.custom-size__field > span {
  padding-right: 9px;
  color: var(--muted);
  font-size: 12px;
}
.custom-size small {
  color: var(--muted);
  font-size: 11px;
}
.image-size-controls.is-disabled { opacity: .58; }
.size-preset:disabled,
.locate-image:disabled,
.custom-size button:disabled,
.custom-size input:disabled {
  cursor: not-allowed;
}
.size-preset:not(:disabled):active,
.locate-image:not(:disabled):active,
.custom-size button:not(:disabled):active {
  transform: translateY(1px);
}
.size-preset:focus-visible,
.locate-image:focus-visible,
.custom-size button:focus-visible {
  outline: 3px solid rgba(120, 71, 111, .2);
  outline-offset: 2px;
}
@media (max-width: 700px) {
  :deep(.ql-editor) {
    min-height: 180px;
  }
  .size-presets {
    display: flex;
    padding-bottom: 4px;
    overflow-x: auto;
    scroll-snap-type: x proximity;
  }
  .size-preset {
    width: 132px;
    flex: 0 0 132px;
    scroll-snap-align: start;
  }
  .custom-size {
    grid-template-columns: 1fr auto;
  }
  .custom-size > label,
  .custom-size small { grid-column: 1 / -1; }
}
@media (prefers-reduced-motion: reduce) {
  .rich-text-editor,
  :deep(.ql-editor img) { transition: none; }
}
</style>
