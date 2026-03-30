<template>
  <div class="chat-header border-bottom px-2 py-2">
    <div class="d-flex justify-content-between align-items-center">
      <div class="d-flex align-items-center gap-2 min-width-0">
        <!-- 모바일 채팅방 토글 -->
        <button class="btn btn-sm btn-outline-secondary d-md-none" @click="$emit('toggle-sidebar')">
          <i class="bi bi-list"></i>
        </button>

        <h6 class="mb-0 text-truncate">
          <i class="bi bi-hash"></i>{{ roomId }}
        </h6>

        <span
          class="badge rounded-pill flex-shrink-0"
          :class="isConnected ? 'bg-success' : 'bg-danger'"
          style="font-size: 0.65em;"
        >
          {{ isConnected ? '연결' : '끊김' }}
        </span>
      </div>

      <div class="d-flex align-items-center gap-1 flex-shrink-0">
        <!-- User dropdown -->
        <div class="dropdown">
          <button
            class="btn btn-outline-secondary btn-sm d-flex align-items-center gap-1"
            type="button"
            data-bs-toggle="dropdown"
          >
            <span class="avatar-sm">{{ avatarText }}</span>
            <span class="d-none d-sm-inline text-truncate" style="max-width: 80px;">{{ username }}</span>
          </button>
          <ul class="dropdown-menu dropdown-menu-end">
            <li>
              <span class="dropdown-item-text small text-muted">
                <i class="bi bi-person me-1"></i>{{ username }}
                <span v-if="isGuest" class="badge bg-secondary ms-1" style="font-size: 0.7em;">게스트</span>
              </span>
            </li>
            <li v-if="isGuest">
              <a class="dropdown-item" href="/login">
                <i class="bi bi-box-arrow-in-right me-2"></i>로그인
              </a>
            </li>
            <li><hr class="dropdown-divider"></li>
            <li>
              <a class="dropdown-item text-danger" href="#" @click.prevent="$emit('logout')">
                <i class="bi bi-box-arrow-left me-2"></i>로그아웃
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
defineEmits<{ logout: [], 'toggle-sidebar': [] }>()

const avatarText = computed(() => props.username ? props.username.substring(0, 2).toUpperCase() : '??')
</script>

<style scoped>
.chat-header {
  background-color: var(--bs-light);
  flex-shrink: 0;
}

[data-bs-theme="dark"] .chat-header {
  background-color: var(--bs-dark);
}

.min-width-0 {
  min-width: 0;
}

.avatar-sm {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--bs-primary);
  color: white;
  font-size: 0.55rem;
  font-weight: bold;
  flex-shrink: 0;
}
</style>
