<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Library, LogOut, Settings2, PanelLeftClose, PanelLeftOpen, SquarePen } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'
import { messageOf } from '../api/request'
import { useChatStore } from '../stores/chat'
import { useSessionNavigationStore } from '../stores/sessionNavigation'
import SessionSidebar from '../components/SessionSidebar.vue'
import Modal from '../components/Modal.vue'
const route = useRoute(), router = useRouter(), user = useUserStore(), navigation = useSessionNavigationStore()
const busy = ref(false), error = ref(''), collapsed = ref(false)
const sidebarWidth = ref(300), viewportWidth = ref(window.innerWidth), resizing = ref(false)
const minSidebarWidth = 240
const maxSidebarWidth = computed(() => Math.min(480, Math.max(minSidebarWidth, viewportWidth.value - 420)))
const visibleSidebarWidth = computed(() => Math.min(sidebarWidth.value, maxSidebarWidth.value))
let resizeHandle: HTMLElement | undefined, resizePointer = -1, dragStart = 0, dragWidth = 300
try {
  const saved = Number(localStorage.getItem('apm-navigation-width'))
  if (Number.isFinite(saved) && saved >= minSidebarWidth && saved <= 480) sidebarWidth.value = saved
} catch { /* The sidebar remains resizable without storage. */ }
function saveSidebarWidth() {
  try { localStorage.setItem('apm-navigation-width', String(sidebarWidth.value)) } catch { /* Keep the in-memory width. */ }
}
function setSidebarWidth(value: number) { sidebarWidth.value = Math.round(Math.max(minSidebarWidth, Math.min(maxSidebarWidth.value, value))) }
function stopResize() {
  const handle = resizeHandle, pointer = resizePointer
  resizeHandle = undefined; resizePointer = -1
  if (resizing.value) { resizing.value = false; saveSidebarWidth() }
  if (handle?.hasPointerCapture(pointer)) handle.releasePointerCapture(pointer)
}
function startResize(event: PointerEvent) {
  if (event.button !== 0 || collapsed.value || window.innerWidth <= 720) return
  event.preventDefault()
  resizeHandle = event.currentTarget as HTMLElement; resizePointer = event.pointerId
  dragStart = event.clientX; dragWidth = visibleSidebarWidth.value; resizing.value = true
  resizeHandle.focus(); resizeHandle.setPointerCapture(event.pointerId)
}
function moveResize(event: PointerEvent) {
  if (resizing.value && event.pointerId === resizePointer) setSidebarWidth(dragWidth + event.clientX - dragStart)
}
function resizeWithKeyboard(event: KeyboardEvent) {
  const step = event.shiftKey ? 40 : 10
  if (event.key === 'ArrowLeft') setSidebarWidth(visibleSidebarWidth.value - step)
  else if (event.key === 'ArrowRight') setSidebarWidth(visibleSidebarWidth.value + step)
  else if (event.key === 'Home') setSidebarWidth(minSidebarWidth)
  else if (event.key === 'End') setSidebarWidth(maxSidebarWidth.value)
  else return
  event.preventDefault(); saveSidebarWidth()
}
function resetSidebarWidth() { setSidebarWidth(300); saveSidebarWidth() }
function updateViewport() { viewportWidth.value = window.innerWidth; if (viewportWidth.value <= 720) stopResize() }
watch(collapsed, stopResize)
onMounted(() => { window.addEventListener('resize', updateViewport); window.addEventListener('blur', stopResize) })
onBeforeUnmount(() => { stopResize(); window.removeEventListener('resize', updateViewport); window.removeEventListener('blur', stopResize) })
const isChat = computed(() => route.path === '/chat' || route.path.startsWith('/chat/'))
const isReading = computed(() => /^\/library\/[^/]+$/.test(route.path))
const selectedId = computed(() => isChat.value && typeof route.params.id === 'string' ? route.params.id : undefined)
const sidebarProps = computed(() => ({ sessions: navigation.sessions, current: selectedId.value === navigation.current?.id ? navigation.current : null, selectedId: selectedId.value, query: navigation.query, busy: navigation.busy, error: navigation.error, hasMore: navigation.page < navigation.pages, expanded: navigation.expanded }))
try { collapsed.value = localStorage.getItem('apm-navigation-collapsed') === 'true' } catch { /* Storage may be unavailable. */ }
watch(collapsed, value => { try { localStorage.setItem('apm-navigation-collapsed', String(value)) } catch { /* Keep the in-memory preference. */ } })
watch(() => user.user?.id, id => navigation.setOwner(id), { immediate: true })
watch(() => route.fullPath, () => { navigation.showHistory = false })
const displayName = computed(() => user.user?.nickname || user.user?.username || '研究者')
const links = [{ to: '/library', title: '论文库', icon: Library }]
function startNew() { navigation.startNew(typeof route.query.question === 'string' ? route.query.question : '') }
async function selectSession(id: string) { const failure = await router.push(`/chat/${id}`); if (!failure || selectedId.value === id) navigation.showHistory = false }
async function logout() {
  busy.value = true; error.value = ''
  try {
    const failure = await router.push('/profile')
    if (failure && router.currentRoute.value.path !== '/profile') return
    await userApi.logout(); user.clear(); useChatStore().clear(); await router.replace('/login')
  } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
</script>
<template>
  <div class="app-shell global-navigation" :class="{ 'chat-shell': isChat, 'reading-shell': isReading, 'navigation-collapsed': collapsed, 'navigation-resizing': resizing }" :style="{ '--navigation-width': `${visibleSidebarWidth}px` }">
    <aside id="workspace-sidebar" class="sidebar">
      <RouterLink to="/chat" class="brand" aria-label="AgentPaperMate 聊天首页" title="AgentPaperMate"><div class="brand-mark">AP</div><div class="brand-copy"><div class="brand-name">AgentPaperMate</div></div></RouterLink>
      <button class="icon-btn navigation-toggle" :aria-label="collapsed ? '展开导航' : '折叠导航'" :title="collapsed ? '展开导航' : '折叠导航'" :aria-expanded="!collapsed" aria-controls="main-navigation" @click="collapsed = !collapsed"><PanelLeftOpen v-if="collapsed" :size="18" /><PanelLeftClose v-else :size="18" /></button>
      <button class="icon-btn global-history-trigger" aria-label="打开会话侧栏" title="会话与导航" @click="navigation.showHistory = true"><PanelLeftOpen :size="19" /></button>
      <button class="session-new global-new-session" aria-label="新建聊天室" title="新建会话" :disabled="navigation.creating" @click="startNew"><SquarePen :size="19" /><span>{{ navigation.creating ? '正在打开…' : '新建会话' }}</span></button>
      <nav id="main-navigation" class="nav" aria-label="主导航"><RouterLink v-for="link in links" :key="link.to" :to="link.to" class="nav-item" :aria-label="link.title" :title="link.title" :class="{ active: route.path.startsWith(link.to) }"><component :is="link.icon" :size="18" /><span class="nav-text">{{ link.title }}</span></RouterLink></nav>
      <div id="chat-sidebar-content" class="chat-sidebar-content"><SessionSidebar v-bind="sidebarProps" @create="startNew" @select="selectSession" @search="navigation.search" @toggle="navigation.expanded = !navigation.expanded" @retry="navigation.load(navigation.retryMore)" @more="navigation.load(true)" /></div>
      <div class="sidebar-bottom"><RouterLink to="/profile" class="user-chip" :title="displayName + ' · 个人设置'" aria-label="个人设置"><img v-if="user.user?.avatarUrl" :src="user.user.avatarUrl" class="avatar" alt="头像" /><div v-else class="avatar">{{ displayName.slice(0, 1) }}</div><div class="user-copy"><b>{{ displayName }}</b><small>个人研究空间</small></div><Settings2 class="more" :size="16" /></RouterLink><button class="icon-btn sidebar-logout" title="退出登录" aria-label="退出登录" :disabled="busy" @click="logout"><LogOut :size="17" /></button></div>
      <details class="mobile-account conversation-menu"><summary class="icon-btn" aria-label="账户菜单" title="账户菜单"><Settings2 :size="18" /></summary><div class="conversation-menu-items"><RouterLink to="/profile" class="mobile-profile"><Settings2 :size="15" />个人设置</RouterLink><button :disabled="busy" @click="logout"><LogOut :size="15" />退出登录</button></div></details>
      <div v-if="!collapsed" class="sidebar-resize-handle" role="separator" tabindex="0" aria-label="调整导航栏宽度" aria-orientation="vertical" aria-controls="workspace-sidebar" :aria-valuemin="minSidebarWidth" :aria-valuemax="maxSidebarWidth" :aria-valuenow="visibleSidebarWidth" title="拖动调整宽度，双击恢复默认" @pointerdown="startResize" @pointermove="moveResize" @pointerup="stopResize" @pointercancel="stopResize" @lostpointercapture="stopResize" @keydown="resizeWithKeyboard" @dblclick="resetSidebarWidth"></div>
    </aside>
    <main class="main">
      <header v-if="!isChat && !isReading" class="topbar"><div class="breadcrumbs"><span>工作空间</span><span>/</span><b>{{ route.meta.title }}</b></div><div class="top-actions"><RouterLink to="/profile" class="account-link">{{ displayName }}</RouterLink></div></header>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div v-if="navigation.creationError" class="notice error" role="alert">{{ navigation.creationError }}</div>
      <div class="content"><RouterView /></div>
      <footer class="footer">AgentPaperMate <span>·</span> 阅读有迹可循，研究持续发生。</footer>
    </main>
    <Modal v-if="navigation.showHistory" title="最近会话" drawer class="history-drawer global-history-drawer" @close="navigation.showHistory = false">
      <button class="session-new" aria-label="新建聊天室" :disabled="navigation.creating" @click="startNew"><SquarePen :size="19" /><span>新建会话</span></button>
      <nav class="nav" aria-label="侧栏导航"><RouterLink v-for="link in links" :key="link.to" :to="link.to" class="nav-item" :class="{ active: route.path.startsWith(link.to) }"><component :is="link.icon" :size="18" /><span>{{ link.title }}</span></RouterLink></nav>
      <SessionSidebar v-bind="sidebarProps" @create="startNew" @select="selectSession" @search="navigation.search" @toggle="navigation.expanded = !navigation.expanded" @retry="navigation.load(navigation.retryMore)" @more="navigation.load(true)" />
    </Modal>
  </div>
</template>
