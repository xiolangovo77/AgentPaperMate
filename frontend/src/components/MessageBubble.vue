<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Quote, ArrowUpRight, RefreshCw } from 'lucide-vue-next'
import { messageApi } from '../api'
import { messageOf } from '../api/request'
import type { Message, Citation } from '../types/api'
import { dateTime } from '../utils/format'
import { renderMarkdown } from '../utils/markdown'
const props = defineProps<{ message: Message }>()
const emit = defineEmits<{ retry: [message: Message] }>()
const citations = ref<Citation[]>([]), error = ref(''), loading = ref(false)
const sourceCount = computed(() => new Set(citations.value.map(citation => citation.paperId)).size)
const html = computed(() => props.message.role === 'assistant' ? renderMarkdown(props.message.content) : '')
async function loadCitations() {
  if (props.message.role !== 'assistant') return
  loading.value = true; error.value = ''
  try { citations.value = await messageApi.citations(props.message.id) } catch (e) { error.value = messageOf(e) } finally { loading.value = false }
}
onMounted(loadCitations)
</script>
<template>
  <div v-if="message.messageType === 'scope_change' || message.role === 'system'" class="message system">{{ message.content }} <time>{{ dateTime(message.createTime) }}</time></div>
  <article v-else class="message" :class="message.role">
    <div class="message-avatar">{{ message.role === 'user' ? '我' : 'AP' }}</div>
    <div class="message-body">
      <div class="message-name"><span>{{ message.role === 'user' ? '你' : 'AgentPaperMate' }}</span><time>{{ dateTime(message.createTime) }}</time></div>
      <div v-if="message.role === 'assistant'" class="bubble markdown" v-html="html"></div>
      <div v-else class="bubble">{{ message.content }}</div>
      <div v-if="message.role === 'assistant'" class="citation-list">
        <small v-if="loading" class="muted">正在读取引用…</small>
        <div v-if="error" class="notice error"><span>引用读取失败：{{ error }}</span><button class="text-link" @click="loadCitations">重试</button></div>
        <details v-if="citations.length" class="citation-sources">
          <summary><Quote :size="13" />参考来源 · {{ sourceCount }} 篇论文 · {{ citations.length }} 条引用<span class="citation-toggle">展开 / 收起</span></summary>
          <div class="citation-items">
            <article v-for="citation in citations" :key="citation.id" class="citation-card">
              <details class="citation-excerpt">
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
      <button v-if="message.role === 'user' && message.runId" class="text-link retry-question" @click="emit('retry', message)"><RefreshCw :size="11" /> 重新执行此问题</button>
    </div>
  </article>
</template>

<style scoped>
.citation-sources { border: 1px solid var(--line); border-radius: 10px; background: white; }
summary { cursor: pointer; font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 3px; border-radius: 6px; }
.citation-sources > summary { padding: 10px 12px; color: var(--primary); }
.citation-toggle { margin-left: 10px; color: var(--muted); font-size: 10px; white-space: nowrap; }
.citation-items { display: grid; gap: 8px; padding: 0 10px 10px; }
.citation-excerpt summary { color: var(--text); }
.evidence-label { color: var(--primary); font-weight: 600; margin-right: 6px; }
.citation-card > a { margin-top: 6px; }
.citation-card blockquote { overflow-wrap: anywhere; }
</style>
