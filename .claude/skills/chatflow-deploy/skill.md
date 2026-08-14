---
name: chatflow-deploy
description: "ChatFlow K3s 프로덕션 배포 스킬. 새 피처를 K3s에 배포하거나, GHCR 이미지 태그를 롤아웃하거나, Helm 차트를 업데이트하거나, 배포 후 Cloudflare 캐시를 퍼지할 때 사용한다. QA PASS 확인 후 배포 절차를 안내한다."
---

# ChatFlow 배포 스킬 (K3s)

프로덕션: 자체 K3s 인스턴스 (호스트는 `.claude/CLAUDE.md` 참고) / 네임스페이스 `chatflow` / 도메인 https://app.chatflow.ai.kr
SSH 접속 정보·Cloudflare 토큰 등 민감 정보는 `.claude/CLAUDE.md`(로컬 전용) 참고 — **소스/env 파일에 절대 직접 쓰지 말 것.**

## 배포 전 필수 확인

- [ ] `_workspace/04_qa_report.md` PASS 상태 확인 (없으면 chatflow-qa 스킬로 검증 먼저)
- [ ] develop 브랜치에 머지되어 있고 CI(ci.yml) 통과 상태인지 확인
- [ ] 현재 실행 중인 이미지 태그 기록 → `_workspace/05_deploy_log.md` (롤백용)
- [ ] **사용자 최종 확인** (프로덕션 배포는 반드시 확인 후 진행)

## 권장 경로 — GH Actions (로컬 Docker daemon 불필요)

```
1. develop 머지 → develop-build.yml이 자동으로 모든 서비스(amd64)를 GHCR로 push
   - 이미지: ghcr.io/<owner>/chatflow/<service>
   - 태그: sha-<full> (immutable) + develop-latest (mutable)

2. GitHub Actions → "Deploy to K3s (manual)" (deploy-k3s.yml) 실행
   - service + tag 선택 → SSH로 K3s node에서 GHCR pull + kubectl set image + rollout 검증

3. 프론트엔드를 배포했다면 → Cloudflare 캐시 퍼지 (아래, 필수)
```

필요 GitHub Secrets: `K3S_HOST`, `K3S_USER`, `K3S_SSH_KEY`, `K3S_GHCR_USERNAME`, `K3S_GHCR_TOKEN`

```bash
# gh CLI로도 트리거 가능
gh workflow run deploy-k3s.yml -f service=<service> -f tag=sha-<full-sha>
gh run watch
```

## 레거시 경로 — 로컬 cross-build (Docker daemon 필요할 때만)

```bash
./scripts/deploy-k3s.sh all      # infra + secrets + images + helm
./scripts/deploy-k3s.sh images   # 이미지만 빌드·전송·import (amd64 크로스 빌드 → scp → k3s ctr import)
./scripts/deploy-k3s.sh helm     # Helm 배포만 (helm/chatflow, values-k3s.yaml)
```

로컬 Docker Desktop daemon이 hang이면 이 경로 전체가 막힌다 — GH Actions 경로를 우선하라.

## 프론트엔드 배포 후 Cloudflare 캐시 퍼지 (필수)

토큰/Zone ID와 실행 명령은 `.claude/CLAUDE.md` 참고.
존을 `timecapsule.chatflow.ai.kr`과 공유하므로 가능하면 URL 리스트 퍼지를 우선한다.

## 배포 후 검증

```bash
# 파드 상태 (SSH 상세는 .claude/CLAUDE.md)
ssh <k3s-node> "sudo kubectl get pods -n chatflow --kubeconfig /etc/rancher/k3s/k3s.yaml"

# 롤아웃 완료 확인
ssh <k3s-node> "sudo kubectl rollout status deployment/<service> -n chatflow --kubeconfig /etc/rancher/k3s/k3s.yaml"

# 헬스체크
curl -s -o /dev/null -w "%{http_code}\n" https://app.chatflow.ai.kr/
curl -s https://app.chatflow.ai.kr/api/chat/rooms | head -c 200   # 401이면 인증 정상 동작 중

# 로그 확인 (오류 시)
ssh <k3s-node> "sudo kubectl logs -n chatflow -l app.kubernetes.io/name=<service> --kubeconfig /etc/rancher/k3s/k3s.yaml --tail=50"
```

## 롤백 절차

```bash
# 방법 1: 직전 리비전으로
ssh <k3s-node> "sudo kubectl rollout undo deployment/<service> -n chatflow --kubeconfig /etc/rancher/k3s/k3s.yaml"

# 방법 2: 특정 이전 sha 태그로 — deploy-k3s.yml 워크플로에 이전 태그 입력
# 이전 태그는 _workspace/05_deploy_log.md 또는 GHCR 패키지 페이지에서 확인
```

## 배포 이력 기록 형식

`_workspace/05_deploy_log.md`에 추가:

```markdown
## {날짜} {서비스명} 배포
- 이전 태그: sha-{old}
- 새 태그: sha-{new}
- 변경 내용: {피처 요약}
- 퍼지: 실행함 / 해당 없음(백엔드만)
- 결과: 성공 / 실패
```

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| K3s 노드 디스크 부족 | 이전 이미지 누적 | `sudo k3s ctr images prune --all` |
| 파드 CrashLoop | Secret/환경 변수 누락 | `kubectl logs` 확인, GEMINI_API_KEY 등 5종 Secret 존재 확인 |
| Gateway 502 | 백엔드 파드 미기동 | `kubectl get pods -n chatflow`, Kafka 기동 순서 확인 |
| 프론트 흰 화면/구버전 | Cloudflare 캐시 | 캐시 퍼지 재실행 |
| helm upgrade 후 이미지 회귀 | `kubectl set image` 드리프트 | `values-k3s.yaml` 태그를 실제 배포 태그로 갱신 후 upgrade |
| STOMP 404 | Gateway `/ws-native` 라우트 누락 | `application-prod.yml` 라우트 + SecurityConfig `permitAll()` 확인 |

## 레거시 참고 (사용 금지)

`docker-compose.prod.yml`, `scripts/deploy-ec2.sh`, `.env.prod.example`은 EC2 시절 유물이다.
2026-04 K3s 이관 이후 실제 배포에 쓰이지 않는다 — 절차를 이 파일들 기준으로 안내하지 말 것.
