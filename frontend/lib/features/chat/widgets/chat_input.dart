import 'package:flutter/material.dart';

class ChatInput extends StatefulWidget {
  final bool isConnected;
  final void Function(String content) onSend;

  const ChatInput({
    super.key,
    required this.isConnected,
    required this.onSend,
  });

  @override
  State<ChatInput> createState() => _ChatInputState();
}

class _ChatInputState extends State<ChatInput> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  static const _maxLength = 1000;
  static const _warnThreshold = 800;

  static const _emojiList = [
    '😀', '😂', '😍', '🥰', '😎', '🤔',
    '👍', '👎', '👋', '🙏', '💪', '🎉',
    '❤️', '🔥', '⭐', '💯', '✅', '❌',
    '😊', '😢', '😡', '🤣', '😱', '🥳',
    '👀', '💬', '📌', '🚀', '💡', '🎯',
    '☕', '🍕', '🎵', '📝', '⏰', '🌟',
  ];

  void _send() {
    final text = _controller.text.trim();
    if (text.isEmpty || !widget.isConnected) return;
    widget.onSend(text);
    _controller.clear();
    _focusNode.requestFocus();
  }

  void _showEmojiSheet() {
    showModalBottomSheet(
      context: context,
      builder: (ctx) {
        return Container(
          padding: const EdgeInsets.all(16),
          height: 280,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '이모지',
                style: Theme.of(ctx).textTheme.titleMedium?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: GridView.builder(
                  gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 6,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                  ),
                  itemCount: _emojiList.length,
                  itemBuilder: (_, i) {
                    return InkWell(
                      borderRadius: BorderRadius.circular(8),
                      onTap: () {
                        final current = _controller.text;
                        if (current.length < _maxLength) {
                          _controller.text = current + _emojiList[i];
                          _controller.selection = TextSelection.fromPosition(
                            TextPosition(offset: _controller.text.length),
                          );
                        }
                        Navigator.of(ctx).pop();
                        _focusNode.requestFocus();
                      },
                      child: Center(
                        child: Text(
                          _emojiList[i],
                          style: const TextStyle(fontSize: 24),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(
        color: colorScheme.surface,
        border: Border(
          top: BorderSide(color: colorScheme.outlineVariant, width: 0.5),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Character counter
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: _controller,
              builder: (_, value, __) {
                if (value.text.length < _warnThreshold) {
                  return const SizedBox.shrink();
                }
                final remaining = _maxLength - value.text.length;
                return Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      '$remaining자 남음',
                      style: TextStyle(
                        fontSize: 11,
                        color:
                            remaining <= 0
                                ? colorScheme.error
                                : colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ),
                );
              },
            ),

            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                // Emoji button
                IconButton(
                  icon: const Icon(Icons.emoji_emotions_outlined, size: 22),
                  onPressed: widget.isConnected ? _showEmojiSheet : null,
                  tooltip: '이모지',
                ),

                // Text field
                Expanded(
                  child: TextField(
                    controller: _controller,
                    focusNode: _focusNode,
                    enabled: widget.isConnected,
                    maxLines: 5,
                    minLines: 1,
                    maxLength: _maxLength,
                    buildCounter: (
                      context, {
                      required currentLength,
                      required isFocused,
                      required maxLength,
                    }) => null,
                    textInputAction: TextInputAction.newline,
                    decoration: InputDecoration(
                      hintText:
                          widget.isConnected
                              ? '메시지를 입력하세요...'
                              : '연결 중...',
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(20),
                        borderSide: BorderSide.none,
                      ),
                      filled: true,
                      fillColor: colorScheme.surfaceContainerHighest.withAlpha(
                        120,
                      ),
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 16,
                        vertical: 10,
                      ),
                      isDense: true,
                    ),
                  ),
                ),

                const SizedBox(width: 4),

                // Send button
                ValueListenableBuilder<TextEditingValue>(
                  valueListenable: _controller,
                  builder: (_, value, __) {
                    final canSend =
                        widget.isConnected && value.text.trim().isNotEmpty;
                    return IconButton(
                      icon: Icon(
                        Icons.send_rounded,
                        color:
                            canSend
                                ? colorScheme.primary
                                : colorScheme.onSurfaceVariant.withAlpha(100),
                      ),
                      onPressed: canSend ? _send : null,
                      tooltip: '전송',
                    );
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
