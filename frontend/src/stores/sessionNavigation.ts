import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { sessionApi } from '../api'
import { messageOf } from '../api/request'
import type { Session } from '../types/api'
import { router } from '../router'

// Shared navigation state survives page changes; conversation data stays in memory.
export const useSessionNavigationStore = defineStore('session-navigation', () => {
  const sessions = ref<Session[]>([]), current = ref<Session | null>(null), query = ref('')
  const busy = ref(false), error = ref(''), page = ref(1), pages = ref(1), retryMore = ref(false)
  const expanded = ref(true), showHistory = ref(false), creating = ref(false), creationError = ref('')
  let newSequence = 0
  let owner = '', epoch = 0, timer: ReturnType<typeof setTimeout> | undefined
  try { expanded.value = localStorage.getItem('apm-recent-expanded') !== 'false' } catch { /* In-memory preference still works. */ }
  watch(expanded, value => { try { localStorage.setItem('apm-recent-expanded', String(value)) } catch { /* Storage may be unavailable. */ } })
  async function load(more = false) {
    if (more && (busy.value || page.value >= pages.value)) return
    clearTimeout(timer)
    if (!owner) return
    const token = ++epoch, next = more ? page.value + 1 : 1
    busy.value = true; error.value = ''; retryMore.value = more
    try {
      const result = await sessionApi.list(next, query.value)
      if (token !== epoch) return
      sessions.value = more ? [...sessions.value, ...result.records] : result.records
      page.value = next; pages.value = Number(result.pages)
    } catch (e) { if (token === epoch) error.value = messageOf(e) }
    finally { if (token === epoch) busy.value = false }
  }
  function search(value: string) {
    query.value = value; epoch++; clearTimeout(timer)
    sessions.value = []; page.value = 1; pages.value = 1; error.value = ''; busy.value = !!owner
    if (value) expanded.value = true
    timer = setTimeout(() => void load(), 300)
  }
  function syncSession(value: Session) {
    current.value = { ...value }
    sessions.value = sessions.value.map(item => item.id === value.id ? { ...value } : item)
  }
  async function refresh() { query.value = ''; await load() }
  async function startNew(value = '') {
    if (creating.value || !owner) return
    const requestedOwner = owner
    creating.value = true; creationError.value = ''; showHistory.value = false
    try {
      // A new chat is local until its first question is submitted.
      await router.push({ path: '/chat', query: { new: `${Date.now()}-${++newSequence}`, ...(value ? { question: value } : {}) } })
    } catch (e) { if (owner === requestedOwner) creationError.value = messageOf(e) }
    finally { if (owner === requestedOwner) creating.value = false }
  }
  function reset() {
    owner = ''; epoch++; clearTimeout(timer)
    sessions.value = []; current.value = null; query.value = ''; busy.value = false; error.value = ''
    page.value = 1; pages.value = 1; showHistory.value = false; creating.value = false; creationError.value = ''
  }
  function setOwner(id?: string) {
    if (owner === (id || '')) return
    reset(); owner = id || ''
    if (owner) void load()
  }
  return { sessions, current, query, busy, error, page, pages, retryMore, expanded, showHistory, creating, creationError, load, search, syncSession, refresh, startNew, reset, setOwner }
})
