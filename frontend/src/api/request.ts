import axios, { type AxiosRequestConfig } from 'axios'
import type { BaseResponse } from '../types/api'

export const apiBase = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')
export class ApiError extends Error {
  constructor(message: string, public code = 0, public httpStatus = 0) { super(message); this.name = 'ApiError' }
  get unauthorized() { return this.code === 40100 || this.httpStatus === 401 }
  get conflict() { return this.code === 40900 || this.httpStatus === 409 }
}
export const http = axios.create({ baseURL: apiBase, withCredentials: true, timeout: 30000 })
let onUnauthorized: (() => void) | undefined
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler }
export function messageOf(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试。' }
export async function request<T>(config: AxiosRequestConfig, quietAuth = false): Promise<T> {
  try {
    const response = await http.request<BaseResponse<T>>(config)
    if (!response.data || typeof response.data.code !== 'number') throw new ApiError('服务返回格式异常，请检查 API 地址。')
    if (response.data.code !== 0) throw new ApiError(response.data.message || '请求失败', response.data.code, response.status)
    return response.data.data
  } catch (error) {
    let result: ApiError
    if (error instanceof ApiError) result = error
    else if (axios.isAxiosError(error)) {
      const status = error.response?.status || 0
      const fallback: Record<number, string> = { 401: '登录已失效，请重新登录。', 403: '没有访问权限。', 404: '请求的数据或接口不存在。', 409: '数据已发生变化，请刷新后重试。', 429: '请求过于频繁，请稍后重试。', 500: '服务暂时不可用，请稍后重试。' }
      result = new ApiError(error.response?.data?.message || fallback[status] || (error.code === 'ECONNABORTED' ? '请求超时，请检查网络后重试。' : '无法连接服务，请确认后端已启动。'), error.response?.data?.code, status)
    } else result = new ApiError(messageOf(error))
    if (result.unauthorized && !quietAuth) onUnauthorized?.()
    throw result
  }
}
export function get<T>(url: string, params?: Record<string, unknown>) { return request<T>({ url, params, method: 'GET' }) }
export function post<T>(url: string, data?: unknown) { return request<T>({ url, data, method: 'POST' }) }
