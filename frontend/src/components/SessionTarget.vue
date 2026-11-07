<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { sessionApi } from '../api'
import { ApiError, messageOf } from '../api/request'
import { readScope } from '../utils/papers'
import type { Session } from '../types/api'
import Modal from './Modal.vue'
import Feedback from './Feedback.vue'
const props = defineProps<{ paperIds: string[]; mode: 'create' | 'existing' }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter(), name = ref(''), target = ref(''), sessions = ref<Session[]>([]), page = ref(0), pages = ref(1)
const busy = ref(false), loading = ref(false), error = ref(''), conflict = ref(false), createdId = ref('')
async function loadMore() { loading.value = true; try { const result = await sessionApi.list(page.value + 1); sessions.value.push(...result.records); page.value++; pages.value = Number(result.pages) } catch (e) { error.value = messageOf(e) } finally { loading.value = false } }
async function submit() {
  busy.value = true; error.value = ''; conflict.value = false
  try {
    if (props.mode === 'create' && !createdId.value) createdId.value = await sessionApi.add(name.value.trim() || '论文研究讨论')
    const id = createdId.value || target.value
    const snapshot = await readScope(id)
    const ids = [...new Set([...snapshot.papers.map(item => item.paperId), ...props.paperIds])]
    if (ids.length > 100) throw new Error('合并后的资料超过 100 篇，请减少选择。')
    await sessionApi.updateScope(id, ids, snapshot.session.scopeVersion)
    emit('close'); await router.push(`/chat/${id}`)
  } catch (e) { error.value = messageOf(e); conflict.value = e instanceof ApiError && e.conflict }
  finally { busy.value = false }
}
onMounted(() => { if (props.mode === 'existing') void loadMore() })
</script>
<template><Modal :title="mode === 'create' ? '用所选论文创建聊天室' : '加入已有聊天室'" :busy="busy" @close="emit('close')"><form @submit.prevent="submit"><p class="muted">已选择 {{ paperIds.length }} 篇论文。添加到已有会话时保留其原有资料。</p><Feedback :error="error" /><p v-if="conflict" class="notice">会话资料已变化。再次确认会重新读取最新范围，合并后原子提交。</p><p v-if="createdId" class="notice">会话已创建，资料尚未保存。重试会复用此会话。<RouterLink class="text-link" :to="`/chat/${createdId}`">前往会话</RouterLink></p><label v-if="mode === 'create'" class="form-label">会话名称<input v-model="name" class="field" maxlength="200" placeholder="例如：论文方法对比" :disabled="busy || !!createdId" /></label><template v-else><label class="form-label">选择聊天室<select v-model="target" class="field" required :disabled="busy"><option disabled value="">请选择</option><option v-for="session in sessions" :key="session.id" :value="session.id">{{ session.name }}</option></select></label><p v-if="!loading && !sessions.length" class="muted">暂无聊天室，请先新建。</p><button v-if="page < pages" type="button" class="text-link" :disabled="loading" @click="loadMore">{{ loading ? '加载中…' : '加载更多会话' }}</button></template><div class="dialog-actions"><button type="button" class="btn" :disabled="busy" @click="emit('close')">取消</button><button class="btn primary" :disabled="busy || !paperIds.length || (mode === 'existing' && !target)">{{ busy ? '正在保存…' : conflict ? '按最新范围重新确认' : '确认并进入聊天室' }}</button></div></form></Modal></template>
