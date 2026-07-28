<script setup lang="ts">
import { computed } from 'vue'
import { QuillEditor } from '@vueup/vue-quill'

const props = withDefaults(defineProps<{
  modelValue?: string
  placeholder?: string
}>(), {
  modelValue: '',
  placeholder: '请输入正文内容'
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const toolbar: unknown[] = [
  [{ header: [2, 3, false] }],
  ['bold', 'italic', 'underline', 'strike'],
  [{ list: 'ordered' }, { list: 'bullet' }],
  ['blockquote', 'link'],
  ['clean']
]

const content = computed({
  get: () => props.modelValue,
  set: (value: string) => {
    const normalized = value.trim() === '<p><br></p>' ? '' : value
    emit('update:modelValue', normalized)
  }
})
</script>

<template>
  <div class="rich-text-editor">
    <QuillEditor
      v-model:content="content"
      content-type="html"
      theme="snow"
      :toolbar="toolbar"
      :placeholder="placeholder"
    />
    <p>支持标题、列表、引用和链接；商品图片请先上传至素材库。</p>
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
.rich-text-editor > p {
  margin: 0;
  padding: 8px 12px;
  color: var(--muted);
  border-top: 1px solid var(--line);
  background: #fafbfa;
  font-size: 11px;
}
:deep(.ql-toolbar.ql-snow) {
  border: 0;
  border-bottom: 1px solid var(--line);
  background: #f7f9f7;
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
