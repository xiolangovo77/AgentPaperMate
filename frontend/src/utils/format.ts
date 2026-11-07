export function dateTime(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}
export function downloadMarkdown(title: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url; link.download = `${title.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_').slice(0, 100) || '研究记录'}.md`
  link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function validPassword(value: string) { return value.trim().length > 0 && value.length >= 6 && new TextEncoder().encode(value).length <= 72 }
export function validId(value: string) { return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n }
