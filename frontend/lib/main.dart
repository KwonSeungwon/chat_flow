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
import 'core/services/web_unload_handler.dart';
import 'core/theme/app_theme.dart';
import 'core/theme/font_scale_provider.dart';
import 'core/theme/theme_provider.dart';
import 'features/auth/auth_provider.dart';
import 'firebase_options.dart';

Future<void> main() async {
  // Suppress Flutter Web SDK noise from focus_traversal.dart's
  // `nearestCommonDirectionality!` null-assert (fires on _viewFocusBinding
  // before the widget tree has a Directionality ancestor). The error never
  // reaches user code and the app keeps working — silencing the console noise.
  bool isHarmlessFocusTraversalError(Object error, StackTrace stack) {
    final s = stack.toString();
    return s.contains('focus_traversal.dart') &&
        s.contains('_pickNext');
  }

  runZonedGuarded<Future<void>>(() async {
    WidgetsFlutterBinding.ensureInitialized();
    try { await dotenv.load(fileName: '.env'); } catch (_) {}

    final defaultErrorHandler = FlutterError.onError;
    FlutterError.onError = (details) {
      if (isHarmlessFocusTraversalError(details.exception, details.stack ?? StackTrace.empty)) return;
      defaultErrorHandler?.call(details);
    };

    PlatformDispatcher.instance.onError = (error, stack) {
      if (isHarmlessFocusTraversalError(error, stack)) return true;
      return false;
    };

    final container = ProviderContainer();
    runApp(UncontrolledProviderScope(
      container: container,
      child: const ChatFlowApp(),
    ));

    // On web, fire POST /api/fcm/unsubscribe-all with keepalive before the tab
    // closes. sendBeacon cannot carry an Authorization header, so fetch+keepalive
    // is the only viable primitive here. The stub is a no-op on native platforms.
    WebUnloadHandler.register(
      jwtProvider: () => container.read(authProvider).token ?? '',
      fcmTokenProvider: FcmService.getToken,
      // On web the app runs on the same origin as the gateway, so an empty
      // string resolves to a relative URL (/api/fcm/unsubscribe-all).
      // Native builds use API_BASE_URL from the .env file.
      apiBaseUrl: kIsWeb
          ? ''
          : const String.fromEnvironment('API_BASE_URL', defaultValue: ''),
    );

    _initFirebaseInBackground();
  }, (error, stack) {
    if (isHarmlessFocusTraversalError(error, stack)) return;
    debugPrint('[uncaught] $error\n$stack');
  });
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
    // Override default ReadingOrderTraversalPolicy with WidgetOrderTraversalPolicy.
    // ReadingOrderTraversalPolicy has a known bug (`nearestCommonDirectionality!`
    // throws on null when focus nodes lack a common Directionality ancestor),
    // which fires on Flutter Web's view focus binding at app start.
    return FocusTraversalGroup(
      policy: WidgetOrderTraversalPolicy(),
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
            child: AppShortcuts(child: child ?? const SizedBox()),
          );
        },
      ),
    );
  }
}
