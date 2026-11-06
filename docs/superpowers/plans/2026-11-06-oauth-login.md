# 네이버·구글·카카오 소셜 로그인 Implementation Plan

**Goal:** 아이디·비밀번호 가입에 더해 네이버·구글·카카오 계정으로 로그인한다(사용자 요청 2026-11-06, 카카오는 같은 날 추가).

## 결정

- 소셜로 처음 들어오면 새 계정을 만든다. 기존 계정과의 연결은 로그인한 상태에서 사용자가 직접 할 때만 한다. 이메일이 같다고 자동으로 합치지 않는다(이메일은 받지 않는다).
- 제공자에게서 받는 값은 고유 ID와 표시 이름뿐이다. 고유 ID는 `user_social_accounts`에, 표시 이름은 username 후보로만 쓴다.
- 한 사용자는 제공자마다 계정 하나만 연결한다. 같은 제공자 계정은 한 사용자에게만 연결된다.
- 테이블은 gateway `schema.sql`(멱등 DDL)에 둔다. users를 가진 쪽이 gateway라서다.

## 1단계: 세팅·테이블 (이 브랜치)

- [x] `user_social_accounts` 테이블: `provider`(GOOGLE/NAVER/KAKAO), `provider_user_id`, `user_id`(users FK, 사용자 삭제 시 함께 삭제), `created_at`, `last_login_at`. UNIQUE(provider, provider_user_id), UNIQUE(user_id, provider).
- [x] `SocialProvider` enum, `SocialAccountEntity`, `SocialAccountRepository`(조회 2개, 마지막 로그인 갱신).
- [x] Testcontainers PostgreSQL로 제약과 쿼리를 검증한다.
- [x] 카카오 준비: `SocialProvider.KAKAO`, provider 엔드포인트(문서 값을 `OAuth2ProviderConfigTest`로 고정).
- [x] `spring-boot-starter-oauth2-client` 추가. 네이버 provider 엔드포인트만 설정하고 registration(client id/secret)은 2단계에서 넣는다. registration이 없으면 OAuth2 클라이언트 자동 설정이 꺼져 지금 동작은 그대로다.

## 2단계: 로그인 흐름 (다음)

- [ ] `users.encoded_password` NULL 허용. **주의:** `DelegatingPasswordEncoder.matches(null, null)`은 true를 돌려준다. 로그인·비밀번호 변경에서 저장된 비밀번호가 없으면 먼저 거절해야 한다. 이 가드와 테스트 없이 NOT NULL을 풀지 않는다.
- [ ] registration 설정(env `OAUTH_GOOGLE_CLIENT_ID` 등, K8s Secret). gateway에 `server.forward-headers-strategy`가 없어 `{baseUrl}`이 Cloudflare·ingress 뒤에서 http·내부 호스트로 만들어진다(카카오 KOE006, 구글·네이버 redirect 불일치). forward-headers를 켜거나 운영 redirect-uri를 절대 URL로 고정한다.
- [ ] `OAuth2ProviderConfigTest`를 `OAuth2ClientPropertiesMapper` 기반으로 바꿔 실제 `ClientRegistration`의 userNameAttributeName·clientAuthenticationMethod를 검증한다. `noClientRegistrationYet_…` 테스트는 이때 지운다.
- [ ] 콜백 → 계정 찾기/만들기 → 우리 JWT 발급 → 일회용 코드로 프론트에 전달.
- [ ] 네이버는 `user-name-attribute: response`가 중첩 맵이라 기본 user service로는 principal name이 맵 문자열이 된다. 커스텀 user service로 `response.id`를 꺼내 `provider_user_id`로 쓴다.
- [ ] username 생성: `users.username`은 UNIQUE, VARCHAR(100)이다. 표시 이름을 가입 규칙에 맞게 다듬고 충돌하면 접미사를 붙인다.
- [ ] 비밀번호 없는 소셜 전용 계정은 마지막 연결을 해제하지 못하게 막는다(로그인 수단이 사라진다).
- [ ] 네이버 `id`는 애플리케이션(client)마다 다르게 발급된다. client를 바꾸면 기존 연결이 끊기므로 client를 새로 만들지 않는다.
- [ ] Flutter: 로그인 버튼, 웹 콜백 라우트, Android는 Custom Tabs + 딥링크.

### 카카오 (developers.kakao.com/docs/ko/kakaologin, 2026-11-06 확인)

| 항목 | 문서 내용 | 2단계 설정 |
| --- | --- | --- |
| 엔드포인트 | 인가 `GET kauth.kakao.com/oauth/authorize`, 토큰 `POST kauth.kakao.com/oauth/token`(form), 사용자 정보 `kapi.kakao.com/v2/user/me`(Bearer) | provider로 등록 완료 |
| client_id | REST API 키(JavaScript·네이티브 앱 키 아님) | `OAUTH_KAKAO_CLIENT_ID` |
| client_secret | REST API 키에 기본 ON. ON이면 토큰 요청에 필수. 예제는 form 본문으로 보낸다(Basic 없음) | `client-authentication-method: client_secret_post`, `OAUTH_KAKAO_CLIENT_SECRET` |
| 식별자 | 최상위 `id`(Long, 19자리 예시). 앱별 고유라 앱을 새로 만들면 전원 재가입 | `provider_user_id`에 문자열로 저장. 카카오 앱을 새로 만들지 않는다 |
| 받는 정보 | 닉네임(`profile_nickname`)·프로필 사진(`profile_image`)만 비즈 앱 없이 설정 가능. 이메일은 비즈 앱 필요 | scope `profile_nickname` 하나. 닉네임은 `kakao_account.profile.nickname`(동의 안 하면 없음) |
| Redirect URI | REST API 키의 [리다이렉트 URI]에 등록, 완전히 일치, 최대 10개, http/https 따로 | `https://app.gossiplog.com/login/oauth2/code/kakao`, `http://localhost:8000/login/oauth2/code/kakao` |
| 사용 설정 | [카카오 로그인] > [사용 설정] ON. OFF면 `KOE004` | 콘솔에서 ON |
| 탈퇴 | 탈퇴 과정에 연결 해제(`POST kapi.kakao.com/v1/user/unlink`) 필수, 회원번호 포함 개인정보 파기 | 회원 탈퇴를 만들 때 어드민 키(`KakaoAK`) 방식으로 unlink 호출. 외부에서 끊는 경우를 위해 연결 해제 웹훅 검토 |
| OIDC | [OpenID Connect] ON이면 `id_token`(iss `https://kauth.kakao.com`, sub=회원번호) | 쓰지 않는다. OAuth2 user-info로 충분하고 설정이 줄어든다 |

- [ ] registration `kakao`: `authorization-grant-type: authorization_code`, `client-authentication-method: client_secret_post`, `scope: profile_nickname`, `redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"`.
- [ ] 닉네임은 중첩 경로라 username 후보는 커스텀 user service에서 꺼낸다(네이버와 같은 처리).

## 3단계: 공개

- [ ] 개인정보처리방침 페이지, 네이버 검수, 구글 프로덕션 게시. 카카오는 닉네임만 받으면 비즈 앱 전환·동의항목 심사가 필요 없다.

## 진행 상태

- 1단계 완료(2026-11-06): `feature/oauth-setup`(테이블·의존성·네이버 provider), `feature/oauth-kakao-prep`(KAKAO enum·카카오 provider). gateway 테스트 124개 통과. prod gateway 배포(helm rev 9).
- 콘솔 등록 완료(2026-11-06). 시크릿 원본은 `~/.config/gossiplog-oauth/*.env`(리포 밖, 600), prod는 K8s Secret `gossiplog-oauth`(키 `OAUTH_{GOOGLE,NAVER,KAKAO}_CLIENT_{ID,SECRET}`, 아직 어떤 Deployment도 참조하지 않음).

| 제공자 | 앱 | Redirect URI | 받는 정보 | 공개 전 상태 |
| --- | --- | --- | --- | --- |
| 구글 | GCP 프로젝트 `gossiplog`, 웹 클라이언트 `gossiplog-web` | `https://app.gossiplog.com/login/oauth2/code/google`, `http://localhost:8000/login/oauth2/code/google` | openid profile(요청 scope로 제한) | 테스트 중, 테스트 사용자 1명 |
| 네이버 | 애플리케이션 `Gossiplog`(Client ID `KYL2rY5V_WmnvgQuPWlO`), PC 웹 | `https://app.gossiplog.com/login/oauth2/code/naver` | 별명(필수) | 개발 중, 검수 전에는 멤버만 |
| 카카오 | 앱 `Gossiplog`(ID 1640047), 카카오 로그인 ON, OIDC OFF | `https://app.gossiplog.com/login/oauth2/code/kakao` | 닉네임(필수 동의) | 비즈 앱 아님. 연결 해제 웹훅 미설정 |

- 다음: 2단계. 로컬 개발용 네이버·카카오 redirect URI는 필요해지면 추가한다.

## 확인

1단계: gateway 테스트 전체 통과(Testcontainers 포함), 앱 컨텍스트 기동 테스트 통과, prod 동작 변화 없음.
