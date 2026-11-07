import type { Run, RunEvent } from '../types/api'
export const terminal = (status: string) => ['completed', 'failed', 'cancelled', 'timeout'].includes(status)
export const eventTypes = ['run_started', 'retrieval_started', 'retrieval_completed', 'run_completed', 'run_failed', 'run_cancelled', 'run_timeout']
export const stageLabels: Record<string, string> = {
  pending: '任务已提交，等待执行', running: '任务正在执行', run_started: '正在准备资料',
  retrieval_started: '正在检索论文证据', retrieval_completed: '检索完成，正在生成并校验答案',
  completed: '回答已完成', failed: '任务失败', cancelled: '任务已取消', timeout: '任务超时',
}
interface Stream {
  addEventListener(type: string, callback: (event: MessageEvent<string>) => void): void
  close(): void
  onerror: ((event: Event) => void) | null
  onopen: ((event: Event) => void) | null
}
interface Options {
  runId: string
  apiBase: string
  getRun: () => Promise<Run>
  getEvents: (after: string) => Promise<RunEvent[]>
  onRun: (run: Run) => void
  onEvent: (event: RunEvent) => void
  onConnection: (state: 'connected' | 'reconnecting' | 'stopped') => void
  onError: (error: unknown) => void
  makeStream?: (url: string) => Stream
  interval?: number
}
/** A subscription never creates or cancels a Run. Cursor values remain decimal strings. */
export function observeRun(options: Options) {
  let stopped = false, cursor = '0', stream: Stream | undefined, timer: ReturnType<typeof setTimeout> | undefined
  let recovering = false, generation = 0
  const stop = () => { stopped = true; generation++; clearTimeout(timer); stream?.close(); stream = undefined; options.onConnection('stopped') }
  function accept(event: RunEvent) {
    if (stopped || event.runId !== options.runId || !/^\d+$/.test(String(event.eventId))) return
    if (BigInt(event.eventId) <= BigInt(cursor)) return
    // A gap must be recovered from the committed event log or a Run snapshot.
    if (BigInt(event.eventId) !== BigInt(cursor) + 1n) { void recover(); return }
    cursor = String(event.eventId)
    options.onEvent(event)
    if (event.type.startsWith('run_') && terminal(event.type.slice(4))) void recover()
  }
  function snapshot(run: Run) {
    if (stopped || run.runId !== options.runId) return
    options.onRun(run)
    if (terminal(run.status)) stop()
  }
  function schedule(delay = options.interval ?? 3500) {
    clearTimeout(timer)
    if (!stopped) timer = setTimeout(() => void recover(), delay)
  }
  async function recover() {
    if (stopped || recovering) return
    recovering = true
    const token = ++generation
    stream?.close(); stream = undefined
    options.onConnection('reconnecting')
    try {
      // Replay before querying the snapshot, so terminal status and missing events are both handled.
      const events = await options.getEvents(cursor)
      if (stopped || token !== generation) return
      for (const event of events) accept(event)
      const run = await options.getRun()
      if (stopped || token !== generation) return
      snapshot(run)
      if (stopped) return
      // The server's run_snapshot semantics allow advancing beyond expired events.
      if (BigInt(String(run.eventSeq)) > BigInt(cursor) && events.length < 100) cursor = String(run.eventSeq)
      connect()
    } catch (error) {
      if (!stopped && token === generation) options.onError(error)
    } finally { recovering = false; schedule() }
  }
  function connect() {
    if (stopped) return
    stream?.close()
    const token = generation
    const make = options.makeStream || ((url: string) => new EventSource(url, { withCredentials: true }) as Stream)
    stream = make(`${options.apiBase}/agentRun/events?id=${encodeURIComponent(options.runId)}&afterEventSeq=${cursor}`)
    stream.onopen = () => { if (!stopped && token === generation) { options.onConnection('connected'); schedule(options.interval ?? 15000) } }
    for (const type of eventTypes) stream.addEventListener(type, event => {
      if (stopped || token !== generation) return
      try { accept(JSON.parse(event.data) as RunEvent) } catch (error) { options.onError(error); void recover() }
    })
    stream.addEventListener('run_snapshot', event => {
      if (stopped || token !== generation) return
      try {
        const run = JSON.parse(event.data) as Run
        snapshot(run)
        if (!stopped) { cursor = String(run.eventSeq); void recover() }
      } catch (error) { options.onError(error); void recover() }
    })
    stream.onerror = () => { if (!stopped && token === generation) { stream?.close(); stream = undefined; options.onConnection('reconnecting'); schedule() } }
  }
  // Polling remains a fallback even if an intermediary silently stalls the SSE connection.
  void recover()
  return { stop, refresh: recover, getCursor: () => cursor }
}
