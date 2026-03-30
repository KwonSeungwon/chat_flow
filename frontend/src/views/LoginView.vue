<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const error = ref('')
const loading = ref(false)

const isRegister = computed(() => mode.value === 'register')
const isFormValid = computed(() => {
  if (!username.value || !password.value) return false
  if (isRegister.value && password.value !== confirmPassword.value) return false
  if (username.value.length < 2) return false
  if (password.value.length < 4) return false
  return true
})

async function handleSubmit() {
  if (!isFormValid.value) return
  error.value = ''
  loading.value = true

  try {
    if (isRegister.value) {
      await auth.register(username.value, password.value)
    } else {
      await auth.login(username.value, password.value)
    }
    router.push('/chat')
  } catch (e: any) {
    const msg = e.response?.data?.message || e.response?.data?.error
    if (isRegister.value) {
      error.value = msg || '이미 사용 중인 아이디입니다.'
    } else {
      error.value = msg || '아이디 또는 비밀번호가 올바르지 않습니다.'
    }
  } finally {
    loading.value = false
  }
}

function switchMode() {
  mode.value = isRegister.value ? 'login' : 'register'
  error.value = ''
}

async function guestLogin() {
  loading.value = true
  error.value = ''
  try {
    const guestName = `Guest_${Math.floor(Math.random() * 100000)}`
    const guestPass = `guest_${guestName}_${Date.now()}`
    await auth.register(guestName, guestPass)
    auth.isGuest = true
    localStorage.setItem('chatflow-isGuest', 'true')
    router.push('/chat')
  } catch {
    error.value = '게스트 로그인에 실패했습니다. 다시 시도해주세요.'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page d-flex align-items-center justify-content-center min-vh-100">
    <div class="login-card card shadow-lg border-0" style="max-width: 420px; width: 100%;">
      <div class="card-body p-4 p-md-5">
        <!-- Logo -->
        <div class="text-center mb-4">
          <div class="logo-icon mb-2">
            <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="text-primary">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
          </div>
          <h3 class="fw-bold mb-1">ChatFlow</h3>
          <p class="text-muted small">{{ isRegister ? '새 계정 만들기' : '로그인하여 채팅 시작' }}</p>
        </div>

        <!-- Error -->
        <div v-if="error" class="alert alert-danger py-2 small" role="alert">
          {{ error }}
        </div>

        <!-- Form -->
        <form @submit.prevent="handleSubmit">
          <div class="mb-3">
            <label for="username" class="form-label small fw-semibold">아이디</label>
            <input
              id="username"
              v-model="username"
              type="text"
              class="form-control"
              placeholder="아이디를 입력하세요"
              autocomplete="username"
              minlength="2"
              maxlength="50"
              required
            />
          </div>

          <div class="mb-3">
            <label for="password" class="form-label small fw-semibold">비밀번호</label>
            <input
              id="password"
              v-model="password"
              type="password"
              class="form-control"
              placeholder="비밀번호를 입력하세요"
              autocomplete="current-password"
              minlength="4"
              required
            />
          </div>

          <div v-if="isRegister" class="mb-3">
            <label for="confirmPassword" class="form-label small fw-semibold">비밀번호 확인</label>
            <input
              id="confirmPassword"
              v-model="confirmPassword"
              type="password"
              class="form-control"
              placeholder="비밀번호를 다시 입력하세요"
              autocomplete="new-password"
              required
            />
            <div v-if="confirmPassword && password !== confirmPassword" class="form-text text-danger">
              비밀번호가 일치하지 않습니다.
            </div>
          </div>

          <button
            type="submit"
            class="btn btn-primary w-100 mb-3"
            :disabled="!isFormValid || loading"
          >
            <span v-if="loading" class="spinner-border spinner-border-sm me-1" />
            {{ isRegister ? '회원가입' : '로그인' }}
          </button>
        </form>

        <!-- Divider -->
        <div class="d-flex align-items-center my-3">
          <hr class="flex-grow-1" />
          <span class="px-2 text-muted small">또는</span>
          <hr class="flex-grow-1" />
        </div>

        <!-- Guest Login -->
        <button
          class="btn btn-outline-secondary w-100 mb-3"
          :disabled="loading"
          @click="guestLogin"
        >
          게스트로 시작하기
        </button>

        <!-- Switch mode -->
        <p class="text-center mb-0 small">
          <template v-if="isRegister">
            이미 계정이 있으신가요?
            <a href="#" class="text-decoration-none" @click.prevent="switchMode">로그인</a>
          </template>
          <template v-else>
            계정이 없으신가요?
            <a href="#" class="text-decoration-none" @click.prevent="switchMode">회원가입</a>
          </template>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 1rem;
}

[data-bs-theme="dark"] .login-page {
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}

.login-card {
  border-radius: 1rem;
}

[data-bs-theme="dark"] .login-card {
  background-color: var(--bs-dark);
}

.logo-icon {
  display: inline-block;
}
</style>
