<template>
  <div id="app" :data-bs-theme="theme">
    <NavBar @toggle-theme="toggleTheme" />
    <main class="container-fluid h-100">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { RouterView } from 'vue-router'
import NavBar from '@/components/NavBar.vue'
import { useTheme } from '@/composables/useTheme'

const { theme, toggleTheme } = useTheme()

onMounted(() => {
  // Bootstrap theme 초기화
  document.documentElement.setAttribute('data-bs-theme', theme.value)
})
</script>

<style lang="scss">
#app {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

main {
  flex: 1;
  overflow: hidden;
}
</style>