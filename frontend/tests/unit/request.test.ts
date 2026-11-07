import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, http, request, setUnauthorizedHandler } from '../../src/api/request'
import { validId, validPassword } from '../../src/utils/format'
afterEach(() => vi.restoreAllMocks())
describe('backend contract', () => {
  it('retains serialized Long IDs without Number conversion', async () => {
    vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: { code: 0, data: '9223372036854775807', message: 'ok' } })
    expect(await request({ url: '/chatSession/add' })).toBe('9223372036854775807')
  })
  it('recognizes a business conflict even when HTTP is 200', async () => {
    vi.spyOn(http, 'request').mockResolvedValue({ status: 200, data: { code: 40900, data: null, message: '笔记已更新' } })
    await expect(request({ url: '/readingNote/update' })).rejects.toMatchObject({ conflict: true, code: 40900 })
  })
  it('only treats unauthenticated responses as expired login', async () => {
    const callback = vi.fn(); setUnauthorizedHandler(callback)
    const spy = vi.spyOn(http, 'request')
    spy.mockResolvedValue({ status: 200, data: { code: 40101, data: null, message: '无权限' } })
    await expect(request({ url: '/agentRun/get' })).rejects.toBeInstanceOf(ApiError)
    expect(callback).not.toHaveBeenCalled()
    spy.mockResolvedValue({ status: 200, data: { code: 40100, data: null, message: '未登录' } })
    await expect(request({ url: '/appUser/get' }, true)).rejects.toBeInstanceOf(ApiError)
    expect(callback).not.toHaveBeenCalled()
    await expect(request({ url: '/chatSession/get' })).rejects.toBeInstanceOf(ApiError)
    expect(callback).toHaveBeenCalledOnce()
  })
  it('validates backend signed Long limits and BCrypt UTF-8 byte limits', () => {
    expect(validId('9223372036854775807')).toBe(true)
    expect(validId('9223372036854775808')).toBe(false)
    expect(validId('-1')).toBe(false)
    expect(validPassword('密'.repeat(24))).toBe(true)
    expect(validPassword('密'.repeat(25))).toBe(false)
  })
})
