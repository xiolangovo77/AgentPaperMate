import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { messageApi, runApi, sessionApi } from '../api'
import { apiBase, ApiError, messageOf } from '../api/request'
import type { Message, Run, Session, SessionPaper, PaperDetail } from '../types/api'
import { useChatStore } from '../stores/chat'
import { downloadMarkdown } from '../utils/format'
import { observeRun, terminal } from '../utils/runObserver'
import { paperDetails, readScope } from '../utils/papers'

export function useChat() {
  const recovery = useChatStore()
  const paperInfo = ref<Record<string, PaperDetail>>({}), checkingPapers = ref(false), readinessError = ref('')
  const allReady = computed(() => papers.value.length > 0 && !checkingPapers.value && !readinessError.value && papers.value.every(item => paperInfo.value[item.paperId]?.canAsk))
  const session = ref<Session | null>(null), papers = ref<SessionPaper[]>([]), messages = ref<Message[]>([])
  const loading = ref(false), error = ref(''), draft = ref(''), savedDraft = ref(''), draftError = ref(''), savingDraft = ref(false)
  const messagePage = ref(1), messagePages = ref(1), moreBusy = ref(false), messageError = ref('')
  const run = ref<Run | null>(null), stage = ref(''), connection = ref('stopped'), runError = ref(''), runId = ref('')
  const submitting = ref(false), cancelling = ref(false), exporting = ref(false)
  const active = computed(() => !!runId.value && (!run.value || !terminal(run.value.status)))
  const uncertain = computed(() => session.value ? recovery.pending[session.value.id] : undefined)
  const dirty = computed(() => !!session.value && draft.value !== savedDraft.value)
  let epoch = 0, draftTimer: ReturnType<typeof setTimeout> | undefined, draftFlight: Promise<boolean> | undefined
  let observer: ReturnType<typeof observeRun> | undefined, lastTerminal = ''
  let scopeEpoch = 0, refreshMessagesAfterFlight = false
  let readinessEpoch = 0, readinessTimer: ReturnType<typeof setTimeout> | undefined

  async function checkReadiness() {
    clearTimeout(readinessTimer)
    const token = epoch, sequence = ++readinessEpoch
    checkingPapers.value = true; readinessError.value = ''
    try {
      const details = await paperDetails(papers.value.map(item => item.paperId))
      if (token !== epoch || sequence !== readinessEpoch) return
      paperInfo.value = Object.fromEntries(details.map(item => [item.id, item]))
      if (details.some(item => ['uploaded', 'processing'].includes(item.status))) readinessTimer = setTimeout(() => void checkReadiness(), 5000)
    } catch (e) { if (token === epoch && sequence === readinessEpoch) readinessError.value = messageOf(e) }
    finally { if (token === epoch && sequence === readinessEpoch) checkingPapers.value = false }
  }
  async function loadMessages(older = false) {
    if (!session.value) return
    if (moreBusy.value) { if (!older) refreshMessagesAfterFlight = true; return }
    const token = epoch, id = session.value.id, page = older ? messagePage.value + 1 : 1
    moreBusy.value = true; messageError.value = ''
    try {
      const result = await messageApi.list(id, page)
      if (token !== epoch) return
      const rows = older ? [...messages.value, ...result.records] : result.records
      messages.value = Array.from(new Map(rows.map(item => [item.id, item])).values()).sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1)
      messagePage.value = page; messagePages.value = Number(result.pages)
    } catch (e) { if (token === epoch) messageError.value = messageOf(e) }
    finally {
      if (token === epoch) {
        moreBusy.value = false
        if (refreshMessagesAfterFlight) { refreshMessagesAfterFlight = false; void loadMessages() }
      }
    }
  }
  async function refreshScope() {
    if (!session.value) return
    const id = session.value.id, token = epoch, scopeToken = ++scopeEpoch
    error.value = ''
    try {
      const snapshot = await readScope(id)
      if (token !== epoch || scopeToken !== scopeEpoch) return
      session.value = snapshot.session; papers.value = snapshot.papers
      await checkReadiness()
      await loadMessages()
    } catch (e) { if (token === epoch && scopeToken === scopeEpoch) error.value = messageOf(e) }
  }
  function observe(id: string) {
    observer?.stop(); runId.value = id; const token = epoch
    observer = observeRun({
      runId: id, apiBase,
      getRun: () => runApi.get(id), getEvents: cursor => runApi.events(id, cursor),
      onRun: value => {
        if (token !== epoch) return
        run.value = value; runError.value = ''
        if (!stage.value || terminal(value.status)) stage.value = value.status
        if (terminal(value.status) && lastTerminal !== `${id}:${value.status}`) {
          lastTerminal = `${id}:${value.status}`
          void refreshScope()
        }
      },
      onEvent: event => { if (token === epoch) { stage.value = event.type; runError.value = '' } },
      onConnection: value => { if (token === epoch) connection.value = value },
      onError: e => { if (token === epoch) runError.value = messageOf(e) },
    })
  }
  async function open(id?: string) {
    observer?.stop(); observer = undefined; clearTimeout(draftTimer)
    const token = ++epoch
    refreshMessagesAfterFlight = false; scopeEpoch++
    clearTimeout(readinessTimer); readinessEpoch++; paperInfo.value = {}; readinessError.value = ''; checkingPapers.value = false
    session.value = null; messages.value = []; papers.value = []; error.value = ''; messageError.value = ''; draftError.value = ''
    draft.value = ''; savedDraft.value = ''; run.value = null; runId.value = ''; runError.value = ''; stage.value = ''; lastTerminal = ''; moreBusy.value = false
    if (!id) { loading.value = false; return }
    loading.value = true
    try {
      const { session: current, papers: scope } = await readScope(id)
      if (token !== epoch) return
      session.value = current; papers.value = scope; draft.value = current.inputDraft || ''; savedDraft.value = draft.value
      void checkReadiness()
      await loadMessages()
      if (token !== epoch) return
      if (current.activeRunId) {
        delete recovery.pending[id]; observe(current.activeRunId)
      } else {
        const lastRunId = [...messages.value].reverse().find(message => message.runId)?.runId
        if (lastRunId) {
          try {
            const previous = await runApi.get(lastRunId)
            if (token !== epoch) return
            run.value = previous; runId.value = previous.runId; stage.value = previous.status; lastTerminal = `${lastRunId}:${previous.status}`
            if (!terminal(previous.status)) observe(lastRunId)
          } catch (e) { if (token === epoch) runError.value = messageOf(e) }
        }
      }
    } catch (e) { if (token === epoch) error.value = messageOf(e) }
    finally { if (token === epoch) loading.value = false }
  }
  async function saveDraft(): Promise<boolean> {
    clearTimeout(draftTimer)
    if (draftFlight) { await draftFlight; return dirty.value && !draftError.value ? saveDraft() : !dirty.value }
    if (!session.value || !dirty.value) return true
    const id = session.value.id, content = draft.value, token = epoch
    savingDraft.value = true; draftError.value = ''
    draftFlight = (async () => {
      try { await sessionApi.update(id, { inputDraft: content }); if (token === epoch) savedDraft.value = content; return true }
      catch (e) { if (token === epoch) draftError.value = messageOf(e); return false }
      finally { savingDraft.value = false; draftFlight = undefined }
    })()
    const ok = await draftFlight
    if (ok && dirty.value && token === epoch) return saveDraft()
    return ok
  }
  watch(draft, () => { clearTimeout(draftTimer); if (dirty.value) draftTimer = setTimeout(() => void saveDraft(), 700) })

  async function send(retry?: Message) {
    if (!session.value || submitting.value || active.value) return
    const id = session.value.id, token = epoch, question = uncertain.value?.question || retry?.content || draft.value.trim()
    error.value = ''
    if (!uncertain.value && (!question || !papers.value.length)) return
    if (new TextEncoder().encode(question).length > 16000) { error.value = '问题不能超过 16000 个 UTF-8 字节，请缩短内容。'; return }
    submitting.value = true
    let createdId = ''
    try {
      if (!recovery.pending[id]) {
        const latest = await readScope(id)
        if (token !== epoch) return
        const changed = latest.session.scopeVersion !== session.value.scopeVersion
        session.value = latest.session; papers.value = latest.papers
        await checkReadiness()
        if (changed) throw new Error('资料范围已变化，已加载最新资料，请重新确认后提问。')
        if (!allReady.value) throw new Error(readinessError.value || '当前资料包含尚未就绪或不可问答的论文，请等待处理完成或调整资料范围。')
      }
      if (retry?.runId) {
        const previous = await runApi.get(retry.runId)
        if (!terminal(previous.status)) throw new Error('该问题仍在执行，请等待完成或先取消任务。')
      }
      // Preserve the exact body on ambiguous transport failures; retry uses the SAME key.
      if (!recovery.pending[id]) recovery.pending[id] = { sessionId: id, requestKey: await runApi.key(), question, ...(retry?.runId ? { retryOfRunId: retry.runId } : {}) }
      createdId = await runApi.create(recovery.pending[id]!)
      delete recovery.pending[id]
    } catch (e) {
      if (e instanceof ApiError && e.code !== 0 && e.code !== 50000 && e.httpStatus < 500) delete recovery.pending[id]
      if (token === epoch) error.value = messageOf(e)
    } finally { submitting.value = false }
    if (!createdId || token !== epoch) return
    run.value = null; stage.value = 'pending'; observe(createdId)
    if (!retry && draft.value.trim() === question) { draft.value = ''; await saveDraft() }
    await loadMessages()
  }
  async function cancel() {
    if (!runId.value || cancelling.value) return
    cancelling.value = true; runError.value = ''
    try { await runApi.cancel(runId.value); await observer?.refresh() }
    catch (e) { runError.value = messageOf(e) } finally { cancelling.value = false }
  }
  async function exportChat() {
    if (!session.value || exporting.value) return
    const current = session.value
    exporting.value = true; error.value = ''
    try {
      let page = 1, pages = 1
      const all = new Map<string, Message>()
      do {
        const result = await messageApi.list(current.id, page)
        for (const item of result.records) all.set(item.id, item)
        pages = Number(result.pages); page++
      } while (page <= pages)
      const sections: string[] = [`# ${current.name}\n\n会话 ID：${current.id}\n导出时间：${new Date().toLocaleString('zh-CN')}\n`]
      for (const message of [...all.values()].sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1)) {
        sections.push(`## ${message.role === 'user' ? '你' : message.role === 'assistant' ? 'AgentPaperMate' : '资料变更'} · ${message.createTime}\n\n${message.content}\n`)
        if (message.role === 'assistant') {
          const citations = await messageApi.citations(message.id)
          for (const item of citations) sections.push(`> ${item.paperTitle}（论文 ${item.paperId}，${item.pageStart > 0 ? `PDF 物理页 ${item.pageStart}–${item.pageEnd}` : '页码未知'}，证据 ${item.evidenceId}）\n> ${item.quotedText.replace(/\n/g, '\n> ')}\n${item.sourceLocation || ''}\n`)
        }
      }
      downloadMarkdown(current.name, sections.join('\n'))
    } catch (e) { error.value = `导出未完成：${messageOf(e)}。请重试，尚未生成不完整文件。` }
    finally { exporting.value = false }
  }
  async function canLeave() {
    if (submitting.value) { error.value = '问题正在提交，请等待提交结果后切换。'; return false }
    const ok = await saveDraft()
    return ok || window.confirm('草稿保存失败。离开将丢失未保存的内容，是否仍要离开？')
  }
  function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || submitting.value || uncertain.value) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { epoch++; observer?.stop(); clearTimeout(draftTimer); clearTimeout(readinessTimer); window.removeEventListener('beforeunload', beforeUnload) })
  return { session, papers, paperInfo, allReady, checkingPapers, readinessError, checkReadiness, messages, loading, error, draft, dirty, draftError, savingDraft, messagePage, messagePages, moreBusy, messageError, run, stage, connection, runError, active, uncertain, submitting, cancelling, exporting, open, refreshScope, loadMessages, saveDraft, send, cancel, exportChat, canLeave }
}
