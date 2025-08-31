const { contextBridge, ipcRenderer } = require('electron')

// Main world에서 접근 가능한 API 정의
contextBridge.exposeInMainWorld('electronAPI', {
  // 테마 관련
  getNativeTheme: () => ipcRenderer.invoke('get-native-theme'),
  
  // 알림 관련
  showNotification: (options) => ipcRenderer.invoke('show-notification', options),
  
  // 앱 정보
  getAppInfo: () => ipcRenderer.invoke('get-app-info'),
  
  // 이벤트 리스너
  onOpenSettings: (callback) => ipcRenderer.on('open-settings', callback),
  
  // 플랫폼 정보
  platform: process.platform,
  
  // 버전 정보
  versions: {
    node: process.versions.node,
    chrome: process.versions.chrome,
    electron: process.versions.electron
  },
  
  // 개발 모드 체크
  isDev: process.env.NODE_ENV === 'development'
})

// DOM이 로드되면 실행
window.addEventListener('DOMContentLoaded', () => {
  // 플랫폼별 CSS 클래스 추가
  document.body.classList.add(`platform-${process.platform}`)
  
  // Electron 환경임을 표시
  document.body.classList.add('electron-app')
  
  console.log('ChatFlow Desktop App loaded!')
})