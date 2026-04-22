import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'core/keyboard/app_shortcuts.dart';
import 'core/routing/app_router.dart';
import 'core/services/fcm_service.dart';
import 'core/theme/app_theme.dart';
import 'core/theme/font_scale_provider.dart';
import 'core/theme/theme_provider.dart';
import 'firebase_options.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  try { await dotenv.load(fileName: '.env'); } catch (_) {}

  // runApp 먼저 호출 — Firebase/FCM이 hang되어도 UI는 즉시 표시되어야 함.
  // 특히 web에서 requestPermission이 user gesture 없이 hang되던 문제 수정.
  runApp(const ProviderScope(child: ChatFlowApp()));

  // Firebase + FCM 초기화는 background fire-and-forget.
  _initFirebaseInBackground();
}

Future<void> _initFirebaseInBackground() async {
  try {
    await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);

    if (!kIsWeb) {
      FlutterError.onError = FirebaseCrashlytics.instance.recordFlutterFatalError;
      PlatformDispatcher.instance.onError = (error, stack) {
        FirebaseCrashlytics.instance.recordError(error, stack, fatal: true);
        return true;
      };
    }

    // FcmService.initialize() 내부 requestPermission은 web에서 user gesture 필요 —
    // hang되어도 전역 UI에 영향 없도록 await 없이 invoke (returned Future 무시 안전).
    // 오류는 내부에서 debugPrint로 로깅.
    unawaited(FcmService.initialize().catchError((e) {
      debugPrint('FcmService init failed: $e');
    }));
  } catch (e) {
    debugPrint('Firebase init failed: $e');
  }
}

class ChatFlowApp extends ConsumerWidget {
  const ChatFlowApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);
    final fontScale = ref.watch(fontScaleProvider);
    return AppShortcuts(
      child: MaterialApp.router(
        title: 'ChatFlow',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light,
        darkTheme: AppTheme.dark,
        themeMode: ref.watch(themeModeProvider),
        routerConfig: router,
        builder: (context, child) {
          return MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(fontScale.factor),
            ),
            child: child ?? const SizedBox(),
          );
        },
      ),
    );
  }
}
