import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:file_picker/file_picker.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/patient_card.dart';
import 'patient_card_input_dialog.dart';

class ChatInput extends StatefulWidget {
  final bool isConnected;
  final bool isAiLoading;
  final bool isHandoff;
  final void Function(String content, {String priority}) onSend;
  final Future<void> Function(String question)? onAskAi;
  final void Function(PatientCard card)? onSendPatientCard;
  final Future<void> Function(String fileName, Uint8List bytes, String mimeType, String content)? onFilePick;

  const ChatInput({
    super.key,
    required this.isConnected,
    this.isAiLoading = false,
    this.isHandoff = false,
    required this.onSend,
    this.onAskAi,
    this.onSendPatientCard,
    this.onFilePick,
  });

  @override
  State<ChatInput> createState() => _ChatInputState();
}

class _ChatInputState extends State<ChatInput> {
  final _controller = TextEditingController();
  final _focusNode  = FocusNode();
  final _keyboardFocusNode = FocusNode();
  static const _maxLength     = 1000;
  static const _warnThreshold = 800;
  bool _aiMode = false;
  String _priority = 'ROUTINE';
  bool _isSending = false; // Guard against Korean IME double-send
  bool _isUploading = false;
  // Pending file attachment
  String? _pendingFileName;
  Uint8List? _pendingFileBytes;
  String? _pendingFileMimeType;

  static const _emojiList = [
    '😀', '😂', '😍', '🥰', '😎', '🤔',
    '👍', '👎', '👋', '🙏', '💪', '🎉',
    '❤️', '🔥', '⭐', '💯', '✅', '❌',
    '😊', '😢', '😡', '🤣', '😱', '🥳',
    '👀', '💬', '📌', '🚀', '💡', '🎯',
    '☕', '🍕', '🎵', '📝', '⏰', '🌟',
  ];

  Future<void> _send() async {
    if (_isSending) return;
    final text = _controller.text.trim();
    final hasFile = _pendingFileName != null && _pendingFileBytes != null;
    if (text.isEmpty && !hasFile) return;
    if (!widget.isConnected) return;
    _isSending = true;
    _controller.value = TextEditingValue.empty;

    if (_aiMode && widget.onAskAi != null && text.isNotEmpty) {
      _focusNode.requestFocus();
      try {
        await widget.onAskAi!(text);
      } catch (e) {
        if (!mounted) { _isSending = false; return; }
        final msg = e.toString().replaceFirst('Exception: ', '');
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
      }
    } else if (hasFile && widget.onFilePick != null) {
      // Upload file + send message with text
      setState(() => _isUploading = true);
      try {
        await widget.onFilePick!(
          _pendingFileName!, _pendingFileBytes!, _pendingFileMimeType ?? 'application/octet-stream', text,
        );
      } catch (e) {
        if (!mounted) { _isSending = false; return; }
        final msg = e.toString().replaceFirst('Exception: ', '');
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
      } finally {
        if (mounted) setState(() {
          _isUploading = false;
          _pendingFileName = null;
          _pendingFileBytes = null;
          _pendingFileMimeType = null;
        });
      }
      _focusNode.requestFocus();
    } else if (text.isNotEmpty) {
      widget.onSend(text, priority: _priority);
      if (widget.isHandoff) setState(() => _priority = 'ROUTINE');
      _focusNode.requestFocus();
    }
    _isSending = false;
  }

  void _showEmojiSheet() {
    final cs = Theme.of(context).colorScheme;
    // Dismiss keyboard before showing sheet to avoid layout conflicts
    FocusScope.of(context).unfocus();

    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: Container(
          margin: const EdgeInsets.fromLTRB(8, 0, 8, 8),
          padding: const EdgeInsets.all(20),
          height: 280,
          decoration: BoxDecoration(
            color: cs.surfaceContainerHighest,
            borderRadius: BorderRadius.circular(24),
            border: Border.all(color: cs.outline.withAlpha(60)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '이모지',
                style: TextStyle(
                  color: cs.onSurface,
                  fontWeight: FontWeight.w700,
                  fontSize: 16,
                ),
              ),
              const SizedBox(height: 16),
              Expanded(
                child: GridView.builder(
                  gridDelegate:
                      const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 6,
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                  ),
                  itemCount: _emojiList.length,
                  itemBuilder: (_, i) => InkWell(
                    borderRadius: BorderRadius.circular(10),
                    onTap: () {
                      if (_controller.text.length < _maxLength) {
                        final text = _controller.text;
                        final sel = _controller.selection;
                        final insertAt = (sel.baseOffset >= 0 &&
                                sel.baseOffset <= text.length)
                            ? sel.baseOffset
                            : text.length;
                        final newText = text.substring(0, insertAt) +
                            _emojiList[i] +
                            text.substring(
                                sel.extentOffset >= 0 ? sel.extentOffset : text.length);
                        _controller.value = TextEditingValue(
                          text: newText,
                          selection: TextSelection.collapsed(
                              offset: insertAt + _emojiList[i].length),
                        );
                      }
                      Navigator.of(ctx).pop();
                      _focusNode.requestFocus();
                    },
                    child: Center(
                      child: Text(
                          _emojiList[i], style: const TextStyle(fontSize: 26)),
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

  Future<void> _pickFile() async {
    if (_isUploading || widget.onFilePick == null) return;
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'pdf', 'docx', 'xlsx', 'zip'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;
      final file = result.files.first;
      final bytes = file.bytes;
      if (bytes == null) return;

      final ext = file.extension?.toLowerCase() ?? '';
      final mimeType = _extToMime(ext);

      setState(() {
        _pendingFileName = file.name;
        _pendingFileBytes = bytes;
        _pendingFileMimeType = mimeType;
      });
      _focusNode.requestFocus();
    } catch (e) {
      if (!mounted) return;
      final msg = e.toString().replaceFirst('Exception: ', '');
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('파일 선택 오류: $msg')),
      );
    }
  }

  String _extToMime(String ext) {
    const map = {
      'jpg': 'image/jpeg', 'jpeg': 'image/jpeg',
      'png': 'image/png', 'gif': 'image/gif', 'webp': 'image/webp',
      'pdf': 'application/pdf',
      'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'zip': 'application/zip',
    };
    return map[ext] ?? 'application/octet-stream';
  }

  @override
  void dispose() {
    _keyboardFocusNode.dispose();
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      decoration: BoxDecoration(
        color: cs.surface,
        border: Border(
          top: BorderSide(color: cs.outline.withAlpha(60)),
        ),
      ),
      child: SafeArea(
        top: false,
        bottom: false, // Scaffold's resizeToAvoidBottomInset handles keyboard; avoiding double inset on mobile web
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Character counter (shows only near limit)
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
                        color: remaining <= 0
                            ? AppColors.error
                            : cs.onSurfaceVariant.withAlpha(150),
                      ),
                    ),
                  ),
                );
              },
            ),

            // Pending file attachment preview
            if (_pendingFileName != null)
              Container(
                margin: const EdgeInsets.only(bottom: 6),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                decoration: BoxDecoration(
                  color: cs.secondaryContainer,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      _pendingFileMimeType?.startsWith('image/') == true
                          ? Icons.image_outlined : Icons.attach_file_rounded,
                      size: 16, color: cs.onSecondaryContainer,
                    ),
                    const SizedBox(width: 6),
                    Flexible(
                      child: Text(
                        _pendingFileName!,
                        style: TextStyle(fontSize: 12, color: cs.onSecondaryContainer),
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                    const SizedBox(width: 4),
                    GestureDetector(
                      onTap: () => setState(() {
                        _pendingFileName = null;
                        _pendingFileBytes = null;
                        _pendingFileMimeType = null;
                      }),
                      child: Icon(Icons.close, size: 16, color: cs.onSecondaryContainer),
                    ),
                  ],
                ),
              ),

            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                // AI mode toggle
                if (widget.onAskAi != null) ...[
                  Container(
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _aiMode
                          ? AppColors.secondary.withAlpha(30)
                          : cs.surfaceContainer,
                      border: Border.all(
                        color: _aiMode ? AppColors.secondary : cs.outline,
                        width: _aiMode ? 1.5 : 1,
                      ),
                    ),
                    child: widget.isAiLoading
                        ? Center(
                            child: SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppColors.secondary,
                              ),
                            ),
                          )
                        : Material(
                            color: Colors.transparent,
                            child: InkWell(
                              onTap: widget.isConnected
                                  ? () => setState(() {
                                        _aiMode = !_aiMode;
                                        _controller.clear();
                                      })
                                  : null,
                              borderRadius: BorderRadius.circular(20),
                              child: Center(
                                child: Text(
                                  'AI',
                                  style: TextStyle(
                                    fontSize: 11,
                                    fontWeight: FontWeight.w700,
                                    color: _aiMode
                                        ? AppColors.secondary
                                        : widget.isConnected
                                            ? cs.onSurfaceVariant
                                            : cs.onSurfaceVariant.withAlpha(80),
                                  ),
                                ),
                              ),
                            ),
                          ),
                  ),
                  const SizedBox(width: 6),
                ],
                // Priority toggle (handoff rooms only)
                if (widget.isHandoff && !_aiMode) ...[
                  GestureDetector(
                    onTap: widget.isConnected ? () {
                      setState(() {
                        _priority = _priority == 'ROUTINE' ? 'URGENT'
                            : _priority == 'URGENT' ? 'STAT' : 'ROUTINE';
                      });
                    } : null,
                    child: Container(
                      height: 40,
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(20),
                        color: _priority == 'STAT' ? const Color(0xFFD32F2F).withAlpha(25)
                            : _priority == 'URGENT' ? const Color(0xFFF57C00).withAlpha(25)
                            : Theme.of(context).colorScheme.surfaceContainer,
                        border: Border.all(
                          color: _priority == 'STAT' ? const Color(0xFFD32F2F)
                              : _priority == 'URGENT' ? const Color(0xFFF57C00)
                              : Theme.of(context).colorScheme.outline,
                          width: _priority != 'ROUTINE' ? 1.5 : 1,
                        ),
                      ),
                      child: Center(
                        child: Text(
                          _priority,
                          style: TextStyle(
                            fontSize: 10,
                            fontWeight: FontWeight.w700,
                            color: _priority == 'STAT' ? const Color(0xFFD32F2F)
                                : _priority == 'URGENT' ? const Color(0xFFF57C00)
                                : Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(width: 6),
                ],
                // SBAR template button (handoff rooms only)
                if (widget.isHandoff && !_aiMode) ...[
                  _CircleIconBtn(
                    icon: Icons.assignment_outlined,
                    enabled: widget.isConnected,
                    active: false,
                    onTap: () {
                      final template = buildSbarTemplate();
                      _controller.text = template;
                      _controller.selection = TextSelection.collapsed(
                        offset: template.length,
                      );
                      _focusNode.requestFocus();
                    },
                  ),
                  const SizedBox(width: 6),
                ],
                // Patient card button (handoff rooms only)
                if (widget.isHandoff && !_aiMode) ...[
                  _CircleIconBtn(
                    icon: Icons.person_add_outlined,
                    enabled: widget.isConnected,
                    active: false,
                    onTap: () async {
                      final card = await showDialog<PatientCard>(
                        context: context,
                        builder: (_) => const PatientCardInputDialog(),
                      );
                      if (card != null && widget.onSendPatientCard != null) {
                        widget.onSendPatientCard!(card);
                      }
                    },
                  ),
                  const SizedBox(width: 6),
                ],
                // File attach button (hidden in AI mode)
                if (!_aiMode && widget.onFilePick != null) ...[
                  _isUploading
                      ? Container(
                          width: 40,
                          height: 40,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: Theme.of(context).colorScheme.surfaceContainer,
                            border: Border.all(color: Theme.of(context).colorScheme.outline),
                          ),
                          child: Center(
                            child: SizedBox(
                              width: 18, height: 18,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: AppColors.primary,
                              ),
                            ),
                          ),
                        )
                      : _CircleIconBtn(
                          icon: Icons.attach_file_rounded,
                          enabled: widget.isConnected && !_isUploading,
                          onTap: _pickFile,
                        ),
                  const SizedBox(width: 6),
                ],
                // Emoji button (hidden in AI mode)
                if (!_aiMode) ...[
                  _CircleIconBtn(
                    icon: Icons.emoji_emotions_outlined,
                    enabled: widget.isConnected,
                    onTap: _showEmojiSheet,
                  ),
                  const SizedBox(width: 8),
                ],

                // Text field (pill style)
                Expanded(
                  child: Container(
                    constraints: const BoxConstraints(maxHeight: 120),
                    decoration: BoxDecoration(
                      color: cs.surfaceContainer,
                      borderRadius: BorderRadius.circular(22),
                      border: Border.all(color: cs.outline.withAlpha(80)),
                    ),
                    child: KeyboardListener(
                      focusNode: _keyboardFocusNode,
                      onKeyEvent: (event) {
                        if (kIsWeb &&
                            event is KeyDownEvent &&
                            event.logicalKey == LogicalKeyboardKey.enter &&
                            !HardwareKeyboard.instance.isShiftPressed) {
                          _send();
                        }
                      },
                      child: TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        enabled: widget.isConnected,
                        maxLines: 5,
                        minLines: 1,
                        inputFormatters: [
                          LengthLimitingTextInputFormatter(_maxLength),
                        ],
                        textInputAction: kIsWeb
                            ? TextInputAction.none
                            : TextInputAction.newline,
                        style: TextStyle(
                          color: cs.onSurface,
                          fontSize: 14,
                          height: 1.4,
                        ),
                        decoration: InputDecoration(
                          hintText: !widget.isConnected
                              ? '연결 중...'
                              : _aiMode
                                  ? 'AI에게 질문하세요...'
                                  : '메시지를 입력하세요...',
                          hintMaxLines: 1,
                          hintStyle: TextStyle(
                              color: cs.onSurfaceVariant.withAlpha(130),
                              fontSize: 14,
                              overflow: TextOverflow.ellipsis),
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          contentPadding: const EdgeInsets.symmetric(
                              horizontal: 16, vertical: 10),
                          isDense: true,
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 8),

                // Send button (animated fill on active)
                ValueListenableBuilder<TextEditingValue>(
                  valueListenable: _controller,
                  builder: (_, value, __) {
                    final canSend = widget.isConnected &&
                        (value.text.trim().isNotEmpty || _pendingFileName != null) &&
                        !(_aiMode && widget.isAiLoading) &&
                        !_isUploading;
                    return AnimatedContainer(
                      duration: const Duration(milliseconds: 180),
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: canSend
                            ? AppColors.primary
                            : cs.surfaceContainer,
                        border: Border.all(
                          color: canSend
                              ? AppColors.primary
                              : cs.outline,
                        ),
                        boxShadow: canSend
                            ? [
                                BoxShadow(
                                  color: AppColors.primary.withAlpha(70),
                                  blurRadius: 10,
                                  offset: const Offset(0, 2),
                                )
                              ]
                            : null,
                      ),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: canSend ? _send : null,
                          borderRadius: BorderRadius.circular(20),
                          child: Icon(
                            Icons.send_rounded,
                            size: 18,
                            color: canSend
                                ? Colors.white
                                : cs.onSurfaceVariant.withAlpha(130),
                          ),
                        ),
                      ),
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

String buildSbarTemplate() {
  return '[SBAR 인수인계]\n'
      '\n'
      '[S] 환자: / 병실: \n'
      '    주호소: \n'
      '\n'
      '[B] 진단: \n'
      '    이력: \n'
      '    알러지: \n'
      '\n'
      '[A] V/S: BP /  HR  RR  BT \n'
      '    현재상태: \n'
      '    주의사항: \n'
      '\n'
      '[R] 다음조치: \n'
      '    투약: \n'
      '    모니터링: ';
}

class _CircleIconBtn extends StatelessWidget {
  final IconData icon;
  final bool enabled;
  final bool active;
  final VoidCallback onTap;
  const _CircleIconBtn({
    required this.icon,
    required this.enabled,
    required this.onTap,
    this.active = false,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: active ? AppColors.secondary.withAlpha(30) : cs.surfaceContainer,
        border: Border.all(
          color: active ? AppColors.secondary : cs.outline,
          width: active ? 1.5 : 1,
        ),
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(20),
          child: Icon(
            icon,
            size: 20,
            color: active
                ? AppColors.secondary
                : enabled
                    ? cs.onSurfaceVariant
                    : cs.onSurfaceVariant.withAlpha(80),
          ),
        ),
      ),
    );
  }
}
