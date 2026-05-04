# 방 내 검색 필터 — Design Spec

- **Date**: 2026-05-04
- **Status**: Approved
- **Phase**: Phase 3 (일부)
- **Owner**: seungwon
- **Goal**: 채팅방 내 메시지를 발신자·날짜·타입 기준으로 필터링하는 검색 기능을 완성 수준으로 구현. 백엔드 신규 엔드포인트 1개 + 프론트엔드 Provider/UI 개선.

---

## 1. Problem & Motivation

현재 방 내 검색(`InRoomSearchSheet`)은 프로토타입 수준이다.
- raw Dio 호출이 위젯 내부에 산재 (Provider 없음)
- 세 개의 기존 엔드포인트를 우선순위 로직으로 분기하는 취약한 구조
- 메시지 타입 필터 없음 (CHAT / FILE / AI_SUMMARY 구분 불가)
- 검색 결과 하이라이팅 없음
- 사이드바 메뉴에 "이 방에서 검색" 항목 없음

---

## 2. Scope

### In scope
- 백엔드: `GET /api/search/rooms/{roomId}/filter` 신규 엔드포인트
  - 선택 필터: `query`, `username`, `startDate`, `endDate`, `messageType`
- 프론트엔드 Provider: `InRoomSearchNotifier` + `inRoomSearchProvider`
- 프론트엔드 UI:
  - `InRoomSearchSheet` 전면 개선 (타입 칩 추가, 하이라이팅, Provider 연동)
  - 사이드바 컨텍스트 메뉴에 "이 방에서 검색" 항목 추가
  - 결과 탭 시 해당 메시지로 스크롤

### Out of scope
- 글로벌 검색에 필터 추가 (기존 `/api/search/korean` 변경 없음)
- 메시지 타입 PATIENT_CARD, JOIN, LEAVE, SYSTEM 필터 (검색 결과에서 이미 제외됨)
- 페이지네이션 (size=50으로 충분)
- i18n

---

## 3. Architecture

### 3.1 Backend — 신규 엔드포인트

**`SearchController`에 추가:**
```
GET /api/search/rooms/{roomId}/filter
  @PathVariable String roomId
  @RequestParam(required=false) String query
  @RequestParam(required=false) String username
  @RequestParam(required=false) @DateTimeFormat(ISO) LocalDateTime startDate
  @RequestParam(required=false) @DateTimeFormat(ISO) LocalDateTime endDate
  @RequestParam(required=false) String messageType  // "CHAT" | "FILE" | "AI_SUMMARY"
  @RequestParam(defaultValue="0") int page
  @RequestParam(defaultValue="50") int size
```

**`KoreanSearchService.searchWithFilters(...)` 신규 메서드:**
- 기본: `matchAll` (query 없을 때) 또는 multi-match (query 있을 때)
- `must`: query가 있으면 multi-match on `content^3, content.ngram^0.3, fileName^2`
- `filter`:
  - roomId → `term(chatRoomId)`
  - username → `term(username.keyword)` 또는 `match(username)`
  - startDate/endDate → `range(timestamp)`
  - messageType → `term(messageType)` (없으면 mustNot JOIN/LEAVE/SYSTEM 적용)
- 정렬: `timestamp DESC`

### 3.2 Frontend Provider

```dart
// in_room_search_state.dart
class InRoomSearchState {
  final List<ChatMessage> results;
  final int total;
  final bool isLoading;
  final bool hasSearched;
  final String? error;
  final String? messageTypeFilter;  // null | 'CHAT' | 'FILE' | 'AI_SUMMARY'
}

// in_room_search_provider.dart
class InRoomSearchNotifier extends StateNotifier<InRoomSearchState> {
  Future<void> search({
    required String roomId,
    String? query,
    String? username,
    DateTime? startDate,
    DateTime? endDate,
  });
  void setMessageTypeFilter(String? type);
}

final inRoomSearchProvider = StateNotifierProvider.family
    .autoDispose<InRoomSearchNotifier, InRoomSearchState, String>(
  (ref, roomId) => InRoomSearchNotifier(ref.watch(dioClientProvider), roomId),
);
```

단일 API 호출: `GET /api/search/rooms/{roomId}/filter`

### 3.3 UI

**사이드바 (`chat_room_sidebar.dart`):**
- `_showRoomContextMenu`의 PopupMenuItem 목록에 추가:
  ```dart
  PopupMenuItem(value: 'search', child: Row(
    children: [Icon(Icons.search, size: 18), SizedBox(width: 8), Text('이 방에서 검색')],
  ))
  ```
- `.then` 핸들러에 `'search'` 케이스 추가 → `showModalBottomSheet(InRoomSearchSheet)`

**`InRoomSearchSheet` 개선:**
- Provider 연동: `ref.watch(inRoomSearchProvider(roomId))`
- 검색어 + 발신자 + 날짜 — 기존 UI 구조 유지 (개선)
- 타입 필터: `Wrap` + `ChoiceChip` 3개 (일반/파일/AI 요약)
  - 선택 시 `notifier.setMessageTypeFilter(type)`
  - 재선택 시 해제 (toggle)
- 결과 아이템: 기존 ListTile → `_highlightedText` 패턴 적용 (글로벌 검색과 동일)
- `onResultTap(messageId)`: Navigator.pop 후 `widget.onResultTap` 콜백 호출

---

## 4. Data Model

새 모델 없음. 기존 `ChatMessage` 재사용. `messageType` 필드는 이미 존재.

MessageType 중 검색 가능한 타입:
| 표시명 | messageType 값 |
|--------|----------------|
| 일반   | CHAT           |
| 파일   | FILE           |
| AI 요약 | AI_SUMMARY    |

---

## 5. UI 흐름

1. 사이드바에서 방 길게 누르기 / 우클릭 → 컨텍스트 메뉴 → "이 방에서 검색"
2. `InRoomSearchSheet` 바텀시트 등장
3. 검색어 입력 (선택) + 발신자 입력 (선택) + 날짜 범위 선택 (선택) + 타입 칩 선택 (선택)
4. "검색" 버튼 → `/api/search/rooms/{roomId}/filter` 단일 호출
5. 결과 리스트: 하이라이팅된 내용 + 발신자 + 시각
6. 결과 탭 → 시트 닫힘 → 채팅방 해당 메시지로 스크롤

---

## 6. Tests

### 단위/위젯
- `in_room_search_provider_test.dart`:
  - query만 있을 때 API 파라미터 검증
  - username 필터 포함 시 파라미터 검증
  - 날짜 범위 포함 시 ISO 형식 변환 검증
  - messageType 필터 세트/해제 검증
- `in_room_search_sheet_test.dart`:
  - ChoiceChip 탭 → filter 상태 변경
  - 검색 버튼 탭 → search() 호출
  - 결과 아이템 탭 → onResultTap 콜백 호출

### 백엔드
- `SearchControllerTest` (기존) 에 `/filter` 엔드포인트 통합 테스트 추가

---

## 7. Risk

| 리스크 | 완화 |
|--------|------|
| `username`이 Text 분석기 적용 필드 — 정확 매칭 어려울 수 있음 | `username.keyword` 서브필드 사용 또는 `match`로 폴백 |
| query + username + date + type 조합 시 결과 0건 | UI에서 "필터를 줄여보세요" 안내 메시지 |
| 기존 `InRoomSearchSheet` 완전 교체 → 회귀 | 기존 파일 덮어쓰기, 동일 클래스명 유지, onResultTap 콜백 시그니처 유지 |

---

## 8. Success Criteria

- [ ] 사이드바 컨텍스트 메뉴에 "이 방에서 검색" 노출
- [ ] 발신자 필터로 특정 유저 메시지만 조회
- [ ] 날짜 범위 필터로 기간 한정 검색
- [ ] 타입 칩으로 파일/AI 요약만 필터링
- [ ] 결과 탭 → 해당 메시지로 스크롤
- [ ] `flutter analyze` 경고 0
- [ ] `flutter test` + 백엔드 테스트 green

---

## End of Spec
