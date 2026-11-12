import { expect, test, type Page, type Route } from '@playwright/test'
import { pdfFixture } from './pdf-fixture'
import type { RunEvent } from '../../src/types/api'

// Explicit browser-test fixtures. None of this data is imported by the application.
const sessionId = '9223372036854775001', paperId = '9223372036854775002'
const envelope = (data: unknown, code = 0, message = 'ok') => ({ code, data, message })
const paged = (records: unknown[]) => ({ records, current: '1', size: '20', total: String(records.length), pages: '1' })
const emptyNote = { researchQuestion: '', methods: '', innovations: '', experimentResults: '', limitationsQuestions: '', personalThoughts: '' }
async function fixtures(page: Page) {
  const state = {
    session: { id: sessionId, name: '测试研究会话', inputDraft: '', scopeVersion: 1, activeRunId: null as string | null, createTime: '2026-11-07T12:00:00' },
    notes: { ...emptyNote, id: '500', paperId, version: 1 },
    updates: [] as Record<string, unknown>[],
    papers: [{ id: '600', sessionId, paperId }],
    messages: [] as Record<string, unknown>[],
    creates: [] as Record<string, unknown>[],
    failCreate: false, conflict: false, expire: false, runStatus: 'running',
    unhandled: [] as string[],
    canAsk: true, recycled: 0,
    runEvents: [] as RunEvent[],
  }
  await page.route('**/api/**', async (route: Route) => {
    if (!new URL(route.request().url()).pathname.startsWith('/api/')) return route.continue()
    const request = route.request(), path = new URL(request.url()).pathname.slice(4)
    const body = request.method() === 'POST' && request.postData() ? request.postDataJSON() : {}
    const ok = (data: unknown) => route.fulfill({ json: envelope(data) })
    if (state.expire) return route.fulfill({ json: envelope(null, 40100, '未登录') })
    if (path === '/appUser/get') return ok({ id: '10', username: 'test_researcher', nickname: '测试研究者' })
    const paper = { id: paperId, title: '测试论文', originalFilename: 'paper.pdf', authors: 'Test Author', topic: 'RAG', status: 'ready', pageCount: 4, fileSize: '1024', createTime: '2026-11-08T12:00:00', updateTime: '2026-11-08T12:00:00' }
    if (path === '/paper/recycle') { state.recycled = body.isRecycled; return ok(true) }
    if (path === '/paper/list/page') return ok(paged((body.isRecycled || 0) !== state.recycled || body.status && body.status !== 'ready' ? [] : [{ ...paper, isRecycled: state.recycled }]))
    if (path === '/paper/get') return ok({ ...paper, isRecycled: state.recycled, id: new URL(request.url()).searchParams.get('id'), canAsk: state.canAsk, jobId: null, abstractText: '测试用论文摘要', errorMessage: null })
    if (path === '/paper/file') return route.fulfill({ contentType: 'application/pdf', body: pdfFixture() })
    if (path === '/paperProcessingJob/get') return ok({ id: '9223372036854775003', paperId, status: 'pending', currentStep: 'queued', totalChunks: null, indexedChunks: null, retryCount: 0, updateTime: '2026-11-08T12:00:00' })
    if (path === '/chatSession/list/page') return ok(paged([state.session]))
    if (path === '/chatSession/get') return ok(state.session)
    if (path === '/chatSession/add') return ok(sessionId)
    if (path === '/chatSession/update') { state.updates.push(body); Object.assign(state.session, body); return ok(true) }
    if (path === '/sessionPaper/list/page') return ok(paged(state.papers))
    if (path === '/sessionPaper/updateScope') {
      state.updates.push(body)
      if (body.scopeVersion !== state.session.scopeVersion) return route.fulfill({ json: envelope(null, 40900, '论文范围已变化，请刷新范围和版本后重试') })
      state.papers = body.paperIds.map((id: string, i: number) => ({ id: String(600 + i), sessionId, paperId: id }))
      state.session.scopeVersion++
      return ok({ sessionId, paperIds: body.paperIds, scopeVersion: state.session.scopeVersion })
    }
    if (path === '/sessionPaper/add') { state.papers.push({ id: '601', sessionId, paperId: body.paperId }); state.session.scopeVersion++; return ok('601') }
    if (path === '/sessionPaper/update') { state.papers = state.papers.filter(item => item.id !== body.id); state.session.scopeVersion++; return ok(true) }
    if (path === '/chatMessage/list/page') return ok(paged(state.messages))
    if (path === '/readingNote/get') return ok(state.notes)
    if (path === '/readingNote/update') {
      state.updates.push(body)
      if (state.conflict) return route.fulfill({ json: envelope(null, 40900, '笔记已更新，请刷新后重新编辑') })
      if (body.version !== state.notes.version) return route.fulfill({ json: envelope(null, 40900, '版本冲突') })
      await new Promise(resolve => setTimeout(resolve, 120))
      Object.assign(state.notes, body, { version: state.notes.version + 1 })
      return ok(state.notes.version)
    }
    if (path === '/agentRun/request-key') return ok('request-key-abcdefghijklmnop')
    if (path === '/agentRun/add') {
      state.creates.push(body)
      if (state.failCreate && state.creates.length === 1) return route.abort('failed')
      state.session.activeRunId = '700'
      state.messages = [{ id: '701', sessionId, role: 'user', runId: '700', messageType: 'text', content: body.question, createTime: '2026-11-07T12:00:00' }]
      return ok('700')
    }
    if (path === '/agentRun/get') return ok({ runId: '700', sessionId, status: state.runStatus, eventSeq: state.runEvents.at(-1)?.eventId || '0', citations: [] })
    if (path === '/agentRun/cancel') { state.runStatus = 'cancelled'; state.session.activeRunId = null; return ok('cancelled') }
    if (path === '/runEvent/list') {
      const after = new URL(request.url()).searchParams.get('afterEventSeq') || '0'
      return ok(state.runEvents.filter(event => BigInt(event.eventId) > BigInt(after)).slice(0, 100))
    }
    if (path === '/agentRun/events') return route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' })
    if (path === '/messageCitation/list') return ok([{ id: '702', messageId: '701', evidenceId: 'E1', paperId, paperTitle: '已校验的引用标题', pageStart: 3, pageEnd: 4, quotedText: '来自论文的真实格式摘录。', sourceLocation: 'Methods' }])
    state.unhandled.push(path)
    return route.fulfill({ status: 404, json: envelope(null, 40400, `Unexpected test endpoint: ${path}`) })
  })
  return state
}

test('overview, real-shaped paper metadata and responsive layouts', async ({ page }) => {
  const state = await fixtures(page), errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '研究总览', exact: true })).toBeVisible()
  await expect(page.locator('.metric-number').last()).toHaveText('1')
  await page.screenshot({ path: 'test-results/overview-desktop.png', fullPage: true })
  for (const width of [1920, 1440, 1280, 768, 390]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await page.screenshot({ path: 'test-results/overview-mobile.png', fullPage: true })
  await page.goto('/library')
  await expect(page.getByRole('textbox', { name: '搜索论文标题' })).toBeEnabled()
  await expect(page.getByRole('link', { name: '测试论文', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '导入 PDF', exact: true }).click()
  await page.locator('input[type=file]').setInputFiles({ name: 'bad.txt', mimeType: 'text/plain', buffer: Buffer.from('no') })
  await expect(page.getByRole('alert')).toContainText('不超过 50 MB')
  await page.keyboard.press('Escape')
  expect(state.unhandled).toEqual([]); expect(errors).toEqual([])
})

test('notes serialize saves and pause on a version conflict without discarding the draft', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/library/${paperId}?tab=notes`)
  const field = page.getByLabel('研究问题', { exact: false })
  await field.fill('第一版内容')
  await expect.poll(() => state.notes.version).toBe(2)
  await field.fill('第二版内容')
  await expect.poll(() => state.notes.version).toBe(3)
  expect(state.updates.filter(item => item.version).map(item => item.version)).toEqual([1, 2])
  state.conflict = true
  await field.fill('保留这份冲突草稿')
  await expect(page.getByText('版本冲突 · 自动保存已暂停')).toBeVisible()
  await expect(field).toHaveValue('保留这份冲突草稿')
  await field.fill('冲突后继续编辑')
  const count = state.updates.length
  await page.waitForTimeout(1100)
  expect(state.updates).toHaveLength(count)
  const downloaded = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Markdown' }).click()
  expect((await downloaded).suggestedFilename()).toContain('阅读笔记')
  page.on('dialog', dialog => dialog.accept())
})

test('chat drafts, atomic empty scope and responsive room', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByRole('heading', { name: '测试研究会话', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('需要持久化的草稿')
  await expect.poll(() => state.session.inputDraft).toBe('需要持久化的草稿')
  await page.reload()
  await expect(page.getByRole('textbox', { name: '研究问题', exact: true })).toHaveValue('需要持久化的草稿')
  await page.screenshot({ path: 'test-results/chat-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/chat-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '调整资料' }).click()
  await page.getByRole('checkbox', { name: '选择 测试论文', exact: true }).uncheck()
  expect(state.papers).toHaveLength(1)
  await page.getByRole('button', { name: '确认选择（0 篇）' }).click()
  await expect.poll(() => state.papers.length).toBe(0)
  await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
  await page.getByRole('button', { name: '调整资料' }).click()
  await page.getByRole('checkbox', { name: '选择 测试论文', exact: true }).check()
  await page.getByRole('button', { name: '确认选择（1 篇）' }).click()
  await expect.poll(() => state.papers.length).toBe(1)
  await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
  expect(state.unhandled).toEqual([])
})

test('ambiguous Run creation retries the same idempotency key and cancel uses the backend', async ({ page }) => {
  const state = await fixtures(page); state.failCreate = true
  await page.goto(`/chat/${sessionId}`)
  await page.getByLabel('回答模式', { exact: true }).selectOption('agent')
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('总结论文')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '确认提交结果' })).toBeVisible()
  await expect(page.getByLabel('回答模式', { exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '确认提交结果' }).click()
  await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible()
  expect(state.creates).toHaveLength(2)
  expect(state.creates[0]).toEqual(state.creates[1])
  expect(state.creates[0]?.executorType).toBe('agent')
  await page.getByRole('button', { name: '停止任务' }).click()
  await expect(page.getByText('任务已取消', { exact: true })).toBeVisible()
  expect(state.runStatus).toBe('cancelled')
})

test('defaults to RAG and uses the selected mode for an explicit historical retry', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByLabel('回答模式', { exact: true })).toHaveValue('rag')
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('论文的方法是什么？')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible()
  expect(state.creates[0]?.executorType).toBe('rag')
  await expect(page.getByLabel('回答模式', { exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '停止任务' }).click()
  await expect(page.getByLabel('回答模式', { exact: true })).toBeEnabled()
  await page.getByLabel('回答模式', { exact: true }).selectOption('agent')
  page.once('dialog', async dialog => {
    expect(dialog.message()).toContain('Agent 自主问答')
    await dialog.accept()
  })
  await page.getByRole('button', { name: '重新执行此问题' }).click()
  await expect.poll(() => state.creates.length).toBe(2)
  expect(state.creates[1]).toMatchObject({ executorType: 'agent', retryOfRunId: '700', question: '论文的方法是什么？' })
  expect(state.unhandled).toEqual([])
})

test('restores Agent skill and tool progress on reload and stops unfinished tools on cancel', async ({ page }) => {
  const state = await fixtures(page)
  state.session.activeRunId = '700'
  const addEvent = (type: string, payload: Record<string, unknown>) => state.runEvents.push({ runId: '700', eventId: String(state.runEvents.length + 1), type, timestamp: '', payload })
  addEvent('run_started', { status: 'running' })
  addEvent('skill_selected', { skillId: 'paper-summary', version: '1.0.0', contentHash: 'test' })
  addEvent('tool_started', { callId: 'a', toolName: 'get_paper_metadata' })
  addEvent('tool_completed', { callId: 'a', toolName: 'get_paper_metadata', elapsedMs: '12' })
  addEvent('tool_started', { callId: 'b', toolName: 'search_paper_chunks' })
  addEvent('tool_failed', { callId: 'b', toolName: 'search_paper_chunks', elapsedMs: 20, errorCode: 'INVALID_ARGUMENT' })
  addEvent('tool_started', { callId: 'c', toolName: 'search_paper_chunks' })
  await page.goto(`/chat/${sessionId}`)
  await expect(page.locator('.run-progress')).not.toHaveAttribute('open')
  await page.locator('.run-progress > summary').click()
  await expect(page.locator('.run-progress')).toHaveAttribute('open')
  await expect(page.locator('.skill-selection')).toHaveText('已选择技能：单篇论文总结')
  await expect(page.getByRole('list', { name: '工具执行记录' }).getByRole('listitem')).toHaveCount(3)
  await expect(page.locator('.run-progress .failed')).toContainText('INVALID_ARGUMENT')
  await expect(page.locator('.run-progress .completed')).toContainText('12 ms')
  await page.reload()
  await expect(page.locator('.run-progress')).not.toHaveAttribute('open')
  await page.locator('.run-progress > summary').click()
  await expect(page.locator('.run-progress')).toHaveAttribute('open')
  await expect(page.getByRole('list', { name: '工具执行记录' }).getByRole('listitem')).toHaveCount(3)
  await expect(page.getByLabel('回答模式', { exact: true })).toHaveValue('agent')
  await page.screenshot({ path: 'test-results/agent-progress-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/agent-progress-mobile.png', fullPage: true })
  await page.getByRole('button', { name: '停止任务' }).click()
  await expect(page.locator('.run-progress .running')).toHaveCount(0)
  await expect(page.locator('.run-progress')).toContainText('任务已结束，未收到工具结果')
  state.messages = [{ id: '701', sessionId, role: 'user', runId: '700', messageType: 'text', content: '总结论文', createTime: '2026-11-07T12:00:00' }]
  await page.reload()
  await expect(page.locator('.run-progress')).not.toHaveAttribute('open')
  await page.locator('.run-progress > summary').click()
  await expect(page.locator('.run-progress')).toHaveAttribute('open')
  await expect(page.getByRole('list', { name: '工具执行记录' }).getByRole('listitem')).toHaveCount(3)
  await expect(page.locator('.run-progress .running')).toHaveCount(0)
  expect(state.unhandled).toEqual([])
})

test('offers single-paper Agent summary and guides multi-paper scope without blocking questions', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/chat/${sessionId}`)
  await page.getByLabel('回答模式', { exact: true }).selectOption('agent')
  await page.getByRole('button', { name: '请总结这篇论文的研究背景、问题、方法、创新、实验与局限', exact: true }).click()
  await expect(page.getByRole('textbox', { name: '研究问题', exact: true })).toHaveValue('请总结这篇论文的研究背景、问题、方法、创新、实验与局限')
  state.papers.push({ id: '601', sessionId, paperId: '900' })
  await page.reload()
  await page.getByLabel('回答模式', { exact: true }).selectOption('agent')
  await page.getByRole('button', { name: '回答模式说明', exact: true }).click()
  await expect(page.getByText('需要结构化总结时，请先将资料范围调整为一篇论文。', { exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '请总结这篇论文的研究背景、问题、方法、创新、实验与局限', exact: true })).toHaveCount(0)
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('两篇论文的方法有什么区别？')
  await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
})

test('sanitizes answers and uses verified citations for physical-page links', async ({ page }) => {
  const state = await fixtures(page)
  state.messages = [{ id: '701', sessionId, role: 'assistant', messageType: 'text', content: '## 研究结论\n<script>window.attacked=true</script><img src=x onerror="window.attacked=true">\n[危险链接](javascript:alert(1))\n\n```java\nSystem.out.println("paper");\n```', createTime: '2026-11-07T12:00:00' }]
  await page.goto(`/chat/${sessionId}`)
  await page.locator('.citation-sources > summary').click()
  await expect(page.getByText('已校验的引用标题', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => (window as unknown as { attacked?: boolean }).attacked)).toBeUndefined()
  await expect(page.locator('.markdown script, .markdown img, .markdown a[href^="javascript:"]')).toHaveCount(0)
  await page.getByRole('link', { name: /PDF 物理第 3–4 页/ }).click()
  await expect(page).toHaveURL(new RegExp(`/library/${paperId}\\?page=3`))
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('3')
  await expect(page.locator('.pdf-toolbar')).toContainText('/ 4 页')
  await expect(page.locator('.pdf-viewer .loading-state')).toHaveCount(0)
  await expect.poll(() => page.locator('[data-page="3"] canvas').evaluate(canvas => (canvas as HTMLCanvasElement).width)).toBeGreaterThan(400)
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('4')
})

test('login expiry does not loop through draft saves', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByRole('textbox', { name: '研究问题', exact: true })).toBeVisible()
  state.expire = true
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('未保存的草稿')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await expect(page.getByText('登录已失效，请重新登录。')).toBeVisible()
})

test('PDF upload submits the returned URL and retries add without uploading twice', async ({ page }) => {
  await fixtures(page)
  let uploads = 0, submissions = 0
  await page.route('**/api/common/upload/paper', async route => {
    uploads++
    expect(route.request().headers()['content-type']).toContain('multipart/form-data; boundary=')
    expect(route.request().postDataBuffer()?.toString()).toContain('name="file"')
    await route.fulfill({ json: envelope('https://private.invalid/papers/uploaded.pdf') })
  })
  await page.route('**/api/paper/add', async route => {
    submissions++
    expect(route.request().postDataJSON()).toEqual({ fileUrl: 'https://private.invalid/papers/uploaded.pdf' })
    if (submissions === 1) return route.fulfill({ json: envelope(null, 50001, '暂时无法提交') })
    return route.fulfill({ json: envelope({ paperId, jobId: '9223372036854775003', status: 'pending' }) })
  })
  await page.goto('/library?upload=1')
  await page.locator('input[type=file]').setInputFiles({ name: 'paper.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nfixture') })
  await page.getByRole('button', { name: '上传并开始处理' }).click()
  await expect(page.getByText('暂时无法提交')).toBeVisible()
  await page.getByRole('button', { name: '重新提交解析任务' }).click()
  await expect(page.getByRole('heading', { name: '已提交论文处理任务' })).toBeVisible()
  expect(uploads).toBe(1); expect(submissions).toBe(2)
  await expect(page.locator('dialog')).toContainText('9223372036854775003')
  await expect(page.locator('dialog')).toContainText('等待处理')
})

test('final Run completion refreshes messages even when a previous history request is in flight', async ({ page }) => {
  const state = await fixtures(page)
  let delayed = false
  await page.route('**/api/chatMessage/list/page?**', async route => {
    const snapshot = JSON.parse(JSON.stringify(state.messages))
    if (snapshot.length === 1 && !delayed) { delayed = true; await new Promise(resolve => setTimeout(resolve, 400)) }
    await route.fulfill({ json: envelope(paged(snapshot)) })
  })
  await page.route('**/api/agentRun/get?**', async route => {
    state.runStatus = 'completed'; state.session.activeRunId = null
    if (!state.messages.some(item => item.id === '702')) state.messages.push({ id: '702', sessionId, role: 'assistant', messageType: 'text', content: '最终答案已持久化。', createTime: '2026-11-07T12:00:01' })
    await route.fulfill({ json: envelope({ runId: '700', sessionId, status: 'completed', eventSeq: '0', citations: [] }) })
  })
  await page.goto(`/chat/${sessionId}`)
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('请返回最终答案')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByText('最终答案已持久化。', { exact: true })).toBeVisible()
  await expect(page.getByText('回答已完成', { exact: true })).toBeVisible()
})

test('scope changes are staged, cancellation writes nothing, and conflicts require reload', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/chat/${sessionId}`)
  await page.getByRole('button', { name: '调整资料' }).click()
  await page.getByRole('checkbox', { name: '选择 测试论文' }).uncheck()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  expect(state.papers).toHaveLength(1)
  expect(state.updates.filter(body => 'scopeVersion' in body)).toHaveLength(0)
  await page.getByRole('button', { name: '调整资料' }).click()
  await page.getByRole('checkbox', { name: '选择 测试论文' }).uncheck()
  state.session.scopeVersion = 2
  await page.getByRole('button', { name: '确认选择（0 篇）' }).click()
  await expect(page.getByText('资料范围已被其他窗口修改，当前选择已保留。请加载最新范围并重新确认。')).toBeVisible()
  await expect(page.getByRole('checkbox', { name: '选择 测试论文' })).not.toBeChecked()
  await expect(page.getByRole('button', { name: '确认选择（0 篇）' })).toBeDisabled()
  expect(state.papers).toHaveLength(1)
  page.on('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '加载最新范围' }).click()
  await expect(page.getByRole('checkbox', { name: '选择 测试论文' })).toBeChecked()
  await page.getByRole('checkbox', { name: '选择 测试论文' }).uncheck()
  await page.getByRole('button', { name: '确认选择（0 篇）' }).click()
  await expect.poll(() => state.papers.length).toBe(0)
  expect(state.updates.filter(body => 'scopeVersion' in body).map(body => body.scopeVersion)).toEqual([1, 2])
})

test('library filters preserve cross-page selections and merge with existing session scope', async ({ page }) => {
  const state = await fixtures(page)
  const records = Array.from({ length: 12 }, (_, i) => ({ id: String(800 + i), title: `分页论文 ${i + 1}`, originalFilename: 'paper.pdf', authors: 'Author', topic: 'RAG', status: 'ready', pageCount: 4, createTime: '2026-11-08T12:00:00' }))
  const queries: Record<string, unknown>[] = []
  await page.route('**/api/paper/list/page', async route => {
    const body = route.request().postDataJSON(); queries.push(body)
    const filtered = records.filter(paper => paper.title.includes(body.title || '') && paper.authors.includes(body.authors || '') && paper.topic.includes(body.topic || '') && (!body.status || body.status === paper.status))
    await route.fulfill({ json: envelope({ ...paged(filtered), records: filtered.slice((body.pageNum - 1) * body.pageSize, body.pageNum * body.pageSize), current: body.pageNum, pages: Math.ceil(filtered.length / body.pageSize) }) })
  })
  await page.goto('/library')
  await page.getByRole('checkbox', { name: '选择 分页论文 1', exact: true }).check()
  await page.getByRole('button', { name: '下一页' }).click()
  await page.getByRole('checkbox', { name: '选择 分页论文 11', exact: true }).check()
  await page.getByRole('textbox', { name: '搜索作者' }).fill('Nobody')
  await expect(page.getByText('没有找到论文')).toBeVisible()
  await expect(page.getByRole('button', { name: '已选 2 篇 · 查看选择' })).toBeVisible()
  expect(queries.at(-1)?.authors).toBe('Nobody')
  await page.getByRole('button', { name: '加入已有聊天室', exact: true }).click()
  await page.getByLabel('选择聊天室').selectOption(sessionId)
  await page.getByRole('button', { name: '确认并进入聊天室' }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  expect(state.papers.map(item => item.paperId).sort()).toEqual([paperId, '800', '810'].sort())
  expect(state.updates.filter(body => 'scopeVersion' in body)).toHaveLength(1)
})

test('ready status without a compatible index cannot create a Run', async ({ page }) => {
  const state = await fixtures(page); state.canAsk = false
  await page.goto(`/chat/${sessionId}`)
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('不能发送此问题')
  await expect(page.getByText('资料中有论文尚未就绪或索引不兼容，暂不能提问。')).toBeVisible()
  await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
  expect(state.creates).toHaveLength(0)
  state.canAsk = true
  await page.getByRole('button', { name: '重新检查' }).click()
  await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
})

test('PDF business errors remain visible and retry renders real pages', async ({ page }) => {
  await fixtures(page)
  let requests = 0
  await page.route('**/api/paper/file?**', route => {
    requests++
    if (requests === 1) return route.fulfill({ json: envelope(null, 40400, '原文暂不可读取') })
    return route.fulfill({ contentType: 'application/pdf', body: pdfFixture() })
  })
  await page.goto(`/library/${paperId}?page=3`)
  await expect(page.getByText('原文暂不可读取')).toBeVisible()
  await page.getByRole('button', { name: '重新加载 PDF' }).click()
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('3')
  await expect(page.locator('.pdf-toolbar')).toContainText('/ 4 页')
  await expect(page.locator('.pdf-viewer .loading-state')).toHaveCount(0)
  await expect.poll(() => page.locator('[data-page="3"] canvas').evaluate(canvas => (canvas as HTMLCanvasElement).width)).toBeGreaterThan(400)
  const zoom = Number((await page.locator('.pdf-zoom-value').innerText()).replace('%', ''))
  await page.getByRole('button', { name: '放大', exact: true }).click()
  await expect(page.locator('.pdf-zoom-value')).toHaveText(`${zoom + 15}%`)
  await expect(page.locator('.pdf-viewer .loading-state')).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/pdf-mobile.png', fullPage: true })
})

test('failed paper retries without uploading and follows the new job to readiness', async ({ page }) => {
  await fixtures(page)
  let jobId = '900', status = 'failed', calls = 0
  await page.route('**/api/paper/get?**', route => route.fulfill({ json: envelope({ id: paperId, title: '失败论文', status: status === 'completed' ? 'ready' : status === 'failed' ? 'failed' : 'uploaded', canAsk: status === 'completed', jobId, isRecycled: 0, createTime: '', updateTime: '' }) }))
  await page.route('**/api/paperProcessingJob/get?**', route => {
    expect(new URL(route.request().url()).searchParams.get('id')).toBe(jobId)
    return route.fulfill({ json: envelope({ id: jobId, paperId, status, currentStep: status === 'failed' ? 'parsing' : 'queued', errorMessage: status === 'failed' ? '解析失败' : null, retryCount: 0, totalChunks: 0, updateTime: '' }) })
  })
  await page.route('**/api/paper/retry', async route => {
    calls++
    expect(route.request().postDataJSON()).toEqual({ id: paperId, jobId: '900' })
    await new Promise(resolve => setTimeout(resolve, 300))
    if (calls === 1) return route.fulfill({ json: envelope(null, 50000, '暂时无法提交') })
    jobId = '901'; status = 'pending'
    return route.fulfill({ json: envelope({ paperId, jobId, status: 'uploaded' }) })
  })
  await page.goto(`/library/${paperId}?tab=notes`)
  await page.getByRole('button', { name: '重新处理', exact: true }).click()
  await expect(page.getByRole('button', { name: '正在提交…', exact: true })).toBeDisabled()
  await expect(page.getByRole('alert')).toContainText('暂时无法提交')
  await page.getByRole('button', { name: '重新处理', exact: true }).click()
  await expect(page.getByText('等待处理', { exact: true })).toBeVisible()
  await expect(page.locator('.processing-panel')).not.toContainText('解析失败')
  status = 'completed'
  await expect(page.getByText('可用于问答', { exact: true })).toBeVisible({ timeout: 8000 })
  expect(calls).toBe(2)
})

test('duplicate upload can restart a failed job and update its receipt', async ({ page }) => {
  const state = await fixtures(page)
  let retried = false, uploads = 0
  await page.route('**/api/common/upload/paper', route => { uploads++; return route.fulfill({ json: envelope('https://private.invalid/paper.pdf') }) })
  await page.route('**/api/paper/add', route => route.fulfill({ json: envelope({ paperId, jobId: '900', status: 'failed', alreadyExists: true }) }))
  await page.route('**/api/paperProcessingJob/get?**', route => route.fulfill({ json: envelope({ id: retried ? '901' : '900', paperId, status: retried ? 'pending' : 'failed', currentStep: retried ? 'queued' : 'parsing', retryCount: 0, updateTime: '' }) }))
  await page.route('**/api/paper/retry', route => { retried = true; return route.fulfill({ json: envelope({ paperId, jobId: '901', status: 'uploaded', alreadyExists: false }) }) })
  await page.goto('/library?upload=1')
  await page.locator('input[type=file]').setInputFiles({ name: 'paper.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nfixture') })
  await page.getByRole('button', { name: '上传并开始处理', exact: true }).click()
  await expect(page.getByText('该论文已存在，未重复创建')).toBeVisible()
  await page.getByRole('button', { name: '重新处理', exact: true }).click()
  await expect(page.locator('.receipt')).toContainText('901')
  await expect(page.getByText('等待处理', { exact: true })).toBeVisible()
  expect(uploads).toBe(1); expect(state.unhandled).toEqual([])
})

test('recycled failed paper hides retry action', async ({ page }) => {
  await fixtures(page)
  await page.route('**/api/paper/get?**', route => route.fulfill({ json: envelope({ id: paperId, title: '回收论文', status: 'failed', canAsk: false, jobId: '900', isRecycled: 1, createTime: '', updateTime: '' }) }))
  await page.route('**/api/paperProcessingJob/get?**', route => route.fulfill({ json: envelope({ id: '900', paperId, status: 'failed', errorMessage: '解析失败', retryCount: 3, updateTime: '' }) }))
  await page.goto(`/library/${paperId}?tab=notes`)
  await expect(page.getByRole('region', { name: '论文处理状态' }).getByText('处理失败', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '重新处理', exact: true })).toHaveCount(0)
})

test('processing job polls to completion and refreshes paper readiness', async ({ page }) => {
  await fixtures(page)
  let jobs = 0, details = 0
  await page.route('**/api/paper/get?**', route => { details++; return route.fulfill({ json: envelope({ id: paperId, title: '处理中论文', authors: null, originalFilename: 'paper.pdf', pageCount: 4, status: jobs >= 2 ? 'ready' : 'processing', canAsk: jobs >= 2, jobId: '900', createTime: '', updateTime: '' }) }) })
  await page.route('**/api/paperProcessingJob/get?**', route => { jobs++; return route.fulfill({ json: envelope({ id: '900', paperId, status: jobs >= 2 ? 'completed' : 'running', currentStep: 'indexing', totalChunks: 10, indexedChunks: jobs >= 2 ? 10 : 5, retryCount: 0, updateTime: '' }) }) })
  await page.goto(`/library/${paperId}?tab=notes`)
  await expect(page.getByText('已写入 5 / 10 个片段')).toBeVisible()
  await expect(page.getByText('已写入 10 / 10 个片段')).toBeVisible({ timeout: 8000 })
  await expect(page.getByText('可用于问答', { exact: true })).toBeVisible()
  expect(details).toBeGreaterThanOrEqual(2)
})


test('long conversations keep the viewport fixed and disclose citations and run events in place', async ({ page }) => {
  const state = await fixtures(page)
  state.runStatus = 'completed'
  state.messages = [
    { id: '701', sessionId, role: 'user', runId: '700', messageType: 'text', content: '比较论文方法并列出证据', createTime: '2026-11-07T12:00:00' },
    { id: '703', sessionId, role: 'assistant', runId: '700', messageType: 'text', content: Array.from({ length: 60 }, (_, i) => `第 ${i + 1} 段研究结论：方法与实验的详细比较。`).join('\n\n'), createTime: '2026-11-07T12:00:01' },
  ]
  state.runEvents = Array.from({ length: 30 }, (_, i) => ({ runId: '700', eventId: String(i + 1), type: 'tool_completed', timestamp: '', payload: { callId: String(i), toolName: 'search_paper_chunks', elapsedMs: 20 } }))
  await page.route('**/api/messageCitation/list**', route => route.fulfill({ json: envelope(Array.from({ length: 25 }, (_, i) => ({ id: String(800 + i), messageId: '703', evidenceId: `E${i + 1}`, paperId, paperTitle: '大量引用测试论文', pageStart: 3, pageEnd: 4, quotedText: '用于验证长摘录不会自动展开。'.repeat(100) }))) }))
  await page.goto(`/chat/${sessionId}`)
  const sources = page.locator('.citation-sources'), progress = page.locator('.run-progress')
  await expect(sources.locator(':scope > summary')).toContainText('1 篇论文 · 25 条引用')
  await expect(sources).not.toHaveAttribute('open')
  await expect(progress).not.toHaveAttribute('open')
  await expect(page.locator('.chat-messages .chat-run')).toHaveCount(1)
  expect(await page.locator('.chat-message-list').evaluate(element => Array.from(element.children).map(child => child.className))).toEqual(['message user', 'chat-run', 'message assistant'])
  for (const viewport of [{ width: 1920, height: 1080 }, { width: 1366, height: 768 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport)
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight <= innerHeight && document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const composer = await page.locator('.composer-box').boundingBox()
    expect(composer).not.toBeNull()
    expect(composer!.y + composer!.height).toBeLessThanOrEqual(viewport.height)
    const scroller = page.locator('.chat-messages')
    expect(await scroller.evaluate(element => element.scrollHeight > element.clientHeight && element.clientHeight > 0)).toBe(true)
  }
  await page.setViewportSize({ width: 1366, height: 768 })
  await sources.locator(':scope > summary').click()
  await expect(sources.locator('.citation-card')).toHaveCount(25)
  await expect(sources.locator('blockquote').first()).not.toBeVisible()
  await sources.locator('.citation-excerpt > summary').first().click()
  await expect(sources.locator('blockquote').first()).toBeVisible()
  await expect(sources.locator('blockquote').nth(1)).not.toBeVisible()
  await sources.locator(':scope > summary').click()
  await page.locator('.chat-messages').evaluate(element => { element.scrollTop = 0 })
  await expect(page.getByRole('button', { name: '回到最新消息' })).toBeVisible()
  await progress.locator(':scope > summary').click()
  await expect(progress.getByRole('listitem')).toHaveCount(30)
  await expect.poll(() => page.locator('.chat-messages').evaluate(element => element.scrollTop)).toBeLessThan(200)
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollHeight <= innerHeight)).toBe(true)
  await page.screenshot({ path: 'test-results/chat-inline-progress.png' })
  await progress.locator(':scope > summary').click()
  await page.getByRole('button', { name: '回到最新消息' }).click()
  await expect(page.getByRole('button', { name: '回到最新消息' })).not.toBeVisible()
  await page.screenshot({ path: 'test-results/chat-collapsed-sources.png' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.screenshot({ path: 'test-results/chat-fixed-mobile.png' })
  expect(state.unhandled).toEqual([])
})


test('each chat turn restores its own skill and all tools and keeps them when the next run starts', async ({ page }) => {
  test.setTimeout(45000)
  const state = await fixtures(page)
  const errors: string[] = [], historyCursors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  const message = (id: string, runId: string, role: string) => ({ id, sessionId, runId, role, messageType: 'text', content: `${runId} 的${role === 'user' ? '问题' : '回答'}`, createTime: '2026-11-07T12:00:00' })
  state.messages = [message('691', '699', 'user'), message('692', '699', 'assistant'), message('701', '700', 'user')]
  state.session.activeRunId = '700'
  const event = (runId: string, eventId: number, type: string, payload: Record<string, unknown>): RunEvent => ({ runId, eventId: String(eventId), type, timestamp: '', payload })
  const history = [event('699', 1, 'skill_selected', { skillId: 'paper-summary' })]
  for (let i = 0; i < 52; i++) {
    history.push(event('699', 2 + i * 2, 'tool_started', { callId: `call-${i}`, toolName: 'search_paper_chunks' }))
    history.push(event('699', 3 + i * 2, 'tool_completed', { callId: `call-${i}`, toolName: 'search_paper_chunks', elapsedMs: i + 10 }))
  }
  state.runEvents = [event('700', 1, 'skill_selected', { skillId: 'paper-qa' }), event('700', 2, 'tool_started', { callId: 'call-0', toolName: 'get_paper_metadata' })]
  const nextEvents = [event('800', 1, 'skill_selected', { skillId: 'paper-summary' }), event('800', 2, 'tool_started', { callId: 'call-0', toolName: 'get_paper_metadata' })]
  const eventsFor = (id: string) => id === '699' ? history : id === '700' ? state.runEvents : nextEvents
  let failHistory = true
  await page.route('**/api/agentRun/get?**', route => {
    const id = new URL(route.request().url()).searchParams.get('id')!
    return route.fulfill({ json: envelope({ runId: id, sessionId, status: id === '699' ? 'completed' : id === '700' ? state.runStatus : 'running', eventSeq: eventsFor(id).at(-1)!.eventId, citations: [] }) })
  })
  await page.route('**/api/runEvent/list?**', route => {
    const params = new URL(route.request().url()).searchParams, id = params.get('runId')!, after = params.get('afterEventSeq') || '0'
    if (id === '699') {
      historyCursors.push(after)
      if (failHistory) return route.fulfill({ json: envelope(null, 50000, '历史事件读取暂时失败') })
    }
    return route.fulfill({ json: envelope(eventsFor(id).filter(item => BigInt(item.eventId) > BigInt(after)).slice(0, 100)) })
  })
  await page.route('**/api/agentRun/add', route => {
    state.session.activeRunId = '800'
    state.messages.push(message('801', '800', 'user'))
    return route.fulfill({ json: envelope('800') })
  })
  await page.goto(`/chat/${sessionId}`)
  const oldRun = page.locator('[data-run-id="699"]'), currentRun = page.locator('[data-run-id="700"]')
  await expect(page.getByRole('heading', { name: '测试研究会话', exact: true })).toBeVisible()
  await expect(page.locator('.chat-run')).toHaveCount(2)
  await expect(oldRun).toContainText('历史事件读取暂时失败')
  failHistory = false
  await oldRun.getByRole('button', { name: '重试' }).click()
  await expect(oldRun.locator('.run-progress')).not.toHaveAttribute('open')
  await oldRun.locator('.run-progress > summary').click()
  await currentRun.locator('.run-progress > summary').click()
  await expect(oldRun.locator('.skill-selection')).toHaveText('已选择技能：单篇论文总结')
  await expect(oldRun.getByRole('listitem')).toHaveCount(52)
  expect(historyCursors).toContain('100')
  await expect(currentRun.locator('.skill-selection')).toHaveText('已选择技能：论文问答')
  await expect(currentRun.getByRole('listitem')).toHaveCount(1)
  await expect(currentRun.locator('li.running')).toContainText('读取论文信息')
  state.runEvents.push(event('700', 3, 'tool_completed', { callId: 'call-0', toolName: 'get_paper_metadata', elapsedMs: 17 }), event('700', 4, 'tool_started', { callId: 'call-1', toolName: 'search_paper_chunks' }))
  await expect(currentRun.getByRole('listitem')).toHaveCount(2, { timeout: 10000 })
  await expect(currentRun.locator('li.completed')).toContainText('17 ms')
  await expect(currentRun.locator('li.running')).toContainText('检索论文原文')
  state.runEvents.push(event('700', 5, 'tool_completed', { callId: 'call-1', toolName: 'search_paper_chunks', elapsedMs: 23 }))
  state.runStatus = 'completed'; state.session.activeRunId = null
  state.messages.push(message('703', '700', 'assistant'))
  await expect(page.getByRole('button', { name: '发送问题' })).toBeVisible({ timeout: 10000 })
  await page.getByLabel('研究问题', { exact: true }).fill('继续研究')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.locator('.chat-run')).toHaveCount(3)
  await expect(currentRun.locator('.run-progress')).not.toHaveAttribute('open')
  await currentRun.locator('.run-progress > summary').click()
  await expect(currentRun.getByRole('listitem')).toHaveCount(2)
  await expect(currentRun.locator('li.completed')).toHaveCount(2)
  await expect(oldRun.getByRole('listitem')).toHaveCount(52)
  await expect(page.locator('[data-run-id="800"] .skill-selection')).toHaveText('已选择技能：单篇论文总结')
  await page.reload()
  await expect(page.locator('.chat-run')).toHaveCount(3)
  await oldRun.locator('.run-progress > summary').click()
  await currentRun.locator('.run-progress > summary').click()
  await expect(oldRun.getByRole('listitem')).toHaveCount(52)
  await expect(currentRun.locator('.skill-selection')).toHaveText('已选择技能：论文问答')
  await expect(currentRun.locator('li.completed')).toHaveCount(2)
  await expect(page.locator('[data-run-id="800"] li.running')).toHaveCount(1)
  expect(errors).toEqual([])
  expect(state.unhandled).toEqual([])
})


test('recycle and restore through library, preserve old chat and hide from picker', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto('/library')
  await page.getByRole('checkbox', { name: '选择 测试论文' }).check()
  page.on('dialog', dialog => dialog.accept())
  await page.getByRole('button', { name: '回收', exact: true }).click()
  await expect(page.getByText('已选 0 篇 · 查看选择')).toBeVisible()
  await expect(page.getByText('没有找到论文', { exact: true })).toBeVisible()
  expect(state.recycled).toBe(1)
  await page.getByRole('button', { name: '回收站', exact: true }).click()
  await expect(page.getByRole('button', { name: '恢复', exact: true })).toBeVisible()
  await expect(page.getByRole('checkbox')).toHaveCount(0)
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByText('该会话关联的部分论文已移入回收站', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '调整资料', exact: true }).click()
  await expect(page.getByText('没有找到论文', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '已选论文（1）', exact: true }).click()
  await expect(page.getByText('已回收（当前会话仍可使用）', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '取消', exact: true }).click()
  await page.goto('/library?view=recycle')
  await page.getByRole('button', { name: '恢复', exact: true }).click()
  await expect(page.getByText('回收站为空', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回论文库', exact: true }).click()
  await expect(page.getByRole('checkbox', { name: '选择 测试论文' })).toBeVisible()
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByText('该会话关联的部分论文已移入回收站', { exact: false })).toHaveCount(0)
})

test('reading workspace expands navigation, renders math, links evidence and keeps the composer clear', async ({ page, context }) => {
  const state = await fixtures(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()) })
  state.runStatus = 'completed'
  state.messages = [
    { id: '701', sessionId, role: 'user', runId: '700', messageType: 'text', content: '这篇论文的方法与实验结果说明了什么？', createTime: '2026-11-07T12:00:00' },
    { id: '703', sessionId, role: 'assistant', runId: '700', messageType: 'text', content: '# 方法与实验解读\n\n论文通过检索相关证据，为生成过程提供可追溯的上下文。研究结论需要结合实验条件理解，尤其注意数据分布、评估指标和对照设置。[E1]\n\n## 核心方法\n\n- 检索与当前问题相关的原文片段。\n- 将上下文与问题联合建模，并在结论中标明证据。\n\n优化目标为 $L = -\\log p(y|x)$。\n\n$$\n\\frac{1}{N}\\sum_{i=1}^{N} x_i^2\n$$\n\n### 对照结果\n\n| 方法 | 证据覆盖 | 可追溯性 |\n| --- | --- | --- |\n| 基线 | 部分 | 有限 |\n| 论文方法 | 更充分 | 原文定位 |\n\n```python\nscore = sum(evidence_scores) / len(evidence_scores)\n# [E1] stays code\n```\n\n需要进一步验证跨领域表现与检索错误的影响。', createTime: '2026-11-07T12:00:01' },
  ]
  state.runEvents = [
    { runId: '700', eventId: '1', type: 'skill_selected', timestamp: '', payload: { skillId: 'paper-qa' } },
    ...Array.from({ length: 4 }, (_, i) => ({ runId: '700', eventId: String(i + 2), type: 'tool_completed', timestamp: '', payload: { callId: String(i), toolName: 'search_paper_chunks', elapsedMs: 20 } })),
  ]
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/chat/${sessionId}`)
  await expect(page.locator('.markdown math')).toHaveCount(2)
  await expect(page.locator('.markdown script, .markdown [style]')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '查看引用 E1', exact: true })).toHaveCount(1)
  await expect(page.locator('.quick-prompts')).toHaveCount(0)
  await expect(page.locator('.chat-aside')).toHaveCount(0)
  await page.getByRole('button', { name: '折叠导航', exact: true }).click()
  await expect.poll(() => page.locator('.sidebar').evaluate(element => element.getBoundingClientRect().width)).toBe(68)
  const body = page.locator('.message.assistant .markdown')
  const width = (await body.boundingBox())!.width
  expect(width).toBeGreaterThanOrEqual(1000)
  expect(width).toBeLessThanOrEqual(1100)
  expect(await body.evaluate(element => [getComputedStyle(element).fontSize, getComputedStyle(element).lineHeight])).toEqual(['16px', '28px'])
  await page.locator('.chat-messages').evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: 'test-results/chat-reading-desktop.png' })
  await page.getByRole('button', { name: '1 篇论文', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '当前资料' })).toBeVisible()
  await expect(page.getByRole('dialog').getByRole('link', { name: '测试论文', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '1 篇论文', exact: true })).toBeFocused()
  await page.getByRole('button', { name: '查看引用 E1', exact: true }).click()
  await expect(page.locator('.citation-excerpt')).toHaveAttribute('open')
  await expect(page.locator('.citation-excerpt blockquote')).toBeVisible()
  await expect(page.locator('.citation-excerpt')).toContainText('Methods')
  await expect(page.getByRole('link', { name: /PDF 物理第 3–4 页/ })).toHaveAttribute('href', `/library/${paperId}?page=3`)
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await page.getByRole('button', { name: '复制回答', exact: true }).click()
  expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(state.messages[1]!.content)
  const draft = page.getByRole('textbox', { name: '研究问题', exact: true })
  const initialHeight = (await draft.boundingBox())!.height
  await draft.fill(Array.from({ length: 10 }, (_, i) => `研究问题 ${i}`).join('\n'))
  expect((await draft.boundingBox())!.height).toBeGreaterThan(initialHeight)
  const viewport = await page.locator('.chat-messages').boundingBox(), composer = await page.locator('.chat-composer').boundingBox()
  expect(viewport!.y + viewport!.height).toBeLessThanOrEqual(composer!.y + 1)
  await page.reload()
  await expect(page.getByRole('button', { name: '展开导航' })).toBeVisible()
  for (const width of [1920, 1440, 1280, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight)).toBe(true)
    expect(await page.locator('.chat-messages').evaluate(element => element.clientHeight)).toBeGreaterThan(100)
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await draft.fill('')
  await page.locator('.chat-messages').evaluate(element => { element.scrollTop = 0 })
  await page.screenshot({ path: 'test-results/chat-reading-mobile.png' })
  await page.getByLabel('账户菜单', { exact: true }).click()
  await expect(page.getByRole('link', { name: '个人设置', exact: true })).toBeVisible()
  expect(errors).toEqual([]); expect(state.unhandled).toEqual([])
})

test('named SSE events update compact progress and completion collapses an expanded trace', async ({ page }) => {
  const state = await fixtures(page)
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  let finish = false, frames = 0
  const event = (eventId: number, type: string, payload: Record<string, unknown>) => ({ runId: '700', eventId: String(eventId), type, timestamp: '', payload })
  await page.route('**/api/agentRun/events?**', async route => {
    frames++
    const events = finish ? [event(3, 'tool_completed', { callId: 'a', toolName: 'read_paper_section', elapsedMs: 120 }), event(4, 'run_completed', {})]
      : [event(1, 'skill_selected', { skillId: 'paper-qa' }), event(2, 'tool_started', { callId: 'a', toolName: 'read_paper_section' })]
    if (finish) {
      state.runStatus = 'completed'; state.session.activeRunId = null
      state.messages.push({ id: '703', sessionId, role: 'assistant', runId: '700', messageType: 'text', content: '来自 SSE 完成事件的回答。[E1]', createTime: '2026-11-07T12:00:01' })
    }
    await route.fulfill({ contentType: 'text/event-stream', body: events.map(item => `id: ${item.eventId}\nevent: ${item.type}\ndata: ${JSON.stringify(item)}\n\n`).join('') })
  })
  await page.goto(`/chat/${sessionId}`)
  await page.getByLabel('回答模式', { exact: true }).selectOption('agent')
  const draft = page.getByLabel('研究问题', { exact: true })
  await draft.fill('分析研究方法')
  await draft.press('Shift+Enter')
  await expect(draft).toHaveValue('分析研究方法\n')
  expect(state.creates).toHaveLength(0)
  await draft.press('Enter')
  await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible()
  await expect(page.getByLabel('回答模式', { exact: true })).toBeDisabled()
  const progress = page.locator('.run-progress')
  await expect(progress.locator('summary')).toContainText('1 次工具调用')
  await expect(progress).not.toHaveAttribute('open')
  await progress.locator('summary').click()
  await expect(progress.getByRole('listitem')).toContainText('读取论文章节')
  await expect(progress.locator('.skill-selection')).toContainText('论文问答')
  finish = true
  await expect(page.locator('.message.assistant')).toContainText('来自 SSE 完成事件的回答。', { timeout: 15000 })
  await expect(progress).not.toHaveAttribute('open')
  await expect(page.getByRole('button', { name: '停止任务' })).toHaveCount(0)
  await expect(page.getByLabel('回答模式', { exact: true })).toBeEnabled()
  await progress.locator('summary').click()
  await expect(progress.getByRole('listitem')).toContainText('120 ms')
  expect(frames).toBeGreaterThanOrEqual(2)
  expect(state.creates).toHaveLength(1)
  expect(state.creates[0]?.executorType).toBe('agent')
  expect(errors).toEqual([]); expect(state.unhandled).toEqual([])
})

test('failed tasks and draft errors remain visible with disclosure closed; chat upload reuses the existing dialog', async ({ page }) => {
  const state = await fixtures(page)
  state.runStatus = 'failed'
  state.messages = [{ id: '701', sessionId, role: 'user', runId: '700', messageType: 'text', content: '失败的问题', createTime: '2026-11-07T12:00:00' }]
  await page.route('**/api/agentRun/get?**', route => route.fulfill({ json: envelope({ runId: '700', sessionId, status: 'failed', eventSeq: '0', errorMessage: '检索服务暂时不可用', citations: [] }) }))
  await page.route('**/api/chatSession/update', route => route.fulfill({ json: envelope(null, 50000, '草稿保存服务不可用') }))
  await page.goto(`/chat/${sessionId}`)
  await expect(page.locator('.run-progress')).not.toHaveAttribute('open')
  await expect(page.getByRole('alert')).toContainText('检索服务暂时不可用')
  await expect(page.getByRole('button', { name: '重新执行此问题' })).toBeVisible()
  await page.getByLabel('研究问题', { exact: true }).fill('不能丢失的草稿')
  await expect(page.locator('.draft-error')).toContainText('草稿保存服务不可用')
  await expect(page.getByRole('button', { name: '重新保存', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '上传 PDF 论文', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '导入论文' })).toBeVisible()
  await expect(page.getByRole('dialog')).toContainText('通过「调整资料」将论文加入当前会话')
  await page.locator('input[type=file]').setInputFiles({ name: 'invalid.txt', mimeType: 'text/plain', buffer: Buffer.from('invalid') })
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('不超过 50 MB')
  await page.keyboard.press('Escape')
  await expect(page.getByLabel('研究问题', { exact: true })).toHaveValue('不能丢失的草稿')
  expect(state.unhandled).toEqual([])
})

test('reading workspace preserves drafts, passage and selectable text while panels change', async ({ page }) => {
  const state = await fixtures(page), errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`/library/${paperId}?page=2`)
  const sheet = page.locator('[data-page="2"]')
  await expect(sheet.locator('canvas')).toBeVisible()
  await expect(sheet.locator('.textLayer')).toContainText('Research paper fixture')
  // Select the actual text overlay, rather than relying only on a canvas screenshot.
  const selected = await sheet.locator('.textLayer').evaluate(element => {
    const selection = window.getSelection()!, range = document.createRange()
    range.selectNodeContents(element); selection.removeAllRanges(); selection.addRange(range)
    return selection.toString()
  })
  expect(selected).toContain('Research paper fixture')
  await page.evaluate(() => window.getSelection()?.removeAllRanges())
  const note = page.getByLabel('研究问题', { exact: false })
  await note.fill('边读边记，收起侧栏也要保存')
  const readingWidth = (await page.locator('.pdf-canvas').boundingBox())!.width
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click()
  await expect.poll(() => state.notes.researchQuestion).toBe('边读边记，收起侧栏也要保存')
  await expect.poll(async () => (await page.locator('.pdf-canvas').boundingBox())!.width).toBeGreaterThan(readingWidth)
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('2')
  await page.getByRole('button', { name: '阅读笔记', exact: true }).click()
  await expect(note).toHaveValue('边读边记，收起侧栏也要保存')
  state.conflict = true
  await note.fill('冲突草稿必须保留')
  await expect(page.getByText('版本冲突 · 自动保存已暂停')).toBeVisible()
  await page.getByRole('button', { name: '论文信息', exact: true }).click()
  await expect(page.getByRole('heading', { name: '论文摘要' })).toBeVisible()
  await page.getByRole('button', { name: '阅读笔记', exact: true }).click()
  await expect(note).toHaveValue('冲突草稿必须保留')
  await page.getByRole('button', { name: '专注阅读', exact: true }).click()
  await expect(page.locator('.reader-side-panel')).toBeHidden()
  await page.keyboard.press('Escape')
  await expect(note).toHaveValue('冲突草稿必须保留')
  await page.locator('.pdf-canvas').evaluate(area => {
    const target = area.querySelector<HTMLElement>('[data-page="3"]')!
    area.scrollTop += target.getBoundingClientRect().top - area.getBoundingClientRect().top - 24
  })
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('3')
  await expect(page.locator('[data-page="3"] canvas')).toBeVisible()
  await page.getByRole('button', { name: '放大', exact: true }).click()
  await expect(page.getByRole('button', { name: '适应宽度', exact: true })).toHaveAttribute('aria-pressed', 'false')
  await page.getByRole('button', { name: '适应宽度', exact: true }).click()
  await expect(page.getByRole('spinbutton', { name: 'PDF 物理页码' })).toHaveValue('3')
  const pageInput = page.getByRole('spinbutton', { name: 'PDF 物理页码' })
  await pageInput.fill('999'); await pageInput.press('Enter')
  await expect(pageInput).toHaveValue('4')
  await pageInput.fill('0'); await pageInput.press('Enter')
  await expect(pageInput).toHaveValue('1')
  expect(errors).toEqual([]); expect(state.unhandled).toEqual([])
  page.on('dialog', dialog => dialog.accept())
})

test('reader fits desktop and mobile viewports and restores fit after resizing', async ({ page }) => {
  await fixtures(page)
  await page.goto(`/library/${paperId}`)
  await expect(page.locator('[data-page="1"] canvas')).toBeVisible()
  await page.screenshot({ path: 'test-results/reader-fixture-desktop.png' })
  for (const width of [1920, 1440, 1280, 900, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    if (await page.getByRole('button', { name: '收起侧栏', exact: true }).isVisible()) await page.getByRole('button', { name: '收起侧栏', exact: true }).click()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight)).toBe(true)
    await expect.poll(() => page.locator('.pdf-canvas').evaluate(area => area.scrollWidth <= area.clientWidth)).toBe(true)
    const sheet = (await page.locator('[data-page="1"]').boundingBox())!
    expect(sheet.y).toBeLessThan(200)
    await expect(page.getByRole('button', { name: '专注阅读', exact: true })).toBeInViewport()
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '阅读笔记', exact: true }).click()
  await expect(page.getByLabel('研究问题', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click()
  const pageInput = page.getByRole('spinbutton', { name: 'PDF 物理页码' })
  await pageInput.fill('4'); await pageInput.press('Enter')
  await expect(page.locator('[data-page="4"] canvas')).toBeVisible()
  await expect(pageInput).toHaveValue('4')
  await pageInput.fill('1'); await pageInput.press('Enter')
  await page.screenshot({ path: 'test-results/reader-fixture-mobile.png' })
})

test('chat entry opens the latest conversation and sidebar switches without losing drafts', async ({ page }) => {
  const state = await fixtures(page)
  state.session.inputDraft = '继续比较论文的实验结果'
  state.messages = [{ id: '701', sessionId, role: 'assistant', messageType: 'text', content: '这是最近一次讨论中保存的回答。', createTime: '2026-11-12T10:00:00' }]
  const names = ['测试研究会话', 'RAG 检索策略比较', '多智能体协作论文阅读', '实验方法与评价指标', '论文创新点总结', '本周研究问题', '模型推理与工具调用', '跨论文证据核查']
  const records = names.map((name, i) => ({ ...state.session, id: i ? String(900 + i) : sessionId, name, inputDraft: i ? '' : state.session.inputDraft }))
  await page.route('**/api/chatSession/list/page', route => route.fulfill({ json: envelope(paged(records.filter(item => item.name.includes(route.request().postDataJSON().name || '')))) }))
  await page.route('**/api/chatSession/get?**', route => route.fulfill({ json: envelope(records.find(item => item.id === new URL(route.request().url()).searchParams.get('id'))) }))
  await page.route('**/api/chatSession/update', async route => {
    const body = route.request().postDataJSON(); state.updates.push(body)
    Object.assign(records.find(item => item.id === body.id)!, body)
    await route.fulfill({ json: envelope(true) })
  })
  await page.goto('/chat')
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await expect(page.getByRole('heading', { name: '测试研究会话', exact: true })).toBeVisible()
  const draft = page.getByRole('textbox', { name: '研究问题', exact: true })
  await expect(draft).toHaveValue('继续比较论文的实验结果')
  await expect(page.locator('.markdown')).toContainText('保存的回答')
  const sidebar = page.locator('#chat-sidebar-content')
  await expect(sidebar.locator('[aria-current="page"]')).toContainText('测试研究会话')
  await expect(page.getByRole('button', { name: '切换会话' })).toHaveCount(0)
  await expect(page.locator('.session-home')).toHaveCount(0)
  await page.screenshot({ path: 'test-results/chat-sidebar-desktop.png' })
  await draft.fill('切换前必须保存的草稿')
  await sidebar.getByRole('button', { name: 'RAG 检索策略比较', exact: true }).click()
  await expect(page).toHaveURL(/\/chat\/901$/)
  expect(records[0]!.inputDraft).toBe('切换前必须保存的草稿')
  await expect(sidebar.locator('[aria-current="page"]')).toHaveText('RAG 检索策略比较')
  await expect(draft).toHaveValue('')
  await sidebar.getByRole('searchbox', { name: '搜索历史会话' }).fill('多智能体')
  await expect(sidebar.locator('li')).toHaveCount(1)
  await expect(page.getByRole('heading', { name: 'RAG 检索策略比较', exact: true })).toBeVisible()
  await sidebar.getByRole('button', { name: '清除搜索', exact: true }).click()
  await expect(sidebar.locator('li')).toHaveCount(8)
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(sidebar).toBeHidden()
  await page.getByRole('button', { name: '打开会话侧栏' }).click()
  const drawer = page.getByRole('dialog', { name: '最近会话' })
  await expect(drawer).toBeVisible()
  await page.screenshot({ path: 'test-results/chat-sidebar-mobile.png' })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '打开会话侧栏' })).toBeFocused()
  await page.getByRole('button', { name: '打开会话侧栏' }).click()
  await drawer.getByRole('button', { name: /测试研究会话/ }).click()
  await expect(drawer).toHaveCount(0)
  await expect(draft).toHaveValue('切换前必须保存的草稿')
  for (const width of [1920, 1280, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 844 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight)).toBe(true)
    await expect(page.getByRole('textbox', { name: '研究问题', exact: true })).toBeInViewport()
  }
})

test('chat entry distinguishes errors and an empty library and carries a new question forward', async ({ page }) => {
  const state = await fixtures(page)
  let failed = true, created: Record<string, unknown> | undefined
  await page.route('**/api/chatSession/list/page', route => route.fulfill({ json: failed ? envelope(null, 50000, '会话暂时无法加载') : envelope(paged(created ? [state.session] : [])) }))
  await page.route('**/api/chatSession/add', async route => {
    created = route.request().postDataJSON(); Object.assign(state.session, created)
    await route.fulfill({ json: envelope(sessionId) })
  })
  await page.goto('/chat?question=' + encodeURIComponent('这些论文有哪些值得继续研究的问题？'))
  await expect(page.locator('.chat-page').getByRole('alert')).toContainText('会话暂时无法加载')
  await expect(page.locator('.chat-welcome')).toHaveCount(0)
  failed = false
  await page.getByRole('button', { name: '重新加载最近会话' }).click()
  await expect(page.getByRole('heading', { name: '开始你的第一段研究讨论' })).toBeVisible()
  await page.getByRole('button', { name: '新建聊天室', exact: true }).click()
  await page.getByLabel('会话名称').fill('继续研究')
  await page.getByRole('dialog').getByRole('button', { name: '创建聊天室', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  expect(created).toEqual({ name: '继续研究', inputDraft: '这些论文有哪些值得继续研究的问题？' })
  await expect(page.getByRole('textbox', { name: '研究问题', exact: true })).toHaveValue('这些论文有哪些值得继续研究的问题？')
  await expect(page.locator('#chat-sidebar-content [aria-current="page"]')).toContainText('继续研究')
})

test('sidebar retries pagination without losing rows and ignores stale search responses', async ({ page }) => {
  const state = await fixtures(page)
  let failMore = true, slowStarted = false
  await page.route('**/api/chatSession/list/page', async route => {
    const { name, pageNum } = route.request().postDataJSON()
    if (name === '慢搜索') { slowStarted = true; await new Promise(resolve => setTimeout(resolve, 1000)) }
    if (name) return route.fulfill({ json: envelope(paged([{ ...state.session, id: '903', name }])) })
    if (pageNum === 2 && failMore) return route.fulfill({ json: envelope(null, 50000, '加载更多失败') })
    await route.fulfill({ json: envelope({ records: [{ ...state.session, id: pageNum === 1 ? sessionId : '902', name: `第 ${pageNum} 个会话` }], current: pageNum, pages: 2, total: 2 }) })
  })
  await page.goto(`/chat/${sessionId}`)
  const sidebar = page.locator('#chat-sidebar-content')
  await expect(sidebar.locator('li')).toHaveCount(1)
  await sidebar.getByRole('button', { name: '加载更多会话' }).click()
  await expect(sidebar.getByRole('alert')).toContainText('加载更多失败')
  await expect(sidebar.locator('li')).toHaveCount(1)
  failMore = false
  await sidebar.getByRole('button', { name: '重试', exact: true }).click()
  await expect(sidebar.locator('li')).toHaveCount(2)
  const search = sidebar.getByRole('searchbox', { name: '搜索历史会话' })
  await search.fill('慢搜索'); await expect.poll(() => slowStarted).toBe(true)
  await search.fill('新搜索')
  await expect(sidebar.locator('li')).toHaveText(['新搜索'])
  await page.waitForTimeout(1100)
  await expect(sidebar.locator('li')).toHaveText(['新搜索'])
  await search.fill('找不到')
  // Search is scoped to history; it must never change the open conversation.
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
})

test('direct chat links stay selected and deleting opens the next latest conversation', async ({ page }) => {
  const state = await fixtures(page)
  let records = [{ ...state.session, id: '901', name: '最近的会话' }, { ...state.session, name: '链接指定的会话' }]
  await page.route('**/api/chatSession/list/page', route => route.fulfill({ json: envelope(paged(records)) }))
  await page.route('**/api/chatSession/get?**', route => route.fulfill({ json: envelope(records.find(item => item.id === new URL(route.request().url()).searchParams.get('id'))) }))
  await page.route('**/api/chatSession/delete', async route => {
    records = records.filter(item => item.id !== route.request().postDataJSON().id)
    await route.fulfill({ json: envelope(true) })
  })
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByRole('heading', { name: '链接指定的会话', exact: true })).toBeVisible()
  await expect(page.locator('#chat-sidebar-content [aria-current="page"]')).toContainText('链接指定的会话')
  await page.getByLabel('会话操作', { exact: true }).click()
  await page.getByRole('button', { name: '删除会话', exact: true }).click()
  await page.getByRole('button', { name: '确认删除' }).click()
  await expect(page).toHaveURL(/\/chat\/901$/)
  await expect(page.getByRole('heading', { name: '最近的会话', exact: true })).toBeVisible()
  await expect(page.locator('#chat-sidebar-content li')).toHaveCount(1)
  await page.getByLabel('会话操作', { exact: true }).click()
  await page.getByRole('button', { name: '重命名会话' }).click()
  await page.getByLabel('会话名称').fill('新的研究主题')
  await page.route('**/api/chatSession/update', async route => {
    Object.assign(records[0]!, route.request().postDataJSON()); await route.fulfill({ json: envelope(true) })
  })
  await page.getByRole('button', { name: '保存名称' }).click()
  await expect(page.locator('#chat-sidebar-content [aria-current="page"]')).toHaveText('新的研究主题')
})

test('failed draft save can cancel a sidebar switch and late entry lookup cannot override a chosen chat', async ({ page }) => {
  const state = await fixtures(page)
  let entryRequests = 0
  await page.route('**/api/chatSession/list/page', async route => {
    entryRequests++
    if (entryRequests <= 2) await new Promise(resolve => setTimeout(resolve, 1200))
    await route.fulfill({ json: envelope(paged([{ ...state.session, id: '901', name: '最近会话' }, state.session])) })
  })
  await page.goto('/chat')
  // A deliberate route change while the entry request is in flight must win.
  await expect.poll(() => entryRequests).toBeGreaterThanOrEqual(1)
  await page.evaluate(id => { history.pushState({}, '', `/chat/${id}`); dispatchEvent(new PopStateEvent('popstate')) }, sessionId)
  await expect(page.getByRole('heading', { name: '测试研究会话', exact: true })).toBeVisible()
  await page.waitForTimeout(1300)
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await page.route('**/api/chatSession/update', route => route.fulfill({ json: envelope(null, 50000, '草稿暂时无法保存') }))
  const draft = page.getByRole('textbox', { name: '研究问题', exact: true })
  await draft.fill('不能丢失的草稿')
  page.once('dialog', dialog => dialog.dismiss())
  await page.locator('#chat-sidebar-content').getByRole('button', { name: '最近会话', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await expect(draft).toHaveValue('不能丢失的草稿')
  await expect(page.locator('#chat-sidebar-content [aria-current="page"]')).toContainText('测试研究会话')
  page.on('dialog', dialog => dialog.accept())
})

test('leaving chat during a slow restore does not reopen the entry route', async ({ page }) => {
  const state = await fixtures(page)
  let started = false, finished = false
  await page.route('**/api/chatSession/get?**', async route => {
    started = true
    await new Promise(resolve => setTimeout(resolve, 1000))
    await route.fulfill({ json: envelope(state.session) }); finished = true
  })
  await page.goto(`/chat/${sessionId}`)
  await expect.poll(() => started).toBe(true)
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '论文库', exact: true }).click()
  await expect(page).toHaveURL(/\/library$/)
  await expect.poll(() => finished).toBe(true)
  await page.waitForTimeout(300)
  await expect(page).toHaveURL(/\/library$/)
  await expect(page.locator('#chat-sidebar-content')).toBeVisible()
})

test('global sidebar keeps recent history and collapse preference across all workspaces', async ({ page }) => {
  await fixtures(page)
  await page.goto('/')
  const sidebar = page.locator('#chat-sidebar-content')
  await expect(sidebar.locator('li')).toHaveCount(1)
  await expect(page.getByRole('link', { name: '聊天室', exact: true })).toHaveCount(0)
  const newButton = page.locator('.sidebar > .global-new-session')
  const overview = page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '研究总览', exact: true })
  expect((await newButton.boundingBox())!.y).toBeLessThan((await overview.boundingBox())!.y)
  await sidebar.getByRole('button', { name: '收起最近会话' }).click()
  await expect(sidebar.locator('.session-sidebar-scroll')).toBeHidden()
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '论文库', exact: true }).click()
  await expect(page).toHaveURL(/\/library$/)
  await expect(sidebar.getByRole('button', { name: '展开最近会话' })).toHaveAttribute('aria-expanded', 'false')
  await page.reload()
  await expect(sidebar.getByRole('button', { name: '展开最近会话' })).toBeVisible()
  await sidebar.getByRole('button', { name: '展开最近会话' }).click()
  await page.getByRole('link', { name: '测试论文', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/library/${paperId}$`))
  await expect(sidebar.getByRole('button', { name: '测试研究会话', exact: true })).toBeVisible()
  await page.screenshot({ path: 'test-results/global-navigation-reader.png' })
  await sidebar.getByRole('button', { name: '测试研究会话', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await expect(sidebar.locator('[aria-current="page"]')).toContainText('测试研究会话')
  await page.getByRole('link', { name: '个人设置', exact: true }).click()
  await expect(sidebar.locator('[aria-current="page"]')).toHaveCount(0)
  await expect(sidebar.locator('li')).toHaveCount(1)
  await sidebar.getByRole('button', { name: '新建最近会话', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '新建聊天室', exact: true })).toBeVisible()
  await page.getByLabel('会话名称').fill('全局新建的讨论')
  await page.getByRole('dialog').getByRole('button', { name: '创建聊天室', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await page.screenshot({ path: 'test-results/global-navigation-chat.png' })
})

test('global history is available on mobile reader and note guards protect switching from history', async ({ page }) => {
  const state = await fixtures(page)
  await page.goto(`/library/${paperId}?tab=notes`)
  await page.getByLabel('研究问题', { exact: false }).fill('切换前应保存的论文笔记')
  await page.locator('#chat-sidebar-content').getByRole('button', { name: '测试研究会话', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  expect(state.notes.researchQuestion).toBe('切换前应保存的论文笔记')
  await page.goto(`/library/${paperId}`)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('button', { name: '打开会话侧栏' }).click()
  const drawer = page.getByRole('dialog', { name: '最近会话' })
  await expect(drawer.getByRole('button', { name: '测试研究会话', exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: '收起最近会话' }).click()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: '打开会话侧栏' })).toBeFocused()
  await page.getByRole('button', { name: '打开会话侧栏' }).click()
  await expect(drawer.getByRole('button', { name: '展开最近会话' })).toHaveAttribute('aria-expanded', 'false')
  await drawer.getByRole('button', { name: '展开最近会话' }).click()
  await drawer.getByRole('button', { name: '测试研究会话', exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/chat/${sessionId}$`))
  await expect(drawer).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
