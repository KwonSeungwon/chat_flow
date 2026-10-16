import 'package:dio/dio.dart';
import '../../../core/network/api_response.dart';

class GameQuestion {
  final int id;
  final String dungeonId;
  final String topicTitle;
  final String stem;
  final List<String> choices;
  final int answerIndex;
  final String explanation;
  final String status;

  const GameQuestion({
    required this.id,
    required this.dungeonId,
    required this.topicTitle,
    required this.stem,
    required this.choices,
    required this.answerIndex,
    required this.explanation,
    required this.status,
  });

  factory GameQuestion.fromJson(Map<String, dynamic> json) => GameQuestion(
        id: (json['id'] as num).toInt(),
        dungeonId: json['dungeonId']?.toString() ?? '',
        topicTitle: json['topicTitle']?.toString() ?? '',
        stem: json['stem']?.toString() ?? '',
        choices: (json['choices'] as List? ?? const [])
            .map((e) => e.toString())
            .toList(),
        answerIndex: (json['answerIndex'] as num?)?.toInt() ?? 0,
        explanation: json['explanation']?.toString() ?? '',
        status: json['status']?.toString() ?? 'DRAFT',
      );
}

class QuestionPage {
  final List<GameQuestion> items;
  final int total;
  const QuestionPage({required this.items, required this.total});
}

class AdminGameApiException implements Exception {
  final int? statusCode;
  final String code;
  final String message;

  AdminGameApiException(
      {this.statusCode, required this.code, required this.message});

  bool get isForbidden => statusCode == 403;
  bool get isDuplicate => statusCode == 409;

  @override
  String toString() => 'AdminGameApiException($code): $message';
}

class AdminGameApi {
  final Dio _dio;

  AdminGameApi(this._dio);

  Future<QuestionPage> listQuestions({
    required String status,
    int page = 0,
    int size = 20,
  }) async {
    try {
      final resp = await _dio.get(
        '/api/game/admin/questions',
        queryParameters: {'status': status, 'page': page, 'size': size},
      );
      final items = apiResponseList(
              apiResponseField<List>(resp.data, 'items') ?? const [])
          .map(
              (e) => GameQuestion.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
      final total =
          apiResponseField<num>(resp.data, 'total')?.toInt() ?? items.length;
      return QuestionPage(items: items, total: total);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  Future<GameQuestion> updateQuestion(
    int id, {
    String? stem,
    List<String>? choices,
    int? answerIndex,
    String? explanation,
    String? status,
  }) async {
    final body = <String, dynamic>{
      if (stem != null) 'stem': stem,
      if (choices != null) 'choices': choices,
      if (answerIndex != null) 'answerIndex': answerIndex,
      if (explanation != null) 'explanation': explanation,
      if (status != null) 'status': status,
    };
    try {
      final resp =
          await _dio.patch('/api/game/admin/questions/$id', data: body);
      final map = apiResponseMap(resp.data);
      if (map == null) {
        throw AdminGameApiException(code: 'UNKNOWN', message: '응답 형식 오류');
      }
      return GameQuestion.fromJson(map);
    } on DioException catch (e) {
      throw _mapError(e);
    }
  }

  static AdminGameApiException _mapError(DioException e) {
    final data = e.response?.data;
    String code = 'UNKNOWN';
    String message = '요청 처리 중 오류가 발생했습니다.';
    if (data is Map) {
      code = data['code']?.toString() ?? data['error']?.toString() ?? code;
      message = data['message']?.toString() ?? message;
    }
    return AdminGameApiException(
        statusCode: e.response?.statusCode, code: code, message: message);
  }
}

/// True when the admin list endpoint answers; false on 403 (not an admin).
/// Other failures (network, 5xx) are treated as not admin so the entry icon
/// stays hidden.
Future<bool> isGameAdmin(AdminGameApi api) async {
  try {
    await api.listQuestions(status: 'DRAFT', size: 1);
    return true;
  } on AdminGameApiException {
    return false;
  }
}
