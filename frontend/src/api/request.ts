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
export function reportApiError(error: ApiError): never { if (error.unauthorized) onUnauthorized?.(); throw error }
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

/** The PDF endpoint returns bytes on success, but BaseResponse JSON on business errors. */
export async function getPdf(id: string, signal?: AbortSignal): Promise<Uint8Array> {
  try {
    const response = await http.get<ArrayBuffer>('/paper/file', { params: { id }, responseType: 'arraybuffer', signal, timeout: 120000, validateStatus: () => true })
    const bytes = new Uint8Array(response.data)
    const type = String(response.headers['content-type'] || '')
    if (type.includes('json')) {
      const body = JSON.parse(new TextDecoder().decode(bytes)) as BaseResponse<unknown>
      throw new ApiError(body.message || '无法读取 PDF', body.code, response.status)
    }
    if (response.status !== 200 && response.status !== 206) throw new ApiError(`PDF 读取失败（HTTP ${response.status}）`, 0, response.status)
    if (new TextDecoder().decode(bytes.slice(0, 5)) !== '%PDF-') throw new ApiError('服务返回的文件不是有效 PDF。')
    return bytes
  } catch (error) {
    if (signal?.aborted) throw error
    if (error instanceof ApiError) return reportApiError(error)
    throw new ApiError(axios.isAxiosError(error) ? 'PDF 下载失败，请检查网络后重试。' : messageOf(error))
  }
}
