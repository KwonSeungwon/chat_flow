import 'package:flutter/material.dart';

import '../../../core/theme/app_theme.dart';

class ConnectionDot extends StatelessWidget {
  final bool connected;
  const ConnectionDot({super.key, required this.connected});

  @override
  Widget build(BuildContext context) {
    final color = connected ? AppColors.success : AppColors.error;
    return Tooltip(
      message: connected ? '연결됨' : '연결 끊김',
      child: Container(
        width: 9,
        height: 9,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: color,
          boxShadow: [
            BoxShadow(color: color.withAlpha(120), blurRadius: 6, spreadRadius: 1),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Animated typing dots ("···")
// ---------------------------------------------------------------------------
class BouncingDots extends StatefulWidget {
  const BouncingDots({super.key});
  @override
  State<BouncingDots> createState() => BouncingDotsState();
}

class BouncingDotsState extends State<BouncingDots> with TickerProviderStateMixin {
  late final List<AnimationController> _controllers;
  late final List<Animation<double>> _animations;

  @override
  void initState() {
    super.initState();
    _controllers = List.generate(3, (i) => AnimationController(
      vsync: this, duration: const Duration(milliseconds: 400),
    ));
    _animations = _controllers.map((c) =>
      Tween(begin: 0.0, end: -4.0).animate(CurvedAnimation(parent: c, curve: Curves.easeInOut)),
    ).toList();
    for (int i = 0; i < 3; i++) {
      Future.delayed(Duration(milliseconds: i * 150), () {
        if (mounted) _controllers[i].repeat(reverse: true);
      });
    }
  }

  @override
  void dispose() {
    for (final c in _controllers) c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(160);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: List.generate(3, (i) => AnimatedBuilder(
        animation: _animations[i],
        builder: (_, child) => Transform.translate(
          offset: Offset(0, _animations[i].value),
          child: child,
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 1),
          child: Text('·', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: color)),
        ),
      )),
    );
  }
}
