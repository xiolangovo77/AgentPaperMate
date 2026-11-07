import { paperApi, sessionApi } from '../api'
import type { PaperDetail, PaperSummary } from '../types/api'
export const paperTitle = (paper: Pick<PaperSummary, 'title' | 'originalFilename'>) => paper.title || paper.originalFilename || '未命名论文'
export const paperStatus: Record<string, string> = { uploaded: '待处理', processing: '处理中', ready: '已完成', failed: '处理失败' }

// Bound concurrency when resolving a large selection; return no partial success on access failures.
export async function paperDetails(ids: string[]) {
  const result: PaperDetail[] = new Array(ids.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(5, ids.length) }, async () => {
    while (next < ids.length) { const index = next++; result[index] = await paperApi.get(ids[index]!) }
  }))
  return result
}
// A paginated membership read is not itself a snapshot. Verify the version around it.
export async function readScope(sessionId: string) {
  for (let attempt = 0; attempt < 3; attempt++) {
    const before = await sessionApi.get(sessionId)
    const first = await sessionApi.papers(sessionId)
    const papers = [...first.records]
    for (let page = 2; page <= Number(first.pages); page++) papers.push(...(await sessionApi.papers(sessionId, page)).records)
    const after = await sessionApi.get(sessionId)
    if (before.scopeVersion === after.scopeVersion) return { session: after, papers }
  }
  throw new Error('资料范围正在被其他窗口修改，请稍后刷新。')
}
