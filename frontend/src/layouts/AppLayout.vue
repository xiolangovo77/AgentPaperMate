<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LayoutDashboard, Library, MessagesSquare, LogOut, Settings2, PanelLeftClose, PanelLeftOpen } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'
import { messageOf } from '../api/request'
import { useChatStore } from '../stores/chat'
const route = useRoute(), router = useRouter(), user = useUserStore()
const busy = ref(false), error = ref('')
const isChat = computed(() => route.path === '/chat' || route.path.startsWith('/chat/'))
const collapsed = ref(false)
try { collapsed.value = localStorage.getItem('apm-navigation-collapsed') === 'true' } catch { /* Storage may be unavailable. */ }
watch(collapsed, value => { try { localStorage.setItem('apm-navigation-collapsed', String(value)) } catch { /* Keep the in-memory preference. */ } })
const displayName = computed(() => user.user?.nickname || user.user?.username || '研究者')
const links = [{ to: '/', title: '研究总览', icon: LayoutDashboard }, { to: '/library', title: '论文库', icon: Library }, { to: '/chat', title: '聊天室', icon: MessagesSquare }]
async function logout() {
  busy.value = true; error.value = ''
  try {
    // Route guards flush notes/drafts before the server session is invalidated.
    const failure = await router.push('/profile')
    if (failure && router.currentRoute.value.path !== '/profile') return
    await userApi.logout(); user.clear(); useChatStore().clear(); await router.replace('/login')
  } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
</script>
<template>
  <div class="app-shell" :class="{ 'chat-shell': isChat, 'navigation-collapsed': collapsed }">
    <aside class="sidebar">
      <RouterLink to="/" class="brand" aria-label="AgentPaperMate 研究总览" title="AgentPaperMate"><div class="brand-mark">AP</div><div class="brand-copy"><div class="brand-name">AgentPaperMate</div><div class="brand-sub">RESEARCH WORKSPACE</div></div></RouterLink>
      <button class="icon-btn navigation-toggle" :aria-label="collapsed ? '展开导航' : '折叠导航'" :title="collapsed ? '展开导航' : '折叠导航'" :aria-expanded="!collapsed" aria-controls="main-navigation" @click="collapsed = !collapsed"><PanelLeftOpen v-if="collapsed" :size="18" /><PanelLeftClose v-else :size="18" /></button>
      <div class="workspace-pill"><span class="status-dot"></span> 我的研究工作空间</div>
      <nav id="main-navigation" class="nav" aria-label="主导航"><div class="nav-label">工作空间</div>
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" class="nav-item" :aria-label="link.title" :title="link.title" :class="{ active: link.to === '/' ? route.path === '/' : route.path.startsWith(link.to) }"><component :is="link.icon" :size="18" /><span class="nav-text">{{ link.title }}</span></RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <RouterLink to="/profile" class="user-chip" :title="displayName + ' · 个人设置'" aria-label="个人设置"><img v-if="user.user?.avatarUrl" :src="user.user.avatarUrl" class="avatar" alt="头像" /><div v-else class="avatar">{{ displayName.slice(0, 1) }}</div><div class="user-copy"><b>{{ displayName }}</b><small>个人研究空间</small></div><Settings2 class="more" :size="16" /></RouterLink>
        <button v-if="isChat" class="icon-btn sidebar-logout" title="退出登录" aria-label="退出登录" :disabled="busy" @click="logout"><LogOut :size="17" /></button>
      </div>
      <details v-if="isChat" class="mobile-account conversation-menu">
        <summary class="icon-btn" aria-label="账户菜单" title="账户菜单"><Settings2 :size="18" /></summary>
        <div class="conversation-menu-items"><RouterLink to="/profile" class="mobile-profile"><Settings2 :size="15" />个人设置</RouterLink><button :disabled="busy" @click="logout"><LogOut :size="15" />退出登录</button></div>
      </details>
    </aside>
    <main class="main">
      <header v-if="!isChat" class="topbar"><div class="breadcrumbs"><span>工作空间</span><span>/</span><b>{{ route.meta.title }}</b></div><div class="top-actions"><RouterLink to="/profile" class="account-link">{{ displayName }}</RouterLink><button class="icon-btn" title="退出登录" aria-label="退出登录" :disabled="busy" @click="logout"><LogOut :size="17" /></button></div></header>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div class="content"><RouterView /></div>
      <footer class="footer">AgentPaperMate <span>·</span> 阅读有迹可循，研究持续发生。</footer>
    </main>
  </div>
</template>
