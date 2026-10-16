import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/auth/login_page.dart';
import '../../features/chat/chat_page.dart';
import '../../features/chat/screens/invite_join_screen.dart';
import '../../features/chat/screens/mentions_screen.dart';
import '../../features/chat/screens/scheduled_messages_screen.dart';
import '../../features/world/admin/question_review_page.dart';
import '../../features/world/dungeon_page.dart';
import '../../features/world/world_page.dart';

class _RouterNotifier extends ChangeNotifier {
  final Ref _ref;
  late final ProviderSubscription<AuthState> _sub;

  _RouterNotifier(this._ref) {
    _sub = _ref.listen<AuthState>(authProvider, (_, __) => notifyListeners());
  }

  @override
  void dispose() {
    _sub.close();
    super.dispose();
  }

  String? redirect(BuildContext context, GoRouterState state) {
    final auth = _ref.read(authProvider);
    if (!auth.isHydrated) return null;
    final loc = state.matchedLocation;
    final isLoginPage = loc == '/login';
    if (!auth.isAuthenticated && !isLoginPage) {
      // Preserve the requested location (path + query) as the post-login target
      return '/login?redirect=${Uri.encodeComponent(state.uri.toString())}';
    }
    if (auth.isAuthenticated && isLoginPage) {
      final redirect = state.uri.queryParameters['redirect'];
      if (redirect != null &&
          redirect.isNotEmpty &&
          redirect.startsWith('/') &&
          !redirect.startsWith('//')) {
        return redirect;
      }
      return '/chat';
    }
    return null;
  }
}

final _routerNotifierProvider = Provider<_RouterNotifier>((ref) {
  final notifier = _RouterNotifier(ref);
  ref.onDispose(notifier.dispose);
  return notifier;
});

final appRouterProvider = Provider<GoRouter>((ref) {
  final notifier = ref.watch(_routerNotifierProvider);
  return GoRouter(
    initialLocation: '/chat',
    refreshListenable: notifier,
    redirect: notifier.redirect,
    routes: [
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginPage(),
      ),
      GoRoute(
        path: '/chat',
        builder: (context, state) => const ChatPage(),
        routes: [
          GoRoute(
            path: ':roomId',
            builder: (context, state) {
              final roomId = state.pathParameters['roomId'];
              final messageId = state.uri.queryParameters['messageId'];
              return roomId != null
                  ? ChatPage(roomId: roomId, scrollToMessageId: messageId)
                  : const ChatPage();
            },
          ),
        ],
      ),
      GoRoute(
        path: '/world',
        builder: (context, state) => const WorldPage(),
        routes: [
          GoRoute(
            path: 'admin',
            builder: (context, state) => const QuestionReviewPage(),
          ),
          GoRoute(
            path: 'dungeon/:id',
            builder: (context, state) =>
                DungeonPage(dungeonId: state.pathParameters['id'] ?? ''),
          ),
        ],
      ),
      GoRoute(
        path: '/scheduled',
        builder: (context, state) => const ScheduledMessagesScreen(),
      ),
      GoRoute(
        path: '/mentions',
        builder: (context, state) => const MentionsScreen(),
      ),
      GoRoute(
        path: '/invite/:token',
        builder: (context, state) {
          final token = state.pathParameters['token'] ?? '';
          return InviteJoinScreen(token: token);
        },
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text('Page not found: ${state.error}')),
    ),
  );
});

/// `MaterialApp.router`의 builder에 쓴다. 저장소 hydration이 끝나기 전에는
/// [child](Router/Navigator)를 만들지 않고 스플래시만 그린다. 그래야 딥링크된
/// 페이지가 로그인 여부를 모르는 채로 생성되어 API를 부르거나 로그인으로
/// 튕기지 않는다.
class AuthHydrationGate extends ConsumerWidget {
  const AuthHydrationGate({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final hydrated = ref.watch(authProvider.select((s) => s.isHydrated));
    if (!hydrated) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    return child;
  }
}
