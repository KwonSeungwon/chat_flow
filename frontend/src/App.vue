<template>
  <div id="app" :data-bs-theme="theme">
    <NavBar v-if="showNavBar" @toggle-theme="toggleTheme" />
    <main class="container-fluid" :class="{ 'h-100': showNavBar, 'min-vh-100': !showNavBar }">
      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import NavBar from '@/components/NavBar.vue'
import { useTheme } from '@/composables/useTheme'

const route = useRoute()
const { theme, toggleTheme } = useTheme()

const showNavBar = computed(() => route.name !== 'login')

onMounted(() => {
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
