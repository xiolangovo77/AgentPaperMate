<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LayoutDashboard, Library, MessagesSquare, LogOut, Settings2, BookOpen, ArrowUpRight } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'
import { messageOf } from '../api/request'
import { useChatStore } from '../stores/chat'
const route = useRoute(), router = useRouter(), user = useUserStore()
const busy = ref(false), error = ref('')
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
  <div class="app-shell">
    <aside class="sidebar">
      <RouterLink to="/" class="brand"><div class="brand-mark">AP</div><div><div class="brand-name">AgentPaperMate</div><div class="brand-sub">RESEARCH WORKSPACE</div></div></RouterLink>
      <div class="workspace-pill"><span class="status-dot"></span> 我的研究工作空间</div>
      <nav class="nav" aria-label="主导航"><div class="nav-label">工作空间</div>
        <RouterLink v-for="link in links" :key="link.to" :to="link.to" class="nav-item" :class="{ active: link.to === '/' ? route.path === '/' : route.path.startsWith(link.to) }"><component :is="link.icon" :size="18" />{{ link.title }}</RouterLink>
      </nav>
      <div class="sidebar-bottom">
        <div class="workspace-note"><BookOpen :size="20" /><b>从阅读，到新的想法。</b><p>让每一次研究讨论，<br />都回到论文与证据。</p><RouterLink to="/chat">继续研究 <ArrowUpRight :size="14" /></RouterLink></div>
        <RouterLink to="/profile" class="user-chip"><img v-if="user.user?.avatarUrl" :src="user.user.avatarUrl" class="avatar" alt="头像" /><div v-else class="avatar">{{ displayName.slice(0, 1) }}</div><div><b>{{ displayName }}</b><small>个人研究空间</small></div><Settings2 class="more" :size="16" /></RouterLink>
      </div>
    </aside>
    <main class="main">
      <header class="topbar"><div class="breadcrumbs"><span>工作空间</span><span>/</span><b>{{ route.meta.title }}</b></div><div class="top-actions"><RouterLink to="/profile" class="account-link">{{ displayName }}</RouterLink><button class="icon-btn" title="退出登录" aria-label="退出登录" :disabled="busy" @click="logout"><LogOut :size="17" /></button></div></header>
      <div v-if="error" class="notice error" role="alert">{{ error }}</div>
      <div class="content"><RouterView /></div>
      <footer class="footer">AgentPaperMate <span>·</span> 阅读有迹可循，研究持续发生。</footer>
    </main>
  </div>
</template>
