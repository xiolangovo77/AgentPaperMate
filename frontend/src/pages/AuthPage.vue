<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowRight, BookOpen, MessagesSquare, Quote } from 'lucide-vue-next'
import { useUserStore } from '../stores/user'
import { userApi } from '../api'
import { messageOf } from '../api/request'
import { validPassword } from '../utils/format'
import Feedback from '../components/Feedback.vue'
const route = useRoute(), router = useRouter(), user = useUserStore()
const register = computed(() => route.path === '/register')
const username = ref(''), password = ref(''), confirmation = ref(''), busy = ref(false)
const error = ref(typeof route.query.error === 'string' ? route.query.error : '')
watch(register, () => { password.value = ''; confirmation.value = ''; error.value = '' })
async function submit() {
  error.value = ''
  if (username.value.trim().length < 4 || username.value.trim().length > 64) { error.value = '账号长度需为 4–64 个字符。'; return }
  if (!validPassword(password.value)) { error.value = '密码至少 6 个字符，最多 72 个 UTF-8 字节。'; return }
  if (register.value && password.value !== confirmation.value) { error.value = '两次输入的密码不一致。'; return }
  busy.value = true
  try {
    if (register.value) await userApi.register(username.value, password.value, confirmation.value)
    await user.login(username.value, password.value)
    const target = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') && !route.query.redirect.startsWith('//') ? route.query.redirect : '/'
    await router.replace(target)
  } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
</script>
<template>
  <main class="auth-page">
    <section class="auth-intro"><div class="brand"><div class="brand-mark">AP</div><div><b>AgentPaperMate</b><div class="brand-sub">RESEARCH WORKSPACE</div></div></div><div class="auth-story"><div class="eyebrow">YOUR NEXT IDEA STARTS HERE</div><h1>读懂论文，<br />连接新的想法。</h1><p>让文献、思考与讨论，在同一个研究空间里自然相连。</p><div class="auth-feature"><BookOpen :size="20" /><span>整理文献，沉淀阅读笔记</span></div><div class="auth-feature"><MessagesSquare :size="20" /><span>围绕论文，展开研究讨论</span></div><div class="auth-feature"><Quote :size="20" /><span>回到原文，追溯每一条证据</span></div></div><small>为专注研究的你而设计。</small></section>
    <section class="auth-form-area"><form class="auth-form" @submit.prevent="submit"><span class="eyebrow">WELCOME TO YOUR WORKSPACE</span><h2>{{ register ? '创建你的研究空间' : '欢迎回来' }}</h2><p class="muted">{{ register ? '创建账号，开始积累你的研究。' : '登录后继续你的阅读与研究讨论。' }}</p>
      <div v-if="route.query.expired" class="notice">登录已失效，请重新登录。</div><Feedback :error="error" />
      <label class="form-label" for="username">账号</label><input id="username" v-model="username" class="field" autocomplete="username" minlength="4" maxlength="64" required placeholder="输入 4–64 位账号" :disabled="busy" />
      <label class="form-label" for="password">密码</label><input id="password" v-model="password" class="field" type="password" :autocomplete="register ? 'new-password' : 'current-password'" minlength="6" required placeholder="至少 6 个字符" :disabled="busy" />
      <template v-if="register"><label class="form-label" for="confirmation">确认密码</label><input id="confirmation" v-model="confirmation" type="password" class="field" autocomplete="new-password" required :disabled="busy" /></template>
      <button class="btn primary auth-submit" :disabled="busy">{{ busy ? '正在连接工作空间…' : register ? '创建账号并登录' : '登录工作空间' }}<ArrowRight :size="17" /></button>
      <p class="auth-switch">{{ register ? '已经有账号？' : '还没有账号？' }} <RouterLink :to="{ path: register ? '/login' : '/register', query: route.query.redirect ? { redirect: route.query.redirect } : {} }">{{ register ? '立即登录' : '创建账号' }}</RouterLink></p>
    </form></section>
  </main>
</template>
<style scoped>
.auth-page { --primary: #4d759b; --primary2: #80a7c8; --soft: #edf3f9; --muted: #788a9d; --text: #283e53; }
.auth-intro { background: linear-gradient(145deg, #f0f5fa, #f7f9fc); }
.auth-intro .brand-sub { color: #8b9dae; }
.auth-story h1 { color: #2d4b68; }
.auth-story > p { color: #74899d; }
.auth-feature { color: #607b96; }
.auth-feature svg { color: #6e94b5; }
.auth-intro > small { color: #98a8b8; }
.auth-form h2 { color: #283e53; }
.auth-form .form-label { color: #647b90; }
.auth-form .field { border-color: #e1e8ef; color: #354c63; }
.auth-form .field::placeholder { color: #9baab9; }
.auth-form .field:focus { border-color: #91b1cd; box-shadow: 0 0 0 3px #6e94b514; }
.auth-page .auth-submit { background: #4d759b; border-color: #4d759b; box-shadow: 0 5px 12px #4d759b1f; }
.auth-page .auth-submit:hover { background: #3e6488; border-color: #3e6488; }
.auth-page :is(button, a, input):focus-visible { outline-color: #6e94b5; }
</style>
