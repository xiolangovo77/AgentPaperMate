<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, FileText, NotebookPen } from 'lucide-vue-next'
import CapabilityGap from '../components/CapabilityGap.vue'
import { validId } from '../utils/format'
const NoteEditor = defineAsyncComponent(() => import('../components/NoteEditor.vue'))
const route = useRoute(), router = useRouter()
const id = computed(() => String(route.params.id)), tab = computed(() => route.query.tab === 'notes' ? 'notes' : 'pdf')
const page = computed(() => { const value = Number(route.query.page); return Number.isSafeInteger(value) && value > 0 ? value : null })
function select(value: string) { void router.replace({ query: { ...route.query, tab: value } }) }
</script>
<template>
  <RouterLink to="/library" class="back-link"><ArrowLeft :size="15" /> 返回论文库</RouterLink><div class="page-heading"><div><div class="eyebrow">PAPER WORKSPACE</div><h1>论文阅读与笔记</h1><p>论文 ID：{{ id }}</p></div></div>
  <div v-if="!validId(id)" class="notice error">论文 ID 无效。</div>
  <template v-else><div class="tabs" role="tablist" aria-label="论文内容"><button role="tab" :aria-selected="tab === 'pdf'" :class="{ active: tab === 'pdf' }" @click="select('pdf')"><FileText :size="16" />论文原文</button><button role="tab" :aria-selected="tab === 'notes'" :class="{ active: tab === 'notes' }" @click="select('notes')"><NotebookPen :size="16" />阅读笔记</button></div><section v-if="tab === 'pdf'" class="panel pdf-unavailable" role="tabpanel"><CapabilityGap title="论文原文暂不可读取" detail="当前服务尚未提供经过权限校验的 PDF 读取接口。接通后可在这里翻页、缩放与定位引用原文。"><p v-if="page" class="citation-target">引用目标：PDF 物理第 {{ page }} 页</p><p class="muted">论文元数据也暂不可查询，当前仅展示论文 ID。</p></CapabilityGap></section><NoteEditor v-else :key="id" :paper-id="id" /></template>
</template>
