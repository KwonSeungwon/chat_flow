import { ref, onMounted } from 'vue'

// Electron API 타입 정의
interface ElectronAPI {
  getNativeTheme: () => Promise<'light' | 'dark'>
  showNotification: (options: NotificationOptions) => Promise<void>
  getAppInfo: () => Promise<AppInfo>
  onOpenSettings: (callback: () => void) => void
  platform: string
  versions: {
    node: string
    chrome: string
    electron: string
  }
  isDev: boolean
}

interface NotificationOptions {
  title?: string
  body: string
  icon?: string
}

interface AppInfo {
  name: string
  version: string
  electronVersion: string
  nodeVersion: string
}

// Global에서 electronAPI 접근
declare global {
  interface Window {
    electronAPI?: ElectronAPI
  }
}

export function useElectron() {
  const isElectron = ref(false)
  const appInfo = ref<AppInfo | null>(null)
  const platform = ref<string>('')
  const isDev = ref(false)

  // Electron 환경 체크
  const checkElectron = () => {
    isElectron.value = !!(window.electronAPI)
    
    if (isElectron.value) {
      platform.value = window.electronAPI!.platform
      isDev.value = window.electronAPI!.isDev
      
      // 앱 정보 가져오기
      window.electronAPI!.getAppInfo().then(info => {
        appInfo.value = info
      })
    }
  }

  // 네이티브 테마 가져오기
  const getNativeTheme = async (): Promise<'light' | 'dark'> => {
    if (isElectron.value && window.electronAPI) {
      return await window.electronAPI.getNativeTheme()
    }
    return 'light'
  }

  // 시스템 알림 표시
  const showNotification = async (options: NotificationOptions) => {
    if (isElectron.value && window.electronAPI) {
      await window.electronAPI.showNotification(options)
    } else {
      // 웹 환경에서는 브라우저 알림 사용
      if ('Notification' in window) {
        if (Notification.permission === 'granted') {
          new Notification(options.title || 'ChatFlow', {
            body: options.body,
            icon: options.icon || '/icon.png'
          })
        } else if (Notification.permission !== 'denied') {
          const permission = await Notification.requestPermission()
          if (permission === 'granted') {
            new Notification(options.title || 'ChatFlow', {
              body: options.body,
              icon: options.icon || '/icon.png'
            })
          }
        }
      }
    }
  }

  // 설정 열기 이벤트 리스너
  const onOpenSettings = (callback: () => void) => {
    if (isElectron.value && window.electronAPI) {
      window.electronAPI.onOpenSettings(callback)
    }
  }

  // 플랫폼별 클래스 추가
  const getPlatformClass = (): string => {
    if (!isElectron.value) return 'web'
    
    switch (platform.value) {
      case 'darwin': return 'macos'
      case 'win32': return 'windows'
      case 'linux': return 'linux'
      default: return 'unknown'
    }
  }

  // 단축키 힌트 텍스트
  const getShortcutText = (shortcut: string): string => {
    if (!isElectron.value) return shortcut
    
    if (platform.value === 'darwin') {
      return shortcut.replace('Ctrl', '⌘').replace('Alt', '⌥').replace('Shift', '⇧')
    }
    return shortcut
  }

  // 컴포넌트 마운트시 실행
  onMounted(() => {
    checkElectron()
  })

  return {
    isElectron: isElectron.value,
    appInfo,
    platform,
    isDev,
    getNativeTheme,
    showNotification,
    onOpenSettings,
    getPlatformClass,
    getShortcutText
  }
}