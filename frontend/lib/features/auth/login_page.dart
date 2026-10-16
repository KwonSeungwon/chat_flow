import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:file_picker/file_picker.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../core/network/api_response.dart';
import '../../core/network/dio_client.dart';
import '../../core/utils/apk_downloader.dart';
import 'auth_provider.dart';

/// 로그인 화면은 앱 테마(다크/라이트)와 관계없이 파스텔 브랜드 팔레트로 고정한다.
class _P {
  static const ink = Color(0xFF24304D);
  static const inkSoft = Color(0xFF5B6788);
  static const muted = Color(0xFF6B7896);
  static const hint = Color(0xFF9AA6C2);
  static const line = Color(0xFFE3E8F2);
  static const tabBg = Color(0xFFF1F4FB);
  static const blue = Color(0xFF6C8CFF);
  static const blueDeep = Color(0xFF4F6EE6);
  static const violet = Color(0xFFA07BFF);
  static const error = Color(0xFFE5484D);
  static const shadow = Color(0x2E3B4A6B);
}

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _usernameCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _isRegister = false;
  bool _obscurePassword = true;
  bool _obscureConfirm = true;
  Uint8List? _profileImageBytes;
  String? _profileImageName;

  @override
  void dispose() {
    _usernameCtrl.dispose();
    _passwordCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  void _switchTab(bool register) {
    if (register == _isRegister) return;
    _formKey.currentState?.reset();
    _usernameCtrl.clear();
    _passwordCtrl.clear();
    _confirmCtrl.clear();
    setState(() => _isRegister = register);
  }

  Future<void> _pickProfileImage() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['jpg', 'jpeg', 'png', 'gif', 'webp'],
      withData: true,
    );
    if (result != null && result.files.isNotEmpty) {
      final file = result.files.first;
      if (file.bytes != null) {
        setState(() {
          _profileImageBytes = file.bytes;
          _profileImageName = file.name;
        });
      }
    }
  }

  Future<void> _submit() async {
    if (!(_formKey.currentState?.validate() ?? false)) return;
    final username = _usernameCtrl.text.trim();
    final password = _passwordCtrl.text;
    final notifier = ref.read(authProvider.notifier);
    if (_isRegister) {
      await notifier.register(username, password);
      // Upload profile image after successful registration.
      // 가입이 실패했으면 토큰이 없다 → 업로드가 401을 받고, 그 401이 authProvider를
      // 리셋해 방금 띄운 실패 사유까지 지워버린다. 반드시 성공을 먼저 확인한다.
      if (ref.read(authProvider).isAuthenticated &&
          _profileImageBytes != null &&
          _profileImageName != null) {
        try {
          final dioClient = ref.read(dioClientProvider);
          final formData = FormData.fromMap({
            'file': MultipartFile.fromBytes(
              _profileImageBytes!,
              filename: _profileImageName,
            ),
          });
          final resp = await dioClient.dio.post('/api/files/upload', data: formData);
          // 응답은 ApiResponse envelope다: {success, data:{fileUrl,...}}.
          // 최상위에서 찾으면 항상 null이라 아바타가 계정에 안 붙는다(파일만 고아로 남음).
          final fileUrl = apiResponseField<String>(resp.data, 'fileUrl');
          if (fileUrl != null) {
            await notifier.updateProfileImage(fileUrl);
          }
        } catch (_) {
          // Profile image upload failure is non-fatal
        }
      }
    } else {
      await notifier.login(username, password);
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = ref.watch(authProvider);

    ref.listen<AuthState>(authProvider, (_, next) {
      if (next.isAuthenticated) {
        final redirect = GoRouterState.of(context).uri.queryParameters['redirect'];
        final safeRedirect = redirect != null &&
                redirect.isNotEmpty &&
                redirect.startsWith('/') &&
                !redirect.startsWith('//')
            ? redirect
            : '/chat';
        context.go(safeRedirect);
      }
    });

    return Scaffold(
      body: DecoratedBox(
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Color(0xFFEEF4FF), Color(0xFFFDF3E7)],
          ),
        ),
        child: SafeArea(
          child: LayoutBuilder(builder: (context, c) {
            final wide = c.maxWidth >= 900;
            final form = _buildForm(auth);
            final card = Container(
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(wide ? 24 : 20),
                boxShadow: const [
                  BoxShadow(color: _P.shadow, blurRadius: 30, offset: Offset(0, 10)),
                ],
              ),
              child: wide
                  ? IntrinsicHeight(
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Expanded(flex: 11, child: _Hero(wide: true)),
                          Expanded(
                            flex: 10,
                            child: ConstrainedBox(
                              constraints: const BoxConstraints(minHeight: 540),
                              child: Center(child: form),
                            ),
                          ),
                        ],
                      ),
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [const _Hero(wide: false), form],
                    ),
            );
            return Center(
              child: SingleChildScrollView(
                padding: EdgeInsets.all(wide ? 32 : 16),
                child: ConstrainedBox(
                  constraints: BoxConstraints(maxWidth: wide ? 1040 : 480),
                  child: card,
                ),
              ),
            );
          }),
        ),
      ),
    );
  }

  Widget _buildForm(AuthState auth) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(28, 30, 28, 26),
      child: Form(
        key: _formKey,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _TabToggle(isRegister: _isRegister, onChanged: _switchTab),
            const SizedBox(height: 18),
            if (_isRegister) ...[
              Center(child: _avatarPicker()),
              const SizedBox(height: 14),
            ],
            _PastelField(
              controller: _usernameCtrl,
              label: '아이디',
              action: TextInputAction.next,
              validator: (v) {
                if (v == null || v.trim().length < 2) {
                  return '아이디는 2자 이상으로 해 주세요';
                }
                return null;
              },
            ),
            const SizedBox(height: 10),
            _PastelField(
              controller: _passwordCtrl,
              label: '비밀번호',
              obscure: _obscurePassword,
              action: _isRegister ? TextInputAction.next : TextInputAction.done,
              onSubmit: _isRegister ? null : (_) => _submit(),
              onToggleObscure: () =>
                  setState(() => _obscurePassword = !_obscurePassword),
              validator: (v) {
                if (v == null || v.length < 8) {
                  return '비밀번호는 8자 이상으로 해 주세요';
                }
                return null;
              },
            ),
            if (_isRegister) ...[
              const SizedBox(height: 10),
              _PastelField(
                controller: _confirmCtrl,
                label: '비밀번호 확인',
                obscure: _obscureConfirm,
                action: TextInputAction.done,
                onSubmit: (_) => _submit(),
                onToggleObscure: () =>
                    setState(() => _obscureConfirm = !_obscureConfirm),
                validator: (v) {
                  if (v != _passwordCtrl.text) {
                    return '비밀번호가 서로 달라요';
                  }
                  return null;
                },
              ),
            ],
            if (auth.error != null) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
                decoration: BoxDecoration(
                  color: _P.error.withAlpha(18),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: _P.error.withAlpha(70)),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.error_outline, color: _P.error, size: 16),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        auth.error!,
                        style: const TextStyle(color: _P.error, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 16),
            _GradientButton(
              label: _isRegister ? '회원가입' : '들어가기',
              loading: auth.isLoading,
              onPressed: auth.isLoading ? null : _submit,
            ),
            const SizedBox(height: 14),
            const Text(
              '회원가입은 아이디와 비밀번호만 있으면 돼요',
              textAlign: TextAlign.center,
              style: TextStyle(color: _P.muted, fontSize: 13),
            ),
            if (kIsWeb) ...[
              const SizedBox(height: 6),
              Center(
                child: TextButton.icon(
                  onPressed: () => downloadApk('/chatflow-app.apk'),
                  icon: const Icon(Icons.android, size: 18),
                  style: TextButton.styleFrom(foregroundColor: _P.muted),
                  label: const Text('Android 앱 받기', style: TextStyle(fontSize: 13)),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _avatarPicker() {
    return GestureDetector(
      onTap: _pickProfileImage,
      child: Stack(
        alignment: Alignment.bottomRight,
        children: [
          CircleAvatar(
            radius: 36,
            backgroundColor: _P.tabBg,
            backgroundImage:
                _profileImageBytes != null ? MemoryImage(_profileImageBytes!) : null,
            child: _profileImageBytes == null
                ? const Icon(Icons.person, size: 36, color: _P.hint)
                : null,
          ),
          Container(
            width: 24,
            height: 24,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: _P.blue,
              border: Border.all(color: Colors.white, width: 2),
            ),
            child: const Icon(Icons.camera_alt, size: 12, color: Colors.white),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────
// 왼쪽(모바일은 위쪽) 히어로: 앱 이름, 한 줄 소개, 태그, 미니 아이소 마을
// ─────────────────────────────────────────────────────────────────
class _Hero extends StatelessWidget {
  final bool wide;
  const _Hero({required this.wide});

  static const _tags = ['💬 실시간 채팅', '🏰 위키 던전', '🎓 졸업까지 8개 건물'];

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment(-0.35, -1),
          end: Alignment(0.35, 1),
          colors: [Color(0xFFE9F1FF), Color(0xFFFFF2E6)],
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: EdgeInsets.fromLTRB(28, wide ? 34 : 26, 28, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Gossiplog',
                  style: TextStyle(
                    color: _P.ink,
                    fontSize: wide ? 34 : 30,
                    fontWeight: FontWeight.w800,
                    letterSpacing: -0.6,
                  ),
                ),
                const SizedBox(height: 8),
                const Text(
                  '친구랑 떠들다가, 같이 던전 깨러 가요.',
                  style: TextStyle(color: _P.inkSoft, fontSize: 15, height: 1.4),
                ),
                const SizedBox(height: 14),
                Wrap(
                  spacing: 6,
                  runSpacing: 8,
                  children: [for (final t in _tags) _Pill(t)],
                ),
              ],
            ),
          ),
          if (wide) const Spacer() else const SizedBox(height: 8),
          AspectRatio(
            aspectRatio: 2,
            // CustomPainter는 DefaultTextStyle을 물려받지 않으므로 앱 폰트(Noto Sans KR)를 직접 넘긴다.
            child: CustomPaint(
                painter: _VillagePainter(DefaultTextStyle.of(context).style)),
          ),
        ],
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  final String text;
  const _Pill(this.text);

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(999),
        boxShadow: const [
          BoxShadow(color: Color(0x1A3B4A6B), blurRadius: 6, offset: Offset(0, 2)),
        ],
      ),
      child: Text(text, style: const TextStyle(color: _P.blueDeep, fontSize: 13)),
    );
  }
}

/// 시안의 400x200 아이소 마을(섬·광장·분수·성 2채·학생·말풍선)을 그대로 옮겼다.
class _VillagePainter extends CustomPainter {
  final TextStyle baseStyle;
  const _VillagePainter(this.baseStyle);

  void _poly(Canvas canvas, List<double> pts, Color color) {
    final path = Path()..moveTo(pts[0], pts[1]);
    for (var i = 2; i < pts.length; i += 2) {
      path.lineTo(pts[i], pts[i + 1]);
    }
    path.close();
    canvas.drawPath(path, Paint()..color = color);
  }

  void _castle(Canvas canvas, double dx, double dy, double s,
      Color top, Color left, Color right, Color roofL, Color roofR) {
    canvas.save();
    canvas.translate(dx, dy);
    canvas.scale(s);
    _poly(canvas, [0, 10, 22, 0, 44, 10, 22, 20], top);
    _poly(canvas, [0, 10, 22, 20, 22, 42, 0, 32], left);
    _poly(canvas, [22, 20, 44, 10, 44, 32, 22, 42], right);
    _poly(canvas, [-4, 10, 22, -16, 22, -2, 0, 10], roofL);
    _poly(canvas, [22, -16, 48, 10, 44, 10, 22, -2], roofR);
    canvas.restore();
  }

  @override
  void paint(Canvas canvas, Size size) {
    canvas.save();
    canvas.scale(size.width / 400, size.height / 200);

    // 떠 있는 섬: 잔디 윗면 + 얇은 옆면
    _poly(canvas, [40, 128, 200, 188, 200, 198, 40, 138], const Color(0xFF6FB487));
    _poly(canvas, [200, 188, 360, 128, 360, 138, 200, 198], const Color(0xFF5A9D74));
    _poly(canvas, [200, 60, 360, 128, 200, 188, 40, 128], const Color(0xFF9FD8A8));
    // 광장과 분수
    _poly(canvas, [200, 90, 260, 118, 200, 146, 140, 118], const Color(0xFFEEF2F8));
    canvas.drawOval(Rect.fromCenter(center: const Offset(200, 116), width: 36, height: 18),
        Paint()..color = const Color(0xFF7CC4F2));
    canvas.drawOval(Rect.fromCenter(center: const Offset(200, 113), width: 12, height: 6),
        Paint()..color = const Color(0xFFD6EEFC));
    // 빨간 지붕 성, 파란 지붕 성
    _castle(canvas, 110, 98, 1, const Color(0xFFFFF8EE), const Color(0xFFF1E2CF),
        const Color(0xFFE2CDB4), const Color(0xFFFF7A6B), const Color(0xFFE85C50));
    _castle(canvas, 246, 121, 40 / 44, const Color(0xFFFFF8EE), const Color(0xFFE7EEFC),
        const Color(0xFFCFDAF3), const Color(0xFF6C8CFF), const Color(0xFF4F6EE6));
    // 학생
    canvas.drawRRect(
        RRect.fromRectAndRadius(const Rect.fromLTWH(207, 114, 10, 13), const Radius.circular(5)),
        Paint()..color = const Color(0xFF6C8CFF));
    canvas.drawCircle(const Offset(212, 109), 6, Paint()..color = const Color(0xFFFFD9B8));
    // 말풍선: 글자 폭에 맞춰 늘린다
    final tp = TextPainter(
      text: TextSpan(
        text: '던전 갈 사람? 🙋',
        style: baseStyle.copyWith(
            fontSize: 10.5,
            height: 1.0,
            letterSpacing: 0,
            color: const Color(0xFF3B4A6B),
            fontWeight: FontWeight.w500),
      ),
      textDirection: TextDirection.ltr,
      maxLines: 1,
    )..layout();
    final bubbleW = tp.width + 20;
    final bubble = RRect.fromRectAndRadius(
        Rect.fromLTWH(269 - bubbleW / 2, 70, bubbleW, 26), const Radius.circular(13));
    canvas.drawRRect(bubble.shift(const Offset(0, 2)),
        Paint()..color = const Color(0x143B4A6B));
    canvas.drawRRect(bubble, Paint()..color = Colors.white);
    _poly(canvas, [244, 92, 254, 95, 238, 104], Colors.white);
    tp.paint(canvas, Offset(269 - tp.width / 2, 83 - tp.height / 2));

    canvas.restore();
  }

  @override
  bool shouldRepaint(_VillagePainter oldDelegate) => oldDelegate.baseStyle != baseStyle;
}

// ─────────────────────────────────────────────────────────────────
// 로그인 / 회원가입 탭
// ─────────────────────────────────────────────────────────────────
class _TabToggle extends StatelessWidget {
  final bool isRegister;
  final ValueChanged<bool> onChanged;
  const _TabToggle({required this.isRegister, required this.onChanged});

  Widget _tab(String label, bool selected, bool value) {
    return Expanded(
      child: Semantics(
        button: true,
        selected: selected,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => onChanged(value),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 160),
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(
              color: selected ? Colors.white : Colors.transparent,
              borderRadius: BorderRadius.circular(9),
              boxShadow: selected
                  ? const [BoxShadow(color: Color(0x14000000), blurRadius: 4, offset: Offset(0, 1))]
                  : null,
            ),
            child: Text(
              label,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 14,
                color: selected ? _P.ink : _P.muted,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: _P.tabBg,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _tab('로그인', !isRegister, false),
          const SizedBox(width: 6),
          _tab('회원가입', isRegister, true),
        ],
      ),
    );
  }
}

class _GradientButton extends StatelessWidget {
  final String label;
  final bool loading;
  final VoidCallback? onPressed;
  const _GradientButton({required this.label, required this.loading, this.onPressed});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        gradient: const LinearGradient(colors: [_P.blue, _P.violet]),
      ),
      child: SizedBox(
        height: 50,
        child: FilledButton(
          onPressed: onPressed,
          style: FilledButton.styleFrom(
            backgroundColor: Colors.transparent,
            disabledBackgroundColor: Colors.transparent,
            shadowColor: Colors.transparent,
            foregroundColor: Colors.white,
            disabledForegroundColor: Colors.white,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          ),
          child: loading
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                )
              : Text(label, style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
        ),
      ),
    );
  }
}

class _PastelField extends StatelessWidget {
  final TextEditingController controller;
  final String label;
  final bool obscure;
  final TextInputAction action;
  final FormFieldValidator<String>? validator;
  final void Function(String)? onSubmit;
  final VoidCallback? onToggleObscure;

  const _PastelField({
    required this.controller,
    required this.label,
    this.obscure = false,
    this.action = TextInputAction.done,
    this.validator,
    this.onSubmit,
    this.onToggleObscure,
  });

  OutlineInputBorder _border(Color color) => OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide(color: color, width: 2),
      );

  @override
  Widget build(BuildContext context) {
    return TextFormField(
      controller: controller,
      obscureText: obscure,
      textInputAction: action,
      onFieldSubmitted: onSubmit,
      validator: validator,
      cursorColor: _P.blue,
      style: const TextStyle(color: _P.ink, fontSize: 15),
      decoration: InputDecoration(
        labelText: label,
        labelStyle: const TextStyle(color: _P.hint, fontSize: 14),
        floatingLabelStyle: const TextStyle(color: _P.blueDeep),
        errorStyle: const TextStyle(color: _P.error),
        suffixIcon: onToggleObscure != null
            ? IconButton(
                icon: Icon(
                  obscure ? Icons.visibility_off_outlined : Icons.visibility_outlined,
                  size: 20,
                  color: _P.hint,
                ),
                onPressed: onToggleObscure,
              )
            : null,
        filled: true,
        fillColor: Colors.white,
        border: _border(_P.line),
        enabledBorder: _border(_P.line),
        focusedBorder: _border(_P.blue),
        errorBorder: _border(_P.error.withAlpha(150)),
        focusedErrorBorder: _border(_P.error),
        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      ),
    );
  }
}
