<template>
  <div class="search-view h-100">
    <div class="container-fluid h-100">
      <div class="row h-100">
        <div class="col-12">
          <div class="search-container d-flex flex-column h-100">
            <!-- 검색 헤더 -->
            <div class="search-header py-4">
              <h2 class="mb-4">
                <i class="bi bi-search me-2"></i>
                메시지 검색
              </h2>

              <div class="row">
                <div class="col-md-8 col-lg-6">
                  <div class="input-group mb-3">
                    <input
                      v-model="searchQuery"
                      type="text"
                      class="form-control form-control-lg"
                      placeholder="검색어를 입력하세요..."
                      @keyup.enter="performSearch"
                    >
                    <button
                      class="btn btn-primary"
                      type="button"
                      @click="performSearch"
                      :disabled="loading || !searchQuery.trim()"
                    >
                      <i v-if="loading" class="loading-spinner me-1"></i>
                      <i v-else class="bi bi-search me-1"></i>
                      검색
                    </button>
                  </div>
                </div>
              </div>

              <!-- 검색 필터 -->
              <div class="search-filters">
                <div class="row">
                  <div class="col-md-3 mb-2">
                    <select v-model="selectedRoom" class="form-select form-select-sm">
                      <option value="">전체 채팅방</option>
                      <option value="general">일반</option>
                      <option value="tech">기술 토론</option>
                      <option value="random">자유 토론</option>
                    </select>
                  </div>
                  <div class="col-md-3 mb-2">
                    <input
                      v-model="selectedUser"
                      type="text"
                      class="form-control form-control-sm"
                      placeholder="사용자명"
                    >
                  </div>
                  <div class="col-md-3 mb-2">
                    <input
                      v-model="dateFrom"
                      type="date"
                      class="form-control form-control-sm"
                    >
                  </div>
                  <div class="col-md-3 mb-2">
                    <input
                      v-model="dateTo"
                      type="date"
                      class="form-control form-control-sm"
                    >
                  </div>
                </div>
              </div>
            </div>

            <!-- 검색 결과 -->
            <div class="search-results flex-grow-1 overflow-auto">
              <div v-if="loading" class="text-center py-5">
                <div class="loading-spinner mx-auto mb-3"></div>
                <p class="text-muted">검색 중...</p>
              </div>

              <div v-else-if="searchError" class="text-center py-5">
                <i class="bi bi-exclamation-triangle text-warning" style="font-size: 4rem;"></i>
                <h5 class="mt-3 text-muted">검색 중 오류가 발생했습니다</h5>
                <p class="text-muted">잠시 후 다시 시도해주세요.</p>
                <button class="btn btn-sm btn-outline-primary" @click="performSearch">다시 검색</button>
              </div>

              <div v-else-if="searchResults.length === 0 && hasSearched" class="text-center py-5">
                <i class="bi bi-search text-muted" style="font-size: 4rem;"></i>
                <h5 class="mt-3 text-muted">검색 결과가 없습니다</h5>
                <p class="text-muted">다른 검색어를 시도해보세요.</p>
              </div>

              <div v-else-if="!hasSearched" class="text-center py-5">
                <i class="bi bi-chat-quote text-muted" style="font-size: 4rem;"></i>
                <h5 class="mt-3 text-muted">메시지를 검색해보세요</h5>
                <p class="text-muted">대화 내용, 사용자명, 날짜 등으로 검색할 수 있습니다.</p>
              </div>

              <div v-else>
                <div class="search-result-header mb-3">
                  <small class="text-muted">
                    전체 {{ totalResults }}건 중 {{ searchResults.length }}건 표시
                  </small>
                </div>

                <div class="search-result-list">
                  <div
                    v-for="result in searchResults"
                    :key="result.id"
                    class="search-result-item card mb-3 fade-in"
                    @click="goToMessage(result)"
                  >
                    <div class="card-body">
                      <div class="d-flex justify-content-between align-items-start mb-2">
                        <div>
                          <strong class="text-primary">{{ result.username }}</strong>
                          <small class="text-muted ms-2">#{{ result.chatRoomId }}</small>
                        </div>
                        <small class="text-muted">{{ formatTime(result.timestamp) }}</small>
                      </div>

                      <p class="card-text" v-html="highlightSearchTerm(result.content)"></p>

                      <div v-if="result.isAiGenerated" class="mt-2">
                        <span class="badge bg-info">
                          <i class="bi bi-robot me-1"></i>
                          AI 요약
                        </span>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 페이지네이션 -->
                <div v-if="totalPages > 1" class="d-flex justify-content-center mt-4">
                  <nav>
                    <ul class="pagination">
                      <li class="page-item" :class="{ disabled: currentPage === 0 }">
                        <a class="page-link" href="#" @click.prevent="changePage(currentPage - 1)">
                          이전
                        </a>
                      </li>
                      <li
                        v-for="page in visiblePages"
                        :key="page"
                        class="page-item"
                        :class="{ active: page === currentPage }"
                      >
                        <a class="page-link" href="#" @click.prevent="changePage(page)">
                          {{ page + 1 }}
                        </a>
                      </li>
                      <li class="page-item" :class="{ disabled: currentPage === totalPages - 1 }">
                        <a class="page-link" href="#" @click.prevent="changePage(currentPage + 1)">
                          다음
                        </a>
                      </li>
                    </ul>
                  </nav>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/utils/api'
import dayjs from 'dayjs'
import DOMPurify from 'dompurify'

interface SearchResultItem {
  id: string
  messageId: string
  chatRoomId: string
  userId: string
  username: string
  content: string
  timestamp: string
  messageType: string
  isAiGenerated: boolean
}

const router = useRouter()

const searchQuery = ref('')
const selectedRoom = ref('')
const selectedUser = ref('')
const dateFrom = ref('')
const dateTo = ref('')

const loading = ref(false)
const hasSearched = ref(false)
const searchError = ref(false)
const searchResults = ref<SearchResultItem[]>([])
const totalResults = ref(0)
const currentPage = ref(0)
const totalPages = ref(0)
const pageSize = 20

const visiblePages = computed(() => {
  const pages = []
  const start = Math.max(0, currentPage.value - 2)
  const end = Math.min(totalPages.value, start + 5)

  for (let i = start; i < end; i++) {
    pages.push(i)
  }

  return pages
})

const performSearch = async () => {
  if (!searchQuery.value.trim()) return

  loading.value = true
  hasSearched.value = true
  searchError.value = false
  currentPage.value = 0

  try {
    let url = '/api/search/korean'
    const params = new URLSearchParams({
      query: searchQuery.value,
      page: currentPage.value.toString(),
      size: pageSize.toString()
    })

    if (selectedRoom.value) {
      params.append('roomId', selectedRoom.value)
    }

    if (selectedUser.value) {
      params.append('username', selectedUser.value)
    }

    if (dateFrom.value) {
      params.append('start', `${dateFrom.value}T00:00:00`)
    }

    if (dateTo.value) {
      params.append('end', `${dateTo.value}T23:59:59`)
    }

    const response = await api.get(`${url}?${params}`)
    const data = response.data

    searchResults.value = data.content || []
    totalResults.value = data.totalElements || 0
    totalPages.value = data.totalPages || 0

  } catch (error) {
    console.error('검색 오류:', error)
    searchError.value = true
    searchResults.value = []
    totalResults.value = 0
    totalPages.value = 0
  } finally {
    loading.value = false
  }
}

const changePage = (page: number) => {
  if (page >= 0 && page < totalPages.value && page !== currentPage.value) {
    currentPage.value = page
    performSearch()
  }
}

const escapeHtml = (text: string): string => {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}

const highlightSearchTerm = (content: string) => {
  if (!searchQuery.value.trim()) return escapeHtml(content)

  const escaped = escapeHtml(content)
  const queryEscaped = searchQuery.value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const regex = new RegExp(`(${queryEscaped})`, 'gi')
  return DOMPurify.sanitize(escaped.replace(regex, '<mark>$1</mark>'), { ALLOWED_TAGS: ['mark'] })
}

const formatTime = (timestamp: string) => {
  return dayjs(timestamp).format('YYYY/MM/DD HH:mm')
}

const goToMessage = (result: SearchResultItem) => {
  router.push(`/chat/${result.chatRoomId}`)
}
</script>

<style scoped>
.search-view {
  background-color: var(--bs-body-bg);
}

.search-result-item {
  cursor: pointer;
  transition: all 0.2s ease;
}

.search-result-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.fade-in {
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

:deep(mark) {
  background-color: #fff3cd;
  padding: 2px 4px;
  border-radius: 3px;
  font-weight: bold;
}

[data-bs-theme="dark"] :deep(mark) {
  background-color: #664d03;
  color: #fff3cd;
}
</style>
