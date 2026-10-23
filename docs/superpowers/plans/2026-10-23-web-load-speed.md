# 웹 첫 로딩 속도 개선 Implementation Plan

**Goal:** 메신저 첫 화면이 늦게 뜨는 문제를 고친다(사용자 요청 2026-10-23 "메신저가 너무 느려").

## 측정 (원인)

- 10Mbps·40ms로 처음 접속하면 UI가 9.7초에 뜬다. `main.dart.js`(gzip 2.06MB)는 3.4초에 끝나고 Noto Sans KR 400·600(각 6.2MB 원본, 3.0MB 전송)은 9.3초에 끝난다. `main.dart`가 `GoogleFonts.pendingFonts`로 이 두 파일을 받을 때까지 `runApp`을 미룬다. 700도 화면에 굵은 글씨가 나오면 3MB를 더 받는다.
- `main.dart.js`·`*.js`가 `no-store`라 다시 접속할 때마다 2MB를 새로 받는다.
- API는 0.15~0.5초, STOMP 메시지 왕복은 0.1~0.13초로 정상. 클라이언트 엣지는 HKG, 터널은 ICN이라 요청마다 약 0.16초가 깔린다(Cloudflare 요금제 경로, 코드로는 못 바꾼다).

## 변경

- [ ] Noto Sans KR 400·600·700을 서브셋(라틴·문장부호·자모·완성형 11,172자, 힌팅 제거)해 `frontend/assets/google_fonts/NotoSansKR-{Regular,SemiBold,Bold}.ttf`로 넣고 pubspec assets에 등록한다. google_fonts는 이 이름의 에셋을 먼저 쓰므로 Dart 코드는 바꾸지 않는다. 서브셋 밖 글자는 CanvasKit 폴백이 그린다. OFL 라이선스 `OFL.txt`를 같은 폴더에 둔다.
- [ ] nginx(helm configmap과 `frontend/nginx.conf`): `main.dart.js`·`flutter_bootstrap.js`·`flutter_service_worker.js`·`*.js`를 `no-cache`(ETag 재검증)로. `index.html`·`version.json`은 그대로 `no-store`. `gzip_proxied any`(Cloudflare가 붙이는 `Via` 때문에 지금은 원본 압축이 꺼진다), `gzip_types`에 `font/ttf`.
- [ ] `flutter analyze`·`flutter test`·`flutter build web --release`, `helm template`.
- [ ] APK 3.0.7(안드로이드도 첫 실행에 18MB를 안 받게).

## 확인

배포 후 10Mbps 조건 첫 접속 UI 시간, gstatic 글꼴 요청 0건, 재접속 시 `main.dart.js` 304, 한글 입력·굵은 글씨 정상.
