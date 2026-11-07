import DOMPurify from 'dompurify'
import { marked } from 'marked'
// Sanitization is applied after Markdown parsing, including HTML inside model answers.
export function renderMarkdown(text: string) {
  return DOMPurify.sanitize(marked.parse(text, { async: false, breaks: true, gfm: true }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'video', 'audio', 'iframe', 'form', 'input', 'style'],
    FORBID_ATTR: ['style'],
  })
}
