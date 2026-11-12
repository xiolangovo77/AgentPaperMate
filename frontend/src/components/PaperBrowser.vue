<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, useId, watch } from 'vue'
import { FileText, RefreshCw, Trash2, RotateCcw, ChevronDown } from 'lucide-vue-next'
import { paperApi } from '../api'
import { messageOf } from '../api/request'
import type { PaperSummary } from '../types/api'
import { dateTime } from '../utils/format'
import { paperStatus, paperTitle } from '../utils/papers'
import Feedback from './Feedback.vue'
const props = withDefaults(defineProps<{ selected?: string[]; selectable?: boolean; refreshKey?: number; recycled?: boolean; manageable?: boolean; collapsibleFilters?: boolean }>(), { selected: () => [], selectable: true, refreshKey: 0, recycled: false, manageable: false, collapsibleFilters: false })
const emit = defineEmits<{ toggle: [paper: PaperSummary]; loaded: [papers: PaperSummary[]]; changed: [id: string] }>()
const actionId = ref(''), filtersOpen = ref(false)
const filtersId = useId()
async function changeRecycle(paper: PaperSummary) {
  if (actionId.value) return
  if (!props.recycled && !window.confirm(`将《${paperTitle(paper)}》移入回收站？可以随时恢复，已有会话仍可使用。`)) return
  actionId.value = paper.id; error.value = ''
  try { await paperApi.recycle(paper.id, props.recycled ? 0 : 1); emit('changed', paper.id); await load() }
  catch (e) { error.value = messageOf(e) }
  finally { actionId.value = '' }
}
const query = reactive({ title: '', authors: '', topic: '', status: '' })
const activeFilters = computed(() => [query.authors, query.topic, query.status].filter(value => value.trim()).length)
const rows = ref<PaperSummary[]>([]), page = ref(0), pages = ref(1), total = ref('0'), loading = ref(false), error = ref('')
const loadMarker = ref<HTMLElement>(), failedMore = ref(false)
let epoch = 0, debounce: ReturnType<typeof setTimeout> | undefined, poll: ReturnType<typeof setTimeout> | undefined
let observer: IntersectionObserver | undefined, scrollRoot: HTMLElement | null = null, frame = 0, disposed = false
async function load(quiet = false, more = false) {
  if (more && (loading.value || page.value >= pages.value)) return
  const token = ++epoch, next = more ? page.value + 1 : 1, last = quiet ? Math.max(1, page.value) : next
  clearTimeout(poll); clearTimeout(debounce)
  loading.value = true; error.value = ''; failedMore.value = more
  try {
    const fetched: PaperSummary[] = []
    let resultPages = 1, resultTotal = '0', fetchedPage = next
    // Refresh loaded pages together so processing status updates preserve the list.
    for (let number = next; number <= last; number++) {
      const result = await paperApi.list({ ...query, isRecycled: props.recycled ? 1 : 0, pageNum: number, pageSize: 10 })
      if (token !== epoch || disposed) return
      fetched.push(...result.records); resultPages = Math.max(1, Number(result.pages)); resultTotal = String(result.total); fetchedPage = number
      if (number >= resultPages) break
    }
    const combined = more ? [...rows.value, ...fetched] : fetched
    rows.value = [...new Map(combined.map(item => [item.id, item])).values()]
    page.value = Math.min(fetchedPage, resultPages); pages.value = resultPages; total.value = resultTotal
    emit('loaded', rows.value)
  } catch (e) { if (token === epoch) error.value = messageOf(e) }
  finally {
    if (token === epoch && !disposed) {
      loading.value = false
      if (rows.value.some(row => ['uploaded', 'processing'].includes(row.status))) poll = setTimeout(() => void load(true), 5000)
    }
  }
}
function requestMore() {
  const marker = loadMarker.value
  if (!marker || !marker.getClientRects().length || loading.value || error.value || page.value >= pages.value || disposed) return
  const rect = marker.getBoundingClientRect(), root = scrollRoot?.getBoundingClientRect()
  if (rect.top <= Math.min(root?.bottom ?? innerHeight, innerHeight) + 180 && rect.bottom >= Math.max(root?.top ?? 0, 0) - 180) void load(false, true)
}
function scheduleMore() { cancelAnimationFrame(frame); frame = requestAnimationFrame(requestMore) }
function resetList() {
  epoch++; clearTimeout(poll); clearTimeout(debounce)
  rows.value = []; page.value = 0; pages.value = 1; error.value = ''; loading.value = true
}
watch(query, () => { resetList(); debounce = setTimeout(() => void load(), 300) })
watch(() => props.recycled, () => { resetList(); void load() })
watch(() => props.refreshKey, () => void load(), { immediate: true })
watch([loading, error, () => rows.value.length, pages], scheduleMore, { flush: 'post' })
onMounted(() => {
  let ancestor = loadMarker.value?.parentElement
  while (ancestor && ancestor !== document.body && ancestor !== document.documentElement) {
    if (/(auto|scroll)/.test(getComputedStyle(ancestor).overflowY)) { scrollRoot = ancestor; break }
    ancestor = ancestor.parentElement
  }
  observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) scheduleMore() }, { root: scrollRoot, rootMargin: '180px 0px' })
  if (loadMarker.value) observer.observe(loadMarker.value)
  scheduleMore()
})
onBeforeUnmount(() => { disposed = true; epoch++; clearTimeout(debounce); clearTimeout(poll); observer?.disconnect(); cancelAnimationFrame(frame) })
</script>
<template><div class="paper-browser"><div class="paper-filters" :class="{ 'filters-collapsible': collapsibleFilters }"><input v-model="query.title" class="field" aria-label="搜索论文标题" placeholder="搜索论文标题" maxlength="200" /><button v-if="collapsibleFilters" type="button" class="btn paper-filter-toggle" :aria-expanded="filtersOpen" :aria-controls="filtersId" @click="filtersOpen = !filtersOpen">更多筛选<span v-if="activeFilters">（{{ activeFilters }}）</span><ChevronDown :size="14" :class="{ expanded: filtersOpen }" /></button><div v-show="!collapsibleFilters || filtersOpen" :id="filtersId" class="paper-advanced-filters"><input v-model="query.authors" class="field" aria-label="搜索作者" placeholder="作者" maxlength="200" /><input v-model="query.topic" class="field" aria-label="筛选主题" placeholder="主题关键词" maxlength="200" /><select v-model="query.status" class="select" aria-label="处理状态"><option value="">全部状态</option><option v-for="(label, value) in paperStatus" :key="value" :value="value">{{ label }}</option></select></div><button class="icon-btn" aria-label="刷新论文列表" @click="load()"><RefreshCw :size="15" /></button></div><Feedback :error="error" :loading="loading && !rows.length"><button class="text-link" @click="load(false, failedMore)">重试</button></Feedback><template v-if="rows.length || (!loading && !error)"><div v-if="!rows.length" class="empty-state"><FileText :size="30" /><h3>{{ recycled ? '回收站为空' : '没有找到论文' }}</h3><p>{{ recycled ? '回收的论文会显示在这里，可以随时恢复。' : '调整筛选条件，或导入你的第一篇 PDF。' }}</p></div><div class="paper-results"><article v-for="paper in rows" :key="paper.id" class="paper-result"><input v-if="selectable && !recycled" type="checkbox" :aria-label="`选择 ${paperTitle(paper)}`" :checked="selected.includes(paper.id)" @change="emit('toggle', paper)" /><div class="paper-result-body"><RouterLink class="paper-title-link" :to="`/library/${paper.id}`">{{ paperTitle(paper) }}</RouterLink><p class="paper-meta">{{ paper.authors || '作者待解析' }} · {{ paper.pageCount === null ? '页数待解析' : `${paper.pageCount} 页` }} · {{ dateTime(paper.createTime) }}</p><div class="tag-row"><span v-if="paper.topic" class="tag">{{ paper.topic }}</span><span class="tag" :class="paper.status === 'ready' ? 'green' : 'purple'">{{ paperStatus[paper.status] || paper.status }}</span></div></div><RouterLink class="btn small" :to="`/library/${paper.id}?tab=notes`">阅读 / 笔记</RouterLink><button v-if="manageable" class="btn small" :disabled="!!actionId" @click="changeRecycle(paper)"><RotateCcw v-if="recycled" :size="14" /><Trash2 v-else :size="14" />{{ recycled ? '恢复' : '回收' }}</button></article></div></template><div ref="loadMarker" class="paper-load-marker"><span v-if="rows.length">共 {{ total }} 篇 · 已加载 {{ rows.length }} 篇</span><span v-if="loading && rows.length" role="status">正在加载论文…</span><span v-else-if="rows.length && page >= pages && !error">已显示全部论文</span></div></div></template>
