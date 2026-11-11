<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Plus, ChevronDown, MessagesSquare, Send, Pencil, Trash2, Download, FileText, SlidersHorizontal, BookOpen, Square, LoaderCircle, Paperclip, CircleHelp, MoreHorizontal } from 'lucide-vue-next'
import { runApi, sessionApi } from '../api'
import { messageOf } from '../api/request'
import type { Message, Session } from '../types/api'
import { dateTime, validId } from '../utils/format'
import { stageLabels, terminal } from '../utils/runObserver'
import { executorLabels } from '../utils/runProgress'
import { useChat } from '../composables/useChat'
import { paperTitle } from '../utils/papers'
import Feedback from '../components/Feedback.vue'
import Modal from '../components/Modal.vue'
import ScopeManager from '../components/ScopeManager.vue'
import MessageBubble from '../components/MessageBubble.vue'
import RunProgress from '../components/RunProgress.vue'
import UploadPaper from '../components/UploadPaper.vue'
const route = useRoute(), router = useRouter(), chat = useChat()
const { session, papers, messages, loading, error, draft, draftError, messagePage, messagePages, moreBusy, messageError, run, stage, connection, runError, active, uncertain, submitting, cancelling, exporting } = chat
const { paperInfo, allReady, checkingPapers, readinessError } = chat
const { executorType, runEvents, runId, runHistory } = chat
const sessions = ref<Session[]>([]), listPage = ref(1), listPages = ref(1), listBusy = ref(false), listError = ref(''), listQuery = ref('')
const showMaterials = ref(false), showUpload = ref(false), showModeHelp = ref(false)
const composerElement = ref<HTMLTextAreaElement>()
function resizeComposer() {
  const element = composerElement.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, Math.min(200, window.innerHeight * .25))}px`
}
watch([draft, composerElement], () => resizeComposer(), { flush: 'post' })
const switcher = ref(false), showScope = ref(false), modal = ref<'create' | 'rename' | 'delete' | null>(null), modalValue = ref(''), modalError = ref(''), modalBusy = ref(false)
const messagesElement = ref<HTMLElement>(), messageListElement = ref<HTMLElement>(), switcherElement = ref<HTMLElement>()
const menuElement = ref<HTMLDetailsElement>()
function closeMenu() { if (menuElement.value) menuElement.value.open = false }
function exportConversation() { closeMenu(); void chat.exportChat() }
const recycledPapers = computed(() => papers.value.map(item => paperInfo.value[item.paperId]).filter(item => item?.isRecycled === 1))
function refreshPaperState() { if (session.value) void chat.checkReadiness() }
const followingLatest = ref(true)
// Render each run once beside its own question, even when only its answer is on this page.
const timeline = computed(() => {
  const items: Array<{ kind: 'message'; key: string; message: Message } | { kind: 'run'; key: string; runId: string }> = []
  const shown = new Set<string>()
  function addRun(id?: string) {
    if (!id || shown.has(id)) return
    shown.add(id)
    items.push({ kind: 'run', key: `run-${id}`, runId: id })
  }
  for (const message of messages.value) {
    if (message.role === 'assistant') addRun(message.runId)
    items.push({ kind: 'message', key: message.id, message })
    if (message.role === 'user') addRun(message.runId)
  }
  addRun(runId.value)
  return items
})
let messageResizeObserver: ResizeObserver | undefined
function trackScroll() {
  const element = messagesElement.value
  if (element) followingLatest.value = element.scrollHeight - element.scrollTop - element.clientHeight < 64
}
function inspectContent(event: MouseEvent) {
  if ((event.target as HTMLElement).closest('summary, .evidence-ref')) {
    followingLatest.value = false
    requestAnimationFrame(trackScroll)
  }
}
function scrollToLatest() {
  followingLatest.value = true
  messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight, behavior: 'instant' })
}
async function send() { followingLatest.value = true; await chat.send() }
async function loadOlderMessages() {
  const element = messagesElement.value
  if (!element) return
  const height = element.scrollHeight, top = element.scrollTop
  followingLatest.value = false
  await chat.loadMessages(true)
  await nextTick()
  element.scrollTop = top + element.scrollHeight - height
  trackScroll()
}
watch(messageListElement, element => {
  messageResizeObserver?.disconnect()
  if (!element) return
  messageResizeObserver = new ResizeObserver(() => {
    if (followingLatest.value) messagesElement.value?.scrollTo({ top: messagesElement.value.scrollHeight, behavior: 'instant' })
  })
  messageResizeObserver.observe(element)
}, { flush: 'post' })
const prompts = computed(() => executorType.value === 'agent'
  ? [...(papers.value.length === 1 ? ['请总结这篇论文的研究背景、问题、方法、创新、实验与局限'] : []), ...(papers.value.length >= 2 && papers.value.length <= 5 ? ['请比较这些论文的方法、实验结果与局限'] : []), '请分析这些论文的研究方向，提出可验证的假设与实验方案', '论文的核心研究问题与创新点是什么？']
  : ['总结核心研究问题与创新点', '对比方法与实验结论', '有哪些局限和值得继续研究的问题？'])
const selectedId = computed(() => typeof route.params.id === 'string' && route.params.id ? route.params.id : undefined)
const modeHint = computed(() => executorType.value === 'agent'
  ? '按问题自动选择问答、总结、多论文比较或研究分析技能。总结支持单篇论文，比较支持 2～5 篇；研究方向建议需进一步验证。'
  : '检索所选论文的相关证据后生成回答。')
const canSend = computed(() => !!session.value && !!draft.value.trim() && allReady.value && !active.value && !submitting.value && !uncertain.value)
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
function begin(kind: 'create' | 'rename' | 'delete') { closeMenu(); modal.value = kind; modalError.value = ''; modalValue.value = kind === 'rename' ? session.value?.name || '' : '' }
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
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); if (canSend.value) void send() }
}
function retry(message: Message) {
  if (active.value || submitting.value || uncertain.value) { error.value = '请先等待或处理当前任务。'; return }
  if (!papers.value.length) { error.value = '请先添加论文。'; return }
  if (window.confirm(`将使用${executorLabels[executorType.value]}和聊天室当前资料范围重新执行这个问题，是否继续？`)) { followingLatest.value = true; void chat.send(message) }
}
function outside(event: MouseEvent) {
  if (switcher.value && !switcherElement.value?.contains(event.target as Node)) switcher.value = false
  if (!menuElement.value?.contains(event.target as Node)) closeMenu()
}
function escape(event: KeyboardEvent) { if (event.key === 'Escape') { switcher.value = false; closeMenu() } }
async function openRoute() {
  showScope.value = false; showMaterials.value = false; showModeHelp.value = false
  closeMenu()
  followingLatest.value = true
  if (selectedId.value && !validId(selectedId.value)) { await chat.open(); error.value = '聊天室 ID 无效。'; return }
  await chat.open(selectedId.value)
  await nextTick(); scrollToLatest()
}
watch(selectedId, () => void openRoute(), { immediate: true })
watch(listQuery, () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => void loadSessions(), 300) })
watch(() => messages.value.at(-1)?.id, async () => { await nextTick(); if (followingLatest.value) scrollToLatest() })
watch(modal, async value => { if (value) { await nextTick(); document.getElementById('session-name')?.focus() } })
onMounted(() => { window.addEventListener('resize', resizeComposer); window.addEventListener('focus', refreshPaperState); void loadSessions(); document.addEventListener('click', outside); document.addEventListener('keydown', escape) })
onBeforeUnmount(() => { window.removeEventListener('resize', resizeComposer); window.removeEventListener('focus', refreshPaperState); messageResizeObserver?.disconnect(); listEpoch++; clearTimeout(searchTimer); document.removeEventListener('click', outside); document.removeEventListener('keydown', escape) })
onBeforeRouteLeave(to => to.meta.public ? true : chat.canLeave())
onBeforeRouteUpdate(async (to, from) => { if (to.params.id !== from.params.id) return chat.canLeave() })
</script>
<template>
  <div class="chat-page" :class="{ 'has-session': session }">
    <div v-if="recycledPapers.length" class="notice" role="status">
      <div>该会话关联的部分论文已移入回收站，当前会话仍可使用这些论文。建议新建会话并重新选择论文，或前往回收站恢复。
        <details><summary>查看已回收论文（{{ recycledPapers.length }}）</summary><ul><li v-for="paper in recycledPapers" :key="paper!.id">{{ paperTitle(paper!) }}</li></ul></details>
        <button class="text-link" @click="begin('create')">新建会话</button> · <RouterLink class="text-link" to="/library?view=recycle">前往回收站</RouterLink>
      </div>
    </div>
    <header class="conversation-header">
      <div class="conversation-title"><h1 :title="session?.name">{{ session?.name || '研究会话' }}</h1>
        <button v-if="session" class="text-link material-trigger" @click="showMaterials = true"><BookOpen :size="14" />{{ papers.length }} 篇论文<ChevronDown :size="12" /></button>
      </div>
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
        <button v-if="session" class="btn small scope-trigger" @click="showScope = true"><SlidersHorizontal :size="15" />调整资料</button>
        <button class="btn new-session" title="新建聊天室" aria-label="新建聊天室" :disabled="submitting" @click="begin('create')"><Plus :size="17" /><span>新建会话</span></button>
        <details v-if="session" ref="menuElement" class="conversation-menu">
          <summary class="icon-btn" aria-label="会话操作" title="会话操作"><MoreHorizontal :size="19" /></summary>
          <div class="conversation-menu-items">
            <button @click="begin('rename')"><Pencil :size="15" />重命名会话</button>
            <button :disabled="exporting" @click="exportConversation"><Download :size="15" />{{ exporting ? '正在导出…' : '导出完整聊天记录' }}</button>
            <button :disabled="submitting" @click="begin('delete')"><Trash2 :size="15" />删除会话</button>
          </div>
        </details>
      </div>
    </header>
    <Feedback :loading="loading" :error="error"><button v-if="!submitting" class="text-link" @click="session ? chat.refreshScope() : openRoute()">刷新</button></Feedback>
    <template v-if="!loading && !session && !selectedId">
      <section class="panel no-session chat-empty"><MessagesSquare :size="40" /><h3>为一个研究问题，留出一间聊天室</h3><p>添加已有论文，围绕同一个主题持续讨论。每个会话独立保存资料范围、消息与草稿。</p><button class="btn primary section-space" @click="begin('create')"><Plus :size="15" />创建聊天室</button><p v-if="route.query.question" class="section-space">待讨论：{{ route.query.question }}</p></section>
      <div class="section-row"><h2>历史研究会话</h2></div><Feedback :loading="listBusy" :error="listError"><button class="text-link" @click="loadSessions()">重试</button></Feedback>
      <div class="paper-grid"><button v-for="item in sessions" :key="item.id" class="paper-card session-card" @click="selectSession(item.id)"><span class="session-icon"><MessagesSquare :size="20" /></span><div><h3 class="paper-title">{{ item.name }}</h3><p class="paper-meta">{{ dateTime(item.lastMessageTime || item.createTime) }}</p></div></button></div><button v-if="listPage < listPages" class="btn section-space" :disabled="listBusy" @click="loadSessions(true)">加载更多</button>
    </template>
    <template v-if="session && !loading">
      <p v-if="papers.length && !allReady" class="notice" role="status">{{ checkingPapers ? '正在检查论文索引…' : readinessError || '资料中有论文尚未就绪或索引不兼容，暂不能提问。' }}<button class="text-link" @click="chat.checkReadiness()">重新检查</button></p><div class="chat-layout">
        <section class="panel chat-panel">
          <div class="chat-message-viewport">
            <div ref="messagesElement" class="chat-messages" aria-label="聊天消息" tabindex="0" @scroll="trackScroll" @click="inspectContent">
              <div ref="messageListElement" class="chat-message-list">
                <button v-if="messagePage < messagePages" class="text-link history-load" :disabled="moreBusy" @click="loadOlderMessages">{{ moreBusy ? '正在加载…' : '加载更早的消息' }}</button>
                <Feedback :error="messageError"><button class="text-link" @click="chat.loadMessages()">重试</button></Feedback>
                <div v-if="runError && !runId" class="notice error">任务状态查询失败：{{ runError }}</div>
                <div v-if="!messages.length && !messageError && !runId" class="chat-empty"><BookOpen :size="30" /><h3>{{ papers.length ? '从一个好问题开始' : '先为讨论选择论文' }}</h3><p>{{ papers.length ? '深入方法、比较结论，让每个想法都有证据。' : '添加论文后，即可围绕原文展开研究讨论。' }}</p>
                  <button v-if="!papers.length" class="btn section-space" @click="showScope = true"><Plus :size="15" />添加论文</button>
                  <div v-if="papers.length" class="quick-prompts"><button v-for="prompt in prompts" :key="prompt" class="quick-prompt" :disabled="submitting || !!uncertain" @click="draft = prompt; composerElement?.focus()">{{ prompt }}</button></div>
                </div>
                <template v-for="item in timeline" :key="item.key">
                  <MessageBubble v-if="item.kind === 'message'" :message="item.message" @retry="retry" />
                  <section v-else class="chat-run" :data-run-id="item.runId" aria-label="本次回答的执行过程">
                    <template v-if="item.runId === runId">
                    <RunProgress :events="runEvents" :status="run?.status" :stage="stageLabels[stage]" :active="active" :reconnecting="connection === 'reconnecting'" :error-message="run?.errorMessage" />
                    <div v-if="runError" class="notice error">任务状态查询失败：{{ runError }}<span v-if="active">，正在尝试恢复。</span></div>
                    </template>
                    <template v-else>
                      <p v-if="runHistory[item.runId]?.loading" class="run-loading" role="status"><LoaderCircle :size="14" class="spin" />正在读取执行记录…</p>
                      <div v-if="runHistory[item.runId]?.error" class="notice error">执行记录读取失败：{{ runHistory[item.runId]?.error }}<button class="text-link" @click="chat.loadRunHistory(item.runId)">重试</button></div>
                      <RunProgress :events="runHistory[item.runId]?.events || []" :status="runHistory[item.runId]?.run?.status" :error-message="runHistory[item.runId]?.run?.errorMessage" />
                    </template>
                  </section>
                </template>
              </div>
            </div>
            <button v-if="!followingLatest" class="btn small jump-latest" @click="scrollToLatest"><ChevronDown :size="14" />回到最新消息</button>
          </div>
          <div class="chat-composer">
            <div v-if="uncertain" class="notice"><div>上次提交结果尚未确认。恢复将复用原请求，不会重复创建任务。<button class="text-link" :disabled="submitting" @click="chat.send()">{{ submitting ? '正在确认…' : '确认提交结果' }}</button></div></div>
            <div class="composer-box">
              <textarea ref="composerElement" v-model="draft" rows="2" aria-label="研究问题" :placeholder="papers.length ? '围绕这些论文，提出你的问题…' : '先添加论文，再开始讨论…'" :disabled="submitting || !!uncertain" @keydown="keydown"></textarea>
              <div class="composer-toolbar">
                <div class="composer-tools">
                  <button class="icon-btn" title="上传 PDF 论文" aria-label="上传 PDF 论文" @click="showUpload = true"><Paperclip :size="18" /></button>
                  <label for="executor-type" class="sr-only">回答模式</label>
                  <select id="executor-type" v-model="executorType" class="select" :disabled="active || submitting || !!uncertain" :title="modeHint">
                    <option value="rag">RAG 检索问答</option><option value="agent">Agent 自主问答</option>
                  </select>
                  <button class="icon-btn mode-help-trigger" title="回答模式说明" aria-label="回答模式说明" @click="showModeHelp = true"><CircleHelp :size="16" /></button>
                </div>
                <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
                <button v-if="active" class="btn small stop-generation" :disabled="cancelling" @click="chat.cancel()"><Square :size="12" />{{ cancelling ? '正在取消…' : '停止任务' }}</button>
                <button v-else class="btn primary send-question" aria-label="发送问题" :disabled="!canSend" @click="send"><LoaderCircle v-if="submitting" :size="18" class="spin" /><Send v-else :size="18" /></button>
              </div>
            </div>
            <p v-if="draftError" class="draft-error" role="alert">草稿保存失败：{{ draftError }} <button class="text-link" @click="chat.saveDraft()">重新保存</button></p>
          </div>
        </section>

      </div>
    </template>
    <Modal v-if="showMaterials && session" title="当前资料" drawer @close="showMaterials = false">
      <p class="muted">{{ papers.length }} 篇论文 · 资料范围版本 {{ session.scopeVersion }}</p>
      <div class="material-list"><RouterLink v-for="paper in papers" :key="paper.id" :to="`/library/${paper.paperId}`" class="material-paper"><FileText :size="18" /><span>{{ paperInfo[paper.paperId] ? paperTitle(paperInfo[paper.paperId]!) : `论文 ${paper.paperId}` }}</span></RouterLink></div>
      <p v-if="!papers.length" class="muted">尚未添加论文。</p>
      <button class="btn primary" @click="showMaterials = false; showScope = true"><SlidersHorizontal :size="15" />调整资料</button>
      <div class="material-guide"><h3>围绕证据展开讨论</h3><p>每次提问使用提交时的资料范围，调整范围不会改写历史回答。点击回答中的引用查看原文摘录、章节与物理页码，再跳转 PDF 核查。</p><RouterLink class="text-link" to="/library">前往论文库 →</RouterLink></div>
    </Modal>
    <Modal v-if="showModeHelp" title="回答模式说明" @close="showModeHelp = false"><p>{{ modeHint }}</p><p v-if="executorType === 'agent' && papers.length > 1">需要结构化总结时，请先将资料范围调整为一篇论文。</p></Modal>
    <UploadPaper v-if="showUpload" for-chat @close="showUpload = false" />
    <ScopeManager v-if="showScope && session" :session-id="session.id"  @close="showScope = false" @changed="chat.refreshScope()" />
    <Modal v-if="modal" :title="modal === 'create' ? '新建聊天室' : modal === 'rename' ? '重命名聊天室' : '删除聊天室'" :busy="modalBusy" @close="modal = null"><form @submit.prevent="confirm"><Feedback :error="modalError" /><template v-if="modal !== 'delete'"><label class="form-label" for="session-name">会话名称</label><input id="session-name" v-model="modalValue" class="field rename-input" maxlength="200" :required="modal === 'rename'" placeholder="例如：Agent 记忆机制研究" :disabled="modalBusy" /><p class="scope-warning">{{ modal === 'create' ? '创建后添加论文，开始独立的研究讨论。' : '修改名称不会影响消息或论文范围。' }}</p></template><p v-else>将删除「{{ session?.name }}」及其聊天记录。若存在活动任务，会先取消并确认任务结束。论文和阅读笔记保留，此操作不可撤销。</p><div class="dialog-actions"><button type="button" class="btn" :disabled="modalBusy" @click="modal = null">取消</button><button class="btn" :class="modal === 'delete' ? 'danger' : 'primary'" :disabled="modalBusy">{{ modalBusy ? '正在处理…' : modal === 'delete' ? '确认删除' : modal === 'create' ? '创建聊天室' : '保存名称' }}</button></div></form></Modal>
  </div>
</template>
