import 'package:chatflow/features/world/admin/admin_game_api.dart';
import 'package:chatflow/features/world/admin/admin_game_provider.dart';
import 'package:chatflow/features/world/admin/question_review_page.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Mock extends Interceptor {
  final List<RequestOptions> captured = [];
  int listStatus = 200;
  int patchStatus = 200;

  Map<String, dynamic> q(int id, String status) => {
        'id': id,
        'dungeonId': 'd1',
        'topicTitle': '토픽',
        'stem': '문제$id',
        'choices': ['가', '나', '다', '라'],
        'answerIndex': 1,
        'explanation': '해설',
        'status': status,
      };

  @override
  void onRequest(RequestOptions o, RequestInterceptorHandler h) {
    captured.add(o);
    int code = o.method == 'GET' ? listStatus : patchStatus;
    Object? data;
    if (o.method == 'GET') {
      data = {
        'data': {
          'items':
              o.queryParameters['status'] == 'DRAFT' ? [q(1, 'DRAFT')] : [],
          'total': o.queryParameters['status'] == 'DRAFT' ? 1 : 0,
        }
      };
    } else if (code == 409) {
      data = {'status': 409, 'code': 'DUPLICATE_QUESTION', 'message': 'dup'};
    } else {
      data = {'data': q(1, (o.data as Map)['status']?.toString() ?? 'DRAFT')};
    }
    if (code >= 400) {
      h.reject(DioException(
          requestOptions: o,
          response: Response(requestOptions: o, data: data, statusCode: code),
          type: DioExceptionType.badResponse));
    } else {
      h.resolve(Response(requestOptions: o, data: data, statusCode: code));
    }
  }
}

Widget _app(_Mock mock) {
  final dio = Dio(BaseOptions(baseUrl: 'http://localhost'))
    ..interceptors.add(mock);
  return ProviderScope(
    overrides: [adminGameApiProvider.overrideWithValue(AdminGameApi(dio))],
    child: const MaterialApp(home: QuestionReviewPage()),
  );
}

void main() {
  testWidgets('approve sends PATCH and removes card', (tester) async {
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    expect(find.text('문제1'), findsOneWidget);

    await tester.tap(find.text('승인'));
    await tester.pumpAndSettle();

    final patch = mock.captured.firstWhere((o) => o.method == 'PATCH');
    expect(patch.path, '/api/game/admin/questions/1');
    expect(patch.data, {'status': 'APPROVED'});
    expect(find.text('문제1'), findsNothing);
  });

  testWidgets('403 shows admin-only message', (tester) async {
    final mock = _Mock()..listStatus = 403;
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    expect(find.text('관리자만 볼 수 있습니다'), findsWidgets);
  });

  testWidgets('409 on approve shows snackbar and keeps card', (tester) async {
    final mock = _Mock()..patchStatus = 409;
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('승인'));
    await tester.pumpAndSettle();
    expect(find.text('이미 같은 문제가 있습니다.'), findsOneWidget);
    expect(find.text('문제1'), findsOneWidget);
  });

  testWidgets('edit saves content via PATCH', (tester) async {
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수정'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, '문제'), '새 문제');
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
    final patch = mock.captured.firstWhere((o) => o.method == 'PATCH');
    expect((patch.data as Map)['stem'], '새 문제');
    expect((patch.data as Map)['choices'], ['가', '나', '다', '라']);
    expect((patch.data as Map)['answerIndex'], 1);
  });
}
