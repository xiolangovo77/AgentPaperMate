<script setup lang="ts">
import { AlertCircle, LoaderCircle } from 'lucide-vue-next'
defineProps<{ error?: string; loading?: boolean }>()
</script>
<template>
  <div v-if="error" class="notice error" role="alert"><AlertCircle :size="17" /><span>{{ error }}</span><slot /></div>
  <div v-else-if="loading" class="loading-state" role="status"><LoaderCircle class="spin" :size="21" /> 正在加载…</div>
</template>
