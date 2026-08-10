<script setup lang="ts">
import { computed, shallowRef, ref } from 'vue'
import { QuillEditor } from '@vueup/vue-quill'
import { adminErrorMessage } from '../api'
import {
  CATALOG_IMAGE_ACCEPT,
  catalogImageValidationMessage,
  uploadCatalogImage
} from '../catalog-assets'
import { notifySuccess } from '../toast'

type QuillSelection = { index: number; length: number }
type QuillSource = 'api' | 'silent' | 'user'
type RichTextQuill = {
  focus: () => void
  formatText: (index: number, length: number, formats: Record<string, string>, source: QuillSource) => void
  getLength: () => number
  getSelection: (focus?: boolean) => QuillSelection | null
  insertEmbed: (index: number, type: string, value: string, source: QuillSource) => void
  setSelection: (index: number, length: number, source: QuillSource) => void
}

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
let pendingImageIndex: number | undefined

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
  get: () => props.modelValue,
  set: (value: string) => {
    const normalized = value.trim() === '<p><br></p>' ? '' : value
    emit('update:modelValue', normalized)
  }
})

function onReady(instance: RichTextQuill) {
  editor.value = instance
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
    instance.formatText(index, 1, { alt: asset.originalFilename, width: '100%' }, 'user')
    instance.setSelection(index + 1, 0, 'silent')
    instance.focus()
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
    <div class="editor-help">
      <p v-if="imageUploading" role="status"><span class="state-spinner"></span>图片上传中，请稍候…</p>
      <p v-else>点击工具栏中的图片按钮，可上传 JPG、PNG 或 WebP 图片，单张不超过 10 MB。</p>
      <p v-if="imageError" class="error" role="alert">{{ imageError }}</p>
    </div>
  </div>
</template>

<style scoped>
.rich-text-editor {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: white;
}
.rich-text-editor:focus-within {
  border-color: var(--green);
  box-shadow: 0 0 0 3px rgba(49, 93, 80, .1);
}
.editor-help {
  padding: 8px 12px;
  color: var(--muted);
  border-top: 1px solid var(--line);
  background: #fafbfa;
  font-size: 11px;
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
.image-input {
  display: none;
}
:deep(.ql-toolbar.ql-snow) {
  border: 0;
  border-bottom: 1px solid var(--line);
  background: #f7f9f7;
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
}
:deep(.ql-editor.ql-blank::before) {
  color: #9aa29e;
  font-style: normal;
}
@media (max-width: 700px) {
  :deep(.ql-editor) {
    min-height: 180px;
  }
}
</style>
