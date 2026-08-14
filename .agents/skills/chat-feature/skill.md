---
name: chat-feature
description: "ChatFlow에 새로운 채팅 기능을 추가하거나 기존 기능을 개선할 때 사용하는 메인 오케스트레이터. '새 채팅 기능 추가', '채팅방 기능 개선', '메시지 기능 구현', '채팅 피처 개발'과 같은 요청 시 반드시 이 스킬을 사용한다. 아키텍처 설계부터 백엔드/프론트엔드 병렬 구현, QA 검증, 배포까지 전체 파이프라인을 조율한다."
---

# Chat Feature 오케스트레이터

**실행 모드**: 파이프라인 + 팬아웃/팬인 (서브에이전트)

## 아키텍처

```
Phase 1: [chat-architect] — 설계 (서브에이전트, 순차)
                ↓ (팬아웃)
Phase 2: [backend-engineer] ∥ [frontend-engineer] — 병렬 구현
                ↓ (팬인)
Phase 3: [integration-qa] — 양쪽 완료 후 경계면 검증 루프
                ↓
Phase 4: [infra-engineer] — 배포 (사용자 확인 후, 선택적)
```

`chat-architect`, `backend-engineer`, `frontend-engineer`, `integration-qa`, `infra-engineer`는
`.claude/agents/`에 **등록된 에이전트 타입**이다 — Agent 툴의 `subagent_type`으로 직접 지정한다.
"에이전트 정의 .md 파일을 읽어라"라는 프롬프트 우회는 쓰지 않는다.

## 전제 조건

```bash
mkdir -p _workspace   # gitignored
```

파일 네이밍 컨벤션 (에이전트 간 인수인계는 전부 파일 기반):
- `_workspace/01_architect_*.md` — 설계 산출물 (flow, contracts, api_spec)
- `_workspace/02_backend_impl_summary.md` — 백엔드 구현 요약
- `_workspace/03_frontend_impl_summary.md` — 프론트 구현 요약
- `_workspace/04_qa_report.md` — QA 검증 결과
- `_workspace/05_deploy_log.md` — 배포 이력

## Phase 1: 아키텍처 설계

설계가 완료되어야 Phase 2를 시작할 수 있다. 동기(run_in_background: false)로 실행한다.

```
Agent(
  subagent_type: "chat-architect",
  run_in_background: false,
  prompt: "다음 피처를 설계하라: {사용자 요구사항}
  설계 산출물 3개 파일(01_architect_flow.md, 01_architect_contracts.md,
  01_architect_api_spec.md)을 _workspace/에 저장하라."
)
```

Phase 1 완료 조건: `_workspace/01_architect_*.md` 3개 파일 생성 확인.

## Phase 2: 병렬 구현

Phase 1 완료 후 **하나의 메시지에서 두 Agent 호출을 동시에** 보낸다 (병렬 실행).
두 에이전트는 서로 다른 영역(backend vs frontend/)을 수정하므로 충돌하지 않는다.

```
Agent(
  subagent_type: "backend-engineer",
  name: "be-impl",
  prompt: "_workspace/01_architect_*.md 설계 기반으로 백엔드를 구현하라.
  완료 시 _workspace/02_backend_impl_summary.md를 작성하라."
)
Agent(
  subagent_type: "frontend-engineer",
  name: "fe-impl",
  prompt: "_workspace/01_architect_*.md 설계 기반으로 Flutter 프론트를 구현하라.
  API 응답 형식은 설계 contracts를 기준으로 하고, 완료 시
  _workspace/03_frontend_impl_summary.md를 작성하라."
)
```

- 진행 중 질문/조정이 필요하면 `SendMessage({to: "be-impl" | "fe-impl"})`로 컨텍스트를 유지한 채 대화한다.
- 두 완료 알림(task-notification)을 모두 받은 뒤에만 Phase 3으로 넘어간다.

## Phase 3: QA 검증 루프

```
Agent(
  subagent_type: "integration-qa",
  run_in_background: false,
  prompt: "_workspace/01_architect_contracts.md를 계약 기준으로,
  02/03 구현 요약과 실제 코드를 교차 검증하고 _workspace/04_qa_report.md를 작성하라."
)
```

`_workspace/04_qa_report.md`를 읽는다.

**PASS**: 사용자에게 구현 완료 보고 + Phase 4 배포 여부 확인

**FAIL**:
1. 실패 항목의 담당 에이전트에게 SendMessage로 수정 요청 (파일명 + 필드명 포함)
2. 수정 완료 후 integration-qa 재검증 (최대 2회)
3. 2회 후에도 FAIL: 사용자에게 설계 재검토 필요 알림 → Phase 1로 복귀

## Phase 4: 배포 (선택적)

**사용자 확인 후에만** 실행한다. 프로덕션은 K3s(자세한 절차는 `chatflow-deploy` 스킬 / infra-engineer 에이전트).

```
Agent(
  subagent_type: "infra-engineer",
  prompt: "QA PASS 확인됨. 다음 서비스를 K3s에 배포하라: {서비스 목록}.
  권장 경로는 GH Actions(develop-build.yml → Deploy to K3s). 프론트엔드가
  포함되면 배포 후 Cloudflare 캐시 퍼지까지 수행하고 _workspace/05_deploy_log.md에 기록하라."
)
```

## 에러 핸들링

| 상황 | 대응 |
|------|------|
| Phase 1 설계 파일 미생성 | 재시도 1회, 실패 시 사용자 요구사항 명확화 요청 |
| Phase 2 에이전트 1개 실패 | 실패 에이전트만 재시도, 성공한 결과 보존 |
| Phase 3 QA 2회 FAIL | Phase 1 재설계 |
| Phase 4 배포 실패 | infra-engineer에게 롤백 절차 위임 (kubectl rollout undo 또는 이전 sha 태그 재배포) |

## 테스트 시나리오

### 정상 흐름
"채팅방 멤버 초대 기능 추가" → Phase 1 설계 3파일 생성 → Phase 2 백엔드/프론트 병렬 구현 → Phase 3 QA PASS → Phase 4 배포 확인

### 에러 흐름
Phase 3 QA에서 Flutter DTO `createdAt` 필드 타입 불일치 발견 → fe-impl에 SendMessage로 수정 요청 → Phase 3 재검증 → PASS
