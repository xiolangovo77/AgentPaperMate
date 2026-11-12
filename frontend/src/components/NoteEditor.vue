<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { Download, Save, RefreshCw } from 'lucide-vue-next'
import { noteApi } from '../api'
import { ApiError, messageOf } from '../api/request'
import { noteFields, type NoteContent } from '../types/api'
import { downloadMarkdown } from '../utils/format'
import Feedback from './Feedback.vue'
const props = defineProps<{ paperId: string; embedded?: boolean }>()
const form = reactive<NoteContent>({ researchQuestion: '', methods: '', innovations: '', experimentResults: '', limitationsQuestions: '', personalThoughts: '' })
const loading = ref(true), saving = ref(false), error = ref(''), conflict = ref(false), loaded = ref(false), version = ref<number | null>(null), saved = ref(''), savedTime = ref('')
const snapshot = () => JSON.stringify(form)
const dirty = computed(() => loaded.value && snapshot() !== saved.value)
let timer: ReturnType<typeof setTimeout> | undefined
let inflight: Promise<boolean> | undefined
async function load() {
  loading.value = true; error.value = ''; loaded.value = false
  try {
    const note = await noteApi.get(props.paperId)
    for (const [key] of noteFields) form[key] = note?.[key] || ''
    version.value = note?.version ?? null; saved.value = snapshot(); conflict.value = false; loaded.value = true
  } catch (e) { error.value = messageOf(e) } finally { loading.value = false }
}
async function save(): Promise<boolean> {
  clearTimeout(timer)
  if (inflight) { await inflight; return dirty.value && !error.value ? save() : !dirty.value }
  if (!dirty.value || conflict.value || !loaded.value) return !dirty.value
  const body = { ...form }, sent = JSON.stringify(body)
  saving.value = true; error.value = ''
  inflight = (async () => {
    try {
      if (version.value === null) { await noteApi.add(props.paperId, body); version.value = 1 }
      else version.value = await noteApi.update(props.paperId, body, version.value)
      saved.value = sent; savedTime.value = new Date().toLocaleTimeString('zh-CN'); return true
    } catch (e) { error.value = messageOf(e); conflict.value = e instanceof ApiError && e.conflict; return false }
    finally { saving.value = false; inflight = undefined }
  })()
  const result = await inflight
  if (result && dirty.value) timer = setTimeout(() => void save(), 900)
  return result
}
function exportNote() { downloadMarkdown(`论文-${props.paperId}-阅读笔记`, `# 阅读笔记\n\n论文 ID：${props.paperId}\n\n` + noteFields.map(([key, label]) => `## ${label}\n\n${form[key]}\n`).join('\n')) }
function reload() { if (!dirty.value || window.confirm('重新加载会替换当前未保存的编辑。建议先导出草稿备份，是否继续？')) void load() }
async function canLeave() {
  if (!dirty.value) return true
  await save()
  if (!dirty.value) return true
  return window.confirm('笔记尚未保存。离开将丢失本次编辑，是否仍要离开？')
}
watch(form, () => { clearTimeout(timer); if (dirty.value && !conflict.value && !error.value) timer = setTimeout(() => void save(), 900) })
function beforeUnload(e: BeforeUnloadEvent) { if (dirty.value) { e.preventDefault(); e.returnValue = '' } }
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload) })
onBeforeUnmount(() => { clearTimeout(timer); window.removeEventListener('beforeunload', beforeUnload) })
onBeforeRouteLeave(to => to.meta.public ? true : canLeave())
onBeforeRouteUpdate(async to => { if (String(to.params.id) !== props.paperId || (!props.embedded && to.query.tab !== 'notes')) return canLeave() })
</script>
<template>
  <section class="panel note-editor" :role="embedded ? undefined : 'tabpanel'"><div class="section-row"><div><h2>结构化阅读笔记</h2><p class="panel-subtitle">把理解写下来，让下一次阅读更进一步。</p></div><span class="save-state" role="status">{{ saving ? '正在保存…' : conflict ? '版本冲突 · 自动保存已暂停' : dirty ? '有未保存的修改' : savedTime ? `已保存 ${savedTime}` : version ? `已保存 · 版本 ${version}` : '编辑后自动保存' }}</span></div><Feedback :error="error" :loading="loading" /><div v-if="conflict" class="notice"><span>已保留当前编辑。请先导出草稿，再重新加载最新版本并合并内容。</span></div><button v-if="!loaded && !loading" class="btn" @click="load">重新加载</button><template v-if="loaded"><div class="note-grid"><div v-for="([key, label, hint], index) in noteFields" :key="key"><label class="form-label" :for="key"><span class="note-number">0{{ index + 1 }}</span>{{ label }}</label><textarea :id="key" v-model="form[key]" class="field textarea" :placeholder="hint" rows="5"></textarea></div></div><div class="editor-footer"><small class="muted">{{ version ? `当前版本 ${version} · ` : '' }}编辑后自动保存，版本冲突时保留草稿。</small><div class="toolbar"><button class="btn" @click="exportNote"><Download :size="15" />导出 Markdown</button><button v-if="conflict || error" class="btn" :disabled="saving" @click="reload"><RefreshCw :size="15" />加载最新版本</button><button class="btn primary" :disabled="saving || conflict || !dirty" @click="save"><Save :size="15" />保存笔记</button></div></div></template></section>
</template>
