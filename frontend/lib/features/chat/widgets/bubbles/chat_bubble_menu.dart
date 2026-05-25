import 'package:emoji_picker_flutter/emoji_picker_flutter.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../../shared/models/chat_message.dart';
import '../../../chat/admin/widgets/message_report_dialog.dart';

// ─────────────────────────────────────────────────────────────────
// Chat bubble menu — context menu, bottom sheet, emoji picker
// ─────────────────────────────────────────────────────────────────
class ChatBubbleMenu {
  ChatBubbleMenu._();

  static const _quickReactions = ['👍', '❤️', '😂', '😮', '😢', '✅'];

  /// Mobile: long-press bottom-sheet with quick reactions + action list.
  static void showDeleteSheet(
    BuildContext context, {
    required ChatMessage msg,
    required bool isMine,
    required bool isBookmarked,
    VoidCallback? onReply,
    VoidCallback? onForward,
    VoidCallback? onPin,
    VoidCallback? onBookmark,
    VoidCallback? onEdit,
    VoidCallback? onViewEditHistory,
    VoidCallback? onDelete,
    void Function(String emoji)? onReaction,
  }) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36, height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.withAlpha(80),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            if (onReaction != null && !msg.deleted)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    ..._quickReactions.map((e) => GestureDetector(
                      onTap: () { Navigator.of(context).pop(); onReaction(e); },
                      child: Text(e, style: const TextStyle(fontSize: 24)),
                    )),
                    GestureDetector(
                      onTap: () {
                        Navigator.of(context).pop();
                        showEmojiPicker(context, onReaction: onReaction);
                      },
                      child: Container(
                        width: 36, height: 36,
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: const Icon(Icons.add, size: 20),
                      ),
                    ),
                  ],
                ),
              ),
            if (onReply != null)
              ListTile(
                leading: const Icon(Icons.reply),
                title: const Text('답글'),
                onTap: () { Navigator.of(context).pop(); onReply.call(); },
              ),
            if (onForward != null)
              ListTile(
                leading: const Icon(Icons.forward_outlined),
                title: const Text('전달'),
                onTap: () { Navigator.of(context).pop(); onForward.call(); },
              ),
            if (onPin != null)
              ListTile(
                leading: Icon(msg.pinned ? Icons.push_pin : Icons.push_pin_outlined),
                title: Text(msg.pinned ? '고정 해제' : '메시지 고정'),
                onTap: () { Navigator.of(context).pop(); onPin.call(); },
              ),
            if (onBookmark != null)
              ListTile(
                leading: Icon(isBookmarked ? Icons.bookmark : Icons.bookmark_border),
                title: Text(isBookmarked ? '북마크 해제' : '북마크'),
                onTap: () { Navigator.of(context).pop(); onBookmark.call(); },
              ),
            if (!msg.deleted)
              ListTile(
                leading: const Icon(Icons.copy_outlined),
                title: const Text('복사'),
                onTap: () {
                  Navigator.of(context).pop();
                  Clipboard.setData(ClipboardData(text: msg.content));
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('메시지가 복사되었습니다.'), duration: Duration(seconds: 1)));
                },
              ),
            if (onEdit != null)
              ListTile(
                leading: const Icon(Icons.edit_outlined),
                title: const Text('메시지 수정'),
                onTap: () { Navigator.of(context).pop(); onEdit.call(); },
              ),
            if (onViewEditHistory != null)
              ListTile(
                leading: const Icon(Icons.history),
                title: const Text('수정 이력'),
                onTap: () { Navigator.of(context).pop(); onViewEditHistory.call(); },
              ),
            if (onDelete != null)
              ListTile(
                leading: const Icon(Icons.delete_outline, color: Colors.red),
                title: const Text('메시지 삭제', style: TextStyle(color: Colors.red)),
                onTap: () { Navigator.of(context).pop(); onDelete.call(); },
              ),
            // 자기 메시지 / 삭제된 메시지에는 신고 노출 안 함.
            if (!isMine && !msg.deleted)
              ListTile(
                leading: const Icon(Icons.flag_outlined, color: Colors.orange),
                title: const Text('신고', style: TextStyle(color: Colors.orange)),
                onTap: () {
                  Navigator.of(context).pop();
                  showMessageReportDialog(context, msg.effectiveId);
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  /// Full-screen emoji picker as bottom sheet.
  static void showEmojiPicker(
    BuildContext context, {
    void Function(String emoji)? onReaction,
  }) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (sheetCtx) => Container(
        height: 280,
        decoration: BoxDecoration(
          color: Theme.of(sheetCtx).colorScheme.surface,
          borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
        ),
        child: EmojiPicker(
          onEmojiSelected: (_, emoji) {
            Navigator.of(sheetCtx).pop();
            onReaction?.call(emoji.emoji);
          },
          config: Config(
            height: 256,
            checkPlatformCompatibility: true,
            emojiViewConfig: EmojiViewConfig(
              columns: 8,
              emojiSizeMax: 28,
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
            ),
            categoryViewConfig: CategoryViewConfig(
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
              iconColorSelected: Theme.of(sheetCtx).colorScheme.primary,
              indicatorColor: Theme.of(sheetCtx).colorScheme.primary,
            ),
            bottomActionBarConfig: const BottomActionBarConfig(enabled: false),
            searchViewConfig: SearchViewConfig(
              backgroundColor: Theme.of(sheetCtx).colorScheme.surface,
            ),
          ),
        ),
      ),
    );
  }

  /// Desktop: right-click context popup menu.
  static void showContextMenu(
    BuildContext context,
    Offset position, {
    required ChatMessage msg,
    required bool isMine,
    required bool isBookmarked,
    VoidCallback? onReply,
    VoidCallback? onForward,
    VoidCallback? onPin,
    VoidCallback? onBookmark,
    VoidCallback? onEdit,
    VoidCallback? onViewEditHistory,
    VoidCallback? onDelete,
    void Function(String emoji)? onReaction,
  }) {
    final canReport = !isMine && !msg.deleted;
    final items = <PopupMenuEntry<String>>[];
    if (onReaction != null && !msg.deleted) {
      items.add(PopupMenuItem(
        enabled: false,
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            ..._quickReactions.map((e) => GestureDetector(
              onTap: () { Navigator.of(context).pop('react_$e'); },
              child: Text(e, style: const TextStyle(fontSize: 20)),
            )),
            GestureDetector(
              onTap: () {
                Navigator.of(context).pop('emoji_picker');
              },
              child: Container(
                width: 32, height: 32,
                decoration: BoxDecoration(
                  color: Theme.of(context).colorScheme.surfaceContainerHighest,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Icon(Icons.add, size: 18),
              ),
            ),
          ],
        ),
      ));
      items.add(const PopupMenuDivider());
    }
    if (onReply != null) {
      items.add(const PopupMenuItem(value: 'reply', child: Row(children: [Icon(Icons.reply, size: 18), SizedBox(width: 8), Text('답글')])));
    }
    if (onForward != null) {
      items.add(const PopupMenuItem(value: 'forward', child: Row(children: [Icon(Icons.forward_outlined, size: 18), SizedBox(width: 8), Text('전달')])));
    }
    if (onPin != null) {
      items.add(PopupMenuItem(value: 'pin', child: Row(children: [Icon(msg.pinned ? Icons.push_pin : Icons.push_pin_outlined, size: 18), const SizedBox(width: 8), Text(msg.pinned ? '고정 해제' : '고정')])));
    }
    if (onBookmark != null) {
      items.add(PopupMenuItem(value: 'bookmark', child: Row(children: [Icon(isBookmarked ? Icons.bookmark : Icons.bookmark_border, size: 18), const SizedBox(width: 8), Text(isBookmarked ? '북마크 해제' : '북마크')])));
    }
    if (!msg.deleted) {
      items.add(const PopupMenuItem(value: 'copy', child: Row(children: [Icon(Icons.copy_outlined, size: 18), SizedBox(width: 8), Text('복사')])));
    }
    if (onEdit != null) {
      items.add(const PopupMenuItem(value: 'edit', child: Row(children: [Icon(Icons.edit_outlined, size: 18), SizedBox(width: 8), Text('수정')])));
    }
    if (onViewEditHistory != null) {
      items.add(const PopupMenuItem(value: 'history', child: Row(children: [Icon(Icons.history, size: 18), SizedBox(width: 8), Text('수정 이력')])));
    }
    if (onDelete != null) {
      items.add(const PopupMenuItem(value: 'delete', child: Row(children: [Icon(Icons.delete_outline, size: 18, color: Colors.red), SizedBox(width: 8), Text('삭제', style: TextStyle(color: Colors.red))])));
    }
    if (canReport) {
      items.add(const PopupMenuItem(value: 'report', child: Row(children: [Icon(Icons.flag_outlined, size: 18, color: Colors.orange), SizedBox(width: 8), Text('신고', style: TextStyle(color: Colors.orange))])));
    }
    if (items.isEmpty) return;
    showMenu<String>(
      context: context,
      position: RelativeRect.fromLTRB(position.dx, position.dy, position.dx, position.dy),
      items: items,
    ).then((value) {
      if (value == null) return;
      if (value == 'emoji_picker') { if (context.mounted) showEmojiPicker(context, onReaction: onReaction); return; }
      if (value.startsWith('react_')) { onReaction?.call(value.substring(6)); return; }
      if (value == 'copy') {
        Clipboard.setData(ClipboardData(text: msg.content));
        if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('메시지가 복사되었습니다.'), duration: Duration(seconds: 1)));
        return;
      }
      if (value == 'reply') onReply?.call();
      if (value == 'forward') onForward?.call();
      if (value == 'pin') onPin?.call();
      if (value == 'bookmark') onBookmark?.call();
      if (value == 'edit') onEdit?.call();
      if (value == 'history') onViewEditHistory?.call();
      if (value == 'delete') onDelete?.call();
      if (value == 'report') showMessageReportDialog(context, msg.effectiveId);
    });
  }
}
