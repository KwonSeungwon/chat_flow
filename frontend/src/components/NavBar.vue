<template>
  <nav class="navbar navbar-dark bg-primary px-2 py-1">
    <div class="container-fluid">
      <RouterLink class="navbar-brand fw-bold" to="/">
        <i class="bi bi-chat-dots me-1"></i>
        <span class="d-none d-sm-inline">ChatFlow</span>
      </RouterLink>

      <div class="d-flex align-items-center gap-2">
        <RouterLink class="btn btn-outline-light btn-sm" to="/search" title="검색">
          <i class="bi bi-search"></i>
        </RouterLink>

        <button
          class="btn btn-outline-light btn-sm"
          @click="$emit('toggle-theme')"
          :title="getThemeDisplayName()"
        >
          <i :class="getThemeIcon()"></i>
        </button>
      </div>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useTheme } from '@/composables/useTheme'

defineEmits<{ 'toggle-theme': [] }>()

const { getThemeDisplayName, getThemeIcon } = useTheme()
</script>

<style scoped>
.navbar {
  min-height: 40px;
}
.navbar-brand {
  font-size: 1.1rem;
}
</style>
