import 'package:gossiplog/features/world/admin/admin_game_api.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

class _Mock extends Interceptor {
  final List<RequestOptions> captured = [];
  Object? data;
  int status = 200;

  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    captured.add(options);
    final resp =
        Response(requestOptions: options, data: data, statusCode: status);
    if (status >= 400) {
      handler.reject(DioException(
          requestOptions: options,
          response: resp,
          type: DioExceptionType.badResponse));
    } else {
      handler.resolve(resp);
    }
  }
}

Map<String, dynamic> _q(int id, {String status = 'DRAFT'}) => {
      'id': id,
      'dungeonId': 'd1',
      'topicTitle': 't',
      'stem': 's$id',
      'choices': ['a', 'b', 'c', 'd'],
      'answerIndex': 2,
      'explanation': 'e',
      'status': status,
    };

void main() {
  late _Mock mock;
  late AdminGameApi api;

  setUp(() {
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    mock = _Mock();
    dio.interceptors.add(mock);
    api = AdminGameApi(dio);
  });

  test('listQuestions parses envelope and sends query', () async {
    mock.data = {
      'success': true,
      'data': {
        'items': [_q(1), _q(2)],
        'total': 5
      }
    };
    final page = await api.listQuestions(status: 'DRAFT', page: 1, size: 2);
    expect(page.total, 5);
    expect(page.items.map((e) => e.id), [1, 2]);
    expect(page.items.first.answerIndex, 2);
    expect(mock.captured.single.queryParameters,
        {'status': 'DRAFT', 'page': 1, 'size': 2});
  });

  test('updateQuestion PATCHes only given fields', () async {
    mock.data = {'success': true, 'data': _q(7, status: 'APPROVED')};
    final q = await api.updateQuestion(7, status: 'APPROVED');
    expect(q.status, 'APPROVED');
    expect(mock.captured.single.method, 'PATCH');
    expect(mock.captured.single.path, '/api/game/admin/questions/7');
    expect(mock.captured.single.data, {'status': 'APPROVED'});
  });

  test('409 maps to duplicate, 403 to forbidden', () async {
    mock.status = 409;
    mock.data = {'status': 409, 'code': 'DUPLICATE_QUESTION', 'message': 'dup'};
    await expectLater(
        api.updateQuestion(1, stem: 'x'),
        throwsA(isA<AdminGameApiException>()
            .having((e) => e.isDuplicate, 'dup', true)
            .having((e) => e.code, 'code', 'DUPLICATE_QUESTION')));
    mock.status = 403;
    await expectLater(
        api.listQuestions(status: 'DRAFT'),
        throwsA(isA<AdminGameApiException>()
            .having((e) => e.isForbidden, 'f', true)));
  });

  test('malformed payload maps to AdminGameApiException', () async {
    mock.data = {
      'data': {
        'items': [
          {'id': 'x'}
        ],
        'total': 1
      }
    };
    await expectLater(api.listQuestions(status: 'DRAFT'),
        throwsA(isA<AdminGameApiException>()));
  });
}
