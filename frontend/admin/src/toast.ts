import { readonly, ref } from 'vue'

export type ToastTone = 'success' | 'danger' | 'info'
export type ToastMessage = Readonly<{
  id: number
  tone: ToastTone
  title: string
  message?: string
}>

const messages = ref<ToastMessage[]>([])
let nextId = 1

export function dismissToast(id: number) {
  messages.value = messages.value.filter(message => message.id !== id)
}

export function pushToast(
  title: string,
  options: { tone?: ToastTone; message?: string; duration?: number } = {}
) {
  const id = nextId++
  messages.value = [
    ...messages.value,
    { id, title, tone: options.tone ?? 'info', message: options.message }
  ]
  const duration = options.duration ?? 4200
  if (duration > 0 && typeof window !== 'undefined') {
    window.setTimeout(() => dismissToast(id), duration)
  }
  return id
}

export const notifySuccess = (title: string, message?: string) =>
  pushToast(title, { tone: 'success', message })

export const notifyError = (title: string, message?: string) =>
  pushToast(title, { tone: 'danger', message, duration: 6500 })

export function useToastRegion() {
  return { messages: readonly(messages), dismissToast }
}
