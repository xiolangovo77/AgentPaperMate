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
  <article v-else class="message" :class="message.role"><div class="message-avatar">{{ message.role === 'user' ? '我' : 'AP' }}</div><div class="message-body"><div class="message-name"><span>{{ message.role === 'user' ? '你' : 'AgentPaperMate' }}</span><time>{{ dateTime(message.createTime) }}</time></div><div v-if="message.role === 'assistant'" class="bubble markdown" v-html="html"></div><div v-else class="bubble">{{ message.content }}</div><div v-if="message.role === 'assistant'" class="citation-list"><small v-if="loading" class="muted">正在读取引用…</small><div v-if="error" class="notice error"><span>引用读取失败：{{ error }}</span><button class="text-link" @click="loadCitations">重试</button></div><article v-for="citation in citations" :key="citation.id" class="citation-card"><h4><Quote :size="13" />{{ citation.paperTitle || `论文 ${citation.paperId}` }}</h4><blockquote>{{ citation.quotedText }}</blockquote><small v-if="citation.sourceLocation">{{ citation.sourceLocation }}</small><small>证据 {{ citation.evidenceId }} · 论文 {{ citation.paperId }}</small><RouterLink :to="{ path: `/library/${citation.paperId}`, query: citation.pageStart > 0 ? { page: String(citation.pageStart) } : {} }">{{ citation.pageStart > 0 ? `PDF 物理第 ${citation.pageStart}${citation.pageEnd > citation.pageStart ? `–${citation.pageEnd}` : ''} 页` : '页码未知' }} · 查看原文 <ArrowUpRight :size="12" /></RouterLink></article></div><button v-if="message.role === 'user' && message.runId" class="text-link retry-question" @click="emit('retry', message)"><RefreshCw :size="11" /> 重新执行此问题</button></div></article>
</template>
