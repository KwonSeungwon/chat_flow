import { ref, watch, onMounted, onUnmounted } from 'vue'
import { useLocalStorage } from '@vueuse/core'
import { useElectron } from './useElectron'

export function useTheme() {
  const { isElectron, getNativeTheme } = useElectron()
  const theme = useLocalStorage('chatflow-theme', 'auto')

  const toggleTheme = () => {
    if (theme.value === 'light') {
      theme.value = 'dark'
    } else if (theme.value === 'dark') {
      theme.value = 'auto'
    } else {
      theme.value = 'light'
    }
  }

  // 실제 적용될 테마 계산
  const getEffectiveTheme = async (): Promise<'light' | 'dark'> => {
    if (theme.value === 'auto') {
      if (isElectron) {
        return await getNativeTheme()
      } else {
        // 웹에서는 미디어 쿼리 사용
        return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
      }
    }
    return theme.value as 'light' | 'dark'
  }

  // 테마 적용
  const applyTheme = async () => {
    const effectiveTheme = await getEffectiveTheme()
    document.documentElement.setAttribute('data-bs-theme', effectiveTheme)
  }

  // 테마 변경시 적용
  watch(theme, () => {
    applyTheme()
  }, { immediate: true })

  // 시스템 테마 변경 감지 (웹 환경)
  let mediaQueryCleanup: (() => void) | null = null

  onMounted(() => {
    if (!isElectron) {
      const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
      const handler = () => {
        if (theme.value === 'auto') {
          applyTheme()
        }
      }
      mediaQuery.addEventListener('change', handler)
      mediaQueryCleanup = () => mediaQuery.removeEventListener('change', handler)
    }
  })

  onUnmounted(() => {
    mediaQueryCleanup?.()
  })

  // 테마 이름 반환
  const getThemeDisplayName = () => {
    switch (theme.value) {
      case 'light': return '라이트 모드'
      case 'dark': return '다크 모드'
      case 'auto': return '시스템 설정'
      default: return '시스템 설정'
    }
  }

  // 테마 아이콘 반환
  const getThemeIcon = () => {
    switch (theme.value) {
      case 'light': return 'bi-sun'
      case 'dark': return 'bi-moon'
      case 'auto': return 'bi-circle-half'
      default: return 'bi-circle-half'
    }
  }

  return {
    theme,
    toggleTheme,
    getThemeDisplayName,
    getThemeIcon,
    applyTheme
  }
}