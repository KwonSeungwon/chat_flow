import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import '../../shared/models/chat_message.dart';
import 'search_provider.dart';

class SearchPage extends ConsumerStatefulWidget {
  const SearchPage({super.key});

  @override
  ConsumerState<SearchPage> createState() => _SearchPageState();
}

class _SearchPageState extends ConsumerState<SearchPage> {
  final _queryCtrl = TextEditingController();

  @override
  void dispose() {
    _queryCtrl.dispose();
    super.dispose();
  }

  void _doSearch() {
    final q = _queryCtrl.text.trim();
    if (q.isEmpty) return;
    ref.read(searchProvider.notifier).search(q);
    FocusScope.of(context).unfocus();
  }

  String _formatTimestamp(String timestamp) {
    try {
      final dt = DateTime.parse(timestamp);
      final local = dt.toLocal();
      return DateFormat('yyyy-MM-dd HH:mm').format(local);
    } catch (_) {
      return '';
    }
  }

  /// Highlight [query] within [text] using RichText / TextSpan.
  Widget _highlightedText(String text, String query) {
    if (query.isEmpty) return Text(text, maxLines: 3, overflow: TextOverflow.ellipsis);

    final lowerText = text.toLowerCase();
    final lowerQuery = query.toLowerCase();
    final spans = <TextSpan>[];
    int start = 0;

    while (true) {
      final idx = lowerText.indexOf(lowerQuery, start);
      if (idx == -1) {
        spans.add(TextSpan(text: text.substring(start)));
        break;
      }
      if (idx > start) {
        spans.add(TextSpan(text: text.substring(start, idx)));
      }
      spans.add(
        TextSpan(
          text: text.substring(idx, idx + query.length),
          style: TextStyle(
            backgroundColor: Theme.of(context).colorScheme.primaryContainer,
            fontWeight: FontWeight.bold,
          ),
        ),
      );
      start = idx + query.length;
    }

    return RichText(
      maxLines: 3,
      overflow: TextOverflow.ellipsis,
      text: TextSpan(
        style: DefaultTextStyle.of(context).style.copyWith(fontSize: 14),
        children: spans,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final searchState = ref.watch(searchProvider);
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;

    ref.listen<SearchState>(searchProvider, (prev, next) {
      if (next.error != null && next.error != prev?.error) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('검색에 실패했습니다. 잠시 후 다시 시도해주세요.'), duration: Duration(seconds: 3)),
        );
      }
    });

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.canPop() ? context.pop() : context.go('/chat'),
        ),
        title: const Text('메시지 검색'),
      ),
      body: Column(
        children: [
          // Search bar
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
            color: colorScheme.surfaceContainer,
            child: Row(
              children: [
                Expanded(
                  child: Container(
                    height: 48,
                    decoration: BoxDecoration(
                      color: colorScheme.surfaceContainerHigh,
                      borderRadius: BorderRadius.circular(24),
                      border: Border.all(color: colorScheme.outline.withAlpha(80), width: 1),
                    ),
                    child: TextField(
                      controller: _queryCtrl,
                      autofocus: true,
                      decoration: InputDecoration(
                        hintText: '검색어를 입력하세요...',
                        hintStyle: TextStyle(color: colorScheme.onSurfaceVariant.withAlpha(130), fontSize: 15),
                        prefixIcon: Padding(
                          padding: const EdgeInsets.only(left: 14, right: 8),
                          child: Icon(Icons.search, size: 22, color: colorScheme.onSurfaceVariant.withAlpha(130)),
                        ),
                        prefixIconConstraints: const BoxConstraints(minWidth: 44, minHeight: 44),
                        border: InputBorder.none,
                        enabledBorder: InputBorder.none,
                        focusedBorder: InputBorder.none,
                        contentPadding: const EdgeInsets.symmetric(horizontal: 0, vertical: 14),
                      ),
                      style: TextStyle(color: colorScheme.onSurface, fontSize: 15),
                      cursorColor: colorScheme.onSurface,
                      textInputAction: TextInputAction.search,
                      onSubmitted: (_) => _doSearch(),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                SizedBox(
                  height: 48,
                  child: ElevatedButton(
                    onPressed: searchState.isLoading ? null : _doSearch,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: colorScheme.primary,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(24),
                      ),
                      elevation: 0,
                    ),
                    child: searchState.isLoading
                        ? const SizedBox(
                            width: 18, height: 18,
                            child: CircularProgressIndicator(
                              strokeWidth: 2, color: Colors.white),
                          )
                        : const Text('검색', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                  ),
                ),
              ],
            ),
          ),

          // Results
          Expanded(
            child: _buildResults(searchState, colorScheme, theme),
          ),
        ],
      ),
    );
  }

  Widget _buildResults(SearchState searchState, ColorScheme colorScheme, ThemeData theme) {
    if (searchState.isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (!searchState.hasSearched) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search, size: 48, color: colorScheme.onSurfaceVariant.withAlpha(100)),
            const SizedBox(height: 12),
            Text(
              '검색어를 입력하여\n메시지를 찾아보세요',
              textAlign: TextAlign.center,
              style: TextStyle(color: colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      );
    }

    if (searchState.results.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.search_off, size: 48, color: colorScheme.onSurfaceVariant.withAlpha(100)),
            const SizedBox(height: 12),
            Text(
              '검색 결과가 없습니다',
              style: TextStyle(color: colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
          child: Text(
            '총 ${searchState.total}개의 결과',
            style: theme.textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            itemCount: searchState.results.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, index) {
              final msg = searchState.results[index];
              return _SearchResultTile(
                msg: msg,
                query: _queryCtrl.text.trim(),
                formattedTime: _formatTimestamp(msg.timestamp),
                highlightedContent: _highlightedText(msg.content, _queryCtrl.text.trim()),
                onTap: () {
                  if (msg.chatRoomId.isNotEmpty) {
                    final msgId = msg.messageId ?? '';
                    final uri = msgId.isNotEmpty
                        ? '/chat/${msg.chatRoomId}?messageId=$msgId'
                        : '/chat/${msg.chatRoomId}';
                    context.push(uri);
                  }
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class _SearchResultTile extends StatelessWidget {
  final ChatMessage msg;
  final String query;
  final String formattedTime;
  final Widget highlightedContent;
  final VoidCallback onTap;

  const _SearchResultTile({
    required this.msg,
    required this.query,
    required this.formattedTime,
    required this.highlightedContent,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      leading: CircleAvatar(
        radius: 18,
        backgroundColor: colorScheme.primaryContainer,
        child: Text(
          msg.username.isNotEmpty ? msg.username[0].toUpperCase() : '?',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: colorScheme.onPrimaryContainer,
          ),
        ),
      ),
      title: Row(
        children: [
          Text(
            msg.username,
            style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 13),
          ),
          const SizedBox(width: 8),
          Text(
            formattedTime,
            style: TextStyle(
              fontSize: 11,
              color: colorScheme.onSurfaceVariant,
            ),
          ),
          if (msg.chatRoomId.isNotEmpty) ...[
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: colorScheme.secondaryContainer,
                borderRadius: BorderRadius.circular(4),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.forum_outlined,
                      size: 10, color: colorScheme.onSecondaryContainer),
                  const SizedBox(width: 3),
                  Text(
                    '채팅방',
                    style: TextStyle(
                      fontSize: 10,
                      color: colorScheme.onSecondaryContainer,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ],
      ),
      subtitle: Padding(
        padding: const EdgeInsets.only(top: 4),
        child: highlightedContent,
      ),
      onTap: onTap,
    );
  }
}
