const { app, Menu, Tray, nativeImage } = require('electron')
const path = require('path')

let tray = null

function createTray(mainWindow) {
  // 트레이 아이콘 생성
  const iconPath = path.join(__dirname, '../public/tray-icon.png')
  let trayIcon
  
  try {
    trayIcon = nativeImage.createFromPath(iconPath)
    if (trayIcon.isEmpty()) {
      // 기본 아이콘 사용
      trayIcon = nativeImage.createFromPath(path.join(__dirname, '../public/icon.png'))
    }
    
    // macOS에서는 트레이 아이콘 크기 조정
    if (process.platform === 'darwin') {
      trayIcon = trayIcon.resize({ width: 16, height: 16 })
      trayIcon.setTemplateImage(true)
    }
  } catch (error) {
    console.error('트레이 아이콘 로드 실패:', error)
    // 빈 아이콘으로라도 트레이 생성
    trayIcon = nativeImage.createEmpty()
  }

  tray = new Tray(trayIcon)
  
  // 트레이 메뉴 생성
  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'ChatFlow 열기',
      click: () => {
        if (mainWindow) {
          if (mainWindow.isMinimized()) {
            mainWindow.restore()
          }
          mainWindow.show()
          mainWindow.focus()
        }
      }
    },
    { type: 'separator' },
    {
      label: '새 메시지',
      accelerator: 'CmdOrCtrl+N',
      click: () => {
        if (mainWindow) {
          mainWindow.show()
          mainWindow.focus()
          mainWindow.webContents.send('focus-message-input')
        }
      }
    },
    {
      label: '채팅방 목록',
      click: () => {
        if (mainWindow) {
          mainWindow.show()
          mainWindow.focus()
          mainWindow.webContents.send('show-room-list')
        }
      }
    },
    { type: 'separator' },
    {
      label: '환경설정',
      click: () => {
        if (mainWindow) {
          mainWindow.show()
          mainWindow.focus()
          mainWindow.webContents.send('open-settings')
        }
      }
    },
    { type: 'separator' },
    {
      label: 'ChatFlow 종료',
      role: 'quit'
    }
  ])

  tray.setContextMenu(contextMenu)
  tray.setToolTip('ChatFlow - 실시간 채팅 + AI 요약')

  // 트레이 아이콘 클릭 이벤트 (Windows/Linux)
  if (process.platform !== 'darwin') {
    tray.on('click', () => {
      if (mainWindow) {
        if (mainWindow.isVisible()) {
          mainWindow.hide()
        } else {
          mainWindow.show()
          mainWindow.focus()
        }
      }
    })
  }

  // 더블클릭으로 창 열기 (macOS)
  if (process.platform === 'darwin') {
    tray.on('double-click', () => {
      if (mainWindow) {
        mainWindow.show()
        mainWindow.focus()
      }
    })
  }

  return tray
}

// 트레이 아이콘 업데이트 (새 메시지 알림 등)
function updateTrayIcon(hasUnreadMessages = false) {
  if (!tray) return

  try {
    let iconPath
    if (hasUnreadMessages) {
      iconPath = path.join(__dirname, '../public/tray-icon-unread.png')
    } else {
      iconPath = path.join(__dirname, '../public/tray-icon.png')
    }

    let icon = nativeImage.createFromPath(iconPath)
    
    // 파일이 없으면 기본 아이콘 사용
    if (icon.isEmpty()) {
      icon = nativeImage.createFromPath(path.join(__dirname, '../public/icon.png'))
    }

    if (process.platform === 'darwin') {
      icon = icon.resize({ width: 16, height: 16 })
      icon.setTemplateImage(!hasUnreadMessages) // 읽지 않은 메시지가 있으면 컬러 아이콘
    }

    tray.setImage(icon)
    
    // 툴팁 업데이트
    const tooltip = hasUnreadMessages 
      ? 'ChatFlow - 새 메시지가 있습니다!' 
      : 'ChatFlow - 실시간 채팅 + AI 요약'
    tray.setToolTip(tooltip)

  } catch (error) {
    console.error('트레이 아이콘 업데이트 실패:', error)
  }
}

// 트레이 제거
function destroyTray() {
  if (tray) {
    tray.destroy()
    tray = null
  }
}

module.exports = {
  createTray,
  updateTrayIcon,
  destroyTray
}