import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../../core/network/dio_client.dart';

class InviteJoinScreen extends ConsumerStatefulWidget {
  final String token;
  const InviteJoinScreen({super.key, required this.token});

  @override
  ConsumerState<InviteJoinScreen> createState() => _InviteJoinScreenState();
}

class _InviteJoinScreenState extends ConsumerState<InviteJoinScreen> {
  String? _roomId;
  String? _roomName;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _validateInvite();
  }

  Future<void> _validateInvite() async {
    try {
      final dio = ref.read(dioClientProvider).dio;
      final resp = await dio.post(
        '/api/chat/rooms/join-by-invite',
        data: {'token': widget.token},
      );
      final data = resp.data;
      String? roomId;
      String? roomName;
      if (data is Map && data['data'] is Map) {
        final d = data['data'] as Map;
        roomId = d['roomId']?.toString();
        roomName = d['roomName']?.toString();
      }
      if (mounted) {
        if (roomId == null || roomId.isEmpty) {
          setState(() {
            _error = '초대 링크가 유효하지 않습니다';
            _loading = false;
          });
        } else {
          setState(() {
            _roomId = roomId;
            _roomName = roomName;
            _loading = false;
          });
        }
      }
    } on DioException catch (e) {
      String errorMessage;
      final statusCode = e.response?.statusCode;
      if (statusCode == 410) {
        errorMessage = '초대 링크가 만료되었습니다';
      } else if (statusCode == 400) {
        errorMessage = '채팅방이 만석입니다';
      } else if (statusCode == 404) {
        errorMessage = '채팅방이 삭제되었습니다';
      } else {
        errorMessage = '초대 링크가 유효하지 않습니다';
      }
      if (mounted) {
        setState(() {
          _error = errorMessage;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _error = '초대 링크가 유효하지 않습니다';
          _loading = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/chat'),
        ),
        title: const Text('초대 링크'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: _loading
              ? const CircularProgressIndicator()
              : _error != null
                  ? _ErrorView(
                      error: _error!,
                      onBack: () => context.go('/chat'),
                    )
                  : _SuccessView(
                      roomName: _roomName ?? '채팅방',
                      onEnter: () => context.go('/chat/$_roomId'),
                      onBack: () => context.go('/chat'),
                    ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Success view — room name + enter button
// ---------------------------------------------------------------------------
class _SuccessView extends StatelessWidget {
  final String roomName;
  final VoidCallback onEnter;
  final VoidCallback onBack;

  const _SuccessView({
    required this.roomName,
    required this.onEnter,
    required this.onBack,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 400),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: cs.primaryContainer,
            ),
            child: Icon(Icons.chat_bubble_outline_rounded,
                size: 36, color: cs.onPrimaryContainer),
          ),
          const SizedBox(height: 24),
          Text(
            '채팅방 초대',
            style: TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            roomName,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: cs.primary,
            ),
          ),
          const SizedBox(height: 8),
          Text(
            '위 채팅방에 입장하시겠습니까?',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 14,
              color: cs.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: onEnter,
              icon: const Icon(Icons.login_rounded),
              label: const Text('입장하기'),
              style: FilledButton.styleFrom(
                padding: const EdgeInsets.symmetric(vertical: 14),
              ),
            ),
          ),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: OutlinedButton(
              onPressed: onBack,
              child: const Text('돌아가기'),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Error view — error message + back button
// ---------------------------------------------------------------------------
class _ErrorView extends StatelessWidget {
  final String error;
  final VoidCallback onBack;

  const _ErrorView({required this.error, required this.onBack});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 400),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            width: 72,
            height: 72,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: cs.errorContainer,
            ),
            child: Icon(Icons.link_off_rounded,
                size: 36, color: cs.onErrorContainer),
          ),
          const SizedBox(height: 24),
          Text(
            '초대 링크 오류',
            style: TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.w700,
              color: cs.onSurface,
            ),
          ),
          const SizedBox(height: 12),
          Text(
            error,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 15,
              color: cs.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: onBack,
              child: const Text('채팅 목록으로'),
            ),
          ),
        ],
      ),
    );
  }
}
