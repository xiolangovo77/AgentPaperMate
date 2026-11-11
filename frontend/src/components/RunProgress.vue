<script setup lang="ts">
import { computed } from 'vue'
import { Check, CircleAlert, LoaderCircle, Wrench } from 'lucide-vue-next'
import type { RunEvent, RunStatus } from '../types/api'
import { runProgress } from '../utils/runProgress'

const props = defineProps<{ events: RunEvent[]; status?: RunStatus }>()
const progress = computed(() => runProgress(props.events, props.status))
const labels = { running: '执行中', completed: '已完成', failed: '调用失败', unconfirmed: '任务已结束，未收到工具结果' }
</script>

<template>
  <details v-if="progress.skill || progress.tools.length" class="run-progress" open>
    <summary>执行过程<span v-if="progress.skill"> · {{ progress.skill }}</span><span v-if="progress.tools.length"> · {{ progress.tools.length }} 次工具调用</span></summary>
    <p v-if="progress.skill" class="skill-selection">已选择技能：{{ progress.skill }}</p>
    <ol v-if="progress.tools.length" aria-label="工具执行记录">
      <li v-for="tool in progress.tools" :key="tool.callId" :class="tool.status">
        <LoaderCircle v-if="tool.status === 'running'" :size="14" class="spin" />
        <Check v-else-if="tool.status === 'completed'" :size="14" />
        <CircleAlert v-else-if="tool.status === 'failed'" :size="14" />
        <Wrench v-else :size="14" />
        <span class="tool-label">{{ tool.label }}<small v-if="tool.errorCode">{{ tool.errorCode }}</small></span>
        <span>{{ labels[tool.status] }}<small v-if="tool.elapsedMs !== undefined"> · {{ tool.elapsedMs }} ms</small></span>
      </li>
    </ol>
  </details>
</template>

<style scoped>
.run-progress { padding: 12px 16px; border-top: 1px solid var(--line); background: #fafaff; font-size: 12px; color: var(--muted); }
summary { cursor: pointer; color: var(--text); line-height: 1.8; overflow-wrap: anywhere; }
.skill-selection { margin: 10px 0; }
ol { list-style: none; padding: 0; margin: 10px 0 0; }
li { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; padding: 7px 0; line-height: 1.6; overflow-wrap: anywhere; }
.tool-label { flex: 1; min-width: 90px; }
.tool-label small { display: block; }
.completed > svg { color: var(--green); }
.running > svg { color: var(--primary); }
.failed { color: #b6413c; }
</style>
