import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../features/auth/auth_provider.dart';
import '../../features/auth/login_page.dart';
import '../../features/chat/chat_page.dart';
import '../../features/search/search_page.dart';

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
    final isLoginPage = state.matchedLocation == '/login';
    if (!auth.isAuthenticated && !isLoginPage) return '/login';
    if (auth.isAuthenticated && isLoginPage) return '/chat';
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
        path: '/search',
        builder: (context, state) => const SearchPage(),
      ),
    ],
    errorBuilder: (context, state) => Scaffold(
      body: Center(child: Text('Page not found: ${state.error}')),
    ),
  );
});
