<script setup lang="ts">
import { ref } from 'vue'
import { UploadCloud, FileText, CheckCircle2 } from 'lucide-vue-next'
import { paperApi, uploadFile } from '../api'
import { messageOf } from '../api/request'
import type { UploadedPaper } from '../types/api'
import Modal from './Modal.vue'
import Feedback from './Feedback.vue'
const emit = defineEmits<{ close: []; uploaded: [paper: UploadedPaper] }>()
const file = ref<File>(), phase = ref<'choose' | 'upload' | 'submit' | 'done'>('choose'), error = ref(''), progress = ref<number | null>(null), receipt = ref<UploadedPaper>()
// Keep the uploaded URL in memory when paper/add fails, so retry does not upload the file twice.
let uploadedUrl = ''
function choose(value?: File) {
  error.value = ''
  if (!value) return
  if (!/\.pdf$/i.test(value.name) || value.size === 0 || value.size > 50 * 1024 * 1024) { error.value = '请选择不超过 50 MB 的非空 PDF 文件。'; return }
  file.value = value; uploadedUrl = ''
}
function drop(event: DragEvent) { if (phase.value === 'choose') choose(event.dataTransfer?.files[0]) }
async function submit() {
  if (!file.value || phase.value !== 'choose') return
  error.value = ''
  try {
    if (!uploadedUrl) { phase.value = 'upload'; uploadedUrl = await uploadFile('paper', file.value, value => progress.value = value) }
    phase.value = 'submit'
    const data = await paperApi.add(uploadedUrl)
    receipt.value = { ...data, filename: file.value.name, submittedAt: new Date().toISOString() }
    emit('uploaded', receipt.value); phase.value = 'done'
  } catch (e) { error.value = messageOf(e); phase.value = 'choose' }
}
</script>
<template>
  <Modal title="导入论文" :busy="phase === 'upload' || phase === 'submit'" @close="emit('close')"><Feedback :error="error" />
    <template v-if="phase !== 'done'"><p class="muted">上传 PDF，建立你的研究资料。</p><label class="upload-zone" @dragover.prevent @drop.prevent="drop"><UploadCloud :size="34" /><strong>{{ file?.name || '点击选择，或拖放 PDF 到这里' }}</strong><span>{{ file ? `${(file.size / 1024 / 1024).toFixed(2)} MB` : '仅支持 PDF · 单个文件不超过 50 MB' }}</span><input type="file" class="sr-only" accept=".pdf,application/pdf" :disabled="phase !== 'choose'" @change="choose(($event.target as HTMLInputElement).files?.[0])" /></label><div v-if="phase === 'upload'" class="upload-progress" role="status"><progress :value="progress ?? undefined" max="100"></progress><span>{{ progress === null ? '正在上传…' : `文件上传 ${progress}%` }}</span></div><p v-if="phase === 'submit'" role="status">正在提交解析任务…</p><p class="notice blue">上传后由服务端异步解析。当前暂无法查询解析进度，提交成功不代表已可用于问答。</p><div class="dialog-actions"><button class="btn" :disabled="phase !== 'choose'" @click="emit('close')">取消</button><button class="btn primary" :disabled="!file || phase !== 'choose'" @click="submit">{{ phase === 'choose' ? uploadedUrl ? '重新提交解析任务' : '上传并开始处理' : '处理中…' }}</button></div></template>
    <div v-else-if="receipt" class="upload-success"><CheckCircle2 :size="42" /><h3>已提交论文处理任务</h3><p><FileText :size="16" /> {{ receipt.filename }}</p><dl class="receipt"><dt>论文 ID</dt><dd>{{ receipt.paperId }}</dd><dt>任务 ID</dt><dd>{{ receipt.jobId }}</dd><dt>提交时状态</dt><dd>{{ receipt.status }}</dd></dl><p class="notice blue">尚无处理状态查询接口，请勿将此回执视为解析完成。</p><div class="dialog-actions"><RouterLink class="btn" :to="`/library/${receipt.paperId}?tab=notes`">记录阅读笔记</RouterLink><button class="btn primary" @click="emit('close')">完成</button></div></div>
  </Modal>
</template>
