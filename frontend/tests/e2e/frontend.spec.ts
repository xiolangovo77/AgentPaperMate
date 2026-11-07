import { expect, test, type Page, type Route } from '@playwright/test'

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
  }
  await page.route('**/api/**', async (route: Route) => {
    if (!new URL(route.request().url()).pathname.startsWith('/api/')) return route.continue()
    const request = route.request(), path = new URL(request.url()).pathname.slice(4)
    const body = request.method() === 'POST' && request.postData() ? request.postDataJSON() : {}
    const ok = (data: unknown) => route.fulfill({ json: envelope(data) })
    if (state.expire) return route.fulfill({ json: envelope(null, 40100, '未登录') })
    if (path === '/appUser/get') return ok({ id: '10', username: 'test_researcher', nickname: '测试研究者' })
    if (path === '/chatSession/list/page') return ok(paged([state.session]))
    if (path === '/chatSession/get') return ok(state.session)
    if (path === '/chatSession/add') return ok(sessionId)
    if (path === '/chatSession/update') { state.updates.push(body); Object.assign(state.session, body); return ok(true) }
    if (path === '/sessionPaper/list/page') return ok(paged(state.papers))
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
    if (path === '/agentRun/get') return ok({ runId: '700', sessionId, status: state.runStatus, eventSeq: '0', citations: [] })
    if (path === '/agentRun/cancel') { state.runStatus = 'cancelled'; state.session.activeRunId = null; return ok('cancelled') }
    if (path === '/runEvent/list') return ok([])
    if (path === '/agentRun/events') return route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' })
    if (path === '/messageCitation/list') return ok([{ id: '702', messageId: '701', evidenceId: 'E1', paperId, paperTitle: '已校验的引用标题', pageStart: 3, pageEnd: 4, quotedText: '来自论文的真实格式摘录。', sourceLocation: 'Methods' }])
    state.unhandled.push(path)
    return route.fulfill({ status: 404, json: envelope(null, 40400, `Unexpected test endpoint: ${path}`) })
  })
  return state
}

test('overview, explicit capability gaps and responsive layouts', async ({ page }) => {
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
  await expect(page.getByRole('textbox', { name: '搜索论文' })).toBeDisabled()
  await expect(page.getByText('论文库正在等待查询能力接通')).toBeVisible()
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

test('chat drafts, individual scope updates, empty scope, and responsive room', async ({ page }) => {
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
  await page.getByRole('button', { name: '移出', exact: true }).click()
  await expect.poll(() => state.papers.length).toBe(0)
  await page.getByRole('button', { name: '完成', exact: true }).click()
  await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
  await page.getByRole('button', { name: '调整资料' }).click()
  await page.getByLabel('按已知论文 ID 添加一篇').fill(paperId)
  await page.getByRole('button', { name: '添加', exact: true }).click()
  await expect.poll(() => state.papers.length).toBe(1)
  await page.getByRole('button', { name: '完成', exact: true }).click()
  await expect(page.getByRole('button', { name: '发送问题' })).toBeEnabled()
  expect(state.unhandled).toEqual([])
})

test('ambiguous Run creation retries the same idempotency key and cancel uses the backend', async ({ page }) => {
  const state = await fixtures(page); state.failCreate = true
  await page.goto(`/chat/${sessionId}`)
  await page.getByRole('textbox', { name: '研究问题', exact: true }).fill('总结论文')
  await page.getByRole('button', { name: '发送问题' }).click()
  await expect(page.getByRole('button', { name: '确认提交结果' })).toBeVisible()
  await page.getByRole('button', { name: '确认提交结果' }).click()
  await expect(page.getByRole('button', { name: '停止任务' })).toBeVisible()
  expect(state.creates).toHaveLength(2)
  expect(state.creates[0]).toEqual(state.creates[1])
  await page.getByRole('button', { name: '停止任务' }).click()
  await expect(page.getByText('任务已取消', { exact: true })).toBeVisible()
  expect(state.runStatus).toBe('cancelled')
})

test('sanitizes answers and uses verified citations for physical-page links', async ({ page }) => {
  const state = await fixtures(page)
  state.messages = [{ id: '701', sessionId, role: 'assistant', messageType: 'text', content: '## 研究结论\n<script>window.attacked=true</script><img src=x onerror="window.attacked=true">\n[危险链接](javascript:alert(1))\n\n```java\nSystem.out.println("paper");\n```', createTime: '2026-11-07T12:00:00' }]
  await page.goto(`/chat/${sessionId}`)
  await expect(page.getByText('已校验的引用标题', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => (window as unknown as { attacked?: boolean }).attacked)).toBeUndefined()
  await expect(page.locator('.markdown script, .markdown img, .markdown a[href^="javascript:"]')).toHaveCount(0)
  await page.getByRole('link', { name: /PDF 物理第 3–4 页/ }).click()
  await expect(page).toHaveURL(new RegExp(`/library/${paperId}\\?page=3`))
  await expect(page.getByText('引用目标：PDF 物理第 3 页')).toBeVisible()
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
  await expect(page.locator('dialog')).toContainText('尚无处理状态查询接口')
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
