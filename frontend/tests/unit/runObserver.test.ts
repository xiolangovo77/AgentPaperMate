import { afterEach, describe, expect, it, vi } from 'vitest'
import { observeRun } from '../../src/utils/runObserver'
import type { Run, RunEvent } from '../../src/types/api'

class FakeStream {
  listeners = new Map<string, (event: MessageEvent<string>) => void>()
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  close = vi.fn()
  addEventListener(type: string, callback: (event: MessageEvent<string>) => void) { this.listeners.set(type, callback) }
  emit(type: string, data: unknown) { this.listeners.get(type)?.({ data: JSON.stringify(data) } as MessageEvent<string>) }
}
const run = (status: Run['status'] = 'running', seq = '0'): Run => ({ runId: '9223372036854775000', sessionId: '20', status, eventSeq: seq, citations: [] })
const event = (id: string, type = 'retrieval_started'): RunEvent => ({ runId: run().runId, eventId: id, type, timestamp: '', payload: {} })
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve() }
function setup() {
  vi.useFakeTimers()
  const streams: FakeStream[] = [], urls: string[] = []
  const getRun = vi.fn(async () => run()), getEvents = vi.fn(async (_cursor: string): Promise<RunEvent[]> => [])
  const onEvent = vi.fn(), onRun = vi.fn(), onError = vi.fn(), onConnection = vi.fn()
  const observer = observeRun({ runId: run().runId, apiBase: '/api', interval: 100, getRun, getEvents, onEvent, onRun, onError, onConnection,
    makeStream: url => { urls.push(url); const stream = new FakeStream(); streams.push(stream); return stream } })
  return { streams, urls, getRun, getEvents, onEvent, onRun, onError, onConnection, observer }
}
afterEach(() => { vi.useRealTimers() })
describe('committed Run event recovery', () => {
  it('deduplicates events and reconnects using the last committed sequence without creating a Run', async () => {
    const state = setup(); await flush()
    state.streams[0]!.emit('retrieval_started', event('1'))
    state.streams[0]!.emit('retrieval_started', event('1'))
    expect(state.onEvent).toHaveBeenCalledTimes(1)
    state.getRun.mockResolvedValue(run('running', '1'))
    state.streams[0]!.onerror?.({} as Event)
    await vi.advanceTimersByTimeAsync(100)
    expect(state.getEvents).toHaveBeenLastCalledWith('1')
    expect(state.urls.at(-1)).toContain('afterEventSeq=1')
    expect(state.urls.at(-1)).toContain('id=9223372036854775000')
    state.observer.stop()
  })
  it('retrieves a committed final answer and closes the stream on completion', async () => {
    const state = setup(); await flush()
    state.getRun.mockResolvedValue(run('completed', '1'))
    state.streams[0]!.emit('run_completed', event('1', 'run_completed'))
    await flush()
    expect(state.onRun).toHaveBeenLastCalledWith(run('completed', '1'))
    expect(state.onConnection).toHaveBeenLastCalledWith('stopped')
    const count = state.getRun.mock.calls.length
    await vi.advanceTimersByTimeAsync(1000)
    expect(state.getRun).toHaveBeenCalledTimes(count)
  })
  it('handles expired event logs through a snapshot without losing Long precision', async () => {
    const state = setup(); await flush()
    state.streams[0]!.emit('run_snapshot', run('running', '9007199254740993'))
    state.getRun.mockResolvedValue(run('running', '9007199254740993'))
    await flush()
    expect(state.getEvents).toHaveBeenLastCalledWith('9007199254740993')
    expect(state.observer.getCursor()).toBe('9007199254740993')
    state.observer.stop()
  })
  it('ignores late responses after navigating away and does not cancel the server task', async () => {
    const state = setup(); await flush()
    let resolve!: (value: Run) => void
    state.getRun.mockImplementationOnce(() => new Promise<Run>(done => resolve = done))
    const pending = state.observer.refresh(); await flush()
    const before = state.onRun.mock.calls.length
    state.observer.stop(); resolve(run('completed', '1')); await pending
    expect(state.onRun).toHaveBeenCalledTimes(before)
    expect(state.streams).toHaveLength(1)
  })
  it('recovers a missing event before delivering the next one', async () => {
    const state = setup(); await flush()
    state.getEvents.mockResolvedValue([event('1'), event('2', 'retrieval_completed')])
    state.getRun.mockResolvedValue(run('running', '2'))
    state.streams[0]!.emit('retrieval_completed', event('2', 'retrieval_completed'))
    await flush()
    expect(state.onEvent.mock.calls.map(call => call[0].eventId)).toEqual(['1', '2'])
    state.observer.stop()
  })
})
