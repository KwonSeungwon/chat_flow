import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/utils/url_helper.dart';
import '../../../../shared/models/chat_message.dart';
import '../pdf_viewer_dialog.dart';
import 'avatar.dart';
import 'chat_bubble.dart' show bubbleRadius;

class FileBubble extends StatelessWidget {
  final ChatMessage msg;
  final bool isMine;
  final String time;
  final int readCount;
  final void Function(ChatMessage parent)? onOpenThread;
  final int replyCount;

  const FileBubble({
    super.key,
    required this.msg,
    required this.isMine,
    required this.time,
    this.readCount = 0,
    this.onOpenThread,
    this.replyCount = 0,
  });

  Future<void> _launchUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  void _showFullscreenImage(BuildContext context, String url) {
    showDialog(
      context: context,
      builder: (_) => Dialog.fullscreen(
        backgroundColor: Colors.black,
        child: Stack(
          children: [
            Center(
              child: InteractiveViewer(
                minScale: 0.5,
                maxScale: 4.0,
                child: Image.network(url, fit: BoxFit.contain,
                  errorBuilder: (_, __, ___) => const Icon(Icons.broken_image, color: Colors.white54, size: 64)),
              ),
            ),
            Positioned(
              top: 16, right: 16,
              child: IconButton(
                icon: const Icon(Icons.close, color: Colors.white, size: 28),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ),
            Positioned(
              bottom: 16, right: 16,
              child: IconButton(
                icon: const Icon(Icons.open_in_new, color: Colors.white70, size: 24),
                tooltip: '브라우저에서 열기',
                onPressed: () => _launchUrl(url),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final cp = ChatPalette.of(context);
    final bubbleColor = isMine ? cp.myBubble : cp.otherBubble;
    final onBubble = isMine ? cp.onMyBubble : cp.onOtherBubble;
    final bubbleShadow = [
      BoxShadow(color: cp.bubbleShadow, blurRadius: 8, offset: const Offset(0, 2)),
    ];
    final metaStyle = TextStyle(fontSize: 10, color: cp.meta);
    final screenWidth = MediaQuery.of(context).size.width;
    // Responsive image width for mobile
    final imageWidth = screenWidth < 600
        ? (screenWidth * 0.55).clamp(140.0, 220.0)
        : 220.0;
    // 파일 메시지는 그룹핑 없이 매번 이름·아바타를 보이므로 늘 꼬리 모서리를 쓴다.
    final radius = bubbleRadius(isMine: isMine, isFirstInGroup: true);
    final fullUrl = buildFullUrl(msg.fileUrl!);

    // Text content that user typed (not default [파일] prefix)
    final hasTextContent = msg.content.isNotEmpty &&
        !msg.content.startsWith('[파일]');

    Widget content;
    if (msg.isImageFile) {
      content = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          GestureDetector(
            onTap: () => _showFullscreenImage(context, fullUrl),
            child: ClipRRect(
              borderRadius: hasTextContent
                  ? BorderRadius.only(topLeft: radius.topLeft, topRight: radius.topRight)
                  : radius,
              child: Image.network(
                fullUrl,
                width: imageWidth,
                fit: BoxFit.cover,
                loadingBuilder: (_, child, progress) {
                  if (progress == null) return child;
                  return Container(
                    width: imageWidth,
                    height: 160,
                    decoration: BoxDecoration(
                      color: cs.surfaceContainer,
                      borderRadius: radius,
                    ),
                    child: Center(
                      child: CircularProgressIndicator(
                        value: progress.expectedTotalBytes != null
                            ? progress.cumulativeBytesLoaded / progress.expectedTotalBytes!
                            : null,
                        strokeWidth: 2,
                      ),
                    ),
                  );
                },
                errorBuilder: (_, __, ___) => Container(
                  width: imageWidth,
                  height: 80,
                  decoration: BoxDecoration(
                    color: cs.surfaceContainer,
                    borderRadius: radius,
                    border: Border.all(color: cs.outline.withAlpha(80)),
                  ),
                  child: Center(
                    child: Icon(Icons.broken_image_outlined, color: cs.onSurfaceVariant),
                  ),
                ),
              ),
            ),
          ),
          if (hasTextContent)
            Container(
              width: imageWidth,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              decoration: BoxDecoration(
                color: bubbleColor,
                borderRadius: BorderRadius.only(
                  bottomLeft: radius.bottomLeft,
                  bottomRight: radius.bottomRight,
                ),
              ),
              child: Text(
                msg.content,
                style: TextStyle(
                  fontSize: 14,
                  color: onBubble,
                  height: 1.4,
                ),
              ),
            ),
        ],
      );
    } else if (msg.isPdfFile) {
      final fileMaxWidth = screenWidth < 600
          ? (screenWidth * 0.65).clamp(160.0, 260.0)
          : 260.0;
      content = GestureDetector(
        onTap: () => PdfViewerDialog.open(context, fullUrl, msg.fileName ?? 'PDF'),
        child: Container(
          constraints: BoxConstraints(maxWidth: fileMaxWidth),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: bubbleColor,
            borderRadius: radius,
            boxShadow: bubbleShadow,
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: cs.error.withAlpha(28),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(Icons.picture_as_pdf, size: 24, color: cs.error),
              ),
              const SizedBox(width: 10),
              Flexible(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      msg.fileName ?? 'PDF',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w500,
                        color: onBubble,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'PDF 미리보기',
                      style: TextStyle(
                        fontSize: 11,
                        color: isMine ? onBubble.withAlpha(170) : cs.primary,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right,
                size: 20,
                color: onBubble.withAlpha(150),
              ),
            ],
          ),
        ),
      );
    } else {
      final fileMaxWidth = screenWidth < 600
          ? (screenWidth * 0.65).clamp(160.0, 260.0)
          : 260.0;
      content = Container(
        constraints: BoxConstraints(maxWidth: fileMaxWidth),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        decoration: BoxDecoration(
          color: bubbleColor,
          borderRadius: radius,
          boxShadow: bubbleShadow,
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.insert_drive_file_outlined,
              size: 28,
              color: onBubble.withAlpha(170),
            ),
            const SizedBox(width: 10),
            Flexible(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    msg.fileName ?? '파일',
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      color: onBubble,
                    ),
                  ),
                  const SizedBox(height: 4),
                  GestureDetector(
                    onTap: () => _launchUrl(fullUrl),
                    child: Text(
                      '다운로드',
                      style: TextStyle(
                        fontSize: 11,
                        color: isMine
                            ? onBubble.withAlpha(170)
                            : cs.primary,
                        decoration: TextDecoration.underline,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.only(top: 4, bottom: 2),
      child: Row(
        mainAxisAlignment: isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!isMine) ...[
            Avatar(name: msg.username, color: avatarColor(msg.username)),
            const SizedBox(width: 8),
          ],
          Flexible(
            child: Column(
              crossAxisAlignment: isMine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
              children: [
                if (!isMine)
                  Padding(
                    padding: const EdgeInsets.only(left: 2, bottom: 4),
                    child: Text(
                      msg.username,
                      style: TextStyle(
                        fontSize: 12,
                        color: cp.name,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (isMine)
                      Padding(
                        padding: const EdgeInsets.only(right: 6, bottom: 2),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (readCount > 0)
                              Text(
                                '읽음 $readCount',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: cp.readCount,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            Text(time, style: metaStyle),
                          ],
                        ),
                      ),
                    Flexible(child: content),
                    if (!isMine)
                      Padding(
                        padding: const EdgeInsets.only(left: 6, bottom: 2),
                        child: Text(time, style: metaStyle),
                      ),
                  ],
                ),
                if (replyCount > 0 && onOpenThread != null)
                  Padding(
                    padding: EdgeInsets.only(
                        top: 4, left: isMine ? 0 : 4, right: isMine ? 4 : 0),
                    child: GestureDetector(
                      onTap: () => onOpenThread!(msg),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                        decoration: BoxDecoration(
                          color: cp.otherBubble,
                          borderRadius: BorderRadius.circular(14),
                          border: Border.all(
                              color: cs.primary.withAlpha(70),
                              width: 1),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(Icons.forum_outlined,
                                size: 13,
                                color: Theme.of(context).colorScheme.primary),
                            const SizedBox(width: 4),
                            Text('$replyCount개 답글',
                                style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w600,
                                    color: Theme.of(context).colorScheme.primary)),
                          ],
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (isMine) const SizedBox(width: 6),
        ],
      ),
    );
  }
}
