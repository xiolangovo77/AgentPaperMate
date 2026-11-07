import { defineStore } from 'pinia'
import { ref } from 'vue'
import { userApi } from '../api'
import { ApiError } from '../api/request'
import type { User } from '../types/api'

export const useUserStore = defineStore('user', () => {
  const user = ref<User | null>(null)
  const checked = ref(false)
  async function restore() {
    try { user.value = await userApi.current() }
    catch (error) { if (!(error instanceof ApiError && error.unauthorized)) throw error; user.value = null }
    checked.value = true
  }
  async function login(username: string, password: string) { user.value = await userApi.login(username, password); checked.value = true }
  function clear() { user.value = null; checked.value = false }
  return { user, checked, restore, login, clear }
})
