<template>
  <div class="chat-header border-bottom p-3 bg-light">
    <div class="d-flex justify-content-between align-items-center">
      <div class="d-flex align-items-center">
        <h5 class="mb-0 me-3">
          <i class="bi bi-hash me-1"></i>
          {{ roomId }}
        </h5>

        <div class="connection-status">
          <span
            class="badge rounded-pill"
            :class="isConnected ? 'bg-success' : 'bg-danger'"
          >
            <i :class="isConnected ? 'bi bi-wifi' : 'bi bi-wifi-off'" class="me-1"></i>
            {{ isConnected ? '연결됨' : '연결 끊김' }}
          </span>
        </div>
      </div>

      <div class="d-flex align-items-center gap-3">
        <div class="participants d-none d-md-flex align-items-center">
          <i class="bi bi-people me-1"></i>
          <small class="text-muted">{{ participants.length }}명 온라인</small>
        </div>

        <!-- User profile -->
        <div class="dropdown">
          <button
            class="btn btn-outline-secondary btn-sm dropdown-toggle d-flex align-items-center"
            type="button"
            data-bs-toggle="dropdown"
          >
            <span class="user-avatar me-1">{{ avatarText }}</span>
            <span class="d-none d-md-inline">{{ username }}</span>
            <span v-if="isGuest" class="badge bg-secondary ms-1 d-none d-md-inline" style="font-size: 0.6em;">게스트</span>
          </button>
          <ul class="dropdown-menu dropdown-menu-end">
            <li>
              <span class="dropdown-item-text small text-muted">
                <i class="bi bi-person me-2"></i>{{ username }}
              </span>
            </li>
            <li v-if="isGuest">
              <a class="dropdown-item" href="/login">
                <i class="bi bi-box-arrow-in-right me-2"></i>
                로그인 / 회원가입
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li>
              <a class="dropdown-item" href="#">
                <i class="bi bi-info-circle me-2"></i>
                채팅방 정보
              </a>
            </li>
            <li>
              <a class="dropdown-item" href="#">
                <i class="bi bi-bell me-2"></i>
                알림 설정
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li>
              <a class="dropdown-item text-danger" href="#" @click.prevent="$emit('logout')">
                <i class="bi bi-box-arrow-left me-2"></i>
                로그아웃
              </a>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  roomId: string
  isConnected: boolean
  participants: string[]
  username: string
  isGuest: boolean
}

const props = defineProps<Props>()
defineEmits<{ logout: [] }>()

const avatarText = computed(() => {
  return props.username ? props.username.substring(0, 2).toUpperCase() : '??'
})
</script>

<style scoped>
.chat-header {
  background-color: var(--bs-light);
  border-color: var(--bs-border-color);
}

[data-bs-theme="dark"] .chat-header {
  background-color: var(--bs-dark);
}

.connection-status .badge {
  font-size: 0.75em;
}

.user-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background-color: var(--bs-primary);
  color: white;
  font-size: 0.6em;
  font-weight: bold;
}
</style>
