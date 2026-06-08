import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/api_response.dart';

void main() {
  group('apiResponseList', () {
    test('returns a bare list as-is', () {
      expect(apiResponseList([1, 2, 3]), [1, 2, 3]);
    });

    test('unwraps {data: [...]}', () {
      expect(apiResponseList({'data': [1, 2]}), [1, 2]);
    });

    test('unwraps Spring Page {content: [...]}', () {
      expect(apiResponseList({'content': [9]}), [9]);
    });

    test('unwraps {data: {content: [...]}}', () {
      expect(apiResponseList({'data': {'content': [5]}}), [5]);
    });

    test('returns empty list for null/malformed', () {
      expect(apiResponseList(null), const []);
      expect(apiResponseList({'foo': 'bar'}), const []);
    });
  });

  group('apiResponseField', () {
    test('reads field from {data: {...}} envelope', () {
      expect(apiResponseField<Object>({'data': {'id': 7}}, 'id'), 7);
    });
    test('reads field from bare map (no data wrapper)', () {
      expect(apiResponseField<Object>({'id': 7}, 'id'), 7);
    });
    test('returns null when field absent or payload non-map', () {
      expect(apiResponseField<Object>({'data': {}}, 'id'), isNull);
      expect(apiResponseField<Object>('not a map', 'id'), isNull);
    });
  });
}
