<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Search, FileText, ArrowRight } from 'lucide-vue-next'
import CapabilityGap from '../components/CapabilityGap.vue'
import UploadPaper from '../components/UploadPaper.vue'
import type { UploadedPaper } from '../types/api'
import { dateTime, validId } from '../utils/format'
const route = useRoute(), router = useRouter(), showUpload = ref(route.query.upload === '1'), receipts = ref<UploadedPaper[]>([]), paperId = ref('')
function closeUpload() { showUpload.value = false; if (route.query.upload) void router.replace('/library') }
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">YOUR RESEARCH LIBRARY</div><h1>论文库</h1><p>收集值得阅读的论文，积累属于你的研究线索。</p></div><button class="btn primary" @click="showUpload = true"><Plus :size="16" /> 导入 PDF</button></div>
  <div class="toolbar library-filters"><div class="input-icon"><Search :size="17" /><input class="field" aria-label="搜索论文" placeholder="搜索论文标题或作者" disabled /></div><select class="select" aria-label="研究主题" disabled><option>全部主题</option></select><select class="select" aria-label="处理状态" disabled><option>全部状态</option></select></div>
  <section class="panel library-unavailable"><CapabilityGap title="论文库正在等待查询能力接通" detail="当前服务支持上传论文，但还不能查询论文列表、元数据和处理状态。搜索、筛选、分页及批量选择暂不可用。"><button class="btn primary" @click="showUpload = true"><Plus :size="15" /> 先导入一篇论文</button></CapabilityGap></section>
  <section v-if="receipts.length" class="panel section-space"><h2 class="panel-title">本次页面的上传回执</h2><p class="panel-subtitle">这里只显示刚刚收到的提交结果，刷新后不保留；不是论文库或实时处理状态。</p><article v-for="receipt in receipts" :key="receipt.paperId" class="receipt-row"><FileText :size="25" /><div><b>{{ receipt.filename }}</b><p class="muted">提交时状态：{{ receipt.status }} · {{ dateTime(receipt.submittedAt) }}</p><small>论文 {{ receipt.paperId }} · 任务 {{ receipt.jobId }}</small></div><RouterLink class="btn small" :to="`/library/${receipt.paperId}?tab=notes`">阅读笔记</RouterLink></article></section>
  <section class="panel section-space"><h2 class="panel-title">已有论文的阅读笔记</h2><p class="panel-subtitle">如果你已有上传回执中的论文 ID，可以直接打开笔记；访问权限由服务端校验。</p><form class="toolbar section-space" @submit.prevent="validId(paperId.trim()) && router.push(`/library/${paperId.trim()}?tab=notes`)"><input v-model="paperId" class="field id-input" inputmode="numeric" placeholder="输入论文 ID" aria-label="论文 ID" /><button class="btn" :disabled="!validId(paperId.trim())">打开笔记 <ArrowRight :size="15" /></button></form></section>
  <UploadPaper v-if="showUpload" @close="closeUpload" @uploaded="receipts.unshift($event)" />
</template>
