<script setup lang="ts">
import { onMounted, ref, onBeforeUnmount, onUnmounted } from 'vue'
import { X } from 'lucide-vue-next'
defineProps<{ title: string; busy?: boolean; wide?: boolean; drawer?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const previous = document.activeElement as HTMLElement | null
onMounted(() => dialog.value?.showModal())
// Release the modal's inert background before restoring its trigger's focus.
onBeforeUnmount(() => dialog.value?.close())
onUnmounted(() => { if (previous?.isConnected) previous.focus() })
</script>
<template>
  <dialog ref="dialog" class="app-dialog" :class="{ wide, drawer }" aria-labelledby="dialog-title" @cancel.prevent="!busy && emit('close')" @click="event => { if (event.target === dialog && !busy) emit('close') }">
    <header class="dialog-header"><h2 id="dialog-title">{{ title }}</h2><button class="icon-btn" aria-label="关闭" :disabled="busy" @click="emit('close')"><X :size="19" /></button></header>
    <div class="dialog-body"><slot /></div>
  </dialog>
</template>
