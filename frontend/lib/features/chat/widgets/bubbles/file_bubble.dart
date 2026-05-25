import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/utils/url_helper.dart';
import '../../../../shared/models/chat_message.dart';
import '../pdf_viewer_dialog.dart';
import 'avatar.dart';

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
    final screenWidth = MediaQuery.of(context).size.width;
    // Responsive image width for mobile
    final imageWidth = screenWidth < 600
        ? (screenWidth * 0.55).clamp(140.0, 220.0)
        : 220.0;
    final radius = BorderRadius.only(
      topLeft: const Radius.circular(20),
      topRight: const Radius.circular(20),
      bottomLeft: Radius.circular(isMine ? 20 : 5),
      bottomRight: Radius.circular(isMine ? 5 : 20),
    );
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
                color: isMine ? null : cs.surfaceContainer,
                gradient: isMine ? AppColors.myBubbleGradient : null,
                borderRadius: BorderRadius.only(
                  bottomLeft: radius.bottomLeft,
                  bottomRight: radius.bottomRight,
                ),
                border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
              ),
              child: Text(
                msg.content,
                style: TextStyle(
                  fontSize: 14,
                  color: isMine ? Colors.white : cs.onSurface,
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
            color: isMine ? null : cs.surfaceContainer,
            gradient: isMine ? AppColors.myBubbleGradient : null,
            borderRadius: radius,
            border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 40, height: 40,
                decoration: BoxDecoration(
                  color: Colors.red.withAlpha(25),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Icon(Icons.picture_as_pdf, size: 24, color: Colors.red),
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
                        color: isMine ? Colors.white : cs.onSurface,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'PDF 미리보기',
                      style: TextStyle(
                        fontSize: 11,
                        color: isMine ? Colors.white.withAlpha(200) : cs.primary,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right,
                size: 20,
                color: isMine ? Colors.white.withAlpha(180) : cs.onSurfaceVariant,
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
          color: isMine ? null : cs.surfaceContainer,
          gradient: isMine ? AppColors.myBubbleGradient : null,
          borderRadius: radius,
          border: isMine ? null : Border.all(color: cs.outline.withAlpha(80)),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.insert_drive_file_outlined,
              size: 28,
              color: isMine ? Colors.white.withAlpha(220) : cs.onSurfaceVariant,
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
                      color: isMine ? Colors.white : cs.onSurface,
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
                            ? Colors.white.withAlpha(200)
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
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        mainAxisAlignment: isMine ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.end,
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
                    padding: const EdgeInsets.only(left: 4, bottom: 3),
                    child: Text(
                      msg.username,
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
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
                        padding: const EdgeInsets.only(right: 5, bottom: 3),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.end,
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (readCount > 0)
                              Text(
                                '읽음 $readCount',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: Theme.of(context).colorScheme.primary.withAlpha(180),
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            Text(
                              time,
                              style: TextStyle(
                                fontSize: 10,
                                color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
                              ),
                            ),
                          ],
                        ),
                      ),
                    Flexible(child: content),
                    if (!isMine)
                      Padding(
                        padding: const EdgeInsets.only(left: 5, bottom: 3),
                        child: Text(
                          time,
                          style: TextStyle(
                            fontSize: 10,
                            color: Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(140),
                          ),
                        ),
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
                        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        decoration: BoxDecoration(
                          color: Theme.of(context).colorScheme.primaryContainer.withAlpha(60),
                          borderRadius: BorderRadius.circular(12),
                          border: Border.all(
                              color: Theme.of(context).colorScheme.primary.withAlpha(80),
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
