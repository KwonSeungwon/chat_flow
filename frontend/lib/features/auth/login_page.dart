import 'dart:ui';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/apk_downloader.dart';
import 'auth_provider.dart';

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  final _usernameCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl  = TextEditingController();
  final _formKey      = GlobalKey<FormState>();
  bool _obscurePassword = true;
  bool _obscureConfirm  = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _tabController.addListener(() {
      if (_tabController.indexIsChanging) {
        _formKey.currentState?.reset();
        _usernameCtrl.clear();
        _passwordCtrl.clear();
        _confirmCtrl.clear();
      }
    });
  }

  @override
  void dispose() {
    _tabController.dispose();
    _usernameCtrl.dispose();
    _passwordCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  bool get _isRegister => _tabController.index == 1;

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final username = _usernameCtrl.text.trim();
    final password = _passwordCtrl.text;
    final notifier = ref.read(authProvider.notifier);
    if (_isRegister) {
      await notifier.register(username, password);
    } else {
      await notifier.login(username, password);
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);

    ref.listen<AuthState>(authProvider, (_, next) {
      if (next.isAuthenticated) context.go('/chat');
    });

    return Scaffold(
      backgroundColor: AppColors.bg,
      body: Stack(
        children: [
          // Background: violet glow top-left
          Positioned(
            top: -200,
            left: -120,
            child: Container(
              width: 600,
              height: 600,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [AppColors.primary.withAlpha(28), Colors.transparent],
                ),
              ),
            ),
          ),
          // Background: teal glow bottom-right
          Positioned(
            bottom: -160,
            right: -160,
            child: Container(
              width: 500,
              height: 500,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(
                  colors: [AppColors.secondary.withAlpha(22), Colors.transparent],
                ),
              ),
            ),
          ),

          // Content
          Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    _Logo(),
                    const SizedBox(height: 32),

                    // Glass card
                    ClipRRect(
                      borderRadius: BorderRadius.circular(24),
                      child: BackdropFilter(
                        filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                        child: Container(
                          padding: const EdgeInsets.all(24),
                          decoration: BoxDecoration(
                            color: AppColors.surfaceHigh.withAlpha(230),
                            borderRadius: BorderRadius.circular(24),
                            border: Border.all(color: AppColors.border),
                          ),
                          child: Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              // Tab bar
                              Container(
                                padding: const EdgeInsets.all(4),
                                decoration: BoxDecoration(
                                  color: AppColors.bg,
                                  borderRadius: BorderRadius.circular(13),
                                ),
                                child: TabBar(
                                  controller: _tabController,
                                  onTap: (_) => setState(() {}),
                                  indicator: BoxDecoration(
                                    borderRadius: BorderRadius.circular(10),
                                    color: AppColors.surfaceHigher,
                                    border: Border.all(
                                        color: AppColors.border, width: 1),
                                  ),
                                  indicatorSize: TabBarIndicatorSize.tab,
                                  dividerColor: Colors.transparent,
                                  labelColor: AppColors.primary,
                                  unselectedLabelColor: AppColors.textSecondary,
                                  labelStyle: const TextStyle(
                                      fontWeight: FontWeight.w600, fontSize: 14),
                                  unselectedLabelStyle: const TextStyle(
                                      fontWeight: FontWeight.w400, fontSize: 14),
                                  tabs: const [
                                    Tab(text: '로그인'),
                                    Tab(text: '회원가입'),
                                  ],
                                ),
                              ),
                              const SizedBox(height: 24),

                              Form(
                                key: _formKey,
                                child: Column(
                                  children: [
                                    _NebField(
                                      controller: _usernameCtrl,
                                      label: '아이디',
                                      icon: Icons.person_outline_rounded,
                                      action: TextInputAction.next,
                                      validator: (v) {
                                        if (v == null || v.trim().length < 2) {
                                          return '아이디는 2자 이상이어야 합니다';
                                        }
                                        return null;
                                      },
                                    ),
                                    const SizedBox(height: 12),
                                    _NebField(
                                      controller: _passwordCtrl,
                                      label: '비밀번호',
                                      icon: Icons.lock_outline_rounded,
                                      obscure: _obscurePassword,
                                      action: _isRegister
                                          ? TextInputAction.next
                                          : TextInputAction.done,
                                      onSubmit:
                                          _isRegister ? null : (_) => _submit(),
                                      onToggleObscure: () => setState(
                                          () => _obscurePassword = !_obscurePassword),
                                      validator: (v) {
                                        if (v == null || v.length < 4) {
                                          return '비밀번호는 4자 이상이어야 합니다';
                                        }
                                        return null;
                                      },
                                    ),
                                    if (_isRegister) ...[
                                      const SizedBox(height: 12),
                                      _NebField(
                                        controller: _confirmCtrl,
                                        label: '비밀번호 확인',
                                        icon: Icons.lock_outline_rounded,
                                        obscure: _obscureConfirm,
                                        action: TextInputAction.done,
                                        onSubmit: (_) => _submit(),
                                        onToggleObscure: () => setState(
                                            () => _obscureConfirm = !_obscureConfirm),
                                        validator: (v) {
                                          if (v != _passwordCtrl.text) {
                                            return '비밀번호가 일치하지 않습니다';
                                          }
                                          return null;
                                        },
                                      ),
                                    ],
                                    if (auth.error != null) ...[
                                      const SizedBox(height: 12),
                                      Container(
                                        padding: const EdgeInsets.symmetric(
                                            horizontal: 12, vertical: 8),
                                        decoration: BoxDecoration(
                                          color: AppColors.error.withAlpha(20),
                                          borderRadius: BorderRadius.circular(10),
                                          border: Border.all(
                                              color: AppColors.error.withAlpha(80)),
                                        ),
                                        child: Row(
                                          children: [
                                            const Icon(Icons.error_outline,
                                                color: AppColors.error, size: 16),
                                            const SizedBox(width: 8),
                                            Expanded(
                                              child: Text(
                                                auth.error!,
                                                style: const TextStyle(
                                                    color: AppColors.error,
                                                    fontSize: 13),
                                              ),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ],
                                    const SizedBox(height: 20),
                                    SizedBox(
                                      width: double.infinity,
                                      height: 50,
                                      child: FilledButton(
                                        onPressed: auth.isLoading ? null : _submit,
                                        child: auth.isLoading
                                            ? const SizedBox(
                                                width: 20,
                                                height: 20,
                                                child: CircularProgressIndicator(
                                                    strokeWidth: 2,
                                                    color: Colors.white),
                                              )
                                            : Text(_isRegister ? '회원가입' : '로그인'),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),

                    if (kIsWeb) ...[
                      const SizedBox(height: 16),
                      SizedBox(
                        width: double.infinity,
                        height: 50,
                        child: OutlinedButton.icon(
                          onPressed: () => downloadApk('/chatflow-app.apk'),
                          icon: const Icon(Icons.android, size: 20),
                          style: OutlinedButton.styleFrom(
                            foregroundColor: AppColors.success,
                            side: BorderSide(
                                color: AppColors.success.withAlpha(120)),
                            shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(14)),
                          ),
                          label: const Text('Android 앱 다운로드'),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Logo with gradient glow
// ─────────────────────────────────────────────────────────────────
class _Logo extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          width: 72,
          height: 72,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(18),
            boxShadow: [
              BoxShadow(
                color: AppColors.primary.withAlpha(90),
                blurRadius: 28,
                spreadRadius: 4,
              ),
            ],
          ),
          child: ClipRRect(
            borderRadius: BorderRadius.circular(18),
            child: Image.asset('assets/app_icon.png', width: 72, height: 72),
          ),
        ),
        const SizedBox(height: 18),
        const Text(
          'ChatFlow',
          style: TextStyle(
            color: AppColors.textPrimary,
            fontSize: 28,
            fontWeight: FontWeight.w700,
            letterSpacing: -0.5,
          ),
        ),
        const SizedBox(height: 5),
        const Text(
          'AI 기반 실시간 채팅 플랫폼',
          style: TextStyle(color: AppColors.textSecondary, fontSize: 13),
        ),
      ],
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// Custom form field for login
// ─────────────────────────────────────────────────────────────────
class _NebField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final IconData icon;
  final bool obscure;
  final TextInputAction action;
  final FormFieldValidator<String>? validator;
  final void Function(String)? onSubmit;
  final VoidCallback? onToggleObscure;

  const _NebField({
    required this.controller,
    required this.label,
    required this.icon,
    this.obscure = false,
    this.action = TextInputAction.done,
    this.validator,
    this.onSubmit,
    this.onToggleObscure,
  });

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      obscureText: obscure,
      textInputAction: action,
      onFieldSubmitted: onSubmit,
      validator: validator,
      style: const TextStyle(color: AppColors.textPrimary, fontSize: 15),
      decoration: InputDecoration(
        labelText: label,
        prefixIcon: Icon(icon, size: 20, color: AppColors.textSecondary),
        suffixIcon: onToggleObscure != null
            ? IconButton(
                icon: Icon(
                  obscure
                      ? Icons.visibility_off_outlined
                      : Icons.visibility_outlined,
                  size: 20,
                  color: AppColors.textSecondary,
                ),
                onPressed: onToggleObscure,
              )
            : null,
        filled: true,
        fillColor: AppColors.surfaceHigher,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: AppColors.error),
        ),
        labelStyle: const TextStyle(color: AppColors.textSecondary),
        contentPadding:
            const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      ),
    );
  }
}
