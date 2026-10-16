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
  bool approvedHasItem = false;

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
      final st = o.queryParameters['status'];
      final items = st == 'DRAFT'
          ? [q(1, 'DRAFT')]
          : (st == 'APPROVED' && approvedHasItem)
              ? [q(1, 'APPROVED')]
              : <Map<String, dynamic>>[];
      data = {
        'data': {'items': items, 'total': items.length}
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
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
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
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock()..listStatus = 403;
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    expect(find.text('관리자만 볼 수 있습니다'), findsWidgets);
  });

  testWidgets('409 on approve shows snackbar and keeps card', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock()..patchStatus = 409;
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('승인'));
    await tester.pumpAndSettle();
    expect(find.text('이미 같은 문제가 있습니다.'), findsOneWidget);
    expect(find.text('문제1'), findsOneWidget);
  });

  testWidgets('edit saves content via PATCH', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
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

  testWidgets('reject sends REJECTED and removes card', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('반려'));
    await tester.pumpAndSettle();
    final patch = mock.captured.firstWhere((o) => o.method == 'PATCH');
    expect(patch.data, {'status': 'REJECTED'});
    expect(find.text('문제1'), findsNothing);
  });

  testWidgets('status change reloads other tabs', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    int approvedGets() => mock.captured
        .where((o) =>
            o.method == 'GET' && o.queryParameters['status'] == 'APPROVED')
        .length;
    await tester.tap(find.text('승인됨'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('검수 대기'));
    await tester.pumpAndSettle();
    final before = approvedGets();
    expect(before, 1);
    await tester.tap(find.text('승인'));
    await tester.pumpAndSettle();
    expect(approvedGets(), before + 1);
  });

  testWidgets('editing an APPROVED question removes it (reset to DRAFT)',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock()..approvedHasItem = true;
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('승인됨'));
    await tester.pumpAndSettle();
    expect(find.text('문제1'), findsOneWidget);
    await tester.tap(find.text('수정'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, '문제'), '고침');
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
    expect(find.text('고침'), findsNothing);
    expect(find.text('문제1'), findsNothing);
  });

  testWidgets('validation errors shown and save disabled', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수정'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, '보기 2'), '   ');
    await tester.pumpAndSettle();
    expect(find.text('내용을 입력하세요'), findsOneWidget);
    final save =
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, '저장'));
    expect(save.onPressed, isNull);
    await tester.tap(find.text('저장'), warnIfMissed: false);
    expect(mock.captured.where((o) => o.method == 'PATCH'), isEmpty);
  });

  testWidgets('changing the radio sends new answerIndex', (tester) async {
    tester.view.physicalSize = const Size(800, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final mock = _Mock();
    await tester.pumpWidget(_app(mock));
    await tester.pumpAndSettle();
    await tester.tap(find.text('수정'));
    await tester.pumpAndSettle();
    await tester.tap(find.byType(Radio<int>).at(3));
    await tester.pumpAndSettle();
    await tester.tap(find.text('저장'));
    await tester.pumpAndSettle();
    final patch = mock.captured.firstWhere((o) => o.method == 'PATCH');
    expect((patch.data as Map)['answerIndex'], 3);
  });
}
