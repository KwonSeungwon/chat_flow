import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/admin/room_admin_api.dart';
import 'package:chatflow/shared/models/message_report.dart';
import 'package:chatflow/shared/models/room_role.dart';

/// Simple interceptor that captures requests and returns canned responses.
class _MockInterceptor extends Interceptor {
  final List<RequestOptions> captured = [];
  Object? responseData;
  int statusCode = 200;
  DioException? errorToThrow;

  _MockInterceptor();

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    captured.add(options);
    if (errorToThrow != null) {
      handler.reject(errorToThrow!);
      return;
    }
    handler.resolve(Response(
      requestOptions: options,
      data: responseData,
      statusCode: statusCode,
    ));
  }
}

void main() {
  late Dio dio;
  late _MockInterceptor mock;
  late RoomAdminApi api;

  setUp(() {
    dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    mock = _MockInterceptor();
    dio.interceptors.add(mock);
    api = RoomAdminApi(dio);
  });

  group('listMembers', () {
    test('sends GET to correct path and parses response', () async {
      mock.responseData = [
        {'userId': 'u1', 'username': 'Alice', 'role': 'OWNER'},
        {'userId': 'u2', 'username': 'Bob', 'role': 'MEMBER'},
      ];
      final members = await api.listMembers('room-1');
      expect(mock.captured.length, 1);
      expect(mock.captured.first.path, '/api/chat/rooms/room-1/members');
      expect(mock.captured.first.method, 'GET');
      expect(members.length, 2);
      expect(members[0].role, RoomRole.owner);
    });

    test('handles wrapped response format', () async {
      mock.responseData = {
        'data': [
          {'userId': 'u1', 'username': 'Alice', 'role': 'MODERATOR'},
        ]
      };
      final members = await api.listMembers('room-2');
      expect(members.length, 1);
      expect(members[0].role, RoomRole.moderator);
    });
  });

  group('changeRole', () {
    test('sends PATCH with role body', () async {
      mock.responseData = {};
      await api.changeRole('room-1', 'u2', RoomRole.moderator);
      expect(mock.captured.first.path,
          '/api/chat/rooms/room-1/members/u2/role');
      expect(mock.captured.first.method, 'PATCH');
      expect(mock.captured.first.data, {'role': 'MODERATOR'});
    });
  });

  group('kickMember', () {
    test('sends DELETE to correct path', () async {
      mock.responseData = null;
      mock.statusCode = 204;
      await api.kickMember('room-1', 'u3');
      expect(
          mock.captured.first.path, '/api/chat/rooms/room-1/members/u3');
      expect(mock.captured.first.method, 'DELETE');
    });
  });

  group('muteMember', () {
    test('sends POST with minutes and returns mutedUntil', () async {
      mock.responseData = {'mutedUntil': '2099-12-31T23:59:59.000'};
      final result = await api.muteMember('room-1', 'u2', 30);
      expect(mock.captured.first.path,
          '/api/chat/rooms/room-1/members/u2/mute');
      expect(mock.captured.first.method, 'POST');
      expect(mock.captured.first.data, {'minutes': 30});
      expect(result.year, 2099);
    });
  });

  group('unmuteMember', () {
    test('sends DELETE to mute path', () async {
      mock.responseData = null;
      mock.statusCode = 204;
      await api.unmuteMember('room-1', 'u2');
      expect(mock.captured.first.path,
          '/api/chat/rooms/room-1/members/u2/mute');
      expect(mock.captured.first.method, 'DELETE');
    });
  });

  group('listBans', () {
    test('sends GET and parses bans', () async {
      mock.responseData = [
        {
          'userId': 'u5',
          'username': 'Eve',
          'bannedBy': 'Alice',
          'reason': 'Spam',
          'bannedAt': '2026-04-27T10:00:00.000',
        },
      ];
      final bans = await api.listBans('room-1');
      expect(mock.captured.first.path, '/api/chat/rooms/room-1/bans');
      expect(mock.captured.first.method, 'GET');
      expect(bans.length, 1);
      expect(bans[0].userId, 'u5');
    });
  });

  group('banUser', () {
    test('sends POST with userId and reason', () async {
      mock.responseData = {'userId': 'u5', 'bannedAt': '2026-04-27T10:00:00'};
      mock.statusCode = 201;
      await api.banUser('room-1', 'u5', 'Spam');
      expect(mock.captured.first.path, '/api/chat/rooms/room-1/bans');
      expect(mock.captured.first.method, 'POST');
      expect(mock.captured.first.data, {'userId': 'u5', 'reason': 'Spam'});
    });

    test('sends POST without reason when null', () async {
      mock.responseData = {'userId': 'u5', 'bannedAt': '2026-04-27T10:00:00'};
      mock.statusCode = 201;
      await api.banUser('room-1', 'u5', null);
      expect(mock.captured.first.data, {'userId': 'u5'});
    });
  });

  group('unbanUser', () {
    test('sends DELETE to ban path', () async {
      mock.responseData = null;
      mock.statusCode = 204;
      await api.unbanUser('room-1', 'u5');
      expect(mock.captured.first.path, '/api/chat/rooms/room-1/bans/u5');
      expect(mock.captured.first.method, 'DELETE');
    });
  });

  group('submitReport', () {
    test('sends POST and returns reportId', () async {
      mock.responseData = {'reportId': 42};
      mock.statusCode = 201;
      final id =
          await api.submitReport('msg-1', ReportReason.spam, 'test comment');
      expect(mock.captured.first.path, '/api/chat/messages/msg-1/reports');
      expect(mock.captured.first.method, 'POST');
      expect(mock.captured.first.data, {
        'reason': 'SPAM',
        'comment': 'test comment',
      });
      expect(id, 42);
    });

    test('omits comment when null', () async {
      mock.responseData = {'reportId': 1};
      mock.statusCode = 201;
      await api.submitReport('msg-2', ReportReason.harassment, null);
      expect(mock.captured.first.data, {'reason': 'HARASSMENT'});
    });
  });

  group('listReports', () {
    test('sends GET with status query param', () async {
      mock.responseData = [
        {
          'id': 1,
          'messageId': 'msg-1',
          'messageContent': 'bad',
          'messageAuthor': 'spammer',
          'reportedBy': 'reporter',
          'reason': 'SPAM',
          'status': 'PENDING',
          'createdAt': '2026-04-27T12:00:00',
        },
      ];
      final reports = await api.listReports('room-1');
      expect(mock.captured.first.path, '/api/chat/rooms/room-1/reports');
      expect(mock.captured.first.queryParameters, {'status': 'PENDING'});
      expect(reports.length, 1);
    });

    test('supports resolved status filter', () async {
      mock.responseData = [];
      await api.listReports('room-1', status: ReportStatus.resolved);
      expect(mock.captured.first.queryParameters, {'status': 'RESOLVED'});
    });
  });

  group('updateReportStatus', () {
    test('sends PATCH with status body', () async {
      mock.responseData = {};
      await api.updateReportStatus(42, ReportStatus.resolved);
      expect(mock.captured.first.path, '/api/chat/reports/42');
      expect(mock.captured.first.method, 'PATCH');
      expect(mock.captured.first.data, {'status': 'RESOLVED'});
    });
  });

  group('error handling', () {
    test('maps DioException to RoomAdminApiException', () async {
      mock.errorToThrow = DioException(
        requestOptions: RequestOptions(path: '/test'),
        response: Response(
          requestOptions: RequestOptions(path: '/test'),
          statusCode: 403,
          data: {'error': 'PERMISSION_DENIED', 'message': '권한 부족'},
        ),
        type: DioExceptionType.badResponse,
      );
      expect(
        () => api.listMembers('room-1'),
        throwsA(isA<RoomAdminApiException>().having(
          (e) => e.code,
          'code',
          'PERMISSION_DENIED',
        )),
      );
    });
  });
}
