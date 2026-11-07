<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileText, Plus, Trash2 } from 'lucide-vue-next'
import { sessionApi } from '../api'
import { messageOf } from '../api/request'
import type { SessionPaper } from '../types/api'
import { validId } from '../utils/format'
import Modal from './Modal.vue'
import Feedback from './Feedback.vue'
import CapabilityGap from './CapabilityGap.vue'
const props = defineProps<{ sessionId: string; papers: SessionPaper[]; scopeVersion: number }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const tab = ref('selected'), query = ref(''), page = ref(1), paperId = ref(''), busy = ref(false), error = ref(''), success = ref('')
const items = computed(() => props.papers.filter(item => item.paperId.includes(query.value.trim())))
const pages = computed(() => Math.max(1, Math.ceil(items.value.length / 10)))
const shown = computed(() => items.value.slice((page.value - 1) * 10, page.value * 10))
async function add() {
  if (!validId(paperId.value.trim()) || busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try { await sessionApi.addPaper(props.sessionId, paperId.value.trim()); paperId.value = ''; success.value = '已添加一篇论文。索引就绪状态将在提问时由服务端校验。'; emit('changed') } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
async function remove(item: SessionPaper) {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try { await sessionApi.removePaper(item.id); success.value = '已移出论文，历史回答和引用保留。'; page.value = 1; emit('changed') } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
</script>
<template>
  <Modal title="聊天室资料范围" :busy="busy" wide @close="emit('close')"><p class="muted">资料范围版本 {{ scopeVersion }} · 调整只影响后续提问，正在执行的任务保留原有资料快照。</p><div class="tabs"><button :class="{ active: tab === 'selected' }" @click="tab = 'selected'">已选论文（{{ papers.length }}）</button><button :class="{ active: tab === 'browse' }" @click="tab = 'browse'">浏览论文库</button></div><Feedback :error="error" /><p v-if="success" class="notice success" role="status">{{ success }}</p><CapabilityGap v-if="tab === 'browse'" title="暂不能浏览论文库" detail="服务尚未提供论文分页查询和批量范围更新能力，因此无法搜索、跨页选择或一次提交完整论文集合。" /><template v-else><input v-model="query" class="field scope-search" placeholder="按论文 ID 筛选已选资料" aria-label="筛选已选资料" @input="page = 1" /><div v-if="!shown.length" class="empty-state">{{ papers.length ? '没有匹配的论文。' : '当前还没有资料。添加后即可开始研究讨论。' }}</div><article v-for="item in shown" :key="item.id" class="scope-record"><FileText :size="20" /><div><b>论文 {{ item.paperId }}</b><small>标题与处理状态暂不可查询</small></div><RouterLink class="btn small" :to="`/library/${item.paperId}?tab=notes`">笔记</RouterLink><button class="btn small" :disabled="busy" @click="remove(item)"><Trash2 :size="12" />移出</button></article><div v-if="pages > 1" class="scope-pagination"><button class="btn small" :disabled="page === 1" @click="page--">上一页</button><span>{{ page }} / {{ pages }}</span><button class="btn small" :disabled="page >= pages" @click="page++">下一页</button></div><form class="scope-add" @submit.prevent="add"><label class="form-label" for="scope-paper-id">按已知论文 ID 添加一篇</label><div class="toolbar"><input id="scope-paper-id" v-model="paperId" class="field" inputmode="numeric" placeholder="输入上传回执中的论文 ID" :disabled="busy" /><button class="btn primary" :disabled="busy || !validId(paperId.trim())"><Plus :size="14" />添加</button></div><p class="scope-warning">目前仅支持逐篇即时添加或移出，操作会立即保存。论文可在处理中加入范围，但包含未就绪论文的问题会被服务端拒绝。空范围会保留历史记录。</p></form></template><div class="dialog-actions"><button class="btn primary" :disabled="busy" @click="emit('close')">完成</button></div></Modal>
</template>
