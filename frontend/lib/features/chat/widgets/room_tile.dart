import 'package:flutter/material.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_room.dart';
import '../chat_provider.dart' show NotificationPolicy, NotificationPolicyX;

// ─────────────────────────────────────────────────────────────────
// Individual room tile
// ─────────────────────────────────────────────────────────────────
class RoomTile extends StatefulWidget {
  final ChatRoom room;
  final Color color;
  final bool isSelected;
  final bool isFull;
  final NotificationPolicy policy;
  final int unreadCount;
  final List<String> keywords;
  final VoidCallback onTap;
  final VoidCallback? onDelete;
  final VoidCallback? onHide;
  final VoidCallback? onKeywordsTap;
  final void Function(NotificationPolicy)? onPolicyChange;
  final VoidCallback? onSearchTap;

  const RoomTile({
    super.key,
    required this.room,
    required this.color,
    required this.isSelected,
    required this.isFull,
    this.policy = NotificationPolicy.all,
    required this.unreadCount,
    this.keywords = const [],
    required this.onTap,
    this.onDelete,
    this.onHide,
    this.onKeywordsTap,
    this.onPolicyChange,
    this.onSearchTap,
  });

  @override
  State<RoomTile> createState() => RoomTileState();
}

class RoomTileState extends State<RoomTile> {
  bool _hovered = false;

  void _showRoomMenu(BuildContext context) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.all(12),
              child: Text('알림 설정', style: TextStyle(fontWeight: FontWeight.w700)),
            ),
            ...NotificationPolicy.values.map((p) => RadioListTile<NotificationPolicy>(
              title: Text(p.label),
              secondary: Icon(p.icon),
              value: p,
              groupValue: widget.policy,
              onChanged: (v) {
                if (v == null) return;
                Navigator.of(ctx).pop();
                widget.onPolicyChange?.call(v);
              },
            )),
            const Divider(height: 1),
            if (widget.onSearchTap != null)
              ListTile(
                leading: const Icon(Icons.search),
                title: const Text('이 방에서 검색'),
                onTap: () {
                  Navigator.of(ctx).pop();
                  widget.onSearchTap?.call();
                },
              ),
            if (widget.onKeywordsTap != null)
              ListTile(
                leading: const Icon(Icons.notifications_active_outlined),
                title: const Text('키워드 알림'),
                subtitle: Text(
                  widget.keywords.isEmpty ? '설정 안 됨' : widget.keywords.join(', '),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                onTap: () {
                  Navigator.of(ctx).pop();
                  widget.onKeywordsTap?.call();
                },
              ),
            if (_isDm && widget.onHide != null)
              ListTile(
                leading: const Icon(Icons.visibility_off_outlined),
                title: const Text('방 숨기기'),
                subtitle: const Text('상대가 새 메시지를 보내면 다시 보입니다'),
                onTap: () { Navigator.of(ctx).pop(); widget.onHide?.call(); },
              ),
            if (!_isDm && widget.onDelete != null)
              ListTile(
                leading: const Icon(Icons.delete_outline, color: Colors.red),
                title: const Text('채팅방 삭제', style: TextStyle(color: Colors.red)),
                onTap: () { Navigator.of(ctx).pop(); widget.onDelete?.call(); },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  void _showRoomContextMenu(BuildContext context, Offset position) {
    showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(position.dx, position.dy, position.dx, position.dy),
      items: [
        ...NotificationPolicy.values.map((p) => PopupMenuItem(
          value: 'policy_${p.name}',
          child: Row(children: [
            if (widget.policy == p)
              Icon(Icons.check, size: 16, color: Theme.of(context).colorScheme.primary)
            else
              const SizedBox(width: 16),
            const SizedBox(width: 6),
            Icon(p.icon, size: 18),
            const SizedBox(width: 8),
            Text(p.label),
          ]),
        )),
        if (widget.onSearchTap != null)
          const PopupMenuItem(value: 'search', child: Row(children: [
            Icon(Icons.search, size: 18),
            SizedBox(width: 8),
            Text('이 방에서 검색'),
          ])),
        if (widget.onKeywordsTap != null)
          const PopupMenuItem(value: 'keywords', child: Row(children: [
            Icon(Icons.notifications_active_outlined, size: 18),
            SizedBox(width: 8),
            Text('키워드 알림'),
          ])),
        if (_isDm && widget.onHide != null)
          const PopupMenuItem(value: 'hide', child: Row(children: [
            Icon(Icons.visibility_off_outlined, size: 18),
            SizedBox(width: 8),
            Text('방 숨기기'),
          ])),
        if (!_isDm && widget.onDelete != null)
          const PopupMenuItem(value: 'delete', child: Row(children: [
            Icon(Icons.delete_outline, size: 18, color: Colors.red),
            SizedBox(width: 8),
            Text('채팅방 삭제', style: TextStyle(color: Colors.red)),
          ])),
      ],
    ).then((value) {
      if (value == null) return;
      if (value.startsWith('policy_')) {
        final name = value.substring(7);
        final p = NotificationPolicy.values.firstWhere((e) => e.name == name, orElse: () => NotificationPolicy.all);
        widget.onPolicyChange?.call(p);
      }
      if (value == 'search') widget.onSearchTap?.call();
      if (value == 'keywords') widget.onKeywordsTap?.call();
      if (value == 'hide') widget.onHide?.call();
      if (value == 'delete') widget.onDelete?.call();
    });
  }

  /// DM 방 식별. 백엔드 [RoomType.DIRECT] 사용.
  /// (백엔드 ChatRoom.roomType은 default 'GENERAL', fromJson도 fallback 'GENERAL'
  /// 적용하므로 'DIRECT'만 신뢰 가능.)
  bool get _isDm => widget.room.roomType == 'DIRECT';
  String get _displayName {
    if (!_isDm) return widget.room.name;
    // "DM:user1,user2" → show the other user's name
    final raw = widget.room.name.replaceFirst('DM:', '');
    return raw.split(',').map((s) => s.trim()).join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    final bg = widget.isSelected
        ? AppColors.primary.withAlpha(22)
        : _hovered
            ? cs.surfaceContainerHigh
            : Colors.transparent;

    return MouseRegion(
      onEnter: (_) => setState(() => _hovered = true),
      onExit: (_) => setState(() => _hovered = false),
      child: GestureDetector(
        onTap: widget.onTap,
        onLongPress: () => _showRoomMenu(context),
        onSecondaryTapUp: (details) => _showRoomContextMenu(context, details.globalPosition),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 150),
          margin: const EdgeInsets.only(bottom: 2),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(12),
            border: widget.isSelected
                ? Border.all(color: AppColors.primary.withAlpha(55), width: 1)
                : null,
          ),
          child: Row(
            children: [
              // Left accent bar
              AnimatedContainer(
                duration: const Duration(milliseconds: 150),
                width: 3,
                height: 44,
                margin: const EdgeInsets.only(left: 4, right: 10),
                decoration: BoxDecoration(
                  color: widget.isSelected
                      ? AppColors.primary
                      : Colors.transparent,
                  borderRadius: BorderRadius.circular(2),
                ),
              ),

              // Avatar + text (dimmed if full)
              Expanded(
                child: Opacity(
                  opacity:
                      (widget.isFull && !_isDm && !widget.isSelected) ? 0.48
                      : widget.policy == NotificationPolicy.muted ? 0.5
                      : 1.0,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    child: Row(
                      children: [
                        // Gradient avatar
                        Container(
                          width: 38,
                          height: 38,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            gradient: LinearGradient(
                              begin: Alignment.topLeft,
                              end: Alignment.bottomRight,
                              colors: [
                                widget.color.withAlpha(220),
                                widget.color.withAlpha(130),
                              ],
                            ),
                          ),
                          child: Center(
                            child: _isDm
                                ? const Icon(Icons.person, color: Colors.white, size: 20)
                                : Text(
                              _displayName.isNotEmpty
                                  ? _displayName[0].toUpperCase()
                                  : '#',
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.w700,
                                fontSize: 15,
                              ),
                            ),
                          ),
                        ),
                        const SizedBox(width: 10),

                        // Name + participant count
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Row(
                                children: [
                                  if (widget.room.isHandoff) ...[
                                    Container(
                                      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
                                      margin: const EdgeInsets.only(right: 4),
                                      decoration: BoxDecoration(
                                        color: const Color(0xFF00796B).withAlpha(25),
                                        borderRadius: BorderRadius.circular(3),
                                        border: Border.all(color: const Color(0xFF00796B).withAlpha(80), width: 0.5),
                                      ),
                                      child: const Text('SBAR', style: TextStyle(fontSize: 9, fontWeight: FontWeight.w700, color: Color(0xFF00796B))),
                                    ),
                                  ],
                                  if (widget.room.isPrivate) ...[
                                    Icon(Icons.lock, size: 12,
                                        color: cs.onSurfaceVariant.withAlpha(150)),
                                    const SizedBox(width: 4),
                                  ],
                                  Expanded(
                                    child: Text(
                              _displayName,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: widget.isSelected
                                    ? cs.onSurface
                                    : cs.onSurfaceVariant,
                                fontWeight: widget.isSelected
                                    ? FontWeight.w600
                                    : FontWeight.w400,
                                fontSize: 14,
                              ),
                                    ),
                                  ),
                                  ],
                              ),
                              const SizedBox(height: 3),
                              Row(
                                children: [
                                  Icon(
                                    Icons.people_outline_rounded,
                                    size: 11,
                                    color: (widget.isFull && !_isDm)
                                        ? AppColors.error
                                        : cs.onSurfaceVariant.withAlpha(150),
                                  ),
                                  const SizedBox(width: 3),
                                  Text(
                                    '${widget.room.participantCount}/${widget.room.maxParticipants}',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: (widget.isFull && !_isDm)
                                          ? AppColors.error
                                          : cs.onSurfaceVariant.withAlpha(150),
                                      fontWeight: (widget.isFull && !_isDm)
                                          ? FontWeight.w600
                                          : FontWeight.w400,
                                    ),
                                  ),
                                  // DM은 항상 2/2가 정상 상태이므로 만석 뱃지 숨김.
                                  if (widget.isFull && !_isDm) ...[
                                    const SizedBox(width: 6),
                                    Container(
                                      padding: const EdgeInsets.symmetric(
                                          horizontal: 5, vertical: 1),
                                      decoration: BoxDecoration(
                                        color: AppColors.error.withAlpha(22),
                                        borderRadius: BorderRadius.circular(4),
                                        border: Border.all(
                                            color:
                                                AppColors.error.withAlpha(80),
                                            width: 1),
                                      ),
                                      child: const Text(
                                        '만석',
                                        style: TextStyle(
                                          fontSize: 9,
                                          color: AppColors.error,
                                          fontWeight: FontWeight.w700,
                                        ),
                                      ),
                                    ),
                                  ],
                                ],
                              ),
                            ],
                          ),
                        ),
                        if (widget.policy != NotificationPolicy.all)
                          Padding(
                            padding: const EdgeInsets.only(right: 4),
                            child: Icon(widget.policy.icon, size: 14,
                                color: cs.onSurfaceVariant.withAlpha(120)),
                          ),
                        // Badge shows whenever unread > 0 — for MUTED rooms the
                        // count only increments on keyword match (see sidebar
                        // STOMP handler), so a visible badge there means a
                        // keyword hit and should surface.
                        if (widget.unreadCount > 0) ...[
                          Container(
                            constraints: const BoxConstraints(minWidth: 18),
                            height: 18,
                            padding: const EdgeInsets.symmetric(horizontal: 5),
                            decoration: BoxDecoration(
                              color: AppColors.primary,
                              borderRadius: BorderRadius.circular(9),
                            ),
                            child: Center(
                              child: Text(
                                widget.unreadCount > 99
                                    ? '99+'
                                    : '${widget.unreadCount}',
                                style: const TextStyle(
                                  fontSize: 10,
                                  fontWeight: FontWeight.w700,
                                  color: Colors.white,
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                        ] else
                          const SizedBox(width: 8),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
