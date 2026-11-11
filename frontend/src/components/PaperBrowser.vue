<script setup lang="ts">
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { FileText, RefreshCw, Trash2, RotateCcw } from 'lucide-vue-next'
import { paperApi } from '../api'
import { messageOf } from '../api/request'
import type { PaperSummary } from '../types/api'
import { dateTime } from '../utils/format'
import { paperStatus, paperTitle } from '../utils/papers'
import Feedback from './Feedback.vue'
const props = withDefaults(defineProps<{ selected?: string[]; selectable?: boolean; refreshKey?: number; recycled?: boolean; manageable?: boolean }>(), { selected: () => [], selectable: true, refreshKey: 0, recycled: false, manageable: false })
const emit = defineEmits<{ toggle: [paper: PaperSummary]; loaded: [papers: PaperSummary[]]; changed: [id: string] }>()
const actionId = ref('')
async function changeRecycle(paper: PaperSummary) {
  if (actionId.value) return
  if (!props.recycled && !window.confirm(`将《${paperTitle(paper)}》移入回收站？可以随时恢复，已有会话仍可使用。`)) return
  actionId.value = paper.id; error.value = ''
  try { await paperApi.recycle(paper.id, props.recycled ? 0 : 1); emit('changed', paper.id); await load() }
  catch (e) { error.value = messageOf(e) }
  finally { actionId.value = '' }
}
const query = reactive({ title: '', authors: '', topic: '', status: '' })
const rows = ref<PaperSummary[]>([]), page = ref(1), pages = ref(1), total = ref('0'), loading = ref(false), error = ref('')
let epoch = 0, debounce: ReturnType<typeof setTimeout> | undefined, poll: ReturnType<typeof setTimeout> | undefined
async function load(quiet = false) {
  const token = ++epoch; clearTimeout(poll)
  if (!quiet) loading.value = true
  error.value = ''
  try {
    const result = await paperApi.list({ ...query, isRecycled: props.recycled ? 1 : 0, pageNum: page.value, pageSize: 10 })
    if (token !== epoch) return
    if (page.value > Math.max(1, Number(result.pages))) { page.value = Math.max(1, Number(result.pages)); return }
    rows.value = result.records; pages.value = Math.max(1, Number(result.pages)); total.value = String(result.total); emit('loaded', rows.value)
    if (rows.value.some(row => ['uploaded', 'processing'].includes(row.status))) poll = setTimeout(() => void load(true), 5000)
  } catch (e) { if (token === epoch) error.value = messageOf(e) }
  finally { if (token === epoch) loading.value = false }
}
watch(query, () => {
  epoch++; clearTimeout(poll); clearTimeout(debounce); loading.value = true
  debounce = setTimeout(() => { if (page.value !== 1) page.value = 1; else void load() }, 300)
})
watch(page, () => void load())
watch(() => props.recycled, () => { if (page.value !== 1) page.value = 1; else void load() })
watch(() => props.refreshKey, () => void load(), { immediate: true })
onBeforeUnmount(() => { epoch++; clearTimeout(debounce); clearTimeout(poll) })
</script>
<template><div class="paper-browser"><div class="paper-filters"><input v-model="query.title" class="field" aria-label="搜索论文标题" placeholder="搜索论文标题" maxlength="200" /><input v-model="query.authors" class="field" aria-label="搜索作者" placeholder="作者" maxlength="200" /><input v-model="query.topic" class="field" aria-label="筛选主题" placeholder="主题关键词" maxlength="200" /><select v-model="query.status" class="select" aria-label="处理状态"><option value="">全部状态</option><option v-for="(label, value) in paperStatus" :key="value" :value="value">{{ label }}</option></select><button class="icon-btn" aria-label="刷新论文列表" @click="load()"><RefreshCw :size="15" /></button></div><Feedback :error="error" :loading="loading"><button class="text-link" @click="load()">重试</button></Feedback><template v-if="!loading && !error"><div v-if="!rows.length" class="empty-state"><FileText :size="30" /><h3>{{ recycled ? '回收站为空' : '没有找到论文' }}</h3><p>{{ recycled ? '回收的论文会显示在这里，可以随时恢复。' : '调整筛选条件，或导入你的第一篇 PDF。' }}</p></div><div class="paper-results"><article v-for="paper in rows" :key="paper.id" class="paper-result"><input v-if="selectable && !recycled" type="checkbox" :aria-label="`选择 ${paperTitle(paper)}`" :checked="selected.includes(paper.id)" @change="emit('toggle', paper)" /><div class="paper-result-body"><RouterLink class="paper-title-link" :to="`/library/${paper.id}`">{{ paperTitle(paper) }}</RouterLink><p class="paper-meta">{{ paper.authors || '作者待解析' }} · {{ paper.pageCount === null ? '页数待解析' : `${paper.pageCount} 页` }} · {{ dateTime(paper.createTime) }}</p><div class="tag-row"><span v-if="paper.topic" class="tag">{{ paper.topic }}</span><span class="tag" :class="paper.status === 'ready' ? 'green' : 'purple'">{{ paperStatus[paper.status] || paper.status }}</span></div></div><RouterLink class="btn small" :to="`/library/${paper.id}?tab=notes`">阅读 / 笔记</RouterLink><button v-if="manageable" class="btn small" :disabled="!!actionId" @click="changeRecycle(paper)"><RotateCcw v-if="recycled" :size="14" /><Trash2 v-else :size="14" />{{ recycled ? '恢复' : '回收' }}</button></article></div><div class="scope-pagination"><span>共 {{ total }} 篇 · 第 {{ page }} / {{ pages }} 页</span><div class="toolbar"><button class="btn small" :disabled="page <= 1" @click="page--">上一页</button><button class="btn small" :disabled="page >= pages" @click="page++">下一页</button></div></div></template></div></template>
