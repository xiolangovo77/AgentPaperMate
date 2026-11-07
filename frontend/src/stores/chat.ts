import { defineStore } from 'pinia'
import { reactive } from 'vue'
import type { RunCreate } from '../types/api'
// Transport recovery only. Questions and drafts are persisted by the backend, never localStorage.
export const useChatStore = defineStore('chat-recovery', () => {
  const pending = reactive<Record<string, RunCreate>>({})
  function clear() { for (const key of Object.keys(pending)) delete pending[key] }
  return { pending, clear }
})
