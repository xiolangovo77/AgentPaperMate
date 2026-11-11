import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/request'
import { useUserStore } from './stores/user'
import { useChatStore } from './stores/chat'
import './styles/global.css'
import './styles/app.css'
import './styles/typography.css'
import './styles/papers.css'
import './styles/chat.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
setUnauthorizedHandler(() => {
  useUserStore().clear()
  useChatStore().clear()
  if (!router.currentRoute.value.meta.public) void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath, expired: '1' } })
})
app.mount('#app')
