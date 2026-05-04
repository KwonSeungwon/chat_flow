import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/room_member.dart';
import '../../../../shared/models/room_role.dart';
import '../../../../shared/widgets/user_avatar.dart';
import '../../../profile/widgets/profile_preview_dialog.dart';
import '../current_room_role_provider.dart';
import '../room_admin_api_provider.dart';
import '../room_members_provider.dart';
import 'mute_custom_time_dialog.dart';
import 'role_badge.dart';

/// 모바일 풀스크린 바텀시트 / 데스크톱 모달 다이얼로그로 멤버 시트를 띄운다.
Future<void> showRoomMembersSheet(BuildContext context, String roomId) {
  final isMobile = MediaQuery.of(context).size.width < 600;
  if (isMobile) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.of(context).viewInsets.bottom),
        child: SizedBox(
          height: MediaQuery.of(context).size.height * 0.85,
          child: _RoomMembersSheetBody(roomId: roomId),
        ),
      ),
    );
  }
  return showDialog(
    context: context,
    builder: (_) => Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 520, maxHeight: 640),
        child: _RoomMembersSheetBody(roomId: roomId),
      ),
    ),
  );
}

class _RoomMembersSheetBody extends ConsumerStatefulWidget {
  final String roomId;
  const _RoomMembersSheetBody({required this.roomId});

  @override
  ConsumerState<_RoomMembersSheetBody> createState() => _RoomMembersSheetBodyState();
}

class _RoomMembersSheetBodyState extends ConsumerState<_RoomMembersSheetBody> {
  @override
  Widget build(BuildContext context) {
    final membersAsync = ref.watch(roomMembersProvider(widget.roomId));
    final myRole = ref.watch(currentRoomRoleProvider(widget.roomId));

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 12, 8),
          child: Row(
            children: [
              const Icon(Icons.people_outline, size: 22),
              const SizedBox(width: 10),
              const Text('멤버', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.close, size: 22),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: membersAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text('멤버 목록을 불러오지 못했습니다.\n$e',
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13)),
              ),
            ),
            data: (members) {
              if (members.isEmpty) {
                return const Center(child: Text('멤버가 없습니다.'));
              }
              final sorted = [...members]..sort((a, b) {
                  final ra = a.role.index;
                  final rb = b.role.index;
                  if (ra != rb) return ra - rb;
                  return a.username.compareTo(b.username);
                });
              return ListView.separated(
                padding: const EdgeInsets.symmetric(vertical: 8),
                itemCount: sorted.length,
                separatorBuilder: (_, __) => const Divider(height: 1, indent: 60),
                itemBuilder: (_, i) => _MemberTile(
                  roomId: widget.roomId,
                  member: sorted[i],
                  myRole: myRole,
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _MemberTile extends ConsumerWidget {
  final String roomId;
  final RoomMember member;
  final RoomRole? myRole;

  const _MemberTile({
    required this.roomId,
    required this.member,
    required this.myRole,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final canManage = myRole == RoomRole.owner || myRole == RoomRole.moderator;
    // MOD는 OWNER 대상 액션 불가, OWNER/MOD 모두 자기 자신 대상 액션 불가 (UI상 액션 메뉴 숨김)
    final canActOnTarget = canManage &&
        member.role != RoomRole.owner &&
        myRole != null;

    return ListTile(
      leading: UserAvatar(
        fallbackName: member.username,
        radius: 18,
        onTap: () => showProfilePreview(context, member.userId),
      ),
      title: Row(
        children: [
          Flexible(child: Text(member.username, overflow: TextOverflow.ellipsis)),
          if (member.role != RoomRole.member) ...[
            const SizedBox(width: 6),
            RoleBadge(role: member.role),
          ],
          if (member.isMuted) ...[
            const SizedBox(width: 6),
            const Icon(Icons.volume_off_outlined, size: 14, color: Colors.orange),
          ],
        ],
      ),
      subtitle: member.isMuted
          ? Text(
              '음소거 — ${_formatMutedUntil(member.mutedUntil!)}까지',
              style: const TextStyle(fontSize: 11, color: Colors.orange),
            )
          : null,
      trailing: canActOnTarget
          ? PopupMenuButton<String>(
              icon: const Icon(Icons.more_vert, size: 20),
              onSelected: (v) => _handleAction(context, ref, v),
              itemBuilder: (_) => [
                if (myRole == RoomRole.owner && member.role == RoomRole.member)
                  const PopupMenuItem(value: 'promote', child: Text('운영자로 위임')),
                if (myRole == RoomRole.owner && member.role == RoomRole.moderator)
                  const PopupMenuItem(value: 'demote', child: Text('운영자 해제')),
                const PopupMenuItem(value: 'mute_5', child: Text('5분 음소거')),
                const PopupMenuItem(value: 'mute_30', child: Text('30분 음소거')),
                const PopupMenuItem(value: 'mute_60', child: Text('1시간 음소거')),
                const PopupMenuItem(value: 'mute_custom', child: Text('다른 시간...')),
                if (member.isMuted)
                  const PopupMenuItem(value: 'unmute', child: Text('음소거 해제')),
                const PopupMenuItem(
                    value: 'kick',
                    child: Text('강퇴', style: TextStyle(color: Colors.red))),
                const PopupMenuItem(
                    value: 'ban',
                    child: Text('차단 (kick + 재입장 차단)',
                        style: TextStyle(color: Colors.red))),
              ],
            )
          : null,
    );
  }

  Future<void> _handleAction(BuildContext context, WidgetRef ref, String action) async {
    final api = ref.read(roomAdminApiProvider);
    final messenger = ScaffoldMessenger.of(context);

    try {
      switch (action) {
        case 'promote':
          await api.changeRole(roomId, member.userId, RoomRole.moderator);
          break;
        case 'demote':
          await api.changeRole(roomId, member.userId, RoomRole.member);
          break;
        case 'mute_5':
          await api.muteMember(roomId, member.userId, 5);
          break;
        case 'mute_30':
          await api.muteMember(roomId, member.userId, 30);
          break;
        case 'mute_60':
          await api.muteMember(roomId, member.userId, 60);
          break;
        case 'mute_custom':
          final minutes = await showMuteCustomTimeDialog(context);
          if (minutes == null) return;
          await api.muteMember(roomId, member.userId, minutes);
          break;
        case 'unmute':
          await api.unmuteMember(roomId, member.userId);
          break;
        case 'kick':
          if (!await _confirm(context, '${member.username} 님을 강퇴하시겠어요?')) return;
          await api.kickMember(roomId, member.userId);
          break;
        case 'ban':
          if (!await _confirm(context, '${member.username} 님을 차단하시겠어요?\n강퇴 + 재입장 차단됩니다.')) return;
          await api.banUser(roomId, member.userId, null);
          break;
      }
      ref.invalidate(roomMembersProvider(roomId));
      if (context.mounted) {
        messenger.showSnackBar(SnackBar(content: Text('처리되었습니다.'), duration: const Duration(seconds: 2)));
      }
    } catch (e) {
      if (context.mounted) {
        messenger.showSnackBar(SnackBar(content: Text('실패: $e'), duration: const Duration(seconds: 3)));
      }
    }
  }

  Future<bool> _confirm(BuildContext context, String message) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        content: Text(message),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('취소')),
          TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('확인', style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    return result == true;
  }

  String _formatMutedUntil(DateTime mutedUntil) {
    final hh = mutedUntil.hour.toString().padLeft(2, '0');
    final mm = mutedUntil.minute.toString().padLeft(2, '0');
    return '$hh:$mm';
  }
}
