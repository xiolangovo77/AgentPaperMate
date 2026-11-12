<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { Search, SquarePen, X, LoaderCircle, ChevronRight } from 'lucide-vue-next'
import type { Session } from '../types/api'
import Feedback from './Feedback.vue'
const props = defineProps<{ sessions: Session[]; current?: Session | null; selectedId?: string; query: string; busy: boolean; error: string; hasMore: boolean; expanded: boolean }>()
const emit = defineEmits<{ create: []; select: [id: string]; search: [query: string]; retry: []; more: []; toggle: [] }>()
const listId = useId()
const items = computed(() => props.sessions.map(item => item.id === props.current?.id ? props.current : item))
const missingCurrent = computed(() => props.current && !props.query && !props.sessions.some(item => item.id === props.current!.id))
const scrollArea = ref<HTMLElement>()
let resizeObserver: ResizeObserver | undefined
function loadNearEnd() {
  const area = scrollArea.value
  if (!area || !area.clientHeight || !props.expanded || props.busy || props.error || !props.hasMore) return
  if (area.scrollHeight - area.scrollTop - area.clientHeight <= 120) emit('more')
}
// Fill a short list automatically, and continue as the user approaches its bottom.
watch([() => props.busy, () => props.hasMore, () => props.expanded, () => props.sessions.length], loadNearEnd, { flush: 'post' })
onMounted(() => {
  resizeObserver = new ResizeObserver(loadNearEnd)
  if (scrollArea.value) resizeObserver.observe(scrollArea.value)
  loadNearEnd()
})
onBeforeUnmount(() => resizeObserver?.disconnect())
</script>

<template>
  <section class="session-sidebar" aria-label="会话侧栏">
    <div class="session-sidebar-search"><Search :size="16" aria-hidden="true" /><input :value="query" type="search" aria-label="搜索历史会话" placeholder="搜索会话" @input="emit('search', ($event.target as HTMLInputElement).value)" /><button v-if="query" class="icon-btn" aria-label="清除搜索" @click="emit('search', '')"><X :size="14" /></button></div>
    <div class="session-sidebar-label"><button class="recent-toggle" :aria-expanded="expanded" :aria-controls="listId" :aria-label="expanded ? '收起最近会话' : '展开最近会话'" @click="emit('toggle')"><ChevronRight :size="14" :class="{ expanded }" /><span>{{ query.trim() ? '搜索结果' : '最近' }}</span></button><button class="icon-btn recent-create" aria-label="新建最近会话" title="新建会话" @click="emit('create')"><SquarePen :size="15" /></button></div>
    <div ref="scrollArea" v-show="expanded" :id="listId" class="session-sidebar-scroll" :aria-busy="busy" @scroll.passive="loadNearEnd">
      <Feedback :error="error"><button class="text-link" @click="emit('retry')">重试</button></Feedback>
      <button v-if="missingCurrent && current" class="session-history-item active" aria-current="page" :title="current.name" @click="emit('select', current.id)"><span>{{ current.name }}</span><small>当前</small></button>
      <ul><li v-for="item in items" :key="item.id"><button class="session-history-item" :class="{ active: selectedId === item.id }" :aria-current="selectedId === item.id ? 'page' : undefined" :title="item.name" @click="emit('select', item.id)"><span>{{ item.name }}</span><span v-if="item.activeRunId" class="session-running-dot" role="img" aria-label="任务进行中"></span><small v-else-if="item.inputDraft?.trim()">草稿</small></button></li></ul>
      <div v-if="busy" class="session-sidebar-status" role="status"><LoaderCircle :size="15" class="spin" />正在加载…</div>
      <div v-else-if="!items.length && !error && !missingCurrent" class="session-sidebar-empty"><p>{{ query.trim() ? '没有找到匹配的会话' : '还没有研究会话' }}</p><button v-if="query.trim()" class="text-link" @click="emit('search', '')">清除搜索</button></div>
    </div>
  </section>
</template>
