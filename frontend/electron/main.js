const { app, BrowserWindow, Menu, shell, ipcMain, nativeTheme, Notification } = require('electron')
const { autoUpdater } = require('electron-updater')
const windowStateKeeper = require('electron-window-state')
const path = require('path')
const isDev = require('electron-is-dev')

// 메인 윈도우 참조
let mainWindow = null

// 개발 모드 체크
const isDevMode = isDev || process.env.NODE_ENV === 'development'

function createWindow() {
  // 윈도우 상태 관리 (크기, 위치 기억)
  let mainWindowState = windowStateKeeper({
    defaultWidth: 1200,
    defaultHeight: 800
  })

  // 브라우저 윈도우 생성
  mainWindow = new BrowserWindow({
    x: mainWindowState.x,
    y: mainWindowState.y,
    width: mainWindowState.width,
    height: mainWindowState.height,
    minWidth: 800,
    minHeight: 600,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      enableRemoteModule: false,
      preload: path.join(__dirname, 'preload.js'),
      webSecurity: !isDevMode
    },
    icon: path.join(__dirname, '../public/icon.png'),
    titleBarStyle: process.platform === 'darwin' ? 'hiddenInset' : 'default',
    show: false // 준비될 때까지 숨김
  })

  // 윈도우 상태 관리
  mainWindowState.manage(mainWindow)

  // URL 로드
  const startUrl = isDevMode 
    ? 'http://localhost:3000' 
    : `file://${path.join(__dirname, '../dist/index.html')}`
  
  mainWindow.loadURL(startUrl)

  // 윈도우 준비되면 표시
  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
    
    // 개발 모드에서는 DevTools 자동 열기
    if (isDevMode) {
      mainWindow.webContents.openDevTools()
    }
  })

  // 윈도우 닫힘 처리
  mainWindow.on('closed', () => {
    mainWindow = null
  })

  // 외부 링크는 기본 브라우저에서 열기
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  // 새 윈도우 생성 방지
  mainWindow.webContents.on('new-window', (event, navigationUrl) => {
    event.preventDefault()
    shell.openExternal(navigationUrl)
  })
}

// 애플리케이션 메뉴 설정
function createMenu() {
  const template = [
    {
      label: 'ChatFlow',
      submenu: [
        {
          label: 'ChatFlow 정보',
          role: 'about'
        },
        { type: 'separator' },
        {
          label: '환경설정',
          accelerator: 'CmdOrCtrl+,',
          click: () => {
            // 설정 윈도우 열기
            if (mainWindow) {
              mainWindow.webContents.send('open-settings')
            }
          }
        },
        { type: 'separator' },
        {
          label: '서비스',
          role: 'services',
          submenu: []
        },
        { type: 'separator' },
        {
          label: 'ChatFlow 숨기기',
          accelerator: 'Command+H',
          role: 'hide'
        },
        {
          label: '다른 앱 숨기기',
          accelerator: 'Command+Alt+H',
          role: 'hideothers'
        },
        {
          label: '모두 보이기',
          role: 'unhide'
        },
        { type: 'separator' },
        {
          label: 'ChatFlow 종료',
          accelerator: process.platform === 'darwin' ? 'Command+Q' : 'Ctrl+Q',
          click: () => {
            app.quit()
          }
        }
      ]
    },
    {
      label: '편집',
      submenu: [
        { label: '실행 취소', accelerator: 'CmdOrCtrl+Z', role: 'undo' },
        { label: '다시 실행', accelerator: 'Shift+CmdOrCtrl+Z', role: 'redo' },
        { type: 'separator' },
        { label: '잘라내기', accelerator: 'CmdOrCtrl+X', role: 'cut' },
        { label: '복사', accelerator: 'CmdOrCtrl+C', role: 'copy' },
        { label: '붙여넣기', accelerator: 'CmdOrCtrl+V', role: 'paste' },
        { label: '모두 선택', accelerator: 'CmdOrCtrl+A', role: 'selectall' }
      ]
    },
    {
      label: '보기',
      submenu: [
        { label: '다시 로드', accelerator: 'CmdOrCtrl+R', role: 'reload' },
        { label: '강제 다시 로드', accelerator: 'CmdOrCtrl+Shift+R', role: 'forceReload' },
        { label: '개발자 도구', accelerator: 'F12', role: 'toggleDevTools' },
        { type: 'separator' },
        { label: '실제 크기', accelerator: 'CmdOrCtrl+0', role: 'resetZoom' },
        { label: '확대', accelerator: 'CmdOrCtrl+Plus', role: 'zoomIn' },
        { label: '축소', accelerator: 'CmdOrCtrl+-', role: 'zoomOut' },
        { type: 'separator' },
        { label: '전체 화면', accelerator: 'F11', role: 'togglefullscreen' }
      ]
    },
    {
      label: '윈도우',
      submenu: [
        { label: '최소화', accelerator: 'CmdOrCtrl+M', role: 'minimize' },
        { label: '닫기', accelerator: 'CmdOrCtrl+W', role: 'close' }
      ]
    },
    {
      label: '도움말',
      submenu: [
        {
          label: 'ChatFlow 사용법',
          click: () => {
            shell.openExternal('https://github.com/your-repo/chatflow/wiki')
          }
        },
        {
          label: '문제 신고',
          click: () => {
            shell.openExternal('https://github.com/your-repo/chatflow/issues')
          }
        }
      ]
    }
  ]

  // Windows/Linux 메뉴 조정
  if (process.platform !== 'darwin') {
    // macOS가 아닌 경우 첫 번째 메뉴 제거
    template.shift()
  }

  const menu = Menu.buildFromTemplate(template)
  Menu.setApplicationMenu(menu)
}

// IPC 핸들러 설정
function setupIPC() {
  // 테마 변경
  ipcMain.handle('get-native-theme', () => {
    return nativeTheme.shouldUseDarkColors ? 'dark' : 'light'
  })

  // 시스템 알림
  ipcMain.handle('show-notification', (event, { title, body, icon }) => {
    if (Notification.isSupported()) {
      const notification = new Notification({
        title: title || 'ChatFlow',
        body: body || '',
        icon: icon || path.join(__dirname, '../public/icon.png'),
        sound: true
      })
      
      notification.show()
      
      notification.on('click', () => {
        if (mainWindow) {
          if (mainWindow.isMinimized()) mainWindow.restore()
          mainWindow.focus()
        }
      })
    }
  })

  // 앱 정보
  ipcMain.handle('get-app-info', () => {
    return {
      name: app.getName(),
      version: app.getVersion(),
      electronVersion: process.versions.electron,
      nodeVersion: process.versions.node
    }
  })
}

// 앱 이벤트 처리
app.whenReady().then(() => {
  createWindow()
  createMenu()
  setupIPC()
  
  // 자동 업데이트 체크 (프로덕션 환경)
  if (!isDevMode) {
    autoUpdater.checkForUpdatesAndNotify()
  }
})

// 모든 윈도우가 닫혔을 때
app.on('window-all-closed', () => {
  // macOS에서는 Cmd + Q로 명시적으로 종료하기 전까지 앱을 실행 상태로 유지
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

// 앱이 활성화될 때 (macOS)
app.on('activate', () => {
  // macOS에서는 독에서 앱 아이콘을 클릭했을 때 윈도우가 없으면 새로 생성
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
  }
})

// 보안: 새 윈도우 생성 방지
app.on('web-contents-created', (event, contents) => {
  contents.on('new-window', (navigationEvent, url) => {
    navigationEvent.preventDefault()
    shell.openExternal(url)
  })
})

// GPU 가속 비활성화 (성능 문제가 있는 경우)
// app.disableHardwareAcceleration()

// 단일 인스턴스 보장
const gotTheLock = app.requestSingleInstanceLock()

if (!gotTheLock) {
  app.quit()
} else {
  app.on('second-instance', (event, commandLine, workingDirectory) => {
    // 이미 실행 중인 경우 기존 윈도우를 활성화
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })
}