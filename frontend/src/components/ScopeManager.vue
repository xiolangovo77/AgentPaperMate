<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { sessionApi } from '../api'
import { ApiError, messageOf } from '../api/request'
import type { PaperSummary } from '../types/api'
import { paperDetails, paperTitle, readScope } from '../utils/papers'
import Modal from './Modal.vue'
import Feedback from './Feedback.vue'
import PaperBrowser from './PaperBrowser.vue'
const props = defineProps<{ sessionId: string }>()
const emit = defineEmits<{ close: []; changed: [] }>()
const selected = ref<string[]>([]), catalog = ref<Record<string, PaperSummary>>({}), version = ref(0)
const tab = ref('browse'), query = ref(''), page = ref(1), busy = ref(false), loading = ref(true), error = ref(''), conflict = ref(false)
const initialized = ref(false)
const chosen = computed(() => selected.value.filter(id => `${id} ${catalog.value[id] ? paperTitle(catalog.value[id]!) : ''}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const shown = computed(() => chosen.value.slice((page.value - 1) * 10, page.value * 10)), pages = computed(() => Math.max(1, Math.ceil(chosen.value.length / 10)))
let disposed = false
function cache(rows: PaperSummary[]) { for (const item of rows) catalog.value[item.id] = item }
function toggle(paper: PaperSummary) {
  cache([paper]); error.value = ''
  if (selected.value.includes(paper.id)) selected.value = selected.value.filter(id => id !== paper.id)
  else if (selected.value.length < 100) selected.value.push(paper.id)
  else error.value = '每个资料范围最多选择 100 篇论文。'
}
async function load() {
  loading.value = true; error.value = ''
  initialized.value = false
  try {
    const snapshot = await readScope(props.sessionId)
    if (disposed) return
    selected.value = snapshot.papers.map(item => item.paperId); version.value = snapshot.session.scopeVersion; conflict.value = false; page.value = 1; initialized.value = true
    // Failed metadata does not erase an existing membership; it can still be removed.
    for (let offset = 0; offset < selected.value.length && !disposed; offset += 5) {
      const results = await Promise.allSettled(selected.value.slice(offset, offset + 5).map(id => paperDetails([id])))
      if (!disposed) for (const result of results) if (result.status === 'fulfilled') cache(result.value)
    }
  } catch (e) { if (!disposed) error.value = messageOf(e) }
  finally { if (!disposed) loading.value = false }
}
async function save() {
  busy.value = true; error.value = ''
  try { await sessionApi.updateScope(props.sessionId, selected.value, version.value); emit('changed'); emit('close') }
  catch (e) { error.value = messageOf(e); conflict.value = e instanceof ApiError && e.conflict }
  finally { busy.value = false }
}
function reload() { if (window.confirm('将重新读取最新资料范围并替换当前未提交选择，请重新确认后保存。是否继续？')) void load() }
onMounted(load)
onBeforeUnmount(() => { disposed = true })
</script>
<template><Modal title="调整论文范围" :busy="busy" wide @close="emit('close')"><p class="muted">确认后一次保存全部选择。取消不会改动会话，历史回答与运行中任务的资料快照保留。</p><Feedback :loading="loading" :error="error"><button v-if="!conflict" class="text-link" @click="load">重新加载</button></Feedback><div v-if="conflict" class="notice"><div>资料范围已被其他窗口修改，当前选择已保留。请加载最新范围并重新确认。<button class="text-link" @click="reload">加载最新范围</button></div></div><template v-if="!loading"><div class="tabs"><button :class="{ active: tab === 'browse' }" @click="tab = 'browse'">浏览论文库</button><button :class="{ active: tab === 'selected' }" @click="tab = 'selected'">已选论文（{{ selected.length }}）</button></div><fieldset :disabled="busy" class="plain-fieldset"><PaperBrowser v-show="tab === 'browse'" :selected="selected" @toggle="toggle" @loaded="cache" /><div v-if="tab === 'selected'"><input v-model="query" class="field" placeholder="搜索已选论文" aria-label="搜索已选论文" @input="page = 1" /><div v-if="!chosen.length" class="empty-state">没有已选论文。空范围会保留历史记录，但不能提问。</div><article v-for="id in shown" :key="id" class="scope-record"><div><b>{{ catalog[id] ? paperTitle(catalog[id]!) : `论文 ${id}` }}</b><small>{{ id }}<span v-if="catalog[id]?.isRecycled === 1"> · 已回收（当前会话仍可使用）</span></small></div><button class="btn small" @click="selected = selected.filter(item => item !== id); page = 1">移出选择</button></article><div v-if="pages > 1" class="scope-pagination"><button class="btn small" :disabled="page <= 1" @click="page--">上一页</button>{{ page }} / {{ pages }}<button class="btn small" :disabled="page >= pages" @click="page++">下一页</button></div></div></fieldset><p class="scope-warning">可以保留处理中论文，但全部资料就绪后才能提问。最多 100 篇，当前范围版本 {{ version }}。</p><div class="dialog-actions"><button class="btn" :disabled="busy" @click="emit('close')">取消</button><button class="btn primary" :disabled="busy || conflict || !initialized || selected.length > 100" @click="save">{{ busy ? '正在保存…' : `确认选择（${selected.length} 篇）` }}</button></div></template></Modal></template>
