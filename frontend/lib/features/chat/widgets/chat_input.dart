import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:file_picker/file_picker.dart';
import '../../../core/theme/app_theme.dart';
import '../../../shared/models/chat_message.dart';
import '../../../shared/models/patient_card.dart';
import 'patient_card_input_dialog.dart';
import 'sbar_input_dialog.dart';

class ChatInput extends StatefulWidget {
  final bool isConnected;
  final bool isAiLoading;
  final bool isHandoff;
  final void Function(String content, {String priority}) onSend;
  final Future<void> Function(String question)? onAskAi;
  final void Function(PatientCard card)? onSendPatientCard;
  final Future<void> Function(String fileName, Uint8List bytes, String mimeType, String content)? onFilePick;
  final ChatMessage? replyTarget;
  final VoidCallback? onCancelReply;
  final VoidCallback? onTyping;
  final Future<List<Map<String, dynamic>>> Function(String query)? onMentionSearch;

  const ChatInput({
    super.key,
    required this.isConnected,
    this.isAiLoading = false,
    this.isHandoff = false,
    required this.onSend,
    this.onAskAi,
    this.onSendPatientCard,
    this.onFilePick,
    this.replyTarget,
    this.onCancelReply,
    this.onTyping,
    this.onMentionSearch,
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
  // Mention autocomplete
  List<Map<String, dynamic>> _mentionSuggestions = [];
  bool _showMentions = false;
  String _mentionQuery = '';

  static const _emojiList = [
    '😀', '😂', '😍', '🥰', '😎', '🤔',
    '👍', '👎', '👋', '🙏', '💪', '🎉',
    '❤️', '🔥', '⭐', '💯', '✅', '❌',
    '😊', '😢', '😡', '🤣', '😱', '🥳',
    '👀', '💬', '📌', '🚀', '💡', '🎯',
    '☕', '🍕', '🎵', '📝', '⏰', '🌟',
  ];

  void _checkMention(String text) {
    final cursor = _controller.selection.baseOffset;
    if (cursor <= 0) { setState(() => _showMentions = false); return; }
    final before = text.substring(0, cursor);
    final atIdx = before.lastIndexOf('@');
    if (atIdx < 0 || (atIdx > 0 && before[atIdx - 1] != ' ' && before[atIdx - 1] != '\n')) {
      setState(() => _showMentions = false);
      return;
    }
    final query = before.substring(atIdx + 1);
    if (query.contains(' ') || query.length > 20) {
      setState(() => _showMentions = false);
      return;
    }
    _mentionQuery = query;
    if (query.length >= 1 && widget.onMentionSearch != null) {
      widget.onMentionSearch!(query).then((results) {
        if (mounted) setState(() { _mentionSuggestions = results; _showMentions = results.isNotEmpty; });
      });
    } else {
      setState(() => _showMentions = false);
    }
  }

  void _insertMention(String username) {
    final text = _controller.text;
    final cursor = _controller.selection.baseOffset;
    final before = text.substring(0, cursor);
    final atIdx = before.lastIndexOf('@');
    if (atIdx < 0) return;
    final after = text.substring(cursor);
    final newText = '${text.substring(0, atIdx)}@$username $after';
    _controller.value = TextEditingValue(
      text: newText,
      selection: TextSelection.collapsed(offset: atIdx + username.length + 2),
    );
    setState(() => _showMentions = false);
  }

  void _clearController() {
    _controller.value = const TextEditingValue(
      text: '',
      selection: TextSelection.collapsed(offset: 0),
      composing: TextRange.empty,
    );
  }

  Future<void> _send() async {
    if (_isSending) return;
    final text = _controller.text.trim();
    final hasFile = _pendingFileName != null && _pendingFileBytes != null;
    if (text.isEmpty && !hasFile) return;
    if (!widget.isConnected) return;
    _isSending = true;
    try {
      if (_aiMode && widget.onAskAi != null && text.isNotEmpty) {
        _clearController();
        _focusNode.requestFocus();
        try {
          await widget.onAskAi!(text);
        } catch (_) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('AI 요청에 실패했습니다. 잠시 후 다시 시도해주세요.')));
        }
      } else if (hasFile && widget.onFilePick != null) {
        setState(() => _isUploading = true);
        try {
          await widget.onFilePick!(
            _pendingFileName!, _pendingFileBytes!, _pendingFileMimeType ?? 'application/octet-stream', text,
          );
          // 성공 후에만 입력 클리어
          if (mounted) {
            _clearController();
            setState(() {
              _pendingFileName = null;
              _pendingFileBytes = null;
              _pendingFileMimeType = null;
            });
          }
        } catch (_) {
          if (!mounted) return;
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('파일 업로드에 실패했습니다. 잠시 후 다시 시도해주세요.')));
        } finally {
          if (mounted) setState(() => _isUploading = false);
        }
        _focusNode.requestFocus();
      } else if (text.isNotEmpty) {
        _clearController();
        widget.onSend(text, priority: _priority);
        if (widget.isHandoff) setState(() => _priority = 'ROUTINE');
        _focusNode.requestFocus();
      }
    } finally {
      _isSending = false;
    }
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
                          composing: TextRange.empty,
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
      if (bytes == null || bytes.isEmpty) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('파일을 읽을 수 없습니다. 다른 파일을 선택해주세요.')));
        }
        return;
      }

      final ext = file.extension?.toLowerCase() ?? '';
      final mimeType = _extToMime(ext);

      setState(() {
        _pendingFileName = file.name;
        _pendingFileBytes = bytes;
        _pendingFileMimeType = mimeType;
      });
      _focusNode.requestFocus();
    } catch (_) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('파일을 선택할 수 없습니다.')),
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
    final screenWidth = MediaQuery.of(context).size.width;
    final isNarrow = screenWidth < 600;
    final btnSize = isNarrow ? 34.0 : 40.0;
    final btnGap = isNarrow ? 4.0 : 6.0;

    return Container(
      padding: EdgeInsets.fromLTRB(isNarrow ? 8 : 12, 8, isNarrow ? 8 : 12, 8),
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
            // Mention suggestions
            if (_showMentions && _mentionSuggestions.isNotEmpty)
              Container(
                constraints: const BoxConstraints(maxHeight: 150),
                margin: const EdgeInsets.only(bottom: 4),
                decoration: BoxDecoration(
                  color: cs.surfaceContainer,
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: cs.outline.withAlpha(80)),
                ),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: _mentionSuggestions.length,
                  itemBuilder: (_, i) {
                    final user = _mentionSuggestions[i];
                    return ListTile(
                      dense: true,
                      leading: CircleAvatar(radius: 14, child: Text((user['username'] ?? '?')[0].toUpperCase(), style: const TextStyle(fontSize: 12))),
                      title: Text(user['username'] ?? '', style: const TextStyle(fontSize: 13)),
                      onTap: () => _insertMention(user['username'] ?? ''),
                    );
                  },
                ),
              ),
            // Reply target banner
            if (widget.replyTarget != null)
              Container(
                margin: const EdgeInsets.only(bottom: 6),
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                decoration: BoxDecoration(
                  color: cs.primaryContainer.withAlpha(60),
                  borderRadius: BorderRadius.circular(12),
                  border: Border(
                    left: BorderSide(color: AppColors.primary, width: 3),
                  ),
                ),
                child: Row(
                  children: [
                    Icon(Icons.reply_rounded, size: 16, color: AppColors.primary),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            widget.replyTarget!.username,
                            style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700, color: AppColors.primary),
                          ),
                          Text(
                            widget.replyTarget!.content,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                          ),
                        ],
                      ),
                    ),
                    GestureDetector(
                      onTap: widget.onCancelReply,
                      child: Icon(Icons.close, size: 18, color: cs.onSurfaceVariant),
                    ),
                  ],
                ),
              ),

            // Character counter (shows only near limit)
            ValueListenableBuilder<TextEditingValue>(
              valueListenable: _controller,
              builder: (_, value, __) {
                final len = value.text.length;
                if (len == 0) return const SizedBox.shrink();
                return Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: Text(
                      '$len/$_maxLength',
                      style: TextStyle(
                        fontSize: 11,
                        color: len >= _warnThreshold
                            ? (len >= _maxLength ? AppColors.error : const Color(0xFFF57C00))
                            : cs.onSurfaceVariant.withAlpha(120),
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
                    width: btnSize,
                    height: btnSize,
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
                                    fontSize: isNarrow ? 10 : 11,
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
                  SizedBox(width: btnGap),
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
                      height: btnSize,
                      padding: EdgeInsets.symmetric(horizontal: isNarrow ? 5 : 8),
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
                  SizedBox(width: btnGap),
                ],
                // Handoff tools (SBAR + Patient card) — combined menu
                if (widget.isHandoff && !_aiMode) ...[
                  PopupMenuButton<String>(
                    enabled: widget.isConnected,
                    padding: EdgeInsets.zero,
                    constraints: BoxConstraints(minWidth: btnSize, minHeight: btnSize),
                    icon: Icon(
                      Icons.add_circle_outline,
                      size: 22,
                      color: widget.isConnected
                          ? Theme.of(context).colorScheme.onSurfaceVariant
                          : Theme.of(context).colorScheme.onSurfaceVariant.withAlpha(80),
                    ),
                    onSelected: (value) async {
                      if (value == 'sbar') {
                        showDialog(
                          context: context,
                          builder: (_) => SbarInputDialog(
                            onSend: (content) {
                              widget.onSend(content, priority: _priority);
                            },
                          ),
                        );
                      } else if (value == 'patient_card') {
                        final card = await showDialog<PatientCard>(
                          context: context,
                          builder: (_) => const PatientCardInputDialog(),
                        );
                        if (card != null && widget.onSendPatientCard != null) {
                          widget.onSendPatientCard!(card);
                        }
                      }
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(
                        value: 'sbar',
                        child: Row(
                          children: [
                            Icon(Icons.assignment_outlined, size: 20),
                            SizedBox(width: 8),
                            Text('SBAR 인수인계'),
                          ],
                        ),
                      ),
                      PopupMenuItem(
                        value: 'patient_card',
                        child: Row(
                          children: [
                            Icon(Icons.person_add_outlined, size: 20),
                            SizedBox(width: 8),
                            Text('환자 카드 전송'),
                          ],
                        ),
                      ),
                    ],
                  ),
                  SizedBox(width: btnGap),
                ],
                // File attach button (hidden in AI mode)
                if (!_aiMode && widget.onFilePick != null) ...[
                  _isUploading
                      ? Container(
                          width: btnSize,
                          height: btnSize,
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
                          size: btnSize,
                        ),
                  SizedBox(width: btnGap),
                ],
                // Emoji button (hidden in AI mode)
                if (!_aiMode) ...[
                  _CircleIconBtn(
                    icon: Icons.emoji_emotions_outlined,
                    enabled: widget.isConnected,
                    onTap: _showEmojiSheet,
                    size: btnSize,
                  ),
                  SizedBox(width: isNarrow ? 6 : 8),
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
                            !HardwareKeyboard.instance.isShiftPressed &&
                            !_controller.value.composing.isValid) {
                          _send();
                        }
                      },
                      child: TextField(
                        controller: _controller,
                        focusNode: _focusNode,
                        enabled: widget.isConnected,
                        onChanged: (text) {
                          widget.onTyping?.call();
                          _checkMention(text);
                        },
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
                SizedBox(width: isNarrow ? 6 : 8),

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
                      width: btnSize,
                      height: btnSize,
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
  final double size;
  const _CircleIconBtn({
    required this.icon,
    required this.enabled,
    required this.onTap,
    this.active = false,
    this.size = 40,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final iconSize = size < 38 ? 18.0 : 20.0;
    return Container(
      width: size,
      height: size,
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
          borderRadius: BorderRadius.circular(size / 2),
          child: Icon(
            icon,
            size: iconSize,
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
