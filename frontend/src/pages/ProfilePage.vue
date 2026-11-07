<script setup lang="ts">
import { ref } from 'vue'
import { useUserStore } from '../stores/user'
import { userApi, uploadFile } from '../api'
import { messageOf } from '../api/request'
import { validPassword } from '../utils/format'
import Feedback from '../components/Feedback.vue'
const user = useUserStore()
const nickname = ref(user.user?.nickname || ''), password = ref(''), confirmation = ref(''), avatar = ref(user.user?.avatarUrl || '')
const busy = ref(false), error = ref(''), success = ref('')
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  error.value = ''; success.value = ''
  if (!/\.(png|jpe?g|webp)$/i.test(file.name) || file.size > 2 * 1024 * 1024 || file.size === 0) { error.value = '请选择不超过 2 MB 的 JPG、PNG 或 WEBP 图片。'; input.value = ''; return }
  busy.value = true
  try { avatar.value = await uploadFile('avatar', file); success.value = '头像已上传，保存资料后生效。' } catch (e) { error.value = messageOf(e) } finally { busy.value = false; input.value = '' }
}
async function save() {
  error.value = ''; success.value = ''
  if (!nickname.value.trim()) { error.value = '昵称不能为空。'; return }
  if (password.value && (!validPassword(password.value) || password.value !== confirmation.value)) { error.value = '新密码需至少 6 个字符、最多 72 个 UTF-8 字节，且两次输入一致。'; return }
  busy.value = true
  try { await userApi.update({ nickname: nickname.value.trim(), avatarUrl: avatar.value, ...(password.value ? { password: password.value } : {}) }); await user.restore(); password.value = ''; confirmation.value = ''; success.value = '个人资料已保存。' } catch (e) { error.value = messageOf(e) } finally { busy.value = false }
}
</script>
<template>
  <div class="page-heading"><div><div class="eyebrow">YOUR PROFILE</div><h1>个人设置</h1><p>让工作空间更像你。</p></div></div>
  <form class="panel profile-form" @submit.prevent="save"><Feedback :error="error" /><p v-if="success" class="notice success" role="status">{{ success }}</p><div class="profile-avatar"><img v-if="avatar" :src="avatar" class="avatar large" alt="头像预览" /><div v-else class="avatar large">{{ nickname.slice(0, 1) }}</div><label class="btn">上传头像<input type="file" class="sr-only" accept=".jpg,.jpeg,.png,.webp" :disabled="busy" @change="upload" /></label><small class="muted">JPG / PNG / WEBP，最多 2 MB</small></div><label class="form-label" for="account">账号</label><input id="account" :value="user.user?.username" class="field" disabled /><label class="form-label" for="nickname">昵称</label><input id="nickname" v-model="nickname" class="field" required maxlength="100" :disabled="busy" /><div class="divider"></div><h2 class="panel-title">修改密码</h2><p class="panel-subtitle">留空则保留当前密码。</p><label class="form-label" for="new-password">新密码</label><input id="new-password" v-model="password" type="password" autocomplete="new-password" class="field" :disabled="busy" /><label class="form-label" for="confirm-password">确认新密码</label><input id="confirm-password" v-model="confirmation" type="password" autocomplete="new-password" class="field" :disabled="busy" /><button class="btn primary form-submit" :disabled="busy">{{ busy ? '正在保存…' : '保存资料' }}</button></form>
</template>
