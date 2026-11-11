<script setup lang="ts">
import { computed } from 'vue'
import { Check, ChevronDown, CircleAlert, LoaderCircle, Wrench } from 'lucide-vue-next'
import type { RunEvent, RunStatus } from '../types/api'
import { runProgress } from '../utils/runProgress'
import { terminal } from '../utils/runObserver'

const props = defineProps<{ events: RunEvent[]; status?: RunStatus }>()
const progress = computed(() => runProgress(props.events, props.status))
const labels = { running: '执行中', completed: '已完成', failed: '调用失败', unconfirmed: '任务已结束，未收到工具结果' }
const counts = computed(() => {
  const tools = progress.value.tools
  return {
    completed: tools.filter(tool => tool.status === 'completed').length,
    running: tools.filter(tool => tool.status === 'running').length,
    failed: tools.filter(tool => tool.status === 'failed').length,
    unconfirmed: tools.filter(tool => tool.status === 'unconfirmed').length,
  }
})
function duration(ms: number) {
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  return `${Math.floor(ms / 60000)} 分 ${Math.floor((ms % 60000) / 1000)} 秒`
}
const errors: Record<string, string> = {
  INVALID_TOOL_ARGUMENTS: '参数无效或超出资料范围', TOOL_NOT_ALLOWED: '本次任务未开放此工具',
  TOOL_TIMEOUT: '读取超时', TOOL_CANCELLED: '调用已停止', SOURCE_UNAVAILABLE: '论文资料已不可访问',
  TOOL_EXECUTION_FAILED: '暂时无法读取资料', DUPLICATE_TOOL_CALL: '重复调用未执行',
}
</script>

<template>
  <details v-if="progress.skill || progress.tools.length" class="run-progress" open>
    <summary>
      <ChevronDown :size="14" class="expand-icon" aria-hidden="true" />
      <span class="progress-title">执行过程</span>
      <span v-if="progress.skill" class="skill-badge">{{ progress.skill }}</span>
      <span v-if="progress.tools.length" class="call-count">{{ progress.tools.length }} 次调用</span>
    </summary>
    <p v-if="progress.tools.length" class="progress-overview">
      <span>已完成 {{ counts.completed }}</span>
      <span v-if="counts.running" class="running-count">执行中 {{ counts.running }}</span>
      <span v-if="counts.failed" class="failed-count">调用失败 {{ counts.failed }}</span>
      <span v-if="counts.unconfirmed">结果未确认 {{ counts.unconfirmed }}</span>
    </p>
    <p v-else class="progress-overview">{{ props.status && terminal(props.status) ? '本次任务暂无工具调用记录' : '正在分析问题，准备读取论文资料…' }}</p>
    <ol v-if="progress.tools.length" aria-label="工具执行记录">
      <li v-for="(tool, index) in progress.tools" :key="tool.callId" :class="tool.status">
        <span class="status-icon" aria-hidden="true">
          <LoaderCircle v-if="tool.status === 'running'" :size="14" class="spin" />
          <Check v-else-if="tool.status === 'completed'" :size="14" />
          <CircleAlert v-else-if="tool.status === 'failed'" :size="14" />
          <Wrench v-else :size="14" />
        </span>
        <span class="tool-content">
          <span class="tool-heading"><span class="step-number">{{ index + 1 }}.</span> {{ tool.label }}</span>
          <small v-if="tool.errorCode" class="tool-error" :title="tool.errorCode">{{ errors[tool.errorCode] || '本次调用未成功' }}</small>
          <small v-else-if="tool.description" class="tool-description">{{ tool.description }}</small>
        </span>
        <span class="tool-result">
          <span class="status-label">{{ labels[tool.status] }}</span>
          <small v-if="tool.elapsedMs !== undefined" class="elapsed">{{ duration(tool.elapsedMs) }}</small>
        </span>
      </li>
    </ol>
  </details>
</template>

<style scoped>
.run-progress { padding: 14px 16px; border-top: 1px solid var(--line); background: #fafaff; font-size: 12px; color: var(--muted); }
summary { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; color: var(--text); line-height: 1.8; list-style: none; }
summary::-webkit-details-marker { display: none; }
summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 4px; border-radius: 4px; }
.expand-icon { flex-shrink: 0; transition: transform .15s; }
details:not([open]) .expand-icon { transform: rotate(-90deg); }
.progress-title { font-weight: 600; }
.skill-badge { padding: 1px 8px; border-radius: 6px; background: var(--soft); color: var(--primary); font-size: 11px; }
.call-count { margin-left: auto; font-size: 11px; color: var(--muted); }
.progress-overview { display: flex; flex-wrap: wrap; gap: 6px 14px; margin: 9px 0 8px 22px; font-size: 11px; }
.running-count { color: var(--primary); }
.failed-count, .tool-error { color: #b6413c; }
ol { list-style: none; padding: 0; margin: 0; }
li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 10px 0; line-height: 1.6; }
li + li { border-top: 1px solid var(--line); }
.status-icon { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 50%; background: #eeeff5; color: var(--muted); }
.completed .status-icon { color: var(--green); background: #eaf5ef; }
.running .status-icon { color: var(--primary); background: var(--soft); }
.failed .status-icon { color: #b6413c; background: #fff0ee; }
.tool-content { min-width: 0; overflow-wrap: anywhere; }
.tool-heading { display: block; color: var(--text); font-weight: 500; }
.step-number { color: var(--muted); font-variant-numeric: tabular-nums; }
.tool-description, .tool-error { display: block; margin-top: 2px; font-size: 11px; }
.tool-result { display: flex; flex-direction: column; align-items: flex-end; gap: 2px; max-width: 150px; text-align: right; }
.status-label { font-size: 11px; }
.running .status-label { color: var(--primary); }
.failed .status-label { color: #b6413c; }
.elapsed { font-size: 11px; font-variant-numeric: tabular-nums; }
@media (max-width: 480px) {
  li { column-gap: 8px; }
  .tool-result { max-width: 100px; }
  .call-count { width: 100%; margin-left: 22px; }
}
@media (prefers-reduced-motion: reduce) {
  .expand-icon { transition: none; }
  .spin { animation: none; }
}
</style>
