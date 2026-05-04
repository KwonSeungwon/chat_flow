import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../auth/auth_provider.dart';
import '../chat/widgets/create_room_dialog.dart';
import '../profile/widgets/profile_preview_dialog.dart';
import '../../core/theme/theme_provider.dart';

// ---------------------------------------------------------------------------
// Sealed hierarchy for command palette actions
// ---------------------------------------------------------------------------

sealed class CommandAction {
  String get title;
  String? get subtitle;
  IconData get icon;

  /// Execute the action. Returns a Future to allow async operations.
  Future<void> execute(BuildContext context, WidgetRef ref);

  /// Score this action against a search query (case-insensitive substring).
  /// Returns 0 if no match, higher is better (earlier position = higher score).
  int matchScore(String query) {
    if (query.isEmpty) return 1;
    final lower = title.toLowerCase();
    final q = query.toLowerCase();
    final idx = lower.indexOf(q);
    if (idx < 0) {
      // Also try matching subtitle
      final sub = subtitle?.toLowerCase() ?? '';
      final subIdx = sub.indexOf(q);
      if (subIdx < 0) return 0;
      // Subtitle match scores lower than title match
      return 50 - subIdx.clamp(0, 49);
    }
    // Title match: earlier position = higher score
    return 100 - idx.clamp(0, 99);
  }
}

// ---------------------------------------------------------------------------
// Go to a chat room
// ---------------------------------------------------------------------------

class GoToRoomAction extends CommandAction {
  final String roomId;
  final String roomName;
  final String? roomDescription;

  GoToRoomAction({
    required this.roomId,
    required this.roomName,
    this.roomDescription,
  });

  @override
  String get title => roomName;

  @override
  String? get subtitle => roomDescription ?? '채팅방';

  @override
  IconData get icon => Icons.chat_bubble_outline;

  @override
  Future<void> execute(BuildContext context, WidgetRef ref) async {
    // Capture router synchronously — context may become invalid after await.
    final router = GoRouter.of(context);
    router.go('/chat/$roomId');
  }
}

// ---------------------------------------------------------------------------
// View a user's profile
// ---------------------------------------------------------------------------

class ViewProfileAction extends CommandAction {
  final String userId;
  final String username;
  final String? profileImageUrl;

  ViewProfileAction({
    required this.userId,
    required this.username,
    this.profileImageUrl,
  });

  @override
  String get title => username;

  @override
  String? get subtitle => '사용자';

  @override
  IconData get icon => Icons.person_outline;

  @override
  Future<void> execute(BuildContext context, WidgetRef ref) async {
    await showProfilePreview(context, userId);
  }
}

// ---------------------------------------------------------------------------
// Quick actions (static shortcuts)
// ---------------------------------------------------------------------------

enum QuickActionType { createRoom, goSearch, toggleTheme, logout }

class QuickAction extends CommandAction {
  final QuickActionType type;
  final String _title;
  final IconData _icon;

  QuickAction._({
    required this.type,
    required String title,
    required IconData icon,
  })  : _title = title,
        _icon = icon;

  @override
  String get title => _title;

  @override
  String? get subtitle => '빠른 실행';

  @override
  IconData get icon => _icon;

  @override
  Future<void> execute(BuildContext context, WidgetRef ref) async {
    // Capture router synchronously before any async gap.
    final router = GoRouter.of(context);
    switch (type) {
      case QuickActionType.createRoom:
        if (context.mounted) {
          showDialog(
            context: context,
            builder: (_) => const CreateRoomDialog(),
          );
        }
      case QuickActionType.goSearch:
        router.push('/search');
      case QuickActionType.toggleTheme:
        try {
          await ref.read(themeModeProvider.notifier).toggle();
        } catch (e) {
          debugPrint('[CommandPalette] toggleTheme error: $e');
        }
      case QuickActionType.logout:
        try {
          await ref.read(authProvider.notifier).logout();
          // Mirror chat_page.dart pattern — navigate to login after logout.
          if (context.mounted) {
            router.go('/login');
          }
        } catch (e) {
          debugPrint('[CommandPalette] logout error: $e');
        }
    }
  }

  // ---- Factory constructors for each static quick action ----

  static QuickAction createRoom() => QuickAction._(
        type: QuickActionType.createRoom,
        title: '새 방 만들기',
        icon: Icons.add_circle_outline,
      );

  static QuickAction goSearch() => QuickAction._(
        type: QuickActionType.goSearch,
        title: '전체 검색',
        icon: Icons.search,
      );

  static QuickAction toggleTheme() => QuickAction._(
        type: QuickActionType.toggleTheme,
        title: '다크/라이트 모드 토글',
        icon: Icons.brightness_6_outlined,
      );

  static QuickAction logout() => QuickAction._(
        type: QuickActionType.logout,
        title: '로그아웃',
        icon: Icons.logout,
      );

  /// All static quick actions in display order.
  static List<QuickAction> all() => [
        createRoom(),
        goSearch(),
        toggleTheme(),
        logout(),
      ];
}
