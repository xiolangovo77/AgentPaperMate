<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus } from 'lucide-vue-next'
import UploadPaper from '../components/UploadPaper.vue'
import PaperBrowser from '../components/PaperBrowser.vue'
import SessionTarget from '../components/SessionTarget.vue'
import Modal from '../components/Modal.vue'
import type { PaperSummary } from '../types/api'
import { paperTitle } from '../utils/papers'
const route = useRoute(), router = useRouter(), showUpload = ref(route.query.upload === '1'), refreshKey = ref(0)
const selected = ref<PaperSummary[]>([]), target = ref<'create' | 'existing' | null>(null), showSelected = ref(false), error = ref('')
function toggle(paper: PaperSummary) {
  error.value = ''
  if (selected.value.some(item => item.id === paper.id)) selected.value = selected.value.filter(item => item.id !== paper.id)
  else if (selected.value.length < 100) selected.value.push(paper)
  else error.value = '最多选择 100 篇论文。'
}
function closeUpload() { showUpload.value = false; if (route.query.upload) void router.replace('/library') }
</script>
<template><div class="page-heading"><div><div class="eyebrow">YOUR RESEARCH LIBRARY</div><h1>论文库</h1><p>收集值得阅读的论文，积累属于你的研究线索。</p></div><button class="btn primary" @click="showUpload = true"><Plus :size="16" />导入 PDF</button></div><div class="selection-toolbar"><button class="text-link" @click="showSelected = true">已选 {{ selected.length }} 篇 · 查看选择</button><span class="muted">跨页与筛选保留选择</span><button class="btn" :disabled="!selected.length" @click="target = 'existing'">加入已有聊天室</button><button class="btn primary" :disabled="!selected.length" @click="target = 'create'">新建聊天室</button></div><p v-if="error" class="notice error" role="alert">{{ error }}</p><PaperBrowser :selected="selected.map(item => item.id)" :refresh-key="refreshKey" @toggle="toggle" /><UploadPaper v-if="showUpload" @close="closeUpload" @uploaded="refreshKey++" /><SessionTarget v-if="target" :mode="target" :paper-ids="selected.map(item => item.id)" @close="target = null" /><Modal v-if="showSelected" title="已选论文" wide @close="showSelected = false"><p v-if="!selected.length" class="empty-state">还没有选择论文。</p><article v-for="paper in selected" :key="paper.id" class="scope-record"><div><b>{{ paperTitle(paper) }}</b><small>{{ paper.authors }}</small></div><button class="btn small" @click="toggle(paper)">移出选择</button></article><div class="dialog-actions"><button class="btn" @click="selected = []">清除选择</button><button class="btn primary" @click="showSelected = false">完成</button></div></Modal></template>
