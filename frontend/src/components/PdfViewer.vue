<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { getDocument, GlobalWorkerOptions, type PDFDocumentProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { ChevronLeft, ChevronRight, ZoomIn, ZoomOut, MoveHorizontal, Scan, Minimize, FileText } from 'lucide-vue-next'
import Feedback from './Feedback.vue'
import PdfPage from './PdfPage.vue'

GlobalWorkerOptions.workerSrc = workerUrl
const props = defineProps<{ data: Uint8Array; initialPage?: number; focused?: boolean }>()
const emit = defineEmits<{ 'toggle-focus': [] }>()
const scrollArea = ref<HTMLElement>(), document = shallowRef<PDFDocumentProxy>()
const page = ref(props.initialPage || 1), pageInput = ref(page.value)
const width = ref(800), baseSize = ref({ width: 612, height: 792 })
const zoom = ref<number | null>(null), loading = ref(true), error = ref('')
const total = computed(() => document.value?.numPages || 0)
const scale = computed(() => zoom.value ?? Math.max(.1, Math.min(2.5, (width.value - 48) / baseSize.value.width)))
let disposed = false, resizeObserver: ResizeObserver | undefined, frame = 0, layoutGeneration = 0
const assetBase = `${import.meta.env.BASE_URL}pdfjs/`
const task = getDocument({ data: props.data.slice(), cMapUrl: `${assetBase}cmaps/`, cMapPacked: true, standardFontDataUrl: `${assetBase}standard_fonts/`, wasmUrl: `${assetBase}wasm/` })

function pageElement(number: number) { return scrollArea.value?.querySelector<HTMLElement>(`[data-page="${number}"]`) }
function goToPage(value: number) {
  const number = Math.max(1, Math.min(total.value, Math.floor(Number(value) || 1)))
  const element = pageElement(number), area = scrollArea.value
  if (!element || !area) return
  area.scrollTop += element.getBoundingClientRect().top - area.getBoundingClientRect().top - 24
  page.value = number; pageInput.value = number
}
function onScroll() {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(() => {
    const area = scrollArea.value
    if (!area) return
    // The last page can be shorter than the viewport at mobile fit-to-width.
    if (area.scrollTop > 0 && area.scrollHeight - area.clientHeight - area.scrollTop <= 1) {
      page.value = total.value; pageInput.value = total.value; return
    }
    const threshold = area.getBoundingClientRect().top + Math.min(120, area.clientHeight / 4)
    for (const element of area.querySelectorAll<HTMLElement>('[data-page]')) {
      if (element.getBoundingClientRect().bottom > threshold) {
        page.value = Number(element.dataset.page); pageInput.value = page.value; break
      }
    }
  })
}
function adjustZoom(delta: number) { zoom.value = Math.max(.25, Math.min(3, Math.round((scale.value + delta) * 100) / 100)) }
function onKey(event: KeyboardEvent) {
  if (event.target !== scrollArea.value) return
  if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
    event.preventDefault(); goToPage(page.value + (event.key === 'ArrowRight' ? 1 : -1))
  }
}
// Preserve the passage when zooming or resizing the notes panel.
watch(scale, async (value, previous) => {
  const area = scrollArea.value, element = pageElement(page.value)
  if (!area || !element) return
  const generation = ++layoutGeneration
  const offset = area.getBoundingClientRect().top - element.getBoundingClientRect().top
  await nextTick()
  if (disposed || generation !== layoutGeneration) return
  area.scrollTop += element.getBoundingClientRect().top - area.getBoundingClientRect().top + offset * value / previous
})
watch(() => props.initialPage, async value => { await nextTick(); if (total.value) goToPage(value || 1) })
onMounted(async () => {
  if (scrollArea.value) {
    width.value = scrollArea.value.clientWidth
    resizeObserver = new ResizeObserver(entries => { width.value = entries[0]!.target.clientWidth })
    resizeObserver.observe(scrollArea.value)
  }
  try {
    const pdf = await task.promise
    if (disposed) return
    const firstPage = await pdf.getPage(1)
    if (disposed) return
    const viewport = firstPage.getViewport({ scale: 1 })
    baseSize.value = { width: viewport.width, height: viewport.height }
    document.value = pdf
    await nextTick()
    goToPage(props.initialPage || 1)
  } catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : 'PDF 加载失败' }
  finally { if (!disposed) loading.value = false }
})
onBeforeUnmount(() => { disposed = true; resizeObserver?.disconnect(); cancelAnimationFrame(frame); void task.destroy() })
</script>

<template>
  <section class="pdf-viewer" aria-label="论文阅读器">
    <div class="pdf-toolbar">
      <div class="pdf-toolbar-label"><FileText :size="15" /><span>论文原文</span></div>
      <div class="pdf-page-controls">
        <button class="icon-btn" aria-label="上一页" title="上一页" :disabled="page <= 1 || !total" @click="goToPage(page - 1)"><ChevronLeft :size="17" /></button>
        <input v-model="pageInput" class="field" type="number" min="1" :max="total" aria-label="PDF 物理页码" :disabled="!total" @change="goToPage(Number(pageInput))" @keydown.enter="goToPage(Number(pageInput))" />
        <span>/ {{ total }} 页</span>
        <button class="icon-btn" aria-label="下一页" title="下一页" :disabled="page >= total || !total" @click="goToPage(page + 1)"><ChevronRight :size="17" /></button>
      </div>
      <div class="pdf-zoom-controls">
        <button class="icon-btn" aria-label="缩小" title="缩小" :disabled="scale <= .25" @click="adjustZoom(-.15)"><ZoomOut :size="17" /></button>
        <span class="pdf-zoom-value">{{ Math.round(scale * 100) }}%</span>
        <button class="icon-btn" aria-label="放大" title="放大" :disabled="scale >= 3" @click="adjustZoom(.15)"><ZoomIn :size="17" /></button>
        <button class="icon-btn" :class="{ selected: zoom === null }" aria-label="适应宽度" title="适应宽度" :aria-pressed="zoom === null" @click="zoom = null"><MoveHorizontal :size="17" /></button>
        <button class="icon-btn" :aria-label="focused ? '退出专注阅读' : '专注阅读'" :title="focused ? '退出专注阅读' : '专注阅读'" :aria-pressed="!!focused" @click="emit('toggle-focus')"><Minimize v-if="focused" :size="17" /><Scan v-else :size="17" /></button>
      </div>
    </div>
    <Feedback :loading="loading" :error="error" />
    <div ref="scrollArea" class="pdf-canvas" tabindex="0" aria-label="论文连续阅读区域" @scroll.passive="onScroll" @keydown="onKey">
      <template v-if="document">
        <PdfPage v-for="number in total" :key="number" :document="document" :number="number" :scale="scale" :base-size="baseSize" :scroll-root="scrollArea!" />
      </template>
    </div>
    <div class="pdf-reading-footer"><span>第 {{ page }} 页，共 {{ total }} 页</span><span>连续阅读 <span aria-hidden="true">·</span> 可选择文字复制</span></div>
    <div class="pdf-reading-progress" role="progressbar" aria-label="阅读位置" :aria-valuenow="page" :aria-valuemin="1" :aria-valuemax="total || 1"><span :style="{ width: `${total ? page / total * 100 : 0}%` }"></span></div>
  </section>
</template>
