<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Plus, ChevronDown, MessagesSquare, Send, Pencil, Trash2, Download, FileText, SlidersHorizontal, Quote, ShieldCheck, BookOpen, Square, LoaderCircle } from 'lucide-vue-next'
import { runApi, sessionApi } from '../api'
import { messageOf } from '../api/request'
import type { Message, Session } from '../types/api'
import { dateTime, validId } from '../utils/format'
import { stageLabels, terminal } from '../utils/runObserver'
import { useChat } from '../composables/useChat'
import Feedback from '../components/Feedback.vue'
import Modal from '../components/Modal.vue'
import ScopeManager from '../components/ScopeManager.vue'
import MessageBubble from '../components/MessageBubble.vue'
const route = useRoute(), router = useRouter(), chat = useChat()
const { session, papers, messages, loading, error, draft, dirty, draftError, savingDraft, messagePage, messagePages, moreBusy, messageError, run, stage, connection, runError, active, uncertain, submitting, cancelling, exporting } = chat
const sessions = ref<Session[]>([]), listPage = ref(1), listPages = ref(1), listBusy = ref(false), listError = ref(''), listQuery = ref('')
const switcher = ref(false), showScope = ref(false), modal = ref<'create' | 'rename' | 'delete' | null>(null), modalValue = ref(''), modalError = ref(''), modalBusy = ref(false)
const messagesElement = ref<HTMLElement>(), switcherElement = ref<HTMLElement>()
const prompts = ['总结核心研究问题与创新点', '对比方法与实验结论', '有哪些局限和值得继续研究的问题？']
const selectedId = computed(() => typeof route.params.id === 'string' && route.params.id ? route.params.id : undefined)
const canSend = computed(() => !!session.value && !!draft.value.trim() && !!papers.value.length && !active.value && !submitting.value && !uncertain.value)
let listEpoch = 0, searchTimer: ReturnType<typeof setTimeout> | undefined
async function loadSessions(more = false) {
  const token = ++listEpoch, page = more ? listPage.value + 1 : 1
  listBusy.value = true; listError.value = ''
  try {
    const result = await sessionApi.list(page, listQuery.value)
    if (token !== listEpoch) return
    sessions.value = more ? [...sessions.value, ...result.records] : result.records; listPage.value = page; listPages.value = Number(result.pages)
  } catch (e) { if (token === listEpoch) listError.value = messageOf(e) }
  finally { if (token === listEpoch) listBusy.value = false }
}
function begin(kind: 'create' | 'rename' | 'delete') { modal.value = kind; modalError.value = ''; modalValue.value = kind === 'rename' ? session.value?.name || '' : '' }
async function confirm() {
  if (modalBusy.value) return
  modalBusy.value = true; modalError.value = ''
  try {
    if (modal.value === 'create') {
      if (!await chat.canLeave()) return
      const id = await sessionApi.add(modalValue.value.trim() || '新研究会话', typeof route.query.question === 'string' ? route.query.question : '')
      modal.value = null; await router.push(`/chat/${id}`)
    } else if (session.value && modal.value === 'rename') {
      if (!modalValue.value.trim()) throw new Error('聊天室名称不能为空。')
      await sessionApi.update(session.value.id, { name: modalValue.value.trim() }); session.value.name = modalValue.value.trim(); modal.value = null
    } else if (session.value && modal.value === 'delete') {
      const id = session.value.id, current = await sessionApi.get(id)
      if (current.activeRunId) {
        await runApi.cancel(current.activeRunId)
        const latest = await runApi.get(current.activeRunId)
        if (!terminal(latest.status)) throw new Error('任务仍在结束中，请稍后再次删除。')
      }
      if (!await chat.saveDraft()) throw new Error('草稿保存失败，请先重试保存后再删除。')
      await sessionApi.delete(id); modal.value = null; await router.replace('/chat')
    }
    await loadSessions()
  } catch (e) { modalError.value = messageOf(e) } finally { modalBusy.value = false }
}
async function selectSession(id: string) { switcher.value = false; await router.push(`/chat/${id}`) }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (canSend.value) void chat.send() }
}
function retry(message: Message) {
  if (active.value || submitting.value || uncertain.value) { error.value = '请先等待或处理当前任务。'; return }
  if (!papers.value.length) { error.value = '请先添加论文。'; return }
  if (window.confirm('将使用聊天室当前资料范围重新执行这个问题，是否继续？')) void chat.send(message)
}
function outside(event: MouseEvent) { if (switcher.value && !switcherElement.value?.contains(event.target as Node)) switcher.value = false }
function escape(event: KeyboardEvent) { if (event.key === 'Escape') switcher.value = false }
async function openRoute() {
  showScope.value = false
  if (selectedId.value && !validId(selectedId.value)) { await chat.open(); error.value = '聊天室 ID 无效。'; return }
  await chat.open(selectedId.value)
  await nextTick(); messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight })
}
watch(selectedId, () => void openRoute(), { immediate: true })
watch(listQuery, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => void loadSessions(), 300) })
watch(() => messages.value.at(-1)?.id, async () => { await nextTick(); messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight }) })
watch(modal, async value => { if (value) { await nextTick(); document.getElementById('session-name')?.focus() } })
onMounted(() => { void loadSessions(); document.addEventListener('click', outside); document.addEventListener('keydown', escape) })
onBeforeUnmount(() => { listEpoch++; clearTimeout(searchTimer); document.removeEventListener('click', outside); document.removeEventListener('keydown', escape) })
onBeforeRouteLeave(to => to.meta.public ? true : chat.canLeave())
onBeforeRouteUpdate(async (to, from) => { if (to.params.id !== from.params.id) return chat.canLeave() })
</script>
<template>
  <div class="chat-page">
    <div class="page-heading">
      <div><div class="eyebrow">RESEARCH CONVERSATIONS</div><h1>聊天室</h1><p>让问题有依据，让讨论有积累。</p></div>
      <div class="toolbar">
        <div ref="switcherElement" class="chat-session-menu">
          <button class="btn" :aria-expanded="switcher" @click="switcher = !switcher"><MessagesSquare :size="15" />切换会话<ChevronDown :size="14" /></button>
          <div v-if="switcher" class="session-switcher">
            <input v-model="listQuery" class="field" placeholder="搜索历史会话" aria-label="搜索历史会话" />
            <Feedback :error="listError"><button class="text-link" @click="loadSessions()">重试</button></Feedback>
            <div class="session-options"><button v-for="item in sessions" :key="item.id" class="session-option" :class="{ active: session?.id === item.id }" @click="selectSession(item.id)"><MessagesSquare :size="16" /><div><b>{{ item.name }}</b><small>{{ dateTime(item.lastMessageTime || item.createTime) }}</small></div></button><div v-if="!sessions.length && !listBusy && !listError" class="empty-state">没有匹配的会话。</div></div>
            <Feedback :loading="listBusy" /><button v-if="listPage < listPages" class="text-link" :disabled="listBusy" @click="loadSessions(true)">加载更多会话</button>
          </div>
        </div>
        <button class="btn primary" :disabled="submitting" @click="begin('create')"><Plus :size="15" />新建聊天室</button>
      </div>
    </div>
    <Feedback :loading="loading" :error="error"><button v-if="!submitting" class="text-link" @click="session ? chat.refreshScope() : openRoute()">刷新</button></Feedback>
    <template v-if="!loading && !session && !selectedId">
      <section class="panel no-session chat-empty"><MessagesSquare :size="40" /><h3>为一个研究问题，留出一间聊天室</h3><p>添加已有论文，围绕同一个主题持续讨论。每个会话独立保存资料范围、消息与草稿。</p><button class="btn primary section-space" @click="begin('create')"><Plus :size="15" />创建聊天室</button><p v-if="route.query.question" class="section-space">待讨论：{{ route.query.question }}</p></section>
      <div class="section-row"><h2>历史研究会话</h2></div><Feedback :loading="listBusy" :error="listError"><button class="text-link" @click="loadSessions()">重试</button></Feedback>
      <div class="paper-grid"><button v-for="item in sessions" :key="item.id" class="paper-card session-card" @click="selectSession(item.id)"><span class="session-icon"><MessagesSquare :size="20" /></span><div><h3 class="paper-title">{{ item.name }}</h3><p class="paper-meta">{{ dateTime(item.lastMessageTime || item.createTime) }}</p></div></button></div><button v-if="listPage < listPages" class="btn section-space" :disabled="listBusy" @click="loadSessions(true)">加载更多</button>
    </template>
    <template v-if="session && !loading">
      <section class="panel scope-panel"><div class="scope-summary"><div><h2><BookOpen :size="17" />本次讨论的论文 <span class="pill">{{ papers.length }} 篇</span></h2><p class="panel-subtitle">每次提问使用提交时的资料范围，历史回答与引用保留。</p></div><button class="btn small" @click="showScope = true"><SlidersHorizontal :size="14" />调整资料</button></div><div v-if="papers.length" class="selected-preview"><RouterLink v-for="paper in papers.slice(0, 3)" :key="paper.id" class="selected-paper" :to="`/library/${paper.paperId}`"><FileText :size="12" />论文 {{ paper.paperId }}</RouterLink><button v-if="papers.length > 3" class="text-link" @click="showScope = true">查看全部 {{ papers.length }} 篇</button></div><p v-else class="scope-hint">尚未添加论文。添加资料后才能发送问题。</p></section>
      <div class="chat-layout">
        <section class="panel chat-panel">
          <header class="chat-header"><div><h2>{{ session.name }}</h2><p>资料版本 {{ session.scopeVersion }} · {{ active ? '问答任务进行中' : '独立研究会话' }}</p></div><div class="toolbar"><button class="icon-btn" title="重命名会话" aria-label="重命名会话" @click="begin('rename')"><Pencil :size="15" /></button><button class="icon-btn" title="导出完整聊天记录" aria-label="导出完整聊天记录" :disabled="exporting" @click="chat.exportChat()"><LoaderCircle v-if="exporting" :size="15" class="spin" /><Download v-else :size="15" /></button><button class="icon-btn" title="删除会话" aria-label="删除会话" :disabled="submitting" @click="begin('delete')"><Trash2 :size="15" /></button></div></header>
          <div ref="messagesElement" class="chat-messages" aria-label="聊天消息">
            <button v-if="messagePage < messagePages" class="text-link history-load" :disabled="moreBusy" @click="chat.loadMessages(true)">{{ moreBusy ? '正在加载…' : '加载更早的消息' }}</button><Feedback :error="messageError"><button class="text-link" @click="chat.loadMessages()">重试</button></Feedback>
            <div v-if="!messages.length && !messageError" class="chat-empty"><MessagesSquare :size="32" /><h3>一起读懂这些论文</h3><p>从核心问题、方法或实验结论开始。回答完成后，你可以通过引用回看原文证据。</p></div>
            <MessageBubble v-for="message in messages" :key="message.id" :message="message" @retry="retry" />
          </div>
          <div v-if="active || run" class="run-state" :class="run?.status" role="status"><LoaderCircle v-if="active" :size="15" class="spin" /><span>{{ stageLabels[stage] || (run ? stageLabels[run.status] : '正在查询任务') }}<small v-if="active && connection === 'reconnecting'"> · 正在恢复连接</small></span><button v-if="active" class="btn small" :disabled="cancelling" @click="chat.cancel()"><Square :size="11" />{{ cancelling ? '正在取消…' : '停止任务' }}</button><span v-if="run?.errorMessage" class="run-detail">{{ run.errorMessage }}</span></div>
          <div v-if="runError" class="notice error">任务状态查询失败：{{ runError }}<span v-if="active">，正在尝试恢复。</span></div>
          <div class="chat-composer">
            <div v-if="uncertain" class="notice"><div>上次提交结果尚未确认。恢复将复用原请求，不会重复创建任务。<button class="text-link" :disabled="submitting" @click="chat.send()">{{ submitting ? '正在确认…' : '确认提交结果' }}</button></div></div>
            <div class="quick-prompts"><button v-for="prompt in prompts" :key="prompt" class="quick-prompt" :disabled="submitting || !!uncertain" @click="draft = prompt">{{ prompt }}</button></div>
            <div class="composer-box"><textarea v-model="draft" rows="2" aria-label="研究问题" :placeholder="papers.length ? '围绕这些论文，提出你的问题…' : '先添加论文，再开始讨论…'" :disabled="submitting || !!uncertain" @keydown="keydown"></textarea><button class="btn primary" aria-label="发送问题" :disabled="!canSend" @click="chat.send()"><Send :size="16" /></button></div>
            <div class="composer-actions"><span class="composer-hint">Enter 发送 · Shift + Enter 换行<br />论文是否就绪由服务端在提交时校验。</span><span class="composer-hint" role="status">{{ savingDraft ? '正在保存草稿…' : dirty ? '草稿待保存' : '草稿已同步' }}</span></div><p v-if="draftError" class="draft-error">草稿保存失败：{{ draftError }} <button class="text-link" @click="chat.saveDraft()">重新保存</button></p>
          </div>
        </section>
        <aside class="chat-aside"><section class="panel"><h3 class="panel-title">围绕证据展开讨论</h3><p class="panel-subtitle">让回答成为阅读的起点。</p><ul class="guide-list"><li><BookOpen :size="16" /><span>明确选择资料，聚焦当前研究问题。</span></li><li><Quote :size="16" /><span>引用保留原文摘录与物理页码，便于核查。</span></li><li><ShieldCheck :size="16" /><span>资料范围变化不改写历史回答。</span></li></ul></section><section class="panel"><h3 class="panel-title">当前资料</h3><div class="side-stat"><span>关联论文</span><b>{{ papers.length }} 篇</b></div><div class="side-stat"><span>范围版本</span><b>v{{ session.scopeVersion }}</b></div><p class="scope-warning">论文详情与原文读取暂未接通。引用中的标题、页码和摘录来自服务端保存的证据快照。</p><RouterLink class="text-link" to="/library">前往论文库 →</RouterLink></section></aside>
      </div>
    </template>
    <ScopeManager v-if="showScope && session" :session-id="session.id" :papers="papers" :scope-version="session.scopeVersion" @close="showScope = false" @changed="chat.refreshScope()" />
    <Modal v-if="modal" :title="modal === 'create' ? '新建聊天室' : modal === 'rename' ? '重命名聊天室' : '删除聊天室'" :busy="modalBusy" @close="modal = null"><form @submit.prevent="confirm"><Feedback :error="modalError" /><template v-if="modal !== 'delete'"><label class="form-label" for="session-name">会话名称</label><input id="session-name" v-model="modalValue" class="field rename-input" maxlength="200" :required="modal === 'rename'" placeholder="例如：Agent 记忆机制研究" :disabled="modalBusy" /><p class="scope-warning">{{ modal === 'create' ? '创建后添加论文，开始独立的研究讨论。' : '修改名称不会影响消息或论文范围。' }}</p></template><p v-else>将删除「{{ session?.name }}」及其聊天记录。若存在活动任务，会先取消并确认任务结束。论文和阅读笔记保留，此操作不可撤销。</p><div class="dialog-actions"><button type="button" class="btn" :disabled="modalBusy" @click="modal = null">取消</button><button class="btn" :class="modal === 'delete' ? 'danger' : 'primary'" :disabled="modalBusy">{{ modalBusy ? '正在处理…' : modal === 'delete' ? '确认删除' : modal === 'create' ? '创建聊天室' : '保存名称' }}</button></div></form></Modal>
  </div>
</template>
