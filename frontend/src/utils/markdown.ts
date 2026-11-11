import DOMPurify from 'dompurify'
import { marked } from 'marked'
import markedKatex from 'marked-katex-extension'
// Native MathML keeps formulas accessible without permitting model-provided styles.
marked.use(markedKatex({ output: 'mathml', throwOnError: false, trust: false, maxExpand: 1000 }))
// Sanitization is applied after Markdown parsing, including HTML inside model answers.
export function renderMarkdown(text: string, evidenceIds: string[] = []) {
  const clean = DOMPurify.sanitize(marked.parse(text, { async: false, breaks: true, gfm: true }), {
    USE_PROFILES: { html: true, mathMl: true },
    FORBID_TAGS: ['img', 'video', 'audio', 'iframe', 'form', 'input', 'style'],
    FORBID_ATTR: ['style'],
  })
  if (!evidenceIds.length) return clean
  // Link only saved evidence IDs and only prose text, never code, links or formulas.
  const document = new DOMParser().parseFromString(clean, 'text/html')
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  while (walker.nextNode()) nodes.push(walker.currentNode as Text)
  const verified = new Set(evidenceIds)
  for (const node of nodes) {
    if (node.parentElement?.closest('pre, code, a, button, math, .katex')) continue
    const matches = [...node.data.matchAll(/\[(E\d+)\]/g)].filter(match => verified.has(match[1]!))
    if (!matches.length) continue
    const fragment = document.createDocumentFragment()
    let offset = 0
    for (const match of matches) {
      fragment.append(node.data.slice(offset, match.index))
      const button = document.createElement('button')
      button.type = 'button'; button.className = 'evidence-ref'
      button.dataset.evidenceId = match[1]; button.textContent = match[0]
      button.setAttribute('aria-label', `查看引用 ${match[1]}`)
      fragment.append(button)
      offset = match.index! + match[0].length
    }
    fragment.append(node.data.slice(offset))
    node.replaceWith(fragment)
  }
  return document.body.innerHTML
}
