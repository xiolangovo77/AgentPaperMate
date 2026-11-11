<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
import { Quote, ArrowUpRight, RefreshCw, Copy, Check } from 'lucide-vue-next'
import { messageApi } from '../api'
import { messageOf } from '../api/request'
import type { Message, Citation } from '../types/api'
import { dateTime } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'
const props = defineProps<{ message: Message }>()
const emit = defineEmits<{ retry: [message: Message] }>()
const citations = ref<Citation[]>([]), error = ref(''), loading = ref(false)
const sourceCount = computed(() => new Set(citations.value.map(citation => citation.paperId)).size)
const html = computed(() => props.message.role === 'assistant' ? renderMarkdown(props.message.content, citations.value.map(item => item.evidenceId)) : '')
const sourcesElement = ref<HTMLDetailsElement>()
const copied = ref(false), copyError = ref('')
let copyTimer: ReturnType<typeof setTimeout> | undefined
async function copy() {
  copyError.value = ''
  try {
    await navigator.clipboard.writeText(props.message.content)
    copied.value = true; clearTimeout(copyTimer)
    copyTimer = setTimeout(() => copied.value = false, 2000)
  } catch { copyError.value = '复制失败，请选择正文手动复制。' }
}
async function openEvidence(event: MouseEvent) {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>('button.evidence-ref')
  const sources = sourcesElement.value
  if (!button || !sources || !citations.value.some(item => item.evidenceId === button.dataset.evidenceId)) return
  sources.open = true
  await nextTick()
  const details = Array.from(sources.querySelectorAll<HTMLDetailsElement>('.citation-excerpt')).find(item => item.dataset.evidenceId === button.dataset.evidenceId)
  if (details) {
    details.open = true
    details.scrollIntoView({ block: 'nearest' })
    details.querySelector('summary')?.focus({ preventScroll: true })
  }
}
async function loadCitations() {
  if (props.message.role !== 'assistant') return
  loading.value = true; error.value = ''
  try { citations.value = await messageApi.citations(props.message.id) } catch (e) { error.value = messageOf(e) } finally { loading.value = false }
}
onMounted(loadCitations)
onBeforeUnmount(() => clearTimeout(copyTimer))
</script>
<template>
  <div v-if="message.messageType === 'scope_change' || message.role === 'system'" class="message system">{{ message.content }} <time>{{ dateTime(message.createTime) }}</time></div>
  <article v-else class="message" :class="message.role">
    <div class="message-avatar">{{ message.role === 'user' ? '我' : 'AP' }}</div>
    <div class="message-body">
      <div class="message-name"><span>{{ message.role === 'user' ? '你' : 'AgentPaperMate' }}</span><time>{{ dateTime(message.createTime) }}</time></div>
      <div v-if="message.role === 'assistant'" class="bubble markdown" @click="openEvidence" v-html="html"></div>
      <div v-else class="bubble">{{ message.content }}</div>
      <div v-if="message.role === 'assistant'" class="citation-list">
        <small v-if="loading" class="muted">正在读取引用…</small>
        <div v-if="error" class="notice error"><span>引用读取失败：{{ error }}</span><button class="text-link" @click="loadCitations">重试</button></div>
        <details v-if="citations.length" ref="sourcesElement" class="citation-sources">
          <summary><Quote :size="13" />参考来源 · {{ sourceCount }} 篇论文 · {{ citations.length }} 条引用<span class="citation-toggle">展开 / 收起</span></summary>
          <div class="citation-items">
            <article v-for="citation in citations" :key="citation.id" class="citation-card">
              <details class="citation-excerpt" :data-evidence-id="citation.evidenceId">
                <summary><span class="evidence-label">{{ citation.evidenceId }}</span><span>{{ citation.paperTitle || `论文 ${citation.paperId}` }}</span></summary>
                <blockquote>{{ citation.quotedText }}</blockquote>
                <small v-if="citation.sourceLocation">{{ citation.sourceLocation }}</small>
                <small>证据 {{ citation.evidenceId }} · 论文 {{ citation.paperId }}</small>
              </details>
              <RouterLink :to="{ path: `/library/${citation.paperId}`, query: citation.pageStart > 0 ? { page: String(citation.pageStart) } : {} }">{{ citation.pageStart > 0 ? `PDF 物理第 ${citation.pageStart}${citation.pageEnd > citation.pageStart ? `–${citation.pageEnd}` : ''} 页` : '页码未知' }} · 查看原文 <ArrowUpRight :size="12" /></RouterLink>
            </article>
          </div>
        </details>
      </div>
      <div class="message-actions">
        <button class="text-link" :aria-label="message.role === 'assistant' ? '复制回答' : '复制问题'" @click="copy"><Check v-if="copied" :size="13" /><Copy v-else :size="13" />{{ copied ? '已复制' : '复制' }}</button>
        <button v-if="message.role === 'user' && message.runId" class="text-link retry-question" @click="emit('retry', message)"><RefreshCw :size="13" />重新执行此问题</button>
        <span v-if="copied" class="sr-only" role="status">内容已复制</span>
      </div>
      <p v-if="copyError" class="copy-error" role="alert">{{ copyError }}</p>
    </div>
  </article>
</template>

<style scoped>
.citation-sources { margin-top: 16px; }
summary { cursor: pointer; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; border-radius: 6px; }
.citation-sources > summary { padding: 4px 0; color: var(--muted); }
.citation-toggle { margin-left: 10px; color: var(--muted); font-size: 12px; white-space: nowrap; }
.citation-items { display: grid; gap: 0; padding: 8px 0 0; }
.citation-card { border: 0; border-bottom: 1px solid var(--line); border-radius: 0; padding: 12px 0; background: transparent; }
.citation-card blockquote { font-size: 13px; line-height: 1.75; padding: 8px 12px; margin: 12px 0; border-left: 2px solid #d5d6ed; color: #596579; }
.citation-card small, .citation-card > a { font-size: 12px; }
.citation-excerpt summary { color: var(--text); }
.evidence-label { color: var(--primary); font-weight: 600; margin-right: 6px; }
.citation-card > a { margin-top: 6px; }
.citation-card blockquote { overflow-wrap: anywhere; }
</style>
