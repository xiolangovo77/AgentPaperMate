<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { TextLayer, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist'

const props = defineProps<{ document: PDFDocumentProxy; number: number; scale: number; baseSize: { width: number; height: number }; scrollRoot: HTMLElement }>()
const element = ref<HTMLElement>(), canvas = ref<HTMLCanvasElement>(), textContainer = ref<HTMLElement>()
const size = ref(props.baseSize), nearby = ref(false), rendered = ref(false), error = ref('')
const dimensions = computed(() => ({ width: `${size.value.width * props.scale}px`, height: `${size.value.height * props.scale}px`, '--total-scale-factor': props.scale }))
let observer: IntersectionObserver | undefined, renderTask: RenderTask | undefined, textLayer: TextLayer | undefined
let generation = 0, disposed = false

async function render() {
  const token = ++generation
  const previous = renderTask
  previous?.cancel(); textLayer?.cancel()
  try { await previous?.promise } catch { /* Finish cancellation before reusing a canvas. */ }
  if (disposed || token !== generation) return
  rendered.value = false; error.value = ''
  const surface = canvas.value, text = textContainer.value
  if (!surface || !text) return
  text.replaceChildren()
  // Release offscreen backing buffers for long papers.
  if (!nearby.value) { surface.width = 0; surface.height = 0; return }
  try {
    const pdfPage = await props.document.getPage(props.number)
    if (disposed || token !== generation) return
    const base = pdfPage.getViewport({ scale: 1 })
    size.value = { width: base.width, height: base.height }
    const viewport = pdfPage.getViewport({ scale: props.scale })
    const ratio = Math.min(window.devicePixelRatio || 1, 2, Math.sqrt(8_000_000 / (viewport.width * viewport.height)))
    surface.width = Math.floor(viewport.width * ratio); surface.height = Math.floor(viewport.height * ratio)
    renderTask = pdfPage.render({ canvas: surface, viewport, transform: ratio === 1 ? undefined : [ratio, 0, 0, ratio, 0, 0] })
    await renderTask.promise
    if (disposed || token !== generation) return
    rendered.value = true
    textLayer = new TextLayer({ textContentSource: pdfPage.streamTextContent(), container: text, viewport })
    await textLayer.render()
  } catch (e) {
    if (!disposed && token === generation && !(e instanceof Error && e.name === 'RenderingCancelledException')) error.value = '此页加载失败，请重试。'
  }
}
watch([nearby, () => props.scale], () => void render())
onMounted(() => {
  observer = new IntersectionObserver(entries => { nearby.value = entries[0]!.isIntersecting }, { root: props.scrollRoot, rootMargin: '800px 0px' })
  if (element.value) observer.observe(element.value)
})
onBeforeUnmount(() => { disposed = true; generation++; observer?.disconnect(); renderTask?.cancel(); textLayer?.cancel(); if (canvas.value) { canvas.value.width = 0; canvas.value.height = 0 } })
</script>

<template>
  <article ref="element" class="pdf-sheet" :data-page="number" :style="dimensions" :aria-label="`论文第 ${number} 页`" :aria-busy="nearby && !rendered && !error">
    <canvas ref="canvas" v-show="rendered" :aria-label="`论文 PDF 第 ${number} 页`"></canvas>
    <div ref="textContainer" class="textLayer"></div>
    <div v-if="!rendered && !error" class="pdf-page-placeholder">{{ nearby ? '正在呈现' : '第' }} {{ number }} 页</div>
    <div v-if="error" class="pdf-page-error" role="alert"><span>{{ error }}</span><button class="btn small" @click="render">重试此页</button></div>
  </article>
</template>
