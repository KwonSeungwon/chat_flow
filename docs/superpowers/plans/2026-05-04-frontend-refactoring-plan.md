# Frontend Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Flutter 프론트엔드의 ChatMessage.toJson() 누락 필드 보완, 공통 상수 파일 생성, chat_page.dart 다이얼로그 추출(TextEditingController dispose 버그 수정 포함)로 유지보수성을 높인다.

**Architecture:** 모델 레이어(ChatMessage) → 상수 레이어(core/constants) → UI 레이어(dialogs) 순서로 진행한다. 각 태스크는 독립적이며 개별 커밋 가능하다.

**Tech Stack:** Flutter 3.22, Dart 3.3, Riverpod 2.5, GoRouter 14

---

## File Structure

| 파일 | 역할 | 변경 |
|------|------|------|
| `frontend/lib/shared/models/chat_message.dart` | toJson() 누락 필드 추가 | **수정** |
| `frontend/lib/core/constants/ui_constants.dart` | 레이아웃·애니메이션 숫자 상수 | **신규** |
| `frontend/lib/core/constants/chat_strings.dart` | 반복 한국어 문자열 상수 | **신규** |
| `frontend/lib/features/chat/dialogs/change_password_dialog.dart` | 비밀번호 변경 다이얼로그 (독립 위젯) | **신규** |
| `frontend/lib/features/chat/chat_page.dart` | _showChangePasswordDialog 함수 제거, import 추가 | **수정** |
| `frontend/test/shared/models/chat_message_test.dart` | toJson 라운드트립 테스트 | **신규** |

---

## Task 1: ChatMessage.toJson() 누락 필드 보완

현재 `toJson()`에서 `id`, `messageId`, `deleted`, `edited`, `editedAt`, `pinned`, `reactions`, `localId` 필드가 직렬화되지 않는다. 북마크, 편집 이력, 핀 상태 등이 서버 전송 시 유실될 수 있다.

**Files:**
- Modify: `frontend/lib/shared/models/chat_message.dart` (줄 126-141)
- Create: `frontend/test/shared/models/chat_message_test.dart`

- [ ] **Step 1: 실패 테스트 작성**

```dart
// frontend/test/shared/models/chat_message_test.dart
import 'package:chatflow/shared/models/chat_message.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ChatMessage.toJson()', () {
    test('라운드트립 — fromJson(toJson()) == 원본', () {
      final original = ChatMessage(
        id: 'id-1',
        messageId: 'msg-1',
        chatRoomId: 'room-1',
        userId: 'user-1',
        username: 'tester',
        content: 'hello',
        timestamp: '2026-01-01T00:00:00.000Z',
        type: 'CHAT',
        deleted: true,
        edited: true,
        editedAt: '2026-01-01T01:00:00.000Z',
        pinned: true,
        reactions: {'👍': ['user-1', 'user-2']},
        localId: 'local-1',
      );

      final json = original.toJson();
      final restored = ChatMessage.fromJson(json);

      expect(restored.id, 'id-1');
      expect(restored.messageId, 'msg-1');
      expect(restored.deleted, true);
      expect(restored.edited, true);
      expect(restored.editedAt, '2026-01-01T01:00:00.000Z');
      expect(restored.pinned, true);
      expect(restored.reactions['👍'], ['user-1', 'user-2']);
      expect(restored.localId, 'local-1');
    });

    test('null 옵셔널 필드는 toJson에서 제외', () {
      final msg = ChatMessage(
        chatRoomId: 'room-1',
        userId: 'user-1',
        username: 'tester',
        content: 'hello',
        timestamp: '2026-01-01T00:00:00.000Z',
        type: 'CHAT',
      );
      final json = msg.toJson();
      expect(json.containsKey('id'), false);
      expect(json.containsKey('messageId'), false);
      expect(json.containsKey('editedAt'), false);
      expect(json.containsKey('localId'), false);
    });

    test('빈 reactions는 toJson에서 제외', () {
      final msg = ChatMessage(
        chatRoomId: 'r', userId: 'u', username: 'n',
        content: 'c', timestamp: 't', type: 'CHAT',
        reactions: const {},
      );
      final json = msg.toJson();
      expect(json.containsKey('reactions'), false);
    });
  });
}
```

- [ ] **Step 2: 테스트 실행 (RED 확인)**

```bash
cd frontend && flutter test test/shared/models/chat_message_test.dart 2>&1 | tail -10
```
Expected: FAIL — id/messageId/deleted 등이 toJson에 없어서 restored 값 불일치

- [ ] **Step 3: ChatMessage.toJson() 수정**

`frontend/lib/shared/models/chat_message.dart` 줄 126-141을 다음으로 교체:
```dart
Map<String, dynamic> toJson() => {
  if (id != null) 'id': id,
  if (messageId != null) 'messageId': messageId,
  'chatRoomId': chatRoomId,
  'userId': userId,
  'username': username,
  'content': content,
  'timestamp': timestamp,
  'type': type,
  'priority': priority,
  'isAiGenerated': isAiGenerated,
  'deleted': deleted,
  'edited': edited,
  if (editedAt != null) 'editedAt': editedAt,
  'pinned': pinned,
  if (reactions.isNotEmpty) 'reactions': reactions,
  if (fileUrl != null) 'fileUrl': fileUrl,
  if (fileName != null) 'fileName': fileName,
  if (fileContentType != null) 'fileContentType': fileContentType,
  if (parentMessageId != null) 'parentMessageId': parentMessageId,
  if (parentMessagePreview != null) 'parentMessagePreview': parentMessagePreview,
  if (forwardedFrom != null) 'forwardedFrom': forwardedFrom,
  if (localId != null) 'localId': localId,
};
```

주의: `reactions`를 서버에 보낼 때 Map 그대로 넣는다. `fromJson`의 `parseReactions()`가 Map과 String 둘 다 처리하므로 라운드트립에 문제 없다.

- [ ] **Step 4: 테스트 실행 (GREEN 확인)**

```bash
cd frontend && flutter test test/shared/models/chat_message_test.dart 2>&1 | tail -5
```
Expected: `All tests passed!`

- [ ] **Step 5: 전체 테스트 회귀 확인**

```bash
cd frontend && flutter test --reporter compact 2>&1 | tail -5
```
Expected: `All tests passed!`

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/shared/models/chat_message.dart \
  frontend/test/shared/models/chat_message_test.dart
git commit -m "fix(model): ChatMessage.toJson() 누락 필드 보완 — id/deleted/pinned/reactions/localId"
```

---

## Task 2: UIConstants + ChatStrings 상수 파일 생성

`chat_page.dart`, `chat_room_sidebar.dart`, `chat_input.dart` 등에서 동일 문자열("취소", "저장")과 숫자 값이 여러 곳에 하드코딩되어 있다. 공통 상수 파일을 만들어 한 곳에서 관리한다.

> **범위 제한:** 모든 사용처를 한 번에 교체하면 diff가 너무 커진다. 이 태스크는 파일 생성 + 대표적 사용처 1~2곳 교체만 한다. 나머지는 이후 점진적으로 교체한다.

**Files:**
- Create: `frontend/lib/core/constants/ui_constants.dart`
- Create: `frontend/lib/core/constants/chat_strings.dart`

- [ ] **Step 1: ui_constants.dart 생성**

```dart
// frontend/lib/core/constants/ui_constants.dart
class UIConstants {
  UIConstants._();

  // 레이아웃
  static const double sidebarWidth = 280.0;
  static const double dialogMaxWidth = 500.0;
  static const double dialogMaxHeight = 600.0;
  static const double borderRadius = 12.0;
  static const double smallBorderRadius = 8.0;

  // 참여자
  static const int maxParticipants = 10;
  static const int maxBookmarks = 50;
  static const int maxChatLength = 1000;
  static const int chatWarnThreshold = 800;

  // 스크롤 임계값
  static const double scrollThresholdBottom = 80.0;
  static const double scrollThresholdTop = 100.0;

  // 애니메이션
  static const Duration shortAnimation = Duration(milliseconds: 200);
  static const Duration mediumAnimation = Duration(milliseconds: 350);
}
```

- [ ] **Step 2: chat_strings.dart 생성**

```dart
// frontend/lib/core/constants/chat_strings.dart
class ChatStrings {
  ChatStrings._();

  // 공통 버튼
  static const String cancel = '취소';
  static const String save = '저장';
  static const String confirm = '확인';
  static const String close = '닫기';

  // 프로필
  static const String profileImageChanged = '프로필 이미지가 변경되었습니다.';
  static const String profileImageChangeFailed = '프로필 이미지 변경에 실패했습니다.';
  static const String passwordChanged = '비밀번호가 변경되었습니다.';
  static const String passwordChangeFailed = '비밀번호 변경에 실패했습니다.';
  static const String passwordMismatch = '새 비밀번호가 일치하지 않습니다.';

  // 초대
  static const String inviteLinkCopied = '초대 링크가 클립보드에 복사되었습니다 (24시간 유효)';

  // 방 설정
  static const String roomSettingsTitle = '채팅방 설정';
  static const String roomSettingsChanged = '채팅방 설정이 변경되었습니다.';

  // 동적 문자열
  static String roomFull(int max) => '이 채팅방은 만석입니다 (최대 $max명)';
  static String readByCount(int count) => '읽은 사람 ($count명)';
  static String participantCount(int count) => '$count명 참여 중';
}
```

- [ ] **Step 3: 빌드 확인**

```bash
cd frontend && flutter analyze lib/core/constants/ 2>&1 | tail -5
```
Expected: `No issues found!`

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/core/constants/ui_constants.dart \
  frontend/lib/core/constants/chat_strings.dart
git commit -m "feat: UIConstants + ChatStrings 상수 파일 생성"
```

---

## Task 3: ChangePasswordDialog 독립 위젯 추출 (dispose 버그 수정)

`chat_page.dart`의 `_showChangePasswordDialog()` 함수(줄 66-121)는 `TextEditingController` 3개를 생성하지만 `dispose()`를 호출하지 않는다. 다이얼로그가 닫힐 때 컨트롤러가 메모리에 남는다. `StatefulWidget`으로 분리해 `dispose()`를 보장한다.

**Files:**
- Create: `frontend/lib/features/chat/dialogs/change_password_dialog.dart`
- Modify: `frontend/lib/features/chat/chat_page.dart` (줄 66-121 제거, import + showDialog 교체)

- [ ] **Step 1: 테스트 작성**

```dart
// frontend/test/features/chat/dialogs/change_password_dialog_test.dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/dialogs/change_password_dialog.dart';

void main() {
  testWidgets('비밀번호 3개 입력 필드가 표시된다', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('current_pw')), findsOneWidget);
    expect(find.byKey(const Key('new_pw')), findsOneWidget);
    expect(find.byKey(const Key('confirm_pw')), findsOneWidget);
    expect(find.text('취소'), findsOneWidget);
    expect(find.text('변경'), findsOneWidget);
  });

  testWidgets('새 비밀번호 불일치 시 에러 메시지 표시', (tester) async {
    await tester.pumpWidget(ProviderScope(
      child: MaterialApp(
        home: Scaffold(
          body: Builder(builder: (ctx) => TextButton(
            onPressed: () => showDialog(
              context: ctx,
              builder: (_) => const ChangePasswordDialog(),
            ),
            child: const Text('open'),
          )),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('current_pw')), 'old123');
    await tester.enterText(find.byKey(const Key('new_pw')), 'new123');
    await tester.enterText(find.byKey(const Key('confirm_pw')), 'different');
    await tester.tap(find.text('변경'));
    await tester.pump();

    expect(find.text('새 비밀번호가 일치하지 않습니다.'), findsOneWidget);
  });
}
```

- [ ] **Step 2: 테스트 실행 (RED 확인)**

```bash
cd frontend && flutter test test/features/chat/dialogs/change_password_dialog_test.dart 2>&1 | tail -10
```
Expected: FAIL — 파일이 없으므로 컴파일 오류

- [ ] **Step 3: dialogs 디렉토리 생성 + 위젯 구현**

```dart
// frontend/lib/features/chat/dialogs/change_password_dialog.dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants/chat_strings.dart';
import '../../auth/auth_provider.dart';

class ChangePasswordDialog extends ConsumerStatefulWidget {
  const ChangePasswordDialog({super.key});

  @override
  ConsumerState<ChangePasswordDialog> createState() => _ChangePasswordDialogState();
}

class _ChangePasswordDialogState extends ConsumerState<ChangePasswordDialog> {
  final _currentCtrl = TextEditingController();
  final _newCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  String? _error;
  bool _loading = false;

  @override
  void dispose() {
    _currentCtrl.dispose();
    _newCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_newCtrl.text != _confirmCtrl.text) {
      setState(() => _error = ChatStrings.passwordMismatch);
      return;
    }
    setState(() { _loading = true; _error = null; });
    try {
      await ref.read(authProvider.notifier).changePassword(
        _currentCtrl.text,
        _newCtrl.text,
      );
      if (!mounted) return;
      Navigator.of(context).pop();
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text(ChatStrings.passwordChanged)),
      );
    } catch (e) {
      setState(() {
        _loading = false;
        _error = ChatStrings.passwordChangeFailed;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('비밀번호 변경'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            key: const Key('current_pw'),
            controller: _currentCtrl,
            obscureText: true,
            decoration: const InputDecoration(labelText: '현재 비밀번호'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: const Key('new_pw'),
            controller: _newCtrl,
            obscureText: true,
            decoration: const InputDecoration(labelText: '새 비밀번호'),
          ),
          const SizedBox(height: 8),
          TextField(
            key: const Key('confirm_pw'),
            controller: _confirmCtrl,
            obscureText: true,
            decoration: const InputDecoration(labelText: '새 비밀번호 확인'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: const TextStyle(color: Colors.red, fontSize: 12)),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: _loading ? null : () => Navigator.of(context).pop(),
          child: const Text(ChatStrings.cancel),
        ),
        FilledButton(
          onPressed: _loading ? null : _submit,
          child: _loading
              ? const SizedBox(
                  width: 16, height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
              : const Text('변경'),
        ),
      ],
    );
  }
}
```

주의: `authProvider.notifier.changePassword(current, newPw)` 메서드가 AuthNotifier에 있어야 한다. 없다면 해당 메서드를 먼저 추가해야 한다. AuthNotifier를 확인하고 없으면 아래 stub을 추가:
```dart
// auth_provider.dart의 AuthNotifier에 추가
Future<void> changePassword(String current, String newPassword) async {
  final dio = ref.read(dioClientProvider).dio;
  await dio.post('/api/auth/change-password', data: {
    'currentPassword': current,
    'newPassword': newPassword,
  });
}
```

- [ ] **Step 4: 테스트 실행 (GREEN 확인)**

```bash
cd frontend && flutter test test/features/chat/dialogs/change_password_dialog_test.dart 2>&1 | tail -5
```
Expected: `All tests passed!`

- [ ] **Step 5: chat_page.dart 교체**

`chat_page.dart` 줄 66-121의 `_showChangePasswordDialog` 함수 전체를 **삭제**하고, 해당 함수를 호출하는 곳을 다음으로 교체:
```dart
// 기존:
onPressed: () => _showChangePasswordDialog(context, ref),
// 변경:
onPressed: () => showDialog(
  context: context,
  builder: (_) => const ChangePasswordDialog(),
),
```

`chat_page.dart` 상단 import 추가:
```dart
import 'dialogs/change_password_dialog.dart';
```

- [ ] **Step 6: analyze + 전체 테스트**

```bash
cd frontend && flutter analyze lib/features/chat/chat_page.dart lib/features/chat/dialogs/ 2>&1 | tail -5
flutter test 2>&1 | tail -5
```
Expected: No issues, All tests passed!

- [ ] **Step 7: Commit**

```bash
git add frontend/lib/features/chat/dialogs/change_password_dialog.dart \
  frontend/lib/features/chat/chat_page.dart \
  frontend/test/features/chat/dialogs/change_password_dialog_test.dart
git commit -m "refactor: ChangePasswordDialog 추출 — TextEditingController dispose 버그 수정"
```

---

## 셀프 리뷰

**Spec coverage:**
- ✅ ChatMessage.toJson() 누락 필드 (T1)
- ✅ 공통 상수 파일 (T2)
- ✅ TextEditingController dispose 버그 수정 (T3)
- ✅ 다이얼로그 분리 패턴 수립 (T3 — 나머지 다이얼로그는 동일 패턴 반복)
- ⬜ chat_messages_list.dart (2,569줄) 분해 — 별도 플랜 필요
- ⬜ Navigator → context.pop() 전체 마이그레이션 — 별도 플랜 필요

**Type consistency:**
- T2에서 만든 `ChatStrings.passwordMismatch`를 T3의 `ChangePasswordDialog`에서 참조 — 일치.
- T3의 `Key('current_pw')` 가 테스트와 위젯에서 동일하게 사용 — 일치.

**Placeholder scan:** 없음.
