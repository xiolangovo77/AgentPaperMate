import { cpSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
const root = new URL('../', import.meta.url)
for (const name of ['cmaps', 'standard_fonts', 'wasm']) {
  const target = fileURLToPath(new URL(`public/pdfjs/${name}/`, root))
  mkdirSync(target, { recursive: true })
  cpSync(fileURLToPath(new URL(`node_modules/pdfjs-dist/${name}/`, root)), target, { recursive: true })
}
