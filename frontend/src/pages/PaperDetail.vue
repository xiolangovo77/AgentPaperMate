<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, Info, NotebookPen, MessagesSquare, X, Minimize, ChevronDown } from 'lucide-vue-next'
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
const route = useRoute()
const id = computed(() => String(route.params.id))
const page = computed(() => { const n = Number(route.query.page); return Number.isSafeInteger(n) && n > 0 ? n : 1 })
const paper = ref<PaperDetail>(), error = ref(''), loading = ref(false), pdfError = ref(''), pdfLoading = ref(false), data = shallowRef<Uint8Array>()
const target = ref<'create' | 'existing' | null>(null), focused = ref(false)
const panel = ref<'notes' | 'details' | null>(route.query.tab === 'notes' || window.matchMedia('(min-width: 1100px)').matches ? 'notes' : null)
const notesOpened = ref(panel.value === 'notes')
let epoch = 0, controller: AbortController | undefined
async function load() {
  const token = ++epoch; loading.value = !paper.value; error.value = ''
  try {
    if (!validId(id.value)) throw new Error('论文 ID 无效。')
    const result = await paperApi.get(id.value)
    if (token === epoch) {
      const firstLoad = !paper.value
      paper.value = result
      if (firstLoad && (result.status === 'failed' || result.status === 'processing' || result.status === 'uploaded')) panel.value = 'details'
    }
  } catch (e) { if (token === epoch) error.value = messageOf(e) }
  finally { if (token === epoch) loading.value = false }
}
async function loadPdf() {
  controller?.abort(); const current = new AbortController(); controller = current; pdfError.value = ''; pdfLoading.value = true
  try { const bytes = await getPdf(id.value, current.signal); if (!current.signal.aborted) data.value = bytes }
  catch (e) { if (!current.signal.aborted) pdfError.value = messageOf(e) }
  finally { if (!current.signal.aborted) pdfLoading.value = false }
}
function togglePanel(value: 'notes' | 'details') { panel.value = panel.value === value ? null : value }
watch(panel, value => { if (value === 'notes') notesOpened.value = true })
watch(() => route.query.tab, value => { if (value === 'notes') panel.value = 'notes'; else if (value === 'pdf') panel.value = null })
watch(id, async () => {
  controller?.abort(); pdfLoading.value = false; paper.value = undefined; data.value = undefined; pdfError.value = ''; target.value = null
  await load()
}, { immediate: true })
watch(() => paper.value?.id, () => { if (paper.value && !data.value && !pdfLoading.value) void loadPdf() })
function escapeFocus(event: KeyboardEvent) { if (event.key === 'Escape') focused.value = false }
onMounted(() => window.addEventListener('keydown', escapeFocus))
onBeforeUnmount(() => { epoch++; controller?.abort(); window.removeEventListener('keydown', escapeFocus) })
</script>

<template>
  <section class="paper-workspace" :class="{ 'reader-focus': focused }" aria-label="论文阅读工作区">
    <header class="reader-header">
      <RouterLink to="/library" class="icon-btn reader-back" aria-label="返回论文库" title="返回论文库"><ArrowLeft :size="18" /></RouterLink>
      <div class="reader-title">
        <span class="reader-eyebrow">论文阅读 <span v-if="paper?.pageCount">/ {{ paper.pageCount }} 页</span></span>
        <h1 :title="paper ? paperTitle(paper) : ''">{{ paper ? paperTitle(paper) : '正在打开论文…' }}</h1>
      </div>
      <div v-if="paper" class="reader-actions">
        <button class="btn reader-panel-toggle" aria-label="论文信息" :class="{ selected: panel === 'details' }" :aria-expanded="panel === 'details'" aria-controls="paper-side-panel" @click="togglePanel('details')"><Info :size="16" /><span>论文信息</span></button>
        <button class="btn reader-panel-toggle" aria-label="阅读笔记" :class="{ selected: panel === 'notes' }" :aria-expanded="panel === 'notes'" aria-controls="paper-side-panel" @click="togglePanel('notes')"><NotebookPen :size="16" /><span>阅读笔记</span></button>
        <details v-if="!paper.isRecycled" class="reader-chat-menu">
          <summary class="btn primary" aria-label="论文问答"><MessagesSquare :size="16" /><span>论文问答</span><ChevronDown :size="13" /></summary>
          <div class="reader-chat-options"><button @click="target = 'create'">新建聊天室</button><button @click="target = 'existing'">加入聊天室</button></div>
        </details>
        <button v-if="focused" class="icon-btn" aria-label="退出专注模式" title="退出专注模式（Esc）" @click="focused = false"><Minimize :size="17" /></button>
      </div>
    </header>
    <Feedback :error="error" :loading="loading"><button class="text-link" @click="load">重试</button></Feedback>
    <template v-if="paper">
      <p v-if="paper.isRecycled" class="notice reader-notice">该论文已回收，已有会话仍可使用。<RouterLink to="/library?view=recycle" class="text-link">前往回收站恢复</RouterLink></p>
      <div class="reader-body" :class="{ 'has-panel': !!panel && !focused }">
        <div class="reader-document">
          <Feedback :loading="pdfLoading" :error="pdfError"><button class="text-link" @click="loadPdf">重新加载 PDF</button></Feedback>
          <PdfViewer v-if="data" :key="id" :data="data" :initial-page="page" :focused="focused" @toggle-focus="focused = !focused" />
        </div>
        <aside id="paper-side-panel" v-show="panel && !focused" class="reader-side-panel" aria-label="论文辅助面板">
          <div class="reader-panel-header">
            <div class="reader-panel-tabs" role="tablist" aria-label="阅读辅助">
              <button id="notes-tab" role="tab" :aria-selected="panel === 'notes'" aria-controls="reader-notes" @click="panel = 'notes'"><NotebookPen :size="15" />笔记</button>
              <button id="details-tab" role="tab" :aria-selected="panel === 'details'" aria-controls="reader-details" @click="panel = 'details'"><Info :size="15" />信息</button>
            </div>
            <button class="icon-btn" aria-label="收起侧栏" title="收起侧栏" @click="panel = null"><X :size="17" /></button>
          </div>
          <div class="reader-panel-scroll">
            <div id="reader-notes" v-show="panel === 'notes'" role="tabpanel" aria-labelledby="notes-tab">
              <NoteEditor v-if="notesOpened" :key="id" :paper-id="id" embedded />
            </div>
            <div id="reader-details" v-show="panel === 'details'" role="tabpanel" aria-labelledby="details-tab" class="reader-details">
              <div class="reader-info-heading">关于这篇论文</div>
              <h2>{{ paperTitle(paper) }}</h2>
              <p class="reader-authors">{{ paper.authors || '作者待解析' }}</p>
              <div class="tag-row"><span class="tag">{{ paperStatus[paper.status] }}</span><span class="tag" :class="paper.canAsk ? 'green' : 'purple'">{{ paper.canAsk ? '可用于问答' : '问答索引尚未就绪' }}</span><span v-if="paper.topic" class="tag">{{ paper.topic }}</span></div>
              <section v-if="paper.abstractText" class="reader-abstract"><h3>论文摘要</h3><p>{{ paper.abstractText }}</p></section>
              <PaperProgress v-if="paper.jobId" :key="paper.jobId" :job-id="paper.jobId" :allow-retry="!paper.isRecycled" @settled="load" @restarted="load" />
              <div v-else-if="paper.errorMessage" class="notice error">{{ paper.errorMessage }}</div>
            </div>
          </div>
        </aside>
      </div>
      <SessionTarget v-if="target" :mode="target" :paper-ids="[id]" @close="target = null" />
    </template>
  </section>
</template>
