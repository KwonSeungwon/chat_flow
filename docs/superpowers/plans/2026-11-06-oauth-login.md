# 네이버·구글 소셜 로그인 Implementation Plan

**Goal:** 아이디·비밀번호 가입에 더해 네이버·구글 계정으로 로그인한다(사용자 요청 2026-11-06).

## 결정

- 소셜로 처음 들어오면 새 계정을 만든다. 기존 계정과의 연결은 로그인한 상태에서 사용자가 직접 할 때만 한다. 이메일이 같다고 자동으로 합치지 않는다(이메일은 받지 않는다).
- 제공자에게서 받는 값은 고유 ID와 표시 이름뿐이다. 고유 ID는 `user_social_accounts`에, 표시 이름은 username 후보로만 쓴다.
- 한 사용자는 제공자마다 계정 하나만 연결한다. 같은 제공자 계정은 한 사용자에게만 연결된다.
- 테이블은 gateway `schema.sql`(멱등 DDL)에 둔다. users를 가진 쪽이 gateway라서다.

## 1단계: 세팅·테이블 (이 브랜치)

- [x] `user_social_accounts` 테이블: `provider`(GOOGLE/NAVER), `provider_user_id`, `user_id`(users FK, 사용자 삭제 시 함께 삭제), `created_at`, `last_login_at`. UNIQUE(provider, provider_user_id), UNIQUE(user_id, provider).
- [x] `SocialProvider` enum, `SocialAccountEntity`, `SocialAccountRepository`(조회 2개, 마지막 로그인 갱신).
- [x] Testcontainers PostgreSQL로 제약과 쿼리를 검증한다.
- [x] `spring-boot-starter-oauth2-client` 추가. 네이버 provider 엔드포인트만 설정하고 registration(client id/secret)은 2단계에서 넣는다. registration이 없으면 OAuth2 클라이언트 자동 설정이 꺼져 지금 동작은 그대로다.

## 2단계: 로그인 흐름 (다음)

- [ ] `users.encoded_password` NULL 허용. **주의:** `DelegatingPasswordEncoder.matches(null, null)`은 true를 돌려준다. 로그인·비밀번호 변경에서 저장된 비밀번호가 없으면 먼저 거절해야 한다. 이 가드와 테스트 없이 NOT NULL을 풀지 않는다.
- [ ] registration 설정(env `OAUTH_GOOGLE_CLIENT_ID` 등, K8s Secret), Cloudflare 뒤 forwarded 헤더.
- [ ] 콜백 → 계정 찾기/만들기 → 우리 JWT 발급 → 일회용 코드로 프론트에 전달.
- [ ] 네이버는 `user-name-attribute: response`가 중첩 맵이라 기본 user service로는 principal name이 맵 문자열이 된다. 커스텀 user service로 `response.id`를 꺼내 `provider_user_id`로 쓴다.
- [ ] username 생성: `users.username`은 UNIQUE, VARCHAR(100)이다. 표시 이름을 가입 규칙에 맞게 다듬고 충돌하면 접미사를 붙인다.
- [ ] 비밀번호 없는 소셜 전용 계정은 마지막 연결을 해제하지 못하게 막는다(로그인 수단이 사라진다).
- [ ] 네이버 `id`는 애플리케이션(client)마다 다르게 발급된다. client를 바꾸면 기존 연결이 끊기므로 client를 새로 만들지 않는다.
- [ ] Flutter: 로그인 버튼, 웹 콜백 라우트, Android는 Custom Tabs + 딥링크.

## 3단계: 공개

- [ ] 개인정보처리방침 페이지, 네이버 검수, 구글 프로덕션 게시.

## 진행 상태

1단계 구현 완료(2026-11-06, `feature/oauth-setup`). gateway 테스트 120개 통과, 로컬 기동에서 테이블 생성과 가입·로그인 정상 확인. 다음은 2단계로, 네이버·구글 콘솔에서 Client ID를 받아야 시작할 수 있다.

## 확인

1단계: gateway 테스트 전체 통과(Testcontainers 포함), 앱 컨텍스트 기동 테스트 통과, prod 동작 변화 없음.
