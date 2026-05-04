import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../command_action.dart';
import '../command_palette_provider.dart';

/// Show the command palette modal overlay.
///
/// Call from any context that has [ProviderScope] and [Navigator] ancestors.
Future<void> showCommandPalette(BuildContext context) {
  return showDialog<void>(
    context: context,
    barrierColor: Colors.black54,
    builder: (_) => const _CommandPaletteDialog(),
  );
}

// ---------------------------------------------------------------------------
// Dialog shell — centering + constraints
// ---------------------------------------------------------------------------

class _CommandPaletteDialog extends StatelessWidget {
  const _CommandPaletteDialog();

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: const Alignment(0.0, -0.3),
      child: Material(
        type: MaterialType.transparency,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 560, maxHeight: 480),
          child: const _CommandPaletteBody(),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Body — search input + result list + keyboard nav
// ---------------------------------------------------------------------------

class _CommandPaletteBody extends ConsumerStatefulWidget {
  const _CommandPaletteBody();

  @override
  ConsumerState<_CommandPaletteBody> createState() =>
      _CommandPaletteBodyState();
}

class _CommandPaletteBodyState extends ConsumerState<_CommandPaletteBody> {
  final _controller = TextEditingController();
  final _focusNode = FocusNode();
  int _highlightIndex = 0;

  @override
  void initState() {
    super.initState();
    // Autofocus the search field after the first frame
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _focusNode.requestFocus();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _onQueryChanged(String value) {
    ref.read(commandPaletteProvider.notifier).updateQuery(value);
    setState(() => _highlightIndex = 0);
  }

  void _executeSelected(List<CommandAction> results) {
    if (results.isEmpty) return;
    final action = results[_highlightIndex.clamp(0, results.length - 1)];
    Navigator.of(context).pop();
    action.execute(context, ref);
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }
    final results = ref.read(commandPaletteProvider).results;
    final key = event.logicalKey;

    if (key == LogicalKeyboardKey.arrowDown) {
      setState(() {
        _highlightIndex = results.isEmpty
            ? 0
            : (_highlightIndex + 1) % results.length;
      });
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.arrowUp) {
      setState(() {
        _highlightIndex = results.isEmpty
            ? 0
            : (_highlightIndex - 1 + results.length) % results.length;
      });
      return KeyEventResult.handled;
    }
    if (key == LogicalKeyboardKey.enter) {
      _executeSelected(results);
      return KeyEventResult.handled;
    }
    // Esc is handled by showDialog barrier + WillPopScope, but also handle explicitly
    if (key == LogicalKeyboardKey.escape) {
      Navigator.of(context).pop();
      return KeyEventResult.handled;
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final paletteState = ref.watch(commandPaletteProvider);
    final results = paletteState.results;
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    // Ensure highlight index stays in bounds
    if (_highlightIndex >= results.length && results.isNotEmpty) {
      _highlightIndex = results.length - 1;
    }

    return Focus(
      onKeyEvent: _handleKeyEvent,
      child: Container(
        decoration: BoxDecoration(
          color: colorScheme.surface,
          borderRadius: BorderRadius.circular(12),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withAlpha(60),
              blurRadius: 20,
              offset: const Offset(0, 8),
            ),
          ],
          border: Border.all(
            color: colorScheme.outlineVariant.withAlpha(80),
          ),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Search input
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
              child: TextField(
                controller: _controller,
                focusNode: _focusNode,
                autofocus: true,
                onChanged: _onQueryChanged,
                decoration: InputDecoration(
                  hintText: '방 이동, 사용자 찾기, 빠른 실행...',
                  prefixIcon: const Icon(Icons.search, size: 20),
                  suffixIcon: paletteState.isSearchingUsers
                      ? const Padding(
                          padding: EdgeInsets.all(12),
                          child: SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : null,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(8),
                    borderSide: BorderSide.none,
                  ),
                  filled: true,
                  fillColor: colorScheme.surfaceContainerHighest,
                  contentPadding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  isDense: true,
                ),
                style: const TextStyle(fontSize: 14),
              ),
            ),
            // Divider
            Divider(height: 1, color: colorScheme.outlineVariant.withAlpha(60)),
            // Results list
            if (results.isEmpty && paletteState.query.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 32),
                child: Text(
                  '결과 없음',
                  style: TextStyle(
                    color: colorScheme.onSurfaceVariant,
                    fontSize: 13,
                  ),
                ),
              )
            else
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  itemCount: results.length,
                  itemBuilder: (context, index) {
                    final action = results[index];
                    final isHighlighted = index == _highlightIndex;
                    return _CommandResultTile(
                      action: action,
                      isHighlighted: isHighlighted,
                      onTap: () {
                        Navigator.of(context).pop();
                        action.execute(context, ref);
                      },
                      onHover: () {
                        if (_highlightIndex != index) {
                          setState(() => _highlightIndex = index);
                        }
                      },
                    );
                  },
                ),
              ),
            // Footer hint
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 8),
              child: Row(
                children: [
                  const _KeyHint(label: '↑↓'),
                  const SizedBox(width: 4),
                  Text('이동', style: TextStyle(fontSize: 11, color: colorScheme.onSurfaceVariant)),
                  const SizedBox(width: 12),
                  const _KeyHint(label: 'Enter'),
                  const SizedBox(width: 4),
                  Text('실행', style: TextStyle(fontSize: 11, color: colorScheme.onSurfaceVariant)),
                  const SizedBox(width: 12),
                  const _KeyHint(label: 'Esc'),
                  const SizedBox(width: 4),
                  Text('닫기', style: TextStyle(fontSize: 11, color: colorScheme.onSurfaceVariant)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Result tile
// ---------------------------------------------------------------------------

class _CommandResultTile extends StatelessWidget {
  final CommandAction action;
  final bool isHighlighted;
  final VoidCallback onTap;
  final VoidCallback onHover;

  const _CommandResultTile({
    required this.action,
    required this.isHighlighted,
    required this.onTap,
    required this.onHover,
  });

  Color _categoryColor(CommandAction action, ColorScheme cs) {
    return switch (action) {
      GoToRoomAction() => cs.primary,
      ViewProfileAction() => cs.tertiary,
      QuickAction() => cs.secondary,
    };
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final catColor = _categoryColor(action, colorScheme);

    return MouseRegion(
      onEnter: (_) => onHover(),
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          color: isHighlighted
              ? colorScheme.primaryContainer.withAlpha(80)
              : Colors.transparent,
          child: Row(
            children: [
              Icon(action.icon, size: 18, color: catColor),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      action.title,
                      style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500),
                      overflow: TextOverflow.ellipsis,
                    ),
                    if (action.subtitle != null)
                      Text(
                        action.subtitle!,
                        style: TextStyle(
                          fontSize: 11,
                          color: catColor.withAlpha(180),
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Keyboard hint badge
// ---------------------------------------------------------------------------

class _KeyHint extends StatelessWidget {
  final String label;
  const _KeyHint({required this.label});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
      decoration: BoxDecoration(
        color: cs.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(3),
        border: Border.all(color: cs.outline.withAlpha(60)),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontSize: 10,
          fontFamily: 'monospace',
          color: cs.onSurfaceVariant,
        ),
      ),
    );
  }
}
