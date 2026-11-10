import { describe, expect, it } from 'vitest'
import type { RunEvent } from '../../src/types/api'
import { runProgress } from '../../src/utils/runProgress'

const event = (type: string, payload: Record<string, unknown>): RunEvent => ({ runId: '1', eventId: '1', timestamp: '', type, payload })
const call = { callId: 'call-1', toolName: 'search_paper_chunks' }
describe('Agent execution progress', () => {
  it('groups by call ID, retains failed calls and allows later calls to succeed', () => {
    const progress = runProgress([
      event('skill_selected', { skillId: 'paper-summary' }),
      event('tool_started', call),
      event('retrieval_started', {}),
      event('tool_failed', { ...call, elapsedMs: '0', errorCode: 'INVALID_ARGUMENT' }),
      event('tool_started', call),
      event('tool_started', { ...call, callId: 'call-2' }),
      event('tool_completed', { ...call, callId: 'call-2', elapsedMs: 120 }),
    ], 'running')
    expect(progress.skill).toBe('单篇论文总结')
    expect(progress.tools).toHaveLength(2)
    expect(progress.tools[0]).toMatchObject({ status: 'failed', elapsedMs: 0, errorCode: 'INVALID_ARGUMENT' })
    expect(progress.tools[1]).toMatchObject({ status: 'completed', elapsedMs: 120 })
  })
  it('does not leave an unfinished tool spinning after cancellation, timeout or completion', () => {
    for (const status of ['cancelled', 'timeout', 'failed', 'completed']) {
      expect(runProgress([event('tool_started', call)], status).tools[0]?.status).toBe('unconfirmed')
    }
    expect(runProgress([event('tool_started', call)], 'running').tools[0]?.status).toBe('running')
  })
  it('handles missing starts, unknown tool names and invalid elapsed values', () => {
    const progress = runProgress([
      event('tool_started', { toolName: 'missing_call_id' }),
      event('tool_completed', { ...call, toolName: 'future_tool', elapsedMs: null }),
    ], 'completed')
    expect(progress.tools).toHaveLength(1)
    expect(progress.tools[0]).toMatchObject({ label: 'future_tool', status: 'completed', elapsedMs: undefined })
  })
})
