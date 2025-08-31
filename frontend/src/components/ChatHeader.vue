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
        
        <div class="dropdown">
          <button 
            class="btn btn-outline-secondary btn-sm dropdown-toggle" 
            type="button" 
            data-bs-toggle="dropdown"
          >
            <i class="bi bi-three-dots-vertical"></i>
          </button>
          <ul class="dropdown-menu dropdown-menu-end">
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
              <a class="dropdown-item text-danger" href="#">
                <i class="bi bi-box-arrow-left me-2"></i>
                나가기
              </a>
            </li>
          </ul>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface Props {
  roomId: string
  isConnected: boolean
  participants: string[]
}

defineProps<Props>()
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
</style>