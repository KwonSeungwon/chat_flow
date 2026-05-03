import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../shared/models/message_report.dart';
import 'room_admin_api.dart';
import 'room_admin_api_provider.dart';

/// Key for the reports provider: (roomId, status).
typedef ReportsKey = ({String roomId, ReportStatus status});

final roomReportsProvider = StateNotifierProvider.family<
    RoomReportsNotifier, AsyncValue<List<MessageReport>>, ReportsKey>(
  (ref, key) {
    final api = ref.watch(roomAdminApiProvider);
    return RoomReportsNotifier(api, key.roomId, key.status);
  },
);

class RoomReportsNotifier
    extends StateNotifier<AsyncValue<List<MessageReport>>> {
  final RoomAdminApi _api;
  final String _roomId;
  final ReportStatus _status;

  RoomReportsNotifier(this._api, this._roomId, this._status)
      : super(const AsyncValue.loading()) {
    fetch();
  }

  Future<void> fetch() async {
    try {
      final reports = await _api.listReports(_roomId, status: _status);
      if (!mounted) return;
      state = AsyncValue.data(reports);
    } catch (e, st) {
      debugPrint('[RoomReportsNotifier] fetch error: $e');
      if (!mounted) return;
      state = AsyncValue.error(e, st);
    }
  }
}
