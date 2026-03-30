<template>
  <Teleport to="body">
    <Transition name="modal">
      <div 
        v-if="isVisible" 
        class="modal-overlay"
        @click="handleBackdropClick"
      >
        <div class="modal-container" @click.stop>
          <div class="modal-header">
            <div class="modal-icon">
              <i class="bi bi-chat-heart"></i>
            </div>
            <h3 class="modal-title">새로운 채팅방 만들기</h3>
            <button 
              class="modal-close-btn" 
              @click="closeModal"
              aria-label="닫기"
            >
              <i class="bi bi-x-lg"></i>
            </button>
          </div>

          <div class="modal-body">
            <form @submit.prevent="handleSubmit" class="room-form">
              <div class="form-group">
                <label for="roomName" class="form-label">
                  <i class="bi bi-pencil me-2"></i>
                  채팅방 이름
                </label>
                <input
                  id="roomName"
                  ref="roomNameInput"
                  v-model="formData.name"
                  type="text"
                  class="form-input"
                  :class="{ error: errors.name }"
                  placeholder="채팅방 이름을 입력하세요..."
                  maxlength="50"
                  @input="clearError('name')"
                />
                <div class="char-count">
                  {{ formData.name.length }}/50
                </div>
                <div v-if="errors.name" class="error-message">
                  {{ errors.name }}
                </div>
              </div>

              <div class="form-group">
                <label for="roomDescription" class="form-label">
                  <i class="bi bi-card-text me-2"></i>
                  설명 (선택사항)
                </label>
                <textarea
                  id="roomDescription"
                  v-model="formData.description"
                  class="form-textarea"
                  placeholder="채팅방에 대한 간단한 설명을 입력하세요..."
                  rows="3"
                  maxlength="200"
                ></textarea>
                <div class="char-count">
                  {{ formData.description.length }}/200
                </div>
              </div>

              <div class="form-group">
                <label class="form-label">
                  <i class="bi bi-palette me-2"></i>
                  채팅방 색상
                </label>
                <div class="color-picker">
                  <div 
                    v-for="color in availableColors" 
                    :key="color.value"
                    class="color-option"
                    :class="{ active: formData.color === color.value }"
                    :style="{ backgroundColor: color.value }"
                    @click="formData.color = color.value"
                    :title="color.name"
                  >
                    <i v-if="formData.color === color.value" class="bi bi-check"></i>
                  </div>
                </div>
              </div>

              <div class="form-group">
                <label class="form-label">
                  <i class="bi bi-shield-check me-2"></i>
                  채팅방 설정
                </label>
                <div class="form-switches">
                  <label class="switch-item">
                    <input
                      v-model="formData.isPrivate"
                      type="checkbox"
                      class="form-switch"
                    />
                    <span class="switch-slider"></span>
                    <span class="switch-label">비공개 채팅방</span>
                  </label>
                  <label class="switch-item">
                    <input
                      v-model="formData.allowInvites"
                      type="checkbox"
                      class="form-switch"
                    />
                    <span class="switch-slider"></span>
                    <span class="switch-label">초대 허용</span>
                  </label>
                </div>
              </div>
            </form>
          </div>

          <div class="modal-footer">
            <button 
              type="button" 
              class="btn-secondary"
              @click="closeModal"
            >
              취소
            </button>
            <button 
              type="button" 
              class="btn-primary"
              :disabled="!isFormValid || isLoading"
              @click="handleSubmit"
            >
              <span v-if="isLoading" class="loading-spinner"></span>
              <i v-else class="bi bi-plus-circle me-2"></i>
              {{ isLoading ? '생성 중...' : '채팅방 생성' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, reactive, computed, nextTick, watch } from 'vue'

interface CreateRoomData {
  name: string
  description: string
  color: string
  isPrivate: boolean
  allowInvites: boolean
}

interface Props {
  modelValue: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  'room-created': [roomData: CreateRoomData]
}>()

const roomNameInput = ref<HTMLInputElement>()
const isLoading = ref(false)

const formData = reactive<CreateRoomData>({
  name: '',
  description: '',
  color: '#6366f1',
  isPrivate: false,
  allowInvites: true
})

const errors = reactive({
  name: ''
})

const availableColors = [
  { name: '인디고', value: '#6366f1' },
  { name: '파랑', value: '#3b82f6' },
  { name: '청록', value: '#06b6d4' },
  { name: '에메랄드', value: '#10b981' },
  { name: '라임', value: '#84cc16' },
  { name: '노랑', value: '#eab308' },
  { name: '주황', value: '#f97316' },
  { name: '빨강', value: '#ef4444' },
  { name: '분홍', value: '#ec4899' },
  { name: '보라', value: '#8b5cf6' }
]

const isVisible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const isFormValid = computed(() => {
  return formData.name.trim().length >= 2 && formData.name.length <= 50
})

const clearError = (field: keyof typeof errors) => {
  errors[field] = ''
}

const validateForm = () => {
  let isValid = true
  
  if (!formData.name.trim()) {
    errors.name = '채팅방 이름을 입력해주세요.'
    isValid = false
  } else if (formData.name.trim().length < 2) {
    errors.name = '채팅방 이름은 2글자 이상이어야 합니다.'
    isValid = false
  } else if (formData.name.length > 50) {
    errors.name = '채팅방 이름은 50글자 이하여야 합니다.'
    isValid = false
  }
  
  return isValid
}

const handleSubmit = async () => {
  if (!validateForm()) return
  
  isLoading.value = true
  
  try {
    // Simulate API call
    await new Promise(resolve => setTimeout(resolve, 1500))
    
    emit('room-created', { ...formData })
    closeModal()
    resetForm()
  } catch (error) {
    console.error('Failed to create room:', error)
  } finally {
    isLoading.value = false
  }
}

const closeModal = () => {
  isVisible.value = false
}

const handleBackdropClick = () => {
  closeModal()
}

const resetForm = () => {
  formData.name = ''
  formData.description = ''
  formData.color = '#6366f1'
  formData.isPrivate = false
  formData.allowInvites = true
  errors.name = ''
}

watch(isVisible, async (visible) => {
  if (visible) {
    await nextTick()
    roomNameInput.value?.focus()
  } else {
    resetForm()
  }
})
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 20px;
}

.modal-container {
  background: white;
  border-radius: 20px;
  box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.25);
  max-width: 500px;
  width: 100%;
  max-height: 90vh;
  overflow: hidden;
  position: relative;
}

.modal-header {
  padding: 32px 32px 24px;
  text-align: center;
  position: relative;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
}

.modal-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 60px;
  height: 60px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 50%;
  margin-bottom: 16px;
}

.modal-icon i {
  font-size: 24px;
}

.modal-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.modal-close-btn {
  position: absolute;
  top: 20px;
  right: 20px;
  background: rgba(255, 255, 255, 0.2);
  border: none;
  border-radius: 50%;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  cursor: pointer;
  transition: all 0.2s ease;
}

.modal-close-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.modal-body {
  padding: 32px;
  max-height: 60vh;
  overflow-y: auto;
}

.room-form {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.form-group {
  display: flex;
  flex-direction: column;
}

.form-label {
  font-weight: 600;
  color: #374151;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
}

.form-input,
.form-textarea {
  padding: 12px 16px;
  border: 2px solid #e5e7eb;
  border-radius: 12px;
  font-size: 16px;
  transition: all 0.2s ease;
  background: #fafafa;
}

.form-input:focus,
.form-textarea:focus {
  outline: none;
  border-color: #6366f1;
  background: white;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1);
}

.form-input.error {
  border-color: #ef4444;
  background: #fef2f2;
}

.char-count {
  align-self: flex-end;
  font-size: 12px;
  color: #9ca3af;
  margin-top: 4px;
}

.error-message {
  color: #ef4444;
  font-size: 14px;
  margin-top: 4px;
  display: flex;
  align-items: center;
}

.color-picker {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.color-option {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  border: 3px solid transparent;
  color: white;
}

.color-option:hover {
  transform: scale(1.1);
}

.color-option.active {
  border-color: #374151;
  transform: scale(1.1);
}

.form-switches {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.switch-item {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.form-switch {
  display: none;
}

.switch-slider {
  position: relative;
  width: 48px;
  height: 24px;
  background: #d1d5db;
  border-radius: 12px;
  transition: background 0.2s ease;
}

.switch-slider::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 20px;
  height: 20px;
  background: white;
  border-radius: 50%;
  transition: transform 0.2s ease;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

.form-switch:checked + .switch-slider {
  background: #6366f1;
}

.form-switch:checked + .switch-slider::after {
  transform: translateX(24px);
}

.switch-label {
  font-weight: 500;
  color: #374151;
}

.modal-footer {
  padding: 24px 32px;
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  background: #fafafa;
  border-top: 1px solid #e5e7eb;
}

.btn-secondary,
.btn-primary {
  padding: 12px 24px;
  border-radius: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 120px;
}

.btn-secondary {
  background: white;
  border: 2px solid #d1d5db;
  color: #6b7280;
}

.btn-secondary:hover {
  border-color: #9ca3af;
  color: #374151;
}

.btn-primary {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border: none;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 25px rgba(102, 126, 234, 0.4);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.loading-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top: 2px solid white;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.modal-enter-active,
.modal-leave-active {
  transition: all 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.9) translateY(-20px);
}

/* Dark mode support */
@media (prefers-color-scheme: dark) {
  .modal-container {
    background: #1f2937;
  }
  
  .form-label {
    color: #f9fafb;
  }
  
  .form-input,
  .form-textarea {
    background: #374151;
    border-color: #4b5563;
    color: #f9fafb;
  }
  
  .form-input:focus,
  .form-textarea:focus {
    background: #4b5563;
  }
  
  .switch-label {
    color: #f9fafb;
  }
  
  .modal-footer {
    background: #374151;
    border-color: #4b5563;
  }
  
  .btn-secondary {
    background: #4b5563;
    border-color: #6b7280;
    color: #f9fafb;
  }
}
</style>