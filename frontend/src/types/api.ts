export type Id = string
export interface BaseResponse<T> { code: number; data: T; message: string }
export interface Page<T> { records: T[]; total: number | string; current: number | string; pages: number | string; size: number | string }
export interface User { id: Id; username: string; nickname: string; avatarUrl?: string; createTime?: string }
export interface Session { id: Id; name: string; inputDraft: string | null; scopeVersion: number; activeRunId: Id | null; lastMessageTime?: string; createTime: string; updateTime?: string }
export interface SessionPaper { id: Id; sessionId: Id; paperId: Id; createTime?: string }
export interface PaperReceipt { paperId: Id; jobId: Id; status: string }
export interface UploadedPaper extends PaperReceipt { filename: string; submittedAt: string }
export interface Message { id: Id; sessionId: Id; runId?: Id; role: string; messageType: string; content: string; extraData?: string; createTime: string }
export interface Citation { id: Id; messageId: Id; evidenceId: string; paperId: Id; paperTitle: string; pageStart: number; pageEnd: number; quotedText: string; sourceLocation?: string }
export type RunStatus = 'pending' | 'running' | 'completed' | 'failed' | 'cancelled' | 'timeout'
export interface Run { runId: Id; sessionId: Id; status: RunStatus; eventSeq: string; errorCode?: string; errorMessage?: string; answer?: Message | null; citations: Citation[] }
export interface RunEvent { runId: Id; eventId: string; type: string; timestamp: string; payload: Record<string, unknown> }
export interface RunCreate { sessionId: Id; requestKey: string; question: string; retryOfRunId?: Id }
export const noteFields = [
  ['researchQuestion', '研究问题', '这篇论文试图解决什么问题？'],
  ['methods', '技术方法', '记录方法、模型架构和关键实现。'],
  ['innovations', '核心创新', '相比已有研究，新的贡献是什么？'],
  ['experimentResults', '实验结果', '记录关键指标、对照实验与结论。'],
  ['limitationsQuestions', '局限与疑问', '哪些假设、边界或问题还需要验证？'],
  ['personalThoughts', '个人思考', '与自己的研究有什么联系？下一步可以做什么？'],
] as const
export type NoteField = typeof noteFields[number][0]
export type NoteContent = Record<NoteField, string>
export type Note = NoteContent & { id: Id; paperId: Id; version: number; updateTime?: string }
