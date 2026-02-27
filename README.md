# ChatFlow - 실시간 채팅 + AI 요약 시스템 🚀

> **현대적인 마이크로서비스 아키텍처**로 구축된 실시간 채팅 플랫폼  
> AI 기반 대화 요약, 한국어 검색, 크로스 플랫폼 지원

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.java.net/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.4-brightgreen.svg)](https://vuejs.org/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.5-blue.svg)](https://www.typescriptlang.org/)
[![Valkey](https://img.shields.io/badge/Valkey-7.2-red.svg)](https://valkey.io/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## 📋 목차
- [🏗️ 시스템 아키텍처](#️-시스템-아키텍처)
- [🚀 기술 스택](#-기술-스택)
- [✨ 주요 기능](#-주요-기능)
- [🏃‍♂️ 빠른 시작](#️-빠른-시작)
- [📦 모듈 구조](#-모듈-구조)
- [🔧 환경 설정](#-환경-설정)
- [📊 서비스 접속 정보](#-서비스-접속-정보)
- [🌐 API 명세](#-api-명세)
- [🚢 배포 가이드](#-배포-가이드)
- [🧪 테스트](#-테스트)

## 🏗️ 시스템 아키텍처

```mermaid
graph TB
    subgraph "Frontend Layer"
        WEB[Vue3 Web App<br/>Port: 3000]
        ELECTRON[Electron Desktop App]
    end
    
    subgraph "API Layer"
        GATEWAY[Spring Gateway<br/>Port: 8000]
    end
    
    subgraph "Service Layer"
        CHAT[Chat Service<br/>Port: 8080]
        AI[AI Summary Service<br/>Port: 8081]
        SEARCH[Search Service<br/>Port: 8082]
    end
    
    subgraph "Data Layer"
        KAFKA[Apache Kafka<br/>Port: 9092]
        VALKEY[Valkey<br/>Port: 6379]
        ES[Elasticsearch + Nori<br/>Port: 9200]
        POSTGRES[PostgreSQL<br/>Port: 5432]
    end
    
    subgraph "Monitoring Layer"
        PROMETHEUS[Prometheus<br/>Port: 9090]
        GRAFANA[Grafana<br/>Port: 3001]
        KIBANA[Kibana<br/>Port: 5601]
    end
    
    WEB --> GATEWAY
    ELECTRON --> GATEWAY
    
    GATEWAY --> CHAT
    GATEWAY --> AI  
    GATEWAY --> SEARCH
    
    CHAT --> KAFKA
    CHAT --> VALKEY
    CHAT --> POSTGRES
    
    AI --> KAFKA
    AI --> VALKEY
    
    SEARCH --> KAFKA
    SEARCH --> ES
    
    CHAT --> PROMETHEUS
    AI --> PROMETHEUS
    SEARCH --> PROMETHEUS
    GATEWAY --> PROMETHEUS
    
    PROMETHEUS --> GRAFANA
    ES --> KIBANA
```

## 🚀 기술 스택

### 🖥️ **Frontend**
- ![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D?style=flat&logo=vue.js) **Vue 3.4** - 컴포지션 API, 리액티비티
- ![TypeScript](https://img.shields.io/badge/TypeScript-5.5-3178C6?style=flat&logo=typescript) **TypeScript 5.5** - 타입 안전성
- ![Vite](https://img.shields.io/badge/Vite-5.4-646CFF?style=flat&logo=vite) **Vite 5.4** - 초고속 빌드 도구
- ![Bootstrap](https://img.shields.io/badge/Bootstrap-5.3-7952B3?style=flat&logo=bootstrap) **Bootstrap 5** - 반응형 UI 프레임워크
- ![Pinia](https://img.shields.io/badge/Pinia-2.1-F9D71C?style=flat&logo=vue.js) **Pinia** - Vue 상태 관리
- ![Electron](https://img.shields.io/badge/Electron-31.0-47848F?style=flat&logo=electron) **Electron 31** - 크로스 플랫폼 데스크톱

### ⚡ **Backend**
- ![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat&logo=openjdk) **Java 21** - LTS 버전, 최신 기능
- ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.0-6DB33F?style=flat&logo=spring) **Spring Boot 3.2** - 엔터프라이즈 프레임워크
- ![Spring WebSocket](https://img.shields.io/badge/WebSocket-STOMP-6DB33F?style=flat&logo=spring) **Spring WebSocket** - 실시간 통신
- ![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-7.4-000000?style=flat&logo=apachekafka) **Apache Kafka** - 이벤트 스트리밍
- ![LangChain4J](https://img.shields.io/badge/LangChain4J-0.25-FF6B6B?style=flat) **LangChain4J** - AI 통합 프레임워크
- ![Gemini](https://img.shields.io/badge/Gemini-1.5_Flash-4285F4?style=flat&logo=google) **Google Gemini** - AI 언어 모델

### 🗄️ **Data & Search**
- ![Valkey](https://img.shields.io/badge/Valkey-7.2-DC382D?style=flat&logo=redis) **Valkey 7.2** - Redis 호환 인메모리 DB
- ![Elasticsearch](https://img.shields.io/badge/Elasticsearch-8.11-005571?style=flat&logo=elasticsearch) **Elasticsearch 8.11** - 검색 엔진
- ![Nori](https://img.shields.io/badge/Nori-Korean-005571?style=flat) **Nori 분석기** - 한국어 형태소 분석
- ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?style=flat&logo=postgresql) **PostgreSQL 16** - 관계형 데이터베이스

### 📊 **DevOps & Monitoring**
- ![Docker](https://img.shields.io/badge/Docker-20.10-0db7ed?style=flat&logo=docker) **Docker & Compose** - 컨테이너화
- ![Prometheus](https://img.shields.io/badge/Prometheus-2.47-E6522C?style=flat&logo=prometheus) **Prometheus** - 메트릭 수집
- ![Grafana](https://img.shields.io/badge/Grafana-10.2-F46800?style=flat&logo=grafana) **Grafana** - 모니터링 대시보드
- ![Gradle](https://img.shields.io/badge/Gradle-8.5-02303A?style=flat&logo=gradle) **Gradle 8.5** - 빌드 자동화

## ✨ 주요 기능

### 🎯 **핵심 기능**
- **🔄 실시간 채팅** - WebSocket 기반 즉시 메시지 전송
- **🤖 AI 요약** - Google Gemini 1.5 Flash로 대화 내용 자동 요약
- **🔍 한국어 검색** - Nori 형태소 분석기 + N-gram 검색
- **📱 크로스 플랫폼** - 웹/데스크톱 통합 지원
- **🏗️ 마이크로서비스** - 독립적이고 확장 가능한 아키텍처

### 💎 **고급 기능**
- **🎨 모던 UI/UX** - 아름다운 채팅방 생성 모달, 그라디언트 디자인
- **🌙 다크 모드** - 시스템 테마 자동 감지
- **🚀 성능 최적화** - Valkey 캐싱, 비동기 처리
- **📈 실시간 모니터링** - Prometheus/Grafana 메트릭
- **🔐 보안** - Circuit Breaker, CORS 설정

### 🎪 **사용자 경험**
- **채팅방별 색상 구분** - 시각적 식별성 강화
- **실시간 타이핑 표시** - 사용자 활동 상태 표시
- **검색 결과 하이라이트** - 검색어 강조 표시
- **반응형 디자인** - 모든 디바이스에서 최적화

## 🏃‍♂️ 빠른 시작

### 🔧 **사전 요구사항**
- **Java 21+** (OpenJDK 권장)
- **Node.js 23.5+** 
- **Docker & Docker Compose**
- **Git**

### ⚡ **1분만에 실행하기**

```bash
# 1. 프로젝트 클론
git clone https://github.com/KwonSeungwon/chat_flow.git
cd chat_flow

# 2. 인프라 서비스 실행 (백그라운드)
docker compose up -d valkey kafka elasticsearch postgresql

# 3. 백엔드 빌드 & 실행
./gradlew build
./gradlew bootRun --parallel

# 4. 프론트엔드 실행 (새 터미널)
cd frontend
npm install
npm run dev
```

🎉 **완료!** 브라우저에서 http://localhost:3000 접속

### 🖥️ **데스크톱 앱 실행**

```bash
cd frontend

# 개발 모드 (hot reload)
npm run electron:dev

# 프로덕션 빌드
npm run electron:dist
```

## 📦 모듈 구조

```
chat_flow/
├── 🎨 frontend/                    # Vue3 + Electron 프론트엔드
│   ├── src/
│   │   ├── components/            # 재사용 가능한 컴포넌트
│   │   │   ├── CreateRoomModal.vue    # 채팅방 생성 모달
│   │   │   ├── ChatRoomSidebar.vue    # 채팅방 목록
│   │   │   ├── ChatMessages.vue       # 메시지 렌더링
│   │   │   ├── ChatHeader.vue         # 채팅방 헤더
│   │   │   ├── ChatInput.vue          # 메시지 입력
│   │   │   ├── NavBar.vue             # 네비게이션 바
│   │   │   └── AISummarySidebar.vue   # AI 요약 사이드바
│   │   ├── views/                 # 페이지 뷰 (ChatView, SearchView)
│   │   ├── composables/           # Vue 3 Composables
│   │   │   ├── useWebSocket.ts    # STOMP/WebSocket 관리
│   │   │   ├── useTheme.ts        # 다크/라이트 테마
│   │   │   └── useElectron.ts     # Electron 연동
│   │   ├── types/                 # TypeScript 타입 정의
│   │   └── assets/styles/         # SCSS 스타일
│   ├── electron/                  # Electron 메인 프로세스
│   ├── public/                    # 정적 자원 (아이콘 등)
│   └── package.json
├── 🏗️ common/                      # 공통 라이브러리
│   └── src/main/java/com/chatflow/common/
│       ├── BaseMessage.java       # 메시지 기본 클래스
│       ├── ChatMessage.java       # 채팅 메시지 DTO
│       └── KafkaCommonConfig.java # Kafka 공통 설정
├── 💬 chat-service/                # 실시간 채팅 서비스 (Port: 8080)
│   └── src/main/java/com/chatflow/chat/
│       ├── ChatController.java    # WebSocket STOMP 핸들러
│       ├── ChatService.java       # 메시지 처리 + Kafka 발행
│       └── WebSocketConfig.java   # WebSocket/STOMP 설정
├── 🤖 ai-summary-service/          # AI 요약 서비스 (Port: 8081)
│   └── src/main/java/com/chatflow/aisummary/
│       └── AiSummaryService.java  # Kafka 소비 → Gemini 요약 → Kafka 발행
├── 🔍 search-service/              # 검색 서비스 (Port: 8082)
│   └── src/main/java/com/chatflow/search/
│       ├── SearchController.java      # REST API 엔드포인트
│       ├── SearchService.java         # Kafka 소비 → ES 인덱싱
│       ├── KoreanSearchService.java   # 한국어 검색 (Nori + N-gram)
│       ├── ElasticsearchConfig.java   # ES 클라이언트 설정
│       ├── IndexInitializer.java      # 인덱스 자동 생성
│       └── ChatMessageDocument.java   # ES 도큐먼트 매핑
├── 🚪 gateway-service/             # API 게이트웨이 (Port: 8000)
│   └── src/main/java/com/chatflow/gateway/
│       └── GatewayApplication.java
├── 🐳 elasticsearch/               # Elasticsearch 설정
│   ├── Dockerfile                 # Nori 플러그인 포함
│   └── config/
│       └── korean-analyzer-config.json  # 한국어 분석기 설정
├── 📊 monitoring/                  # 모니터링 설정
│   └── prometheus.yml
├── 🚢 k8s/                        # Kubernetes 매니페스트
├── 📜 scripts/                    # 배포 및 유틸리티 스크립트
└── 📋 docker-compose.local.yml    # 로컬 개발 환경 설정
```

## 🔧 환경 설정

### 🌟 **필수 환경 변수**

```bash
# AI Summary Service - Gemini API 키
export GEMINI_API_KEY="AIza-your-gemini-api-key"

# 개발 환경에서 IDE 실행 시
# IntelliJ IDEA: Run Configuration > Environment variables
# VS Code: launch.json에 env 설정

# Gemini API 키 발급 방법:
# 1. https://aistudio.google.com/app/apikey 접속
# 2. "Create API key" 클릭하여 새 키 생성
# 3. 생성된 키를 환경변수에 설정
```

### ⚙️ **선택적 설정**

```bash
# Valkey 연결 정보 (기본값)
VALKEY_HOST=localhost
VALKEY_PORT=6379

# Elasticsearch 연결 정보 (기본값)  
ELASTICSEARCH_URL=http://localhost:9200

# Kafka 연결 정보 (기본값)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# 로그 레벨 (개발: DEBUG, 운영: INFO)
LOGGING_LEVEL_ROOT=INFO
```

### 🔐 **프로덕션 설정**

```yaml
# application-prod.yml 예시
spring:
  profiles:
    active: prod
  datasource:
    url: jdbc:postgresql://prod-db:5432/chatflow
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  redis:
    host: ${VALKEY_HOST:prod-valkey}
    port: ${VALKEY_PORT:6379}
    password: ${VALKEY_PASSWORD}
    
logging:
  level:
    com.chatflow: INFO
    root: WARN
```

## 📊 서비스 접속 정보

### 🚀 **애플리케이션**
| 서비스 | URL | 설명 |
|--------|-----|------|
| **웹 앱** | http://localhost:3000 | Vue3 프론트엔드 |
| **데스크톱 앱** | `npm run electron:dev` | Electron 앱 |
| **API Gateway** | http://localhost:8000 | 통합 API 엔드포인트 |
| **WebSocket** | ws://localhost:8000/ws | 실시간 통신 |

### 🔧 **개발 도구**
| 도구 | URL | 계정 | 설명 |
|------|-----|------|------|
| **Grafana** | http://localhost:3001 | admin/admin | 모니터링 대시보드 |
| **Prometheus** | http://localhost:9090 | - | 메트릭 수집기 |
| **Kibana** | http://localhost:5601 | - | Elasticsearch 관리 |
| **Swagger UI** | http://localhost:8000/swagger-ui.html | - | API 문서 |

### 🛠️ **인프라 서비스**
| 서비스 | 포트 | 설명 |
|--------|------|------|
| **Valkey** | 6379 | Redis 호환 캐시 |
| **Kafka** | 9092 | 메시지 큐 |
| **Elasticsearch** | 9200 | 검색 엔진 |
| **PostgreSQL** | 5432 | 메인 데이터베이스 |
| **Zookeeper** | 2181 | Kafka 코디네이터 |

## 🌐 API 명세

### 🚪 **Gateway Routes**
- **Base URL**: http://localhost:8000
- **WebSocket**: ws://localhost:8000/ws

### 💬 **Chat API**
```http
# 채팅 메시지 전송
POST /api/chat/rooms/{roomId}/messages
Content-Type: application/json

{
  "content": "안녕하세요!",
  "username": "사용자"
}

# 채팅방 목록 조회
GET /api/chat/rooms

# 채팅방 생성
POST /api/chat/rooms
{
  "name": "새 채팅방",
  "description": "설명",
  "color": "#6366f1",
  "isPrivate": false
}
```

### 🤖 **AI Summary API**
```http
# 채팅방 요약 조회
GET /api/ai-summary/room/{roomId}

# 요약 요청
POST /api/ai-summary/room/{roomId}/generate
```

### 🔍 **Search API**
```http
# 한국어 검색
GET /api/search/korean?query=안녕&roomId=general&page=0&size=20

# N-gram 검색 (부분 매칭)
GET /api/search/ngram?query=안녕&roomId=general

# 시간대별 검색
GET /api/search/rooms/{roomId}/time-range?start=2024-01-01T00:00:00&end=2024-01-02T00:00:00
```

### 📊 **Health Check**
```http
# 서비스 상태 확인
GET /actuator/health

# 메트릭 조회
GET /actuator/metrics
GET /actuator/prometheus
```

## 🚢 배포 가이드

### 🐳 **Docker 배포**

#### 전체 스택 배포
```bash
# 프로덕션 환경 실행
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d

# 로그 확인
docker-compose logs -f

# 스케일링
docker-compose up -d --scale chat-service=3
```

#### 개별 서비스 빌드
```bash
# 백엔드 서비스들
docker build --build-arg SERVICE_NAME=chat-service -t chatflow/chat-service .
docker build --build-arg SERVICE_NAME=ai-summary-service -t chatflow/ai-summary-service .
docker build --build-arg SERVICE_NAME=search-service -t chatflow/search-service .
docker build --build-arg SERVICE_NAME=gateway-service -t chatflow/gateway-service .

# 프론트엔드
cd frontend
docker build -t chatflow/frontend .

# Elasticsearch (Nori 포함)
cd elasticsearch
docker build -t chatflow/elasticsearch .
```

### ☸️ **Kubernetes 배포**

```bash
# 네임스페이스 생성
kubectl create namespace chatflow

# ConfigMap 및 Secret 생성
kubectl apply -f k8s/config/

# 인프라 서비스 배포 (StatefulSet)
kubectl apply -f k8s/infrastructure/

# 애플리케이션 서비스 배포 (Deployment)  
kubectl apply -f k8s/applications/

# 인그레스 설정
kubectl apply -f k8s/ingress/

# 배포 상태 확인
kubectl get pods -n chatflow
kubectl get services -n chatflow
```

### 🔄 **CI/CD Pipeline** (GitHub Actions)

```yaml
# .github/workflows/deploy.yml
name: Deploy ChatFlow

on:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
      - run: ./gradlew test

  build-and-deploy:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - name: Build and Push Docker Images
        run: |
          docker build -t ${{ secrets.REGISTRY }}/chatflow/chat-service .
          docker push ${{ secrets.REGISTRY }}/chatflow/chat-service
      
      - name: Deploy to Kubernetes
        run: |
          kubectl set image deployment/chat-service chat-service=${{ secrets.REGISTRY }}/chatflow/chat-service:${{ github.sha }}
```

## 🧪 테스트

### 🔍 **단위 테스트**
```bash
# 전체 테스트 실행
./gradlew test

# 특정 서비스 테스트
./gradlew :chat-service:test
./gradlew :ai-summary-service:test
./gradlew :search-service:test

# 테스트 커버리지 리포트
./gradlew jacocoTestReport
```

### 🚀 **통합 테스트**
```bash
# 통합 테스트 실행
./gradlew integrationTest

# API 테스트 (Postman Collection 사용)
newman run tests/ChatFlow.postman_collection.json
```

### 🔍 **한국어 검색 테스트**
```bash
# Elasticsearch 한국어 검색 테스트
./test-korean-search.sh

# 예상 결과:
# - "날씨" 검색 → "오늘 날씨가 좋네요" 매칭
# - "공원" 검색 → "공원을 걸었어요" 매칭  
# - "Spring" 검색 → "Spring Boot 연동" 매칭
```

### 🎯 **성능 테스트**
```bash
# JMeter로 부하 테스트
jmeter -n -t tests/ChatFlow-LoadTest.jmx

# k6로 성능 테스트  
k6 run tests/performance-test.js
```

## 🤝 기여하기

### 📋 **개발 가이드라인**
1. **브랜치 전략**: Git Flow 사용
   - `main`: 프로덕션 브랜치
   - `develop`: 개발 브랜치  
   - `feature/*`: 기능 개발
   - `hotfix/*`: 핫픽스

2. **커밋 메시지**: Conventional Commits
   ```
   feat: 새로운 채팅방 생성 모달 추가
   fix: Valkey 연결 오류 수정
   docs: README 업데이트
   test: 한국어 검색 테스트 케이스 추가
   ```

3. **코드 스타일**: 
   - Java: Google Java Style Guide
   - TypeScript: ESLint + Prettier
   - Vue: Vue Style Guide

### 🐛 **이슈 리포팅**
- [GitHub Issues](https://github.com/KwonSeungwon/chat_flow/issues)에서 버그 리포트 및 기능 요청
- 이슈 템플릿을 사용해 상세한 정보 제공

## 📈 로드맵

### 🎯 **v1.1 (다음 릴리스)**
- [ ] 사용자 인증/인가 (JWT)
- [ ] 파일 업로드 및 이미지 공유
- [ ] 메시지 읽음/안읽음 상태
- [ ] 푸시 알림 (PWA)

### 🚀 **v1.2 (향후 계획)**
- [ ] 음성 메시지 
- [ ] 화상 통화 (WebRTC)
- [ ] 다국어 지원 (i18n)
- [ ] 테마 커스터마이징
- [ ] 채팅 봇 통합

### 🌟 **v2.0 (장기 비전)**
- [ ] 모바일 앱 (React Native)
- [ ] 엔터프라이즈 기능 (SSO, 감사 로그)
- [ ] AI 챗봇 어시스턴트
- [ ] 블록체인 기반 보안 메시징

## 📄 라이선스

이 프로젝트는 [MIT 라이선스](LICENSE)하에 배포됩니다.

## 👥 팀

- **개발자**: [KwonSeungwon](https://github.com/KwonSeungwon)
- **AI 어드바이저**: Claude (Anthropic)

## 🙏 감사의 말

이 프로젝트는 다음 오픈소스 프로젝트들의 도움을 받았습니다:

- **Spring Framework** - 강력한 백엔드 프레임워크
- **Vue.js** - 직관적인 프론트엔드 프레임워크  
- **Valkey** - Redis 호환 고성능 인메모리 DB
- **Elasticsearch** - 확장 가능한 검색 엔진
- **Apache Kafka** - 확장 가능한 이벤트 스트리밍 플랫폼

---

<div align="center">

**⭐ 이 프로젝트가 도움이 되었다면 Star를 눌러주세요! ⭐**

Made with ❤️ by [KwonSeungwon](https://github.com/KwonSeungwon)

</div>