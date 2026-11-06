import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'auth_provider.dart';

/// gateway가 소셜 로그인 뒤 일회용 코드를 붙여 보내는 화면. 코드를 세션으로 바꾸고 채팅으로 간다.
class SocialCallbackPage extends ConsumerStatefulWidget {
  final String? code;

  const SocialCallbackPage({super.key, this.code});

  @override
  ConsumerState<SocialCallbackPage> createState() => _SocialCallbackPageState();
}

class _SocialCallbackPageState extends ConsumerState<SocialCallbackPage> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _exchange());
  }

  Future<void> _exchange() async {
    final notifier = ref.read(authProvider.notifier);
    await notifier.loginWithSocialCode(widget.code ?? '');
    if (!mounted) return;
    // 성공하면 라우터가 /chat으로 보낸다. 실패는 로그인 화면에서 auth.error로 알린다.
    if (!ref.read(authProvider).isAuthenticated) context.go('/login');
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}
