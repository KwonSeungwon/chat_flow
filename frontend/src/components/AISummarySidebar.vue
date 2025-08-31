<template>
  <div class="ai-summary-sidebar h-100 d-flex flex-column">
    <div class="sidebar-header p-3 border-bottom">
      <h6 class="mb-0">
        <i class="bi bi-robot me-2"></i>
        AI 요약
      </h6>
    </div>
    
    <div class="summary-content flex-grow-1 overflow-auto p-3">
      <div v-if="loading" class="text-center py-4">
        <div class="loading-spinner mx-auto mb-2"></div>
        <small class="text-muted">요약 생성 중...</small>
      </div>
      
      <div v-else-if="summaries.length === 0" class="text-center py-4">
        <i class="bi bi-chat-quote text-muted" style="font-size: 3rem;"></i>
        <p class="text-muted mt-3">아직 생성된 요약이 없습니다.</p>
      </div>
      
      <div v-else>
        <div 
          v-for="(summary, index) in summaries" 
          :key="index"
          class="summary-item mb-3"
        >
          <div class="card border-info">
            <div class="card-header bg-info text-white d-flex align-items-center">
              <i class="bi bi-robot me-2"></i>
              <small>{{ formatTime(summary.timestamp) }}</small>
            </div>
            <div class="card-body">
              <p class="card-text">{{ summary.content }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <div class="sidebar-footer p-3 border-top">
      <button 
        class="btn btn-outline-info btn-sm w-100" 
        @click="requestSummary"
        :disabled="loading"
      >
        <i class="bi bi-magic me-1"></i>
        새 요약 요청
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import axios from 'axios'
import dayjs from 'dayjs'
import type { ChatMessage } from '@/types'

interface Props {
  roomId: string
}

const props = defineProps<Props>()

const summaries = ref<ChatMessage[]>([])
const loading = ref(false)

const formatTime = (timestamp: string) => {
  return dayjs(timestamp).format('MM/DD HH:mm')
}

const requestSummary = async () => {
  loading.value = true
  try {
    // AI 요약 요청 API 호출
    await axios.post('/api/ai-summary/request', {
      chatRoomId: props.roomId
    })
    
    // 요약 새로고침
    await fetchSummaries()
  } catch (error) {
    console.error('AI 요약 요청 실패:', error)
  } finally {
    loading.value = false
  }
}

const fetchSummaries = async () => {
  try {
    const response = await axios.get(`/api/ai-summary/room/${props.roomId}`)
    summaries.value = response.data || []
  } catch (error) {
    console.error('요약 데이터 로드 실패:', error)
    // 예시 데이터
    summaries.value = [
      {
        id: '1',
        chatRoomId: props.roomId,
        userId: 'ai-system',
        username: 'AI 요약봇',
        content: '최근 대화에서는 프로젝트 계획과 기술 스택 선택에 대한 논의가 있었습니다. 특히 Vue3와 Spring Boot를 사용한 마이크로서비스 아키텍처에 대한 의견이 많았습니다.',
        timestamp: new Date().toISOString(),
        type: 'AI_SUMMARY' as any,
        isAiGenerated: true
      } as ChatMessage
    ]
  }
}

// 채팅방 변경시 요약 새로고침
watch(() => props.roomId, () => {
  if (props.roomId) {
    fetchSummaries()
  }
})

onMounted(() => {
  if (props.roomId) {
    fetchSummaries()
  }
})
</script>

<style scoped>
.ai-summary-sidebar {
  background-color: var(--bs-body-bg);
  min-width: 300px;
}

.summary-item {
  animation: fadeIn 0.3s ease-in-out;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>