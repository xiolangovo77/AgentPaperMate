<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowUpRight, Plus, MessagesSquare, Library, FileCheck2, Layers3, BookOpen } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { sessionApi } from '../api'
import { messageOf } from '../api/request'
import type { Session } from '../types/api'
import { dateTime } from '../utils/format'
import CapabilityGap from '../components/CapabilityGap.vue'
import Feedback from '../components/Feedback.vue'
const user = useUserStore(), sessions = ref<Session[]>([]), total = ref<string | number>('—'), error = ref(''), loading = ref(true)
const prompts = ['这篇论文要解决的核心问题是什么？', '比较这些论文采用的方法与实验结论。', '这些研究有哪些局限和值得探索的方向？']
async function load() { loading.value = true; error.value = ''; try { const page = await sessionApi.list(); sessions.value = page.records.slice(0, 4); total.value = page.total } catch (e) { error.value = messageOf(e) } finally { loading.value = false } }
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">RESEARCH OVERVIEW</div><h1>研究总览</h1><p>欢迎回来，{{ user.user?.nickname || user.user?.username }}。今天，从一个好问题开始。</p></div><RouterLink class="btn primary" to="/library?upload=1"><Plus :size="16" /> 导入 PDF</RouterLink></div>
  <section class="hero"><div class="hero-inner"><div class="hero-kicker">READ · CONNECT · DISCOVER</div><h2>让每一篇论文，<br class="mobile-only" />成为新想法的起点。</h2><p>在阅读中沉淀理解，在讨论中连接线索。建立自己的研究空间，让思考持续发生。</p><div class="hero-actions"><RouterLink class="btn" to="/chat"><MessagesSquare :size="16" /> 开始研究讨论</RouterLink><RouterLink class="btn secondary" to="/library">浏览论文库 <ArrowUpRight :size="15" /></RouterLink></div></div><div class="hero-decoration" aria-hidden="true"><BookOpen :size="118" :stroke-width="1" /></div></section>
  <div class="metric-grid"><article v-for="item in [{ label: '论文总数', value: '—', note: '等待论文查询接口', icon: Library }, { label: '可问答论文', value: '—', note: '等待索引状态查询', icon: FileCheck2 }, { label: '研究主题', value: '—', note: '等待主题统计数据', icon: Layers3 }, { label: '研究会话', value: total, note: '当前账号的全部会话', icon: MessagesSquare }]" :key="item.label" class="metric-card"><div class="metric-top">{{ item.label }}<component :is="item.icon" :size="18" /></div><div class="metric-number">{{ item.value }}</div><div class="metric-caption">{{ item.note }}</div></article></div>
  <div class="section-row"><div><h2>最近的研究讨论</h2><div class="sub">从上次停下的地方继续。</div></div><RouterLink class="text-link" to="/chat">全部会话 →</RouterLink></div>
  <Feedback :loading="loading" :error="error"><button class="text-link" @click="load">重试</button></Feedback>
  <div v-if="!loading && !error" class="paper-grid"><RouterLink v-for="session in sessions" :key="session.id" :to="`/chat/${session.id}`" class="paper-card session-card"><span class="session-icon"><MessagesSquare :size="20" /></span><div><h3 class="paper-title">{{ session.name }}</h3><p class="paper-meta">{{ dateTime(session.lastMessageTime || session.createTime) }}</p></div><ArrowUpRight class="muted" :size="17" /></RouterLink><div v-if="!sessions.length" class="empty-card empty-state full-span"><MessagesSquare :size="30" /><h3>你的第一场研究讨论，从这里开始</h3><p>新建聊天室，把问题和思考留在同一个地方。</p><RouterLink class="btn" to="/chat">创建聊天室</RouterLink></div></div>
  <div class="section-row"><h2>最近导入的论文</h2><RouterLink to="/library" class="text-link">前往论文库 →</RouterLink></div><CapabilityGap title="论文列表暂不可用" detail="当前服务尚未提供论文查询能力。你仍可以上传 PDF；论文数量、主题和处理结果将在查询能力接通后展示。" compact />
  <div class="two-col overview-bottom"><section class="panel"><h2 class="panel-title">从一个研究问题开始</h2><p class="panel-subtitle">选择一个问题，在聊天室中继续探索。</p><div class="prompt-list"><RouterLink v-for="prompt in prompts" :key="prompt" class="prompt-chip" :to="{ path: '/chat', query: { question: prompt } }"><span>↗</span>{{ prompt }}</RouterLink></div></section><section class="panel"><h2 class="panel-title">你的研究工作流</h2><div class="steps"><div v-for="(step, i) in [['整理资料', '导入论文，等待解析与索引完成。'], ['阅读与记录', '回到原文，沉淀你的理解与疑问。'], ['讨论与追溯', '围绕论文提问，结合引用核查结论。']]" :key="i" class="step"><span class="step-num">0{{ i + 1 }}</span><div><b>{{ step[0] }}</b><p>{{ step[1] }}</p></div></div></div></section></div>
</template>
