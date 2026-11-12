<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, FileText, NotebookPen } from 'lucide-vue-next'
import { paperApi } from '../api'
import { getPdf, messageOf } from '../api/request'
import type { PaperDetail } from '../types/api'
import { validId } from '../utils/format'
import { paperStatus, paperTitle } from '../utils/papers'
import Feedback from '../components/Feedback.vue'
import PaperProgress from '../components/PaperProgress.vue'
import SessionTarget from '../components/SessionTarget.vue'
const NoteEditor = defineAsyncComponent(() => import('../components/NoteEditor.vue'))
const PdfViewer = defineAsyncComponent(() => import('../components/PdfViewer.vue'))
const route = useRoute(), router = useRouter()
const id = computed(() => String(route.params.id)), tab = computed(() => route.query.tab === 'notes' ? 'notes' : 'pdf')
const page = computed(() => { const n = Number(route.query.page); return Number.isSafeInteger(n) && n > 0 ? n : 1 })
const paper = ref<PaperDetail>(), error = ref(''), loading = ref(false), pdfError = ref(''), pdfLoading = ref(false), data = shallowRef<Uint8Array>()
const target = ref<'create' | 'existing' | null>(null)
let epoch = 0, controller: AbortController | undefined
async function load() {
  const token = ++epoch; loading.value = true; error.value = ''
  try { if (!validId(id.value)) throw new Error('论文 ID 无效。'); const result = await paperApi.get(id.value); if (token === epoch) paper.value = result }
  catch (e) { if (token === epoch) error.value = messageOf(e) }
  finally { if (token === epoch) loading.value = false }
}
async function loadPdf() {
  controller?.abort(); const current = new AbortController(); controller = current; pdfError.value = ''; pdfLoading.value = true
  try { const bytes = await getPdf(id.value, current.signal); if (!current.signal.aborted) data.value = bytes }
  catch (e) { if (!current.signal.aborted) pdfError.value = messageOf(e) }
  finally { if (!current.signal.aborted) pdfLoading.value = false }
}
watch(id, async () => { controller?.abort(); pdfLoading.value = false; paper.value = undefined; data.value = undefined; pdfError.value = ''; target.value = null; await load() }, { immediate: true })
watch([tab, () => paper.value?.id], () => { if (tab.value === 'pdf' && paper.value && !data.value && !pdfLoading.value) void loadPdf() })
onBeforeUnmount(() => { epoch++; controller?.abort() })
function select(value: string) { void router.replace({ query: { ...route.query, tab: value } }) }
</script>
<template><RouterLink to="/library" class="back-link"><ArrowLeft :size="15" />返回论文库</RouterLink><Feedback :error="error" :loading="loading"><button class="text-link" @click="load">重试</button></Feedback><template v-if="paper"><div class="page-heading"><div><div class="eyebrow">PAPER WORKSPACE</div><h1>{{ paperTitle(paper) }}</h1><p>{{ paper.authors || '作者待解析' }} · {{ paper.pageCount === null ? '页数待解析' : `${paper.pageCount} 页` }}<span v-if="paper.topic"> · {{ paper.topic }}</span></p><div class="tag-row section-space"><span class="tag">{{ paperStatus[paper.status] }}</span><span class="tag" :class="paper.canAsk ? 'green' : 'purple'">{{ paper.canAsk ? '可用于问答' : '问答索引尚未就绪' }}</span></div></div><div v-if="!paper.isRecycled" class="toolbar"><button class="btn" @click="target = 'existing'">加入聊天室</button><button class="btn primary" @click="target = 'create'">新建聊天室</button></div></div><p v-if="paper.isRecycled" class="notice">该论文已回收，已有会话仍可使用。<RouterLink to="/library?view=recycle" class="text-link">前往回收站恢复</RouterLink></p><details v-if="paper.abstractText" class="panel abstract-panel"><summary>论文摘要</summary><p>{{ paper.abstractText }}</p></details><PaperProgress v-if="paper.jobId" :key="paper.jobId" :job-id="paper.jobId" :allow-retry="!paper.isRecycled" @settled="load" @restarted="load" /><div v-else-if="paper.errorMessage" class="notice error">{{ paper.errorMessage }}</div><div class="tabs" role="tablist" aria-label="论文内容"><button role="tab" :aria-selected="tab === 'pdf'" :class="{ active: tab === 'pdf' }" @click="select('pdf')"><FileText :size="16" />论文原文</button><button role="tab" :aria-selected="tab === 'notes'" :class="{ active: tab === 'notes' }" @click="select('notes')"><NotebookPen :size="16" />阅读笔记</button></div><section v-if="tab === 'pdf'" role="tabpanel"><Feedback :loading="pdfLoading" :error="pdfError"><button class="text-link" @click="loadPdf">重新加载 PDF</button></Feedback><PdfViewer v-if="data" :key="id" :data="data" :initial-page="page" /></section><NoteEditor v-else :key="id" :paper-id="id" /><SessionTarget v-if="target" :mode="target" :paper-ids="[id]" @close="target = null" /></template></template>
