import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../constants/storage_keys.dart';
import '../../features/auth/login_page.dart';
import '../../features/chat/chat_page.dart';
import '../../features/search/search_page.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/chat',
    redirect: (context, state) async {
      const storage = FlutterSecureStorage();
      final token = await storage.read(key: StorageKeys.token);
      final isLoginPage = state.matchedLocation == '/login';
      if (token == null && !isLoginPage) return '/login';
      if (token != null && isLoginPage) return '/chat';
      return null;
    },
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
