<template>
  <div class="chat-room-sidebar h-100 d-flex flex-column">
    <div class="sidebar-header p-3 border-bottom">
      <h6 class="mb-0">
        <i class="bi bi-chat-dots me-2"></i>
        채팅방
      </h6>
    </div>

    <div class="room-list flex-grow-1 overflow-auto">
      <!-- 로딩 상태 -->
      <div v-if="loading" class="text-center py-4">
        <div class="spinner-border spinner-border-sm text-primary" role="status"></div>
        <small class="text-muted ms-2">채팅방 불러오는 중...</small>
      </div>

      <!-- 에러 상태 -->
      <div v-else-if="error" class="text-center py-4 px-3">
        <i class="bi bi-exclamation-triangle text-warning" style="font-size: 2rem;"></i>
        <p class="text-muted mt-2 mb-2 small">채팅방 목록을 불러올 수 없습니다.</p>
        <button class="btn btn-sm btn-outline-primary" @click="fetchRooms">다시 시도</button>
      </div>

      <!-- 빈 상태 -->
      <div v-else-if="rooms.length === 0" class="text-center py-4 px-3">
        <i class="bi bi-chat-dots text-muted" style="font-size: 2rem;"></i>
        <p class="text-muted mt-2 small">채팅방이 없습니다. 새로 만들어보세요!</p>
      </div>

      <!-- 채팅방 목록 -->
      <div
        v-for="room in rooms"
        :key="room.id"
        class="room-item p-3 border-bottom cursor-pointer"
        :class="{ active: selectedRoom === room.id }"
        @click="selectRoom(room.id)"
      >
        <div class="d-flex justify-content-between align-items-center">
          <div class="room-info">
            <div class="room-color-indicator" :style="{ backgroundColor: room.color }"></div>
            <div>
              <div class="fw-semibold room-name">{{ room.name }}</div>
              <small class="text-muted">{{ room.participantCount || 0 }}명 참여중</small>
            </div>
          </div>
          <div class="room-status">
            <span class="badge bg-success rounded-pill">{{ room.participantCount || 0 }}</span>
            <i v-if="room.isPrivate" class="bi bi-lock-fill ms-1 text-muted" title="비공개 채팅방"></i>
          </div>
        </div>
      </div>
    </div>

    <div class="sidebar-footer p-3 border-top">
      <button class="btn btn-create-room w-100" @click="showCreateModal = true">
        <i class="bi bi-plus-circle me-2"></i>
        새 채팅방 만들기
      </button>
    </div>

    <!-- Create Room Modal -->
    <CreateRoomModal
      v-model="showCreateModal"
      @room-created="handleRoomCreated"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'
import type { ChatRoom } from '@/types'
import CreateRoomModal from './CreateRoomModal.vue'

interface CreateRoomData {
  name: string
  description: string
  color: string
  isPrivate: boolean
  allowInvites: boolean
}

interface Props {
  selectedRoom: string
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'room-selected': [roomId: string]
  'update:selected-room': [roomId: string]
}>()

const showCreateModal = ref(false)
const rooms = ref<ChatRoom[]>([])
const loading = ref(false)
const error = ref(false)

const fetchRooms = async () => {
  loading.value = true
  error.value = false
  try {
    const response = await axios.get('/api/chat/rooms')
    const data = response.data
    rooms.value = data.data || data || []
  } catch (e) {
    console.error('채팅방 목록 로드 실패:', e)
    error.value = true
    // 백엔드 미연결 시 기본 채팅방 제공
    rooms.value = [
      { id: 'general', name: '일반', participantCount: 0, createdAt: '', color: '#6366f1', isPrivate: false },
      { id: 'tech', name: '기술 토론', participantCount: 0, createdAt: '', color: '#10b981', isPrivate: false },
      { id: 'random', name: '자유 토론', participantCount: 0, createdAt: '', color: '#f97316', isPrivate: false }
    ]
    error.value = false
  } finally {
    loading.value = false
  }
}

const selectRoom = (roomId: string) => {
  emit('update:selected-room', roomId)
  emit('room-selected', roomId)
}

const handleRoomCreated = async (roomData: CreateRoomData) => {
  try {
    const response = await axios.post('/api/chat/rooms', {
      name: roomData.name,
      description: roomData.description,
      color: roomData.color,
      isPrivate: roomData.isPrivate,
      allowInvites: roomData.allowInvites
    })
    const created = response.data.data || response.data
    rooms.value.unshift(created)
    selectRoom(created.id)
  } catch (e) {
    console.error('채팅방 생성 실패:', e)
    // 로컬 폴백
    const newRoom: ChatRoom = {
      id: `room_${Date.now()}`,
      name: roomData.name,
      participantCount: 1,
      createdAt: new Date().toISOString(),
      color: roomData.color,
      isPrivate: roomData.isPrivate,
      description: roomData.description,
      allowInvites: roomData.allowInvites
    }
    rooms.value.unshift(newRoom)
    selectRoom(newRoom.id)
  }
}

onMounted(() => {
  fetchRooms()
})
</script>

<style scoped>
.chat-room-sidebar {
  background-color: var(--bs-body-bg);
  min-width: 280px;
}

.room-item {
  transition: all 0.2s ease;
  cursor: pointer;
  position: relative;
}

.room-item:hover {
  background-color: var(--bs-light);
  transform: translateX(2px);
}

.room-item.active {
  background-color: var(--bs-primary);
  color: white;
}

.room-item.active .text-muted {
  color: rgba(255, 255, 255, 0.7) !important;
}

.room-info {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.room-color-indicator {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 2px solid rgba(255, 255, 255, 0.3);
}

.room-item.active .room-color-indicator {
  border-color: rgba(255, 255, 255, 0.6);
}

.room-name {
  font-size: 15px;
  line-height: 1.3;
}

.btn-create-room {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  color: white;
  padding: 12px 16px;
  border-radius: 12px;
  font-weight: 600;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

.btn-create-room:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(102, 126, 234, 0.4);
  color: white;
}

.btn-create-room:active {
  transform: translateY(0);
}

.btn-create-room::before {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 0;
  height: 0;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 50%;
  transform: translate(-50%, -50%);
  transition: width 0.3s ease, height 0.3s ease;
}

.btn-create-room:hover::before {
  width: 300px;
  height: 300px;
}

.sidebar-header {
  background: linear-gradient(135deg, #f8f9ff 0%, #e8f0fe 100%);
}

.sidebar-footer {
  background: linear-gradient(135deg, #f8f9ff 0%, #e8f0fe 100%);
}

[data-bs-theme="dark"] .room-item:hover {
  background-color: var(--bs-dark);
}

[data-bs-theme="dark"] .sidebar-header,
[data-bs-theme="dark"] .sidebar-footer {
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}
</style>
