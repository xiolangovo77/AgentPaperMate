<script setup lang="ts">
import { CircleDashed } from 'lucide-vue-next'
defineProps<{ title: string; detail: string; compact?: boolean }>()
</script>
<template>
  <div :class="compact ? 'notice blue' : 'capability-gap'">
    <CircleDashed :size="compact ? 18 : 30" />
    <div><strong>{{ title }}</strong><p>{{ detail }}</p><slot /></div>
  </div>
</template>
