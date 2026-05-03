import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/features/chat/admin/room_admin_api.dart';
import 'package:chatflow/features/chat/admin/room_members_provider.dart';
import 'package:chatflow/shared/models/room_role.dart';

/// Simple mock interceptor for Dio that returns canned responses.
class _MockInterceptor extends Interceptor {
  Object? responseData;
  int statusCode = 200;
  DioException? errorToThrow;

  _MockInterceptor();

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
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
  group('RoomMembersNotifier', () {
    late Dio dio;
    late _MockInterceptor mock;
    late RoomAdminApi api;

    setUp(() {
      dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
      mock = _MockInterceptor();
      dio.interceptors.add(mock);
      api = RoomAdminApi(dio);
    });

    test('fetch loads members into state', () async {
      mock.responseData = [
        {'userId': 'u1', 'username': 'Alice', 'role': 'OWNER'},
        {'userId': 'u2', 'username': 'Bob', 'role': 'MEMBER'},
      ];

      final notifier = RoomMembersNotifier(api, 'room-1');
      // Wait for the constructor's fetch to complete
      await Future.delayed(const Duration(milliseconds: 100));

      final members = notifier.debugState.valueOrNull;
      expect(members, isNotNull);
      expect(members!.length, 2);
      expect(members[0].role, RoomRole.owner);
      expect(members[1].role, RoomRole.member);
    });

    test('fetch handles API error gracefully', () async {
      mock.errorToThrow = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.connectionTimeout,
      );

      final notifier = RoomMembersNotifier(api, 'room-1');
      await Future.delayed(const Duration(milliseconds: 100));

      expect(notifier.debugState.hasError, isTrue);
    });

    test('applyMembersUpdate replaces state from STOMP payload', () async {
      mock.responseData = [
        {'userId': 'u1', 'username': 'Alice', 'role': 'OWNER'},
      ];

      final notifier = RoomMembersNotifier(api, 'room-1');
      await Future.delayed(const Duration(milliseconds: 100));
      expect(notifier.debugState.valueOrNull?.length, 1);

      // Simulate STOMP MEMBER_LIST_UPDATED
      notifier.applyMembersUpdate([
        {'userId': 'u1', 'username': 'Alice', 'role': 'OWNER'},
        {'userId': 'u2', 'username': 'Bob', 'role': 'MODERATOR'},
        {'userId': 'u3', 'username': 'Carol', 'role': 'MEMBER'},
      ]);

      final members = notifier.debugState.valueOrNull;
      expect(members, isNotNull);
      expect(members!.length, 3);
      expect(members[1].role, RoomRole.moderator);
    });

    test('applyMemberRemoved removes user from state', () async {
      mock.responseData = [
        {'userId': 'u1', 'username': 'Alice', 'role': 'OWNER'},
        {'userId': 'u2', 'username': 'Bob', 'role': 'MEMBER'},
        {'userId': 'u3', 'username': 'Carol', 'role': 'MEMBER'},
      ];

      final notifier = RoomMembersNotifier(api, 'room-1');
      await Future.delayed(const Duration(milliseconds: 100));
      expect(notifier.debugState.valueOrNull?.length, 3);

      notifier.applyMemberRemoved('u2');

      final members = notifier.debugState.valueOrNull;
      expect(members, isNotNull);
      expect(members!.length, 2);
      expect(members.any((m) => m.userId == 'u2'), isFalse);
    });

    test('applyMemberRemoved is safe when state is loading', () async {
      mock.errorToThrow = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.connectionTimeout,
      );

      final notifier = RoomMembersNotifier(api, 'room-1');
      await Future.delayed(const Duration(milliseconds: 100));

      // Should not throw
      notifier.applyMemberRemoved('u2');
    });
  });
}
