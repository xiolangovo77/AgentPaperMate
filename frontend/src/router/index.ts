import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '../stores/user'
import { messageOf } from '../api/request'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('../pages/AuthPage.vue'), meta: { public: true, title: '登录' } },
    { path: '/register', component: () => import('../pages/AuthPage.vue'), meta: { public: true, title: '注册' } },
    { path: '/', component: () => import('../layouts/AppLayout.vue'), children: [
      { path: '', name: 'overview', component: () => import('../pages/OverviewPage.vue'), meta: { title: '研究总览' } },
      { path: 'library', component: () => import('../pages/LibraryPage.vue'), meta: { title: '论文库' } },
      { path: 'library/:id', component: () => import('../pages/PaperDetail.vue'), meta: { title: '论文阅读' } },
      { path: 'chat/:id?', component: () => import('../pages/ChatPage.vue'), meta: { title: '聊天室' } },
      { path: 'profile', component: () => import('../pages/ProfilePage.vue'), meta: { title: '个人设置' } },
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
router.beforeEach(async to => {
  const store = useUserStore()
  if (to.meta.public) return true
  try { if (!store.checked) await store.restore() }
  catch (error) { return { path: '/login', query: { redirect: to.fullPath, error: messageOf(error) } } }
  if (!store.user) return { path: '/login', query: { redirect: to.fullPath } }
})
router.afterEach(to => { document.title = `${to.meta.title || '研究工作台'} · AgentPaperMate` })
