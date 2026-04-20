# Remaining Backend Improvements Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 잔여 백엔드 3개 항목(AI Summary Gemini 블로킹 분리, Flyway 마이그레이션 인프라, Kafka 파티션 증설)을 구현하고 배포한다.

**Architecture:** 
- Task 1: AI Summary Kafka Listener에서 Gemini 호출을 TaskExecutor로 분리하여 Consumer 스레드 블로킹 제거
- Task 2: Flyway baseline 방식 도입 — 운영 중인 DB 스키마를 V1 baseline으로 등록하고 이후 마이그레이션 관리
- Task 3: `KAFKA_NUM_PARTITIONS: 3` + `KafkaTopicConfig`로 주요 토픽을 3파티션으로 선언

**Tech Stack:** Spring Boot 3.2, Kafka, Flyway 10.x, PostgreSQL 16

---

## 개선 대상 요약

| # | 영역 | 심각도 | 설명 |
|---|------|--------|------|
| 1 | ai-summary-service | High | Gemini API 호출이 Kafka Listener 스레드 블로킹 → 장시간 호출 시 rebalance/lag |
| 2 | chat-service | Medium | Flyway 미도입 — 스키마 변경 관리 어려움, `ddl-auto: validate`가 유일한 안전장치 |
| 3 | infra | Medium | Kafka 단일 파티션 → 추후 컨슈머 확장 대비 3파티션 선언 |

---

## File Map

| 파일 | 변경 유형 | 담당 |
|------|-----------|------|
| `ai-summary-service/src/main/java/com/chatflow/aisummary/config/AsyncConfig.java` | Create | Task 1 |
| `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java` | Modify | Task 1 |
| `chat-service/build.gradle` | Modify (Flyway deps) | Task 2 |
| `chat-service/src/main/resources/db/migration/V1__baseline.sql` | Create | Task 2 |
| `chat-service/src/main/resources/application-prod.yml` | Modify (flyway config) | Task 2 |
| `chat-service/src/main/resources/application-local.yml` | Modify (flyway config) | Task 2 |
| `chat-service/src/main/java/com/chatflow/chat/config/KafkaTopicConfig.java` | Create | Task 3 |
| `k8s/infra/k3s-infra.yaml:172` | Modify (partitions 1→3) | Task 3 |

---

## Task 1: AI Summary Kafka Consumer — Gemini 호출 분리

**Files:**
- Create: `ai-summary-service/src/main/java/com/chatflow/aisummary/config/AsyncConfig.java`
- Modify: `ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java`

**문제**: `handleChatMessage`/`handleSummaryRequest`가 트리거 조건 충족 시 동기적으로 `generateSummary()` → `chatModelClient.generate()` 호출. Gemini API 응답이 수 초~수십 초 걸리면 Kafka Consumer 스레드가 블로킹되어 poll timeout, consumer rebalance 위험.

**해결**: 별도 `ThreadPoolTaskExecutor`를 정의하고 `@Async` 또는 `executor.submit()`으로 Gemini 호출을 오프로드.

- [ ] **Step 1: AsyncConfig 생성**

파일 생성: `ai-summary-service/src/main/java/com/chatflow/aisummary/config/AsyncConfig.java`

```java
package com.chatflow.aisummary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Gemini API 호출 전용 Executor.
     * Kafka Consumer 스레드가 장시간 블로킹되지 않도록 작업을 오프로드한다.
     */
    @Bean(name = "geminiExecutor")
    public Executor geminiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("gemini-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

**설계 근거**:
- `corePoolSize=2, maxPoolSize=4`: 동시 Gemini 호출 최대 4건 허용 (rate limit 10/min 고려)
- `queueCapacity=50`: 폭주 시 대기 버퍼
- `CallerRunsPolicy`: 큐 가득 찰 경우 Consumer 스레드가 직접 실행 (fallback) — 백프레셔

- [ ] **Step 2: AiSummaryService에 @Async 적용**

`ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java`를 먼저 읽어 현재 구조 파악.

`generateSummary()` 메서드에 `@Async("geminiExecutor")` 추가. 단, `@Async`는 같은 클래스 내 호출 시 동작 안 함(Spring AOP 프록시 한계). `handleChatMessage` → `addMessageAndCheckTrigger` → `generateSummary`가 모두 같은 클래스 내라면 프록시 우회 불가.

**대안 (권장)**: `ApplicationContext`에서 `self` 빈을 주입해 프록시 통과. 또는 `geminiExecutor.submit(() -> generateSummary(...))`로 명시적 submit.

파일 상단에 import:
```java
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
```

클래스 필드에 추가:
```java
private final Executor geminiExecutor;
```

생성자에 주입 (Lombok `@RequiredArgsConstructor` 쓴다면 `@Qualifier` 못 쓰므로 명시 생성자 필요):
```java
public AiSummaryService(
        // ... 기존 의존성들 ...
        @Qualifier("geminiExecutor") Executor geminiExecutor
) {
    // ...
    this.geminiExecutor = geminiExecutor;
}
```

**주의**: `@RequiredArgsConstructor`를 쓰고 있다면 제거하고 모든 필드를 받는 명시 생성자로 전환. 또는 `@Qualifier`를 final 필드에 직접 달아 Lombok이 생성자에 포함시키도록 함:
```java
@Qualifier("geminiExecutor") private final Executor geminiExecutor;
```
Lombok 1.18+ 에서는 두 번째 방법이 동작한다.

`addMessageAndCheckTrigger` 또는 트리거 발동 지점에서 `generateSummary` 직접 호출을:
```java
// 변경 전:
generateSummary(chatRoomId, messages);

// 변경 후:
geminiExecutor.execute(() -> generateSummary(chatRoomId, messages));
```

**주의사항**:
- `generateSummary` 내부의 예외는 Executor 스레드에서 처리됨. 로깅 확인.
- `chatModelClient`, `rateLimiter` 등이 thread-safe인지 확인 (Spring Bean이므로 일반적으로 OK)

- [ ] **Step 3: 빌드 확인**

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow
./gradlew :ai-summary-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  ai-summary-service/src/main/java/com/chatflow/aisummary/config/AsyncConfig.java \
  ai-summary-service/src/main/java/com/chatflow/aisummary/service/AiSummaryService.java
git commit -m "$(cat <<'EOF'
perf: AI Summary Gemini 호출을 별도 Executor로 분리 (Kafka 스레드 블로킹 제거)

Gemini API 응답 지연으로 Kafka Consumer 스레드가 블로킹되어
max.poll.interval.ms 초과 시 rebalance가 발생할 수 있었다.
전용 ThreadPoolTaskExecutor(geminiExecutor)로 generateSummary를 오프로드.

Constraint: Gemini rate limit 10/min — maxPoolSize=4로 제한
Rejected: @Async 어노테이션 | 같은 클래스 내 호출 시 프록시 우회 필요
Confidence: high
Scope-risk: moderate

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: chat-service Flyway baseline 도입

**Files:**
- Modify: `chat-service/build.gradle`
- Create: `chat-service/src/main/resources/db/migration/V1__baseline.sql`
- Modify: `chat-service/src/main/resources/application-prod.yml`
- Modify: `chat-service/src/main/resources/application-local.yml`

**문제**: 스키마 변경 이력이 JPA 엔티티 diff에만 있음. `ddl-auto: validate` 해제 없이는 Hibernate 임의 변경 방지 불가하지만 수동 마이그레이션 관리 불가.

**해결**: Flyway를 추가해 이후 마이그레이션을 관리. 현재 운영 중인 스키마는 `V1__baseline.sql`로 캡처하고 `baseline-on-migrate=true`로 기존 DB와 호환. 이 플랜에서는 **baseline만 구축**하고 실제 스키마 변경은 향후 별도 V2, V3로 추가.

- [ ] **Step 1: Flyway 의존성 추가**

`chat-service/build.gradle` 파일을 읽고 `dependencies` 블록에 추가:

```gradle
dependencies {
    // ... 기존 의존성 ...
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
}
```

Spring Boot 3.2의 의존성 관리가 Flyway 버전을 지정하므로 명시 버전 불필요.

- [ ] **Step 2: V1__baseline.sql 생성**

디렉터리 생성:
```bash
mkdir -p /Users/seungwon-kwon/IdeaProjects/chat_flow/chat-service/src/main/resources/db/migration
```

파일 생성: `chat-service/src/main/resources/db/migration/V1__baseline.sql`

현재 JPA 엔티티(ChatRoom, ChatMessageEntity, OutboxEvent)에서 Hibernate가 생성하는 스키마를 수동 DDL로 작성. 모든 테이블은 `CREATE TABLE IF NOT EXISTS`로 작성하여 기존 DB에 안전.

**주의**: 실제 엔티티를 읽어 정확한 컬럼 타입/제약조건을 반영할 것. 아래는 참고 템플릿이며 엔티티 파일 확인 후 정확한 DDL 생성 필요:

```sql
-- V1__baseline.sql
-- 운영 중인 chat-service 스키마의 baseline.
-- 기존 DB에 이미 테이블이 존재하므로 IF NOT EXISTS로 안전.

-- chat_rooms 테이블
CREATE TABLE IF NOT EXISTS chat_rooms (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    color VARCHAR(20),
    room_type VARCHAR(50),
    is_private BOOLEAN DEFAULT FALSE,
    password VARCHAR(255),
    created_by VARCHAR(255),
    participant_count INTEGER DEFAULT 0,
    max_participants INTEGER DEFAULT 1000,
    last_message_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_chat_room_created_at ON chat_rooms(created_at);
CREATE INDEX IF NOT EXISTS idx_chat_room_name ON chat_rooms(name);

-- chat_messages 테이블
CREATE TABLE IF NOT EXISTS chat_messages (
    id VARCHAR(255) PRIMARY KEY,
    message_id VARCHAR(255),
    chat_room_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255),
    username VARCHAR(255),
    content TEXT,
    timestamp TIMESTAMP NOT NULL,
    type VARCHAR(50),
    priority VARCHAR(50),
    is_ai_generated BOOLEAN DEFAULT FALSE,
    file_url TEXT,
    file_name VARCHAR(255),
    file_content_type VARCHAR(100),
    parent_message_id VARCHAR(255),
    parent_message_preview TEXT,
    deleted BOOLEAN DEFAULT FALSE,
    edited BOOLEAN DEFAULT FALSE,
    edited_at TIMESTAMP,
    pinned BOOLEAN DEFAULT FALSE,
    reactions TEXT
);

CREATE INDEX IF NOT EXISTS idx_chat_room_id ON chat_messages(chat_room_id);
CREATE INDEX IF NOT EXISTS idx_timestamp ON chat_messages(timestamp);
CREATE INDEX IF NOT EXISTS idx_chat_room_timestamp ON chat_messages(chat_room_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_parent_message_id ON chat_messages(parent_message_id);

-- outbox_events 테이블
CREATE TABLE IF NOT EXISTS outbox_events (
    id VARCHAR(255) PRIMARY KEY,
    aggregate_type VARCHAR(100),
    aggregate_id VARCHAR(255),
    event_type VARCHAR(100),
    topic VARCHAR(255),
    payload TEXT,
    status VARCHAR(50),
    created_at TIMESTAMP NOT NULL,
    processed_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON outbox_events(status, created_at);
```

**중요**: 위 SQL은 템플릿이다. 반드시 다음 파일들을 읽어 정확한 컬럼 정의를 반영할 것:
- `chat-service/src/main/java/com/chatflow/chat/entity/ChatRoom.java`
- `chat-service/src/main/java/com/chatflow/chat/entity/ChatMessageEntity.java`
- `chat-service/src/main/java/com/chatflow/chat/entity/OutboxEvent.java`

각 엔티티의 `@Column`, `@JoinColumn`, `@Enumerated`, `@Lob` 어노테이션을 확인하여 `NOT NULL`, 길이 제한, 타입을 정확히 반영.

- [ ] **Step 3: application-prod.yml Flyway 설정**

`chat-service/src/main/resources/application-prod.yml`을 읽고 `spring:` 아래에 flyway 블록 추가 (기존 `jpa:`와 같은 레벨):

```yaml
spring:
  # ... 기존 설정 ...
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
    locations: classpath:db/migration
    validate-on-migrate: true
  jpa:
    hibernate:
      ddl-auto: validate   # Flyway가 스키마를 관리, Hibernate는 검증만
    # ... 나머지 jpa 설정 유지 ...
```

**설명**:
- `baseline-on-migrate: true`: 기존 DB에 Flyway를 처음 도입할 때 baseline 레코드 생성
- `baseline-version: 0`: V1보다 낮은 버전, V1이 적용됨
- V1 SQL이 `CREATE TABLE IF NOT EXISTS`이므로 기존 테이블 유지

- [ ] **Step 4: application-local.yml Flyway 설정**

`chat-service/src/main/resources/application-local.yml`을 읽고 flyway 추가. Local은 `create-drop`이므로 Flyway를 비활성화(또는 clean-disabled 해제):

```yaml
spring:
  # ... 기존 설정 ...
  flyway:
    enabled: false   # local은 JPA ddl-auto=create-drop이 우선
  jpa:
    hibernate:
      ddl-auto: create-drop   # 유지
```

**근거**: Local에서는 매 부팅 시 스키마를 재생성하므로 Flyway와 충돌. Flyway를 쓰려면 `create-drop`을 `validate`로 바꿔야 하는데 개발 편의성 저하.

- [ ] **Step 5: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 커밋**

```bash
git add \
  chat-service/build.gradle \
  chat-service/src/main/resources/db/migration/V1__baseline.sql \
  chat-service/src/main/resources/application-prod.yml \
  chat-service/src/main/resources/application-local.yml
git commit -m "$(cat <<'EOF'
feat: chat-service Flyway 도입 — V1 baseline 스키마 캡처

기존 운영 DB와 호환을 위해 baseline-on-migrate=true 설정.
V1__baseline.sql은 CREATE TABLE IF NOT EXISTS로 안전하게 구성.
이후 스키마 변경은 V2__, V3__으로 순차 추가.
prod: ddl-auto=validate 유지, Hibernate는 검증만.
local: flyway.enabled=false (create-drop 유지).

Confidence: medium
Scope-risk: moderate
Directive: V2 이후 마이그레이션 파일 추가 시 Gradle 빌드 후 검증 필수

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Kafka 파티션 증설 (1 → 3)

**Files:**
- Modify: `k8s/infra/k3s-infra.yaml:172`
- Create: `chat-service/src/main/java/com/chatflow/chat/config/KafkaTopicConfig.java`

**문제**: 모든 Kafka 토픽이 파티션 1개. 단일 컨슈머만 가능. 추후 chat-service, ai-summary-service 스케일 아웃 시 병렬 처리 불가.

**해결**: 
- Broker 기본값을 3파티션으로 변경
- 명시적 `NewTopic` Bean으로 핵심 토픽(chat-messages, ai-summary-requests, ai-summaries)의 파티션을 3으로 선언. Spring Kafka가 기동 시 자동 증가 (감소는 불가).

- [ ] **Step 1: k3s-infra.yaml 파티션 수 변경**

파일 `k8s/infra/k3s-infra.yaml` 172라인:

현재:
```yaml
        - name: KAFKA_NUM_PARTITIONS
          value: "1"
```

변경 후:
```yaml
        - name: KAFKA_NUM_PARTITIONS
          value: "3"
```

**주의**: 이 값은 **신규 토픽**의 기본 파티션 수. 기존 토픽은 변경되지 않음 → Step 2로 명시적 선언 필요.

- [ ] **Step 2: chat-service KafkaTopicConfig 생성**

파일 생성: `chat-service/src/main/java/com/chatflow/chat/config/KafkaTopicConfig.java`

```java
package com.chatflow.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 핵심 Kafka 토픽의 파티션 수를 명시적으로 선언.
 * Spring Kafka KafkaAdmin이 기동 시 자동으로 토픽을 생성/증설한다.
 * 파티션은 증가만 가능하므로 감소는 수동 마이그레이션 필요.
 */
@Configuration
public class KafkaTopicConfig {

    private static final int PARTITIONS = 3;
    private static final short REPLICATION = 1;

    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name("chat-messages")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }

    @Bean
    public NewTopic aiSummaryRequestsTopic() {
        return TopicBuilder.name("ai-summary-requests")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }

    @Bean
    public NewTopic aiSummariesTopic() {
        return TopicBuilder.name("ai-summaries")
                .partitions(PARTITIONS)
                .replicas(REPLICATION)
                .build();
    }
}
```

**순서 보장**: chat-messages는 `chatRoomId`가 파티션 키로 사용되므로, 동일 roomId의 메시지는 항상 같은 파티션에 들어간다. 파티션을 3으로 늘려도 룸 단위 순서는 보장됨.

- [ ] **Step 3: 빌드 확인**

```bash
./gradlew :chat-service:compileJava --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add \
  k8s/infra/k3s-infra.yaml \
  chat-service/src/main/java/com/chatflow/chat/config/KafkaTopicConfig.java
git commit -m "$(cat <<'EOF'
feat: Kafka 기본 파티션 1→3, 핵심 토픽 NewTopic 선언

KAFKA_NUM_PARTITIONS를 3으로 상향(신규 토픽 기본값).
KafkaTopicConfig로 chat-messages/ai-summary-requests/ai-summaries를
3파티션으로 명시 선언. 파티션 증설은 Spring Kafka가 기동 시 자동 처리.

Constraint: 단일 Kafka 브로커 환경 — replication=1 유지
Constraint: chatRoomId 파티션 키로 룸 단위 순서 보장됨 (파티션 증가 무관)
Confidence: medium
Scope-risk: moderate

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## 배포 순서

### 순서 A: 인프라 (Kafka 파티션) → 앱 (chat-service, ai-summary-service)

1. `k3s-infra.yaml` 적용 → Kafka 재시작 (신규 토픽만 영향, 기존은 그대로)
2. chat-service 배포 → KafkaTopicConfig가 기존 토픽의 파티션을 3으로 증설
3. Flyway baseline이 기존 DB에 등록되고 검증
4. ai-summary-service 배포 → geminiExecutor 활성화

### 배포 스크립트

```bash
cd /Users/seungwon-kwon/IdeaProjects/chat_flow

# 1. 인프라 적용 (Kafka 재시작)
kubectl apply -f k8s/infra/k3s-infra.yaml --kubeconfig ~/.kube/k3s-config
# 또는 SSH로:
scp k8s/infra/k3s-infra.yaml ksw@node.chatflow.ai.kr:~/
ssh ksw@node.chatflow.ai.kr "sudo kubectl apply -f ~/k3s-infra.yaml --kubeconfig /etc/rancher/k3s/k3s.yaml"

# 2. chat-service + ai-summary-service 빌드·배포
BUILD_TAG="$(date +%Y%m%d-%H%M%S)"
./gradlew :chat-service:bootJar :ai-summary-service:bootJar --no-daemon

docker buildx build --platform linux/amd64 --build-arg SERVICE_NAME=chat-service \
  -t docker.io/chatflow/chat-service:latest --load .
docker buildx build --platform linux/amd64 --build-arg SERVICE_NAME=ai-summary-service \
  -t docker.io/chatflow/ai-summary-service:latest --load .

export COPYFILE_DISABLE=1
docker save \
  docker.io/chatflow/chat-service:latest \
  docker.io/chatflow/ai-summary-service:latest \
  | gzip > /tmp/chatflow-backend.tar.gz

scp /tmp/chatflow-backend.tar.gz ksw@node.chatflow.ai.kr:~/
ssh ksw@node.chatflow.ai.kr "sudo k3s ctr images import ~/chatflow-backend.tar.gz"
ssh ksw@node.chatflow.ai.kr "sudo kubectl rollout restart deployment/chatflow-chat-service deployment/chatflow-ai-summary-service -n chatflow --kubeconfig /etc/rancher/k3s/k3s.yaml"
ssh ksw@node.chatflow.ai.kr "sudo kubectl rollout status deployment/chatflow-chat-service -n chatflow --timeout=3m --kubeconfig /etc/rancher/k3s/k3s.yaml"
```

### 롤백 시나리오
- chat-service 기동 실패(Flyway 검증 실패) → V1__baseline.sql과 실제 스키마 diff 확인. `baseline-version`을 올리거나 SQL 수정.
- Kafka 파티션 불일치 → Spring Kafka가 자동 조정하지만 Consumer 오프셋 재조정 필요할 수 있음. 로그 확인.

---

## Self-Review

### Spec 커버리지
| 이슈 | Task |
|------|------|
| AI Summary 동기 Gemini 호출 | Task 1 ✓ |
| Flyway 마이그레이션 인프라 부재 | Task 2 ✓ |
| Kafka 파티션 1개 | Task 3 ✓ |

### Placeholder 검사
- Task 1 Step 2의 `@Qualifier("geminiExecutor")` 주입 방식 2가지 제시 ✓
- Task 2 Step 2 V1 SQL은 템플릿 + 엔티티 읽기 지시 명확 ✓
- Task 3 Step 1 정확한 라인 명시 ✓

### 실행 순서
- Task 1, 2, 3 독립 실행 가능하지만 배포는 "인프라 → chat-service → ai-summary-service" 순서 권장.
