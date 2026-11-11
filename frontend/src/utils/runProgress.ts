import type { RunEvent } from '../types/api'
import { terminal } from './runObserver'

export const executorLabels = { rag: 'RAG 检索问答', agent: 'Agent 自主问答' }
const toolLabels: Record<string, string> = {
  get_paper_metadata: '读取论文信息',
  search_paper_chunks: '检索论文原文',
  list_paper_sections: '查看论文结构',
  read_paper_section: '读取论文章节',
  read_paper_page: '读取指定页',
  read_neighbor_chunks: '读取相邻片段',
}
const toolDescriptions: Record<string, string> = {
  get_paper_metadata: '读取标题、作者等基本信息',
  search_paper_chunks: '查找与问题相关的原文证据',
  list_paper_sections: '查看已识别的章节目录',
  read_paper_section: '按章节补充原文内容',
  read_paper_page: '读取与指定页码相关的原文分块',
  read_neighbor_chunks: '补充证据片段前后的上下文',
}
const skillLabels: Record<string, string> = {
  'paper-qa': '论文问答', 'paper-summary': '单篇论文总结',
  'paper-comparison': '多论文比较', 'research-analysis': '研究思路分析',
}
const text = (value: unknown) => typeof value === 'string' ? value : ''
interface ToolProgress {
  callId: string
  label: string
  description?: string
  status: 'running' | 'completed' | 'failed' | 'unconfirmed'
  elapsedMs?: number
  errorCode?: string
}

/** Derive display state only from committed events; a failed tool is not a failed Run. */
export function runProgress(events: RunEvent[], status?: string) {
  let skill = ''
  const tools = new Map<string, ToolProgress>()
  for (const event of events) {
    const payload = event.payload
    if (event.type === 'skill_selected') {
      const id = text(payload.skillId)
      skill = skillLabels[id] || id
    }
    if (!['tool_started', 'tool_completed', 'tool_failed'].includes(event.type)) continue
    const callId = text(payload.callId), name = text(payload.toolName)
    if (!callId || !name) continue
    const previous = tools.get(callId)
    if (event.type === 'tool_started' && previous) continue
    const elapsed = payload.elapsedMs
    const elapsedMs = (typeof elapsed === 'number' || (typeof elapsed === 'string' && /^\d+$/.test(elapsed))) ? Number(elapsed) : NaN
    tools.set(callId, {
      callId, label: toolLabels[name] || name, description: toolDescriptions[name],
      status: event.type === 'tool_started' ? 'running' : event.type === 'tool_completed' ? 'completed' : 'failed',
      elapsedMs: Number.isFinite(elapsedMs) && elapsedMs >= 0 ? elapsedMs : undefined,
      errorCode: text(payload.errorCode) || undefined,
    })
  }
  if (status && terminal(status)) {
    for (const tool of tools.values()) if (tool.status === 'running') tool.status = 'unconfirmed'
  }
  return { skill, tools: [...tools.values()] }
}
