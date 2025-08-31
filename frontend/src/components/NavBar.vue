<template>
  <nav class="navbar navbar-expand-lg navbar-dark bg-primary">
    <div class="container-fluid">
      <RouterLink class="navbar-brand fw-bold" to="/">
        <i class="bi bi-chat-dots me-2"></i>
        ChatFlow
      </RouterLink>

      <button 
        class="navbar-toggler" 
        type="button" 
        data-bs-toggle="collapse" 
        data-bs-target="#navbarNav"
      >
        <span class="navbar-toggler-icon"></span>
      </button>

      <div class="collapse navbar-collapse" id="navbarNav">
        <ul class="navbar-nav me-auto">
          <li class="nav-item">
            <RouterLink class="nav-link" to="/chat">
              <i class="bi bi-chat me-1"></i>
              채팅
            </RouterLink>
          </li>
          <li class="nav-item">
            <RouterLink class="nav-link" to="/search">
              <i class="bi bi-search me-1"></i>
              검색
            </RouterLink>
          </li>
        </ul>

        <div class="d-flex align-items-center">
          <button 
            class="btn btn-outline-light me-2" 
            @click="$emit('toggle-theme')"
            :title="getThemeDisplayName()"
          >
            <i :class="getThemeIcon()"></i>
          </button>
          
          <div class="dropdown">
            <button 
              class="btn btn-outline-light dropdown-toggle" 
              type="button" 
              data-bs-toggle="dropdown"
            >
              <i class="bi bi-person me-1"></i>
              {{ username || '게스트' }}
            </button>
            <ul class="dropdown-menu dropdown-menu-end">
              <li>
                <a class="dropdown-item" href="#" @click="showUsernameModal = true">
                  <i class="bi bi-pencil me-1"></i>
                  이름 변경
                </a>
              </li>
              <li><hr class="dropdown-divider"></li>
              <li>
                <a class="dropdown-item text-danger" href="#" @click="logout">
                  <i class="bi bi-box-arrow-right me-1"></i>
                  로그아웃
                </a>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>

    <!-- 사용자명 변경 모달 -->
    <div 
      class="modal fade" 
      id="usernameModal" 
      tabindex="-1" 
      :class="{ show: showUsernameModal }"
      :style="{ display: showUsernameModal ? 'block' : 'none' }"
      @click.self="showUsernameModal = false"
    >
      <div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-header">
            <h5 class="modal-title">이름 변경</h5>
            <button 
              type="button" 
              class="btn-close" 
              @click="showUsernameModal = false"
            ></button>
          </div>
          <div class="modal-body">
            <div class="mb-3">
              <label for="newUsername" class="form-label">새로운 이름</label>
              <input 
                type="text" 
                class="form-control" 
                id="newUsername"
                v-model="newUsername" 
                @keyup.enter="updateUsername"
                maxlength="20"
              >
            </div>
          </div>
          <div class="modal-footer">
            <button 
              type="button" 
              class="btn btn-secondary" 
              @click="showUsernameModal = false"
            >
              취소
            </button>
            <button 
              type="button" 
              class="btn btn-primary" 
              @click="updateUsername"
              :disabled="!newUsername.trim()"
            >
              변경
            </button>
          </div>
        </div>
      </div>
    </div>
    <div 
      v-if="showUsernameModal" 
      class="modal-backdrop fade show"
    ></div>
  </nav>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { useLocalStorage } from '@vueuse/core'
import { useTheme } from '@/composables/useTheme'

defineEmits<{
  'toggle-theme': []
}>()

const { theme, getThemeDisplayName, getThemeIcon } = useTheme()

const username = useLocalStorage('chatflow-username', '')
const showUsernameModal = ref(false)
const newUsername = ref('')

const updateUsername = () => {
  if (newUsername.value.trim()) {
    username.value = newUsername.value.trim()
    showUsernameModal.value = false
    newUsername.value = ''
  }
}

const logout = () => {
  username.value = ''
  // 추가적인 로그아웃 로직
}

onMounted(() => {
  if (!username.value) {
    username.value = `Guest_${Math.floor(Math.random() * 1000)}`
  }
})
</script>

<style scoped>
.navbar-brand {
  font-size: 1.5rem;
}

.nav-link.router-link-active {
  color: rgba(255, 255, 255, 0.9) !important;
  font-weight: 500;
}
</style>