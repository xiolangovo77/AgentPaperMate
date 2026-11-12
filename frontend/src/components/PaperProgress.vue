<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { paperApi } from '../api'
import { messageOf } from '../api/request'
import type { PaperJob, PaperReceipt } from '../types/api'
import Feedback from './Feedback.vue'
const props = withDefaults(defineProps<{ jobId: string; allowRetry?: boolean }>(), { allowRetry: true })
const emit = defineEmits<{ settled: [job: PaperJob]; restarted: [receipt: PaperReceipt] }>()
const activeJobId = ref(props.jobId), retrying = ref(false), retryError = ref('')
const job = ref<PaperJob>(), error = ref(''), loading = ref(false)
const states: Record<string, string> = { pending: '等待处理', running: '处理中', completed: '处理完成', failed: '处理失败' }
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0, settled = ''
async function load(token = generation) {
  loading.value = !job.value; error.value = ''
  try {
    const result = await paperApi.job(activeJobId.value)
    if (token !== generation) return
    job.value = result
    if (result.status === 'completed' || result.status === 'failed') {
      if (settled !== result.status) { settled = result.status; emit('settled', result) }
    } else timer = setTimeout(() => void load(token), 4000)
  } catch (e) { if (token === generation) error.value = messageOf(e) }
  finally { if (token === generation) loading.value = false }
}
function refresh() { clearTimeout(timer); generation++; void load() }
async function retry() {
  if (!job.value || retrying.value || !props.allowRetry || job.value.status !== 'failed') return
  const token = generation
  retrying.value = true; retryError.value = ''
  try {
    const receipt = await paperApi.retry(job.value.paperId, job.value.id)
    if (token !== generation) return
    activeJobId.value = receipt.jobId; job.value = undefined; settled = ''
    refresh(); emit('restarted', receipt)
  } catch (e) { if (token === generation) retryError.value = messageOf(e) }
  finally { retrying.value = false }
}
watch(() => props.jobId, value => { activeJobId.value = value; job.value = undefined; settled = ''; retryError.value = ''; refresh() }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>
<template><section class="processing-panel" aria-label="论文处理状态"><Feedback :error="error" :loading="loading"><button class="text-link" @click="refresh">重新查询</button></Feedback><template v-if="job"><div class="toolbar"><strong>{{ states[job.status] || job.status }}</strong><span v-if="job.currentStep" class="muted">{{ job.currentStep }}</span><button class="text-link" :disabled="retrying" @click="refresh">刷新状态</button></div><p v-if="job.totalChunks !== null && job.totalChunks > 0" class="muted">已写入 {{ job.indexedChunks ?? 0 }} / {{ job.totalChunks }} 个片段</p><p v-if="job.errorMessage" class="notice error">{{ job.errorMessage }}</p><div v-if="job.status === 'failed' && allowRetry"><p class="muted">可使用已上传的 PDF 重新处理，无需再次上传。</p><button class="btn primary" :disabled="retrying" @click="retry">{{ retrying ? '正在提交…' : '重新处理' }}</button><p v-if="retryError" class="notice error" role="alert">{{ retryError }}</p></div><small class="muted">任务 {{ job.id }} · {{ job.updateTime }}<span v-if="job.retryCount"> · 已安排重试 {{ job.retryCount }} 次</span></small></template></section></template>
