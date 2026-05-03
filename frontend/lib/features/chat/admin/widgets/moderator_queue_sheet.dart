import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../shared/models/message_report.dart';
import '../room_admin_api_provider.dart';
import '../room_reports_provider.dart';

Future<void> showModeratorQueueSheet(BuildContext context, String roomId) {
  final isMobile = MediaQuery.of(context).size.width < 600;
  if (isMobile) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => SizedBox(
        height: MediaQuery.of(context).size.height * 0.85,
        child: _ModeratorQueueBody(roomId: roomId),
      ),
    );
  }
  return showDialog(
    context: context,
    builder: (_) => Dialog(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 600, maxHeight: 720),
        child: _ModeratorQueueBody(roomId: roomId),
      ),
    ),
  );
}

class _ModeratorQueueBody extends ConsumerWidget {
  final String roomId;
  const _ModeratorQueueBody({required this.roomId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reportsAsync = ref.watch(
        roomReportsProvider((roomId: roomId, status: ReportStatus.pending)));

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 12, 8),
          child: Row(
            children: [
              const Icon(Icons.shield_outlined, size: 22),
              const SizedBox(width: 10),
              const Text('방 관리 — 신고 처리',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600)),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.refresh, size: 22),
                tooltip: '새로고침',
                onPressed: () => ref.invalidate(
                    roomReportsProvider((roomId: roomId, status: ReportStatus.pending))),
              ),
              IconButton(
                icon: const Icon(Icons.close, size: 22),
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: reportsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text('신고 큐를 불러오지 못했습니다.\n$e',
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 13)),
              ),
            ),
            data: (reports) {
              if (reports.isEmpty) {
                return const Center(
                  child: Padding(
                    padding: EdgeInsets.all(32),
                    child: Text('처리 대기 중인 신고가 없습니다.',
                        style: TextStyle(fontSize: 14, color: Colors.grey)),
                  ),
                );
              }
              return ListView.separated(
                padding: const EdgeInsets.all(12),
                itemCount: reports.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (_, i) => _ReportCard(roomId: roomId, report: reports[i]),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _ReportCard extends ConsumerWidget {
  final String roomId;
  final MessageReport report;

  const _ReportCard({required this.roomId, required this.report});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: BorderSide(color: Colors.grey.withAlpha(60)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                _ReasonChip(reason: report.reason),
                const SizedBox(width: 8),
                Text('작성자: ${report.messageAuthor}',
                    style: const TextStyle(fontSize: 12, color: Colors.grey)),
                const Spacer(),
                Text(_formatTime(report.createdAt),
                    style: const TextStyle(fontSize: 11, color: Colors.grey)),
              ],
            ),
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: Colors.grey.withAlpha(20),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                report.messageContent,
                style: const TextStyle(fontSize: 13),
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (report.comment != null && report.comment!.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text('신고자 코멘트: ${report.comment}',
                  style: const TextStyle(fontSize: 11, color: Colors.grey)),
            ],
            const SizedBox(height: 6),
            Text('신고자: ${report.reportedBy}',
                style: const TextStyle(fontSize: 11, color: Colors.grey)),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(
                  onPressed: () => _resolve(context, ref, ReportStatus.dismissed),
                  child: const Text('무시'),
                ),
                const SizedBox(width: 4),
                TextButton(
                  onPressed: () => _resolve(context, ref, ReportStatus.resolved),
                  child: const Text('처리됨'),
                ),
                const SizedBox(width: 4),
                FilledButton.tonal(
                  onPressed: () => _banAuthor(context, ref),
                  style: FilledButton.styleFrom(
                    foregroundColor: Colors.red,
                  ),
                  child: const Text('작성자 차단'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _resolve(BuildContext context, WidgetRef ref, ReportStatus newStatus) async {
    final api = ref.read(roomAdminApiProvider);
    final messenger = ScaffoldMessenger.of(context);
    try {
      await api.updateReportStatus(report.id, newStatus);
      ref.invalidate(roomReportsProvider((roomId: roomId, status: ReportStatus.pending)));
      if (context.mounted) {
        messenger.showSnackBar(SnackBar(
          content: Text(newStatus == ReportStatus.resolved ? '처리됨으로 표시했습니다.' : '신고를 무시했습니다.'),
          duration: const Duration(seconds: 2),
        ));
      }
    } catch (e) {
      if (context.mounted) {
        messenger.showSnackBar(SnackBar(content: Text('실패: $e')));
      }
    }
  }

  Future<void> _banAuthor(BuildContext context, WidgetRef ref) async {
    final confirm = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        content: Text('${report.messageAuthor} 님을 차단하시겠어요?\n강퇴 + 재입장 차단됩니다.'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('취소')),
          TextButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('차단', style: TextStyle(color: Colors.red))),
        ],
      ),
    );
    if (confirm != true) return;

    final api = ref.read(roomAdminApiProvider);
    final messenger = ScaffoldMessenger.of(context);
    try {
      // ban API는 userId 필요. ReportDto는 messageAuthor(username)만 가짐 — listMembers에서 username으로 매칭.
      final members = await api.listMembers(roomId);
      final author = members.firstWhere(
        (m) => m.username == report.messageAuthor,
        orElse: () => throw Exception('작성자가 더 이상 방에 없습니다.'),
      );
      await api.banUser(roomId, author.userId, '신고 처리');
      // 신고도 자동 RESOLVED 처리
      await api.updateReportStatus(report.id, ReportStatus.resolved);
      ref.invalidate(roomReportsProvider((roomId: roomId, status: ReportStatus.pending)));
      if (context.mounted) {
        messenger.showSnackBar(const SnackBar(content: Text('차단되었고 신고는 처리됨으로 마감.')));
      }
    } catch (e) {
      if (context.mounted) {
        messenger.showSnackBar(SnackBar(content: Text('실패: $e')));
      }
    }
  }

  String _formatTime(DateTime t) {
    final now = DateTime.now();
    final diff = now.difference(t);
    if (diff.inMinutes < 1) return '방금';
    if (diff.inMinutes < 60) return '${diff.inMinutes}분 전';
    if (diff.inHours < 24) return '${diff.inHours}시간 전';
    return '${t.month}/${t.day}';
  }
}

class _ReasonChip extends StatelessWidget {
  final ReportReason reason;
  const _ReasonChip({required this.reason});

  @override
  Widget build(BuildContext context) {
    final color = switch (reason) {
      ReportReason.spam => Colors.blueGrey,
      ReportReason.harassment => Colors.red,
      ReportReason.inappropriate => Colors.orange,
      ReportReason.other => Colors.grey,
    };
    final label = switch (reason) {
      ReportReason.spam => '스팸',
      ReportReason.harassment => '괴롭힘',
      ReportReason.inappropriate => '부적절',
      ReportReason.other => '기타',
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withAlpha(30),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withAlpha(120)),
      ),
      child: Text(label,
          style: TextStyle(fontSize: 10, color: color, fontWeight: FontWeight.w600)),
    );
  }
}
