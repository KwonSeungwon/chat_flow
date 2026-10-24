import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../../../core/theme/app_theme.dart';
import 'create_room_dialog.dart';

class LobbyPlaceholder extends StatelessWidget {
  const LobbyPlaceholder({super.key});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        // 넓은 화면에서 버튼이 화면 폭만큼 늘어나지 않게 폭을 묶는다.
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 92,
                height: 92,
                decoration: ShapeDecoration(
                  shape: ContinuousRectangleBorder(
                      borderRadius: BorderRadius.circular(72)),
                  gradient: AppColors.accentGradient,
                  shadows: [
                    BoxShadow(
                      color: AppColors.blue.withAlpha(70),
                      blurRadius: 24,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                child: const Icon(Icons.forum_rounded,
                    size: 42, color: Colors.white),
              ),
              const SizedBox(height: 24),
              Text(
                '채팅을 시작하세요',
                style: TextStyle(
                  color: colorScheme.onSurface,
                  fontSize: 20,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                '채팅방을 선택하거나 새로 만들어\n대화를 시작할 수 있습니다',
                textAlign: TextAlign.center,
                style: TextStyle(
                    color: ChatPalette.of(context).meta,
                    fontSize: 14,
                    height: 1.5),
              ),
              const SizedBox(height: 28),
              FilledButton.icon(
                onPressed: () => showDialog(
                  context: context,
                  builder: (_) => const CreateRoomDialog(),
                ),
                icon: const Icon(Icons.add_rounded),
                label: const Text('새 채팅방 만들기'),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(
                onPressed: () => context.push('/world'),
                icon: const Icon(Icons.public),
                label: const Text('위키 던전'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
