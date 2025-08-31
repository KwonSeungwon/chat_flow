const path = require('path')

module.exports = {
  appId: 'com.chatflow.app',
  productName: 'ChatFlow',
  copyright: 'Copyright © 2024 ChatFlow Team',
  
  directories: {
    output: 'dist-electron',
    buildResources: 'build'
  },
  
  files: [
    'dist/**/*',
    'electron/**/*',
    'node_modules/**/*',
    '!node_modules/**/{CHANGELOG.md,README.md,readme.md,readme.txt,changelog.md}',
    '!node_modules/**/{test,__tests__,tests,powered-test,example,examples}/**',
    '!node_modules/**/*.d.ts',
    '!node_modules/**/.bin',
    '!**/{.DS_Store,.git,.hg,.svn,CVS,RCS,SCCS,.gitignore,.gitattributes}',
    '!**/{__pycache__,thumbs.db,.flowconfig,.idea,.vs,.nyc_output}',
    '!**/{appveyor.yml,.travis.yml,circle.yml}',
    '!**/{npm-debug.log,yarn.lock,.yarn-integrity,.yarn-metadata.json}'
  ],
  
  // macOS 설정
  mac: {
    category: 'public.app-category.social-networking',
    target: [
      {
        target: 'dmg',
        arch: ['x64', 'arm64']
      },
      {
        target: 'mas', // Mac App Store
        arch: ['x64', 'arm64']
      }
    ],
    icon: 'build/icon.icns',
    darkModeSupport: true,
    hardenedRuntime: true,
    gatekeeperAssess: false,
    entitlements: 'build/entitlements.mac.plist',
    entitlementsInherit: 'build/entitlements.mac.plist'
  },
  
  // Windows 설정
  win: {
    target: [
      {
        target: 'nsis',
        arch: ['x64']
      },
      {
        target: 'msi',
        arch: ['x64']
      }
    ],
    icon: 'build/icon.ico'
  },
  
  // Linux 설정
  linux: {
    target: [
      {
        target: 'AppImage',
        arch: ['x64']
      },
      {
        target: 'deb',
        arch: ['x64']
      },
      {
        target: 'rpm',
        arch: ['x64']
      }
    ],
    icon: 'build/icons',
    category: 'Network'
  },
  
  // NSIS (Windows Installer) 설정
  nsis: {
    oneClick: false,
    allowToChangeInstallationDirectory: true,
    allowElevation: true,
    installerIcon: 'build/icon.ico',
    uninstallerIcon: 'build/icon.ico',
    installerHeaderIcon: 'build/icon.ico',
    createDesktopShortcut: true,
    createStartMenuShortcut: true,
    shortcutName: 'ChatFlow'
  },
  
  // DMG (macOS) 설정
  dmg: {
    contents: [
      {
        x: 130,
        y: 220
      },
      {
        x: 410,
        y: 220,
        type: 'link',
        path: '/Applications'
      }
    ],
    window: {
      width: 540,
      height: 380
    }
  },
  
  // 자동 업데이트 설정
  publish: [
    {
      provider: 'github',
      owner: 'your-github-username',
      repo: 'chatflow',
      private: false
    }
  ],
  
  // 압축 설정
  compression: 'maximum',
  
  // 서명 설정 (프로덕션용)
  afterSign: 'build/notarize.js',
  
  // 추가 리소스
  extraResources: [
    {
      from: 'build/assets',
      to: 'assets'
    }
  ],
  
  // 개발자 설정
  buildDependenciesFromSource: false,
  nodeGypRebuild: false,
  npmRebuild: true
}