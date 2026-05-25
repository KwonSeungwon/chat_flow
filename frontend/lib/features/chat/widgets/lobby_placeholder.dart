import 'package:flutter/material.dart';
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
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 88,
              height: 88,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [
                    AppColors.primary.withAlpha(30),
                    AppColors.secondary.withAlpha(20),
                  ],
                ),
                border: Border.all(
                    color: AppColors.primary.withAlpha(60), width: 1),
              ),
              child: const Icon(Icons.forum_outlined,
                  size: 40, color: AppColors.primary),
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
                  color: colorScheme.onSurfaceVariant, fontSize: 14, height: 1.5),
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
          ],
        ),
      ),
    );
  }
}
