<script setup lang="ts">
// Integration seam: only mount this component with an authenticated backend PDF response.
// Never pass the database's private COS URL directly to the reader.
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { ChevronLeft, ChevronRight, ZoomIn, ZoomOut } from 'lucide-vue-next'
import Feedback from './Feedback.vue'
GlobalWorkerOptions.workerSrc = workerUrl
const props = defineProps<{ data: Uint8Array; initialPage?: number }>()
const canvas = ref<HTMLCanvasElement>(), page = ref(props.initialPage || 1), total = ref(0), scale = ref(1.15), loading = ref(true), error = ref('')
let document: PDFDocumentProxy | undefined, renderTask: RenderTask | undefined, renderGeneration = 0, disposed = false
const task = getDocument({ data: props.data.slice() })
async function render() {
  if (!document || !canvas.value) return
  const generation = ++renderGeneration
  const previous = renderTask; previous?.cancel()
  try { await previous?.promise } catch { /* cancellation before reusing the same canvas */ }
  if (disposed || generation !== renderGeneration) return
  loading.value = true; error.value = ''
  try {
    const physicalPage = Math.max(1, Math.min(document.numPages, Math.floor(Number(page.value) || 1)))
    page.value = physicalPage
    const pdfPage = await document.getPage(physicalPage)
    if (disposed || generation !== renderGeneration) return
    const viewport = pdfPage.getViewport({ scale: scale.value }), ratio = window.devicePixelRatio || 1
    const element = canvas.value
    element.width = Math.floor(viewport.width * ratio); element.height = Math.floor(viewport.height * ratio)
    element.style.width = `${viewport.width}px`; element.style.height = `${viewport.height}px`
    renderTask = pdfPage.render({ canvas: element, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] })
    await renderTask.promise
  } catch (e) { if (e instanceof Error && e.name !== 'RenderingCancelledException' && generation === renderGeneration) error.value = e.message }
  finally { if (generation === renderGeneration) loading.value = false }
}
onMounted(async () => { try { document = await task.promise; if (disposed) { void document.destroy(); return }; total.value = document.numPages; await render() } catch (e) { if (!disposed) { error.value = e instanceof Error ? e.message : 'PDF 加载失败'; loading.value = false } } })
watch([page, scale], () => void render())
watch(() => props.initialPage, value => { page.value = value || 1 })
onBeforeUnmount(() => { disposed = true; renderGeneration++; renderTask?.cancel(); void task.destroy() })
</script>
<template><section class="pdf-viewer"><div class="pdf-toolbar"><button class="icon-btn" aria-label="上一页" :disabled="page <= 1 || !total" @click="page--"><ChevronLeft :size="16" /></button><input v-model.lazy.number="page" class="field" type="number" :min="1" :max="total" aria-label="PDF 物理页码" /><span>/ {{ total }} 页</span><button class="icon-btn" aria-label="下一页" :disabled="page >= total" @click="page++"><ChevronRight :size="16" /></button><button class="icon-btn" aria-label="缩小" :disabled="scale <= .5" @click="scale = Math.max(.5, scale - .15)"><ZoomOut :size="16" /></button><span>{{ Math.round(scale * 100) }}%</span><button class="icon-btn" aria-label="放大" :disabled="scale >= 2.5" @click="scale = Math.min(2.5, scale + .15)"><ZoomIn :size="16" /></button></div><Feedback :loading="loading" :error="error" /><div class="pdf-canvas"><canvas ref="canvas" aria-label="论文 PDF 页面"></canvas></div></section></template>
