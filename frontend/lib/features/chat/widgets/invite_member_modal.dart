import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/constants/chat_strings.dart';
import '../../../core/constants/ui_constants.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/theme/app_theme.dart';

// ---------------------------------------------------------------------------
// Invite member modal — search users and invite to room
// ---------------------------------------------------------------------------
class InviteMemberModal extends ConsumerStatefulWidget {
  final String roomId;
  final int currentCount;

  const InviteMemberModal({super.key, required this.roomId, required this.currentCount});

  @override
  ConsumerState<InviteMemberModal> createState() => InviteMemberModalState();
}

class InviteMemberModalState extends ConsumerState<InviteMemberModal> {
  final _searchCtrl = TextEditingController();
  List<Map<String, dynamic>> _results = [];
  bool _searching = false;
  String? _error;
  String? _inviting;
  Timer? _debounce;

  @override
  void dispose() {
    _debounce?.cancel();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _search(String query) async {
    if (query.trim().isEmpty) {
      setState(() { _results = []; _error = null; });
      return;
    }
    setState(() { _searching = true; _error = null; });
    try {
      final dio = ref.read(dioClientProvider).dio;
      final resp = await dio.get('/api/users/search', queryParameters: {'q': query.trim()});
      final data = resp.data;
      List<dynamic> list = [];
      if (data is Map && data['data'] is List) list = data['data'] as List;
      if (mounted) {
        setState(() {
          _results = list.map((e) => Map<String, dynamic>.from(e as Map)).toList();
          _searching = false;
        });
      }
    } catch (_) {
      if (mounted) setState(() { _searching = false; _error = '검색에 실패했습니다.'; });
    }
  }

  Future<void> _invite(Map<String, dynamic> user) async {
    if (widget.currentCount >= UIConstants.maxParticipants) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(ChatStrings.roomFull(UIConstants.maxParticipants))),
        );
      }
      return;
    }
    final username = user['username']?.toString() ?? '';
    setState(() => _inviting = username);
    try {
      final dio = ref.read(dioClientProvider).dio;
      await dio.post('/api/chat/rooms/${widget.roomId}/invite',
          data: {'targetUsername': username});
      if (mounted) {
        Navigator.of(context).pop();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('$username님을 초대했습니다.')),
        );
      }
    } catch (_) {
      if (mounted) {
        setState(() => _inviting = null);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('초대에 실패했습니다.')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final isFull = widget.currentCount >= UIConstants.maxParticipants;

    return Padding(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).viewInsets.bottom,
      ),
      child: Container(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Center(
              child: Container(
                width: 36, height: 4,
                decoration: BoxDecoration(
                  color: cs.outline.withAlpha(80),
                  borderRadius: BorderRadius.circular(2),
                ),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Icon(Icons.person_add_outlined, size: 20, color: cs.primary),
                const SizedBox(width: 8),
                Text('멤버 초대',
                    style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: cs.onSurface)),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: isFull ? cs.errorContainer : cs.surfaceContainer,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    '${widget.currentCount}/${UIConstants.maxParticipants}명',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: isFull ? cs.onErrorContainer : cs.onSurfaceVariant,
                    ),
                  ),
                ),
              ],
            ),
            if (isFull)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '채팅방이 만석입니다. 멤버가 나간 후 초대할 수 있습니다.',
                  style: TextStyle(fontSize: 12, color: cs.error),
                ),
              ),
            const SizedBox(height: 12),
            TextField(
              controller: _searchCtrl,
              autofocus: true,
              decoration: InputDecoration(
                hintText: '사용자 이름 검색...',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _searching
                    ? const Padding(
                        padding: EdgeInsets.all(12),
                        child: SizedBox(width: 16, height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2)))
                    : null,
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(12)),
                contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                isDense: true,
              ),
              onChanged: (v) {
                _debounce?.cancel();
                _debounce = Timer(const Duration(milliseconds: 300), () => _search(v));
              },
            ),
            const SizedBox(height: 8),
            if (_error != null)
              Text(_error!, style: TextStyle(fontSize: 12, color: cs.error))
            else if (_results.isEmpty && _searchCtrl.text.isNotEmpty && !_searching)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Center(
                  child: Text('검색 결과가 없습니다.',
                      style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant)),
                ),
              )
            else
              ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 280),
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: _results.length,
                  itemBuilder: (_, i) {
                    final user = _results[i];
                    final name = user['username']?.toString() ?? '';
                    final isInviting = _inviting == name;
                    return ListTile(
                      dense: true,
                      leading: CircleAvatar(
                        radius: 18,
                        backgroundColor: AppColors.avatarPalette[
                            name.hashCode.abs() % AppColors.avatarPalette.length].withAlpha(180),
                        child: Text(
                          name.isNotEmpty ? name[0].toUpperCase() : '?',
                          style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w700, fontSize: 13),
                        ),
                      ),
                      title: Text(name, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w500)),
                      trailing: FilledButton(
                        onPressed: (isInviting || isFull)
                            ? null
                            : () => _invite(user),
                        style: FilledButton.styleFrom(
                          minimumSize: const Size(56, 32),
                          padding: const EdgeInsets.symmetric(horizontal: 14),
                        ),
                        child: isInviting
                            ? const SizedBox(width: 14, height: 14,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                            : const Text('초대', style: TextStyle(fontSize: 13)),
                      ),
                    );
                  },
                ),
              ),
          ],
        ),
      ),
    );
  }
}
