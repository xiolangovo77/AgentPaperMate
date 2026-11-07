<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { paperApi } from '../api'
import { messageOf } from '../api/request'
import type { PaperJob } from '../types/api'
import Feedback from './Feedback.vue'
const props = defineProps<{ jobId: string }>()
const emit = defineEmits<{ settled: [job: PaperJob] }>()
const job = ref<PaperJob>(), error = ref(''), loading = ref(false)
const states: Record<string, string> = { pending: '等待处理', running: '处理中', completed: '处理完成', failed: '处理失败' }
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0, settled = ''
async function load(token = generation) {
  loading.value = !job.value; error.value = ''
  try {
    const result = await paperApi.job(props.jobId)
    if (token !== generation) return
    job.value = result
    if (result.status === 'completed' || result.status === 'failed') {
      if (settled !== result.status) { settled = result.status; emit('settled', result) }
    } else timer = setTimeout(() => void load(token), 4000)
  } catch (e) { if (token === generation) error.value = messageOf(e) }
  finally { if (token === generation) loading.value = false }
}
function refresh() { clearTimeout(timer); generation++; void load() }
watch(() => props.jobId, () => { job.value = undefined; settled = ''; refresh() }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>
<template><section class="processing-panel" aria-label="论文处理状态"><Feedback :error="error" :loading="loading"><button class="text-link" @click="refresh">重新查询</button></Feedback><template v-if="job"><div class="toolbar"><strong>{{ states[job.status] || job.status }}</strong><span v-if="job.currentStep" class="muted">{{ job.currentStep }}</span><button class="text-link" @click="refresh">刷新状态</button></div><p v-if="job.totalChunks !== null && job.totalChunks > 0" class="muted">已写入 {{ job.indexedChunks ?? 0 }} / {{ job.totalChunks }} 个片段</p><p v-if="job.errorMessage" class="notice error">{{ job.errorMessage }}</p><small class="muted">任务 {{ job.id }} · {{ job.updateTime }}<span v-if="job.retryCount"> · 已安排重试 {{ job.retryCount }} 次</span></small></template></section></template>
