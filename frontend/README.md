# ChatFlow Frontend

Flutter Web + Android 크로스 플랫폼 프론트엔드.

## 기술 스택

| 패키지 | 버전 | 용도 |
|--------|------|------|
| flutter_riverpod | 2.5 | 상태 관리 (StateNotifier) |
| go_router | 14 | 선언적 라우팅 |
| dio | 5.4 | HTTP + JWT 인터셉터 |
| stomp_dart_client | 2.0 | WebSocket STOMP |
| flutter_secure_storage | 9.2 | JWT 토큰 저장 (minSdk 23) |
| flutter_dotenv | 5.1 | 환경 변수 (.env) |
| intl | 0.19 | 날짜/시간 포맷 (KST) |

## 실행 방법

```bash
flutter pub get

# 웹 개발
flutter run -d chrome

# 프로덕션 웹 빌드
flutter build web --release

# Android APK
flutter build apk --release
```

## 구조

```
lib/
├── main.dart                    # ProviderScope + dotenv
├── core/
│   ├── network/
│   │   ├── dio_client.dart      # HTTP (Bearer 자동 주입, 401 처리)
│   │   └── stomp_service.dart   # STOMP WebSocket (지수 백오프 재연결)
│   ├── routing/app_router.dart  # GoRouter + token redirect
│   └── theme/
│       ├── app_theme.dart       # Material 3 라이트/다크
│       └── theme_provider.dart  # themeModeProvider
├── features/
│   ├── auth/                    # 로그인/회원가입/게스트
│   ├── chat/                    # 채팅방 목록, 메시지, 입력
│   └── search/                  # 한국어 메시지 검색
└── shared/models/               # ChatMessage, ChatRoom
```

## 환경 변수 (.env)

```
API_BASE_URL=https://app.chatflow.ai.kr   # Android 네이티브용
WS_URL=wss://app.chatflow.ai.kr/ws-native # Android 네이티브용
```

> 웹에서는 현재 origin에서 URL을 자동 파생하므로 .env 불필요.

## Docker 빌드 (EC2 배포용)

```bash
# 1. Flutter 웹 빌드
flutter build web --release

# 2. amd64 이미지 빌드 (Mac M1/M2에서 크로스 빌드)
docker buildx build --platform linux/amd64 \
  -t chatflow/frontend:prod --load .

# 3. 저장 & 전송
docker save chatflow/frontend:prod | gzip > /tmp/frontend.tar.gz
scp -i ~/web-app-key.pem /tmp/frontend.tar.gz ubuntu@43.201.22.86:~/
```

## 주요 결정 사항

- **WebSocket 경로**: `/ws-native` — Gateway SecurityConfig에서 `permitAll()` 필수
- **Auth 가드**: `chatNotifierProvider`는 `auth.token != null` 확인 후 `joinRoom` 호출
- **APK 다운로드**: `kIsWeb` 분기 + `dart:html` 조건부 import (3파일 패턴)
- **minSdk=23**: `flutter_secure_storage` 9.x 요구사항
