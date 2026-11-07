import { get, post, request } from './request'
import type { Id, User, Page, Session, SessionPaper, PaperReceipt, Note, NoteContent, Message, Citation, Run, RunCreate, RunEvent } from '../types/api'

export const userApi = {
  current: () => request<User>({ url: '/appUser/get' }, true),
  login: (username: string, password: string) => post<User>('/appUser/login', { username, password }),
  register: (username: string, password: string, checkPassword: string) => post<Id>('/appUser/register', { username, password, checkPassword }),
  logout: () => post<boolean>('/appUser/logout'),
  update: (data: { nickname?: string; avatarUrl?: string; password?: string }) => post<boolean>('/appUser/update', data),
}
export function uploadFile(kind: 'paper' | 'avatar', file: File, progress?: (value: number | null) => void) {
  const data = new FormData()
  data.append('file', file)
  return request<string>({ url: `/common/upload/${kind}`, method: 'POST', data, timeout: 120000,
    onUploadProgress: event => progress?.(event.total ? Math.round(event.loaded / event.total * 100) : null) })
}
export const paperApi = { add: (fileUrl: string) => post<PaperReceipt>('/paper/add', { fileUrl }) }
export const noteApi = {
  get: (paperId: Id) => get<Note | null>('/readingNote/get', { paperId }),
  add: (paperId: Id, content: NoteContent) => post<Id>('/readingNote/add', { paperId, ...content }),
  update: (paperId: Id, content: NoteContent, version: number) => post<number>('/readingNote/update', { paperId, ...content, version }),
}
export const sessionApi = {
  list: (pageNum = 1, name = '') => post<Page<Session>>('/chatSession/list/page', { pageNum, pageSize: 20, name }),
  get: (id: Id) => get<Session>('/chatSession/get', { id }),
  add: (name: string, inputDraft = '') => post<Id>('/chatSession/add', { name, inputDraft }),
  update: (id: Id, data: { name?: string; inputDraft?: string }) => post<boolean>('/chatSession/update', { id, ...data }),
  delete: (id: Id) => post<boolean>('/chatSession/delete', { id }),
  papers: (sessionId: Id, pageNum = 1) => post<Page<SessionPaper>>('/sessionPaper/list/page', { sessionId, pageNum, pageSize: 100 }),
  addPaper: (sessionId: Id, paperId: Id) => post<Id>('/sessionPaper/add', { sessionId, paperId }),
  removePaper: (id: Id) => post<boolean>('/sessionPaper/update', { id, isDelete: 1 }),
}
export const messageApi = {
  list: (sessionId: Id, pageNum = 1) => get<Page<Message>>('/chatMessage/list/page', { sessionId, pageNum, pageSize: 20 }),
  citations: (messageId: Id) => get<Citation[]>('/messageCitation/list', { messageId }),
}
export const runApi = {
  key: () => get<string>('/agentRun/request-key'),
  create: (body: RunCreate) => post<Id>('/agentRun/add', body),
  get: (id: Id) => get<Run>('/agentRun/get', { id }),
  cancel: (id: Id) => post<string>('/agentRun/cancel', { id }),
  events: (runId: Id, afterEventSeq: string) => get<RunEvent[]>('/runEvent/list', { runId, afterEventSeq }),
}
