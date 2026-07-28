import 'dart:convert';
import 'dart:typed_data';

import 'package:chatflow/features/chat/helpers/mention_candidates.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

/// Canned HTTP for [MentionCandidateSource]. The project has no mocking
/// library (pubspec dev_dependencies are flutter_test + flutter_lints only),
/// so we swap Dio's adapter for one that answers from a path→status map and
/// records every request it saw.
class _FakeAdapter implements HttpClientAdapter {
  _FakeAdapter(this.responses);

  /// A path suffix (e.g. `/members`) → the JSON body to return. A path with
  /// no entry answers 403, which is what the gateway does for a non-member.
  final Map<String, Object> responses;
  final List<String> requested = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requested.add(options.path);
    for (final entry in responses.entries) {
      if (options.path.endsWith(entry.key)) {
        return ResponseBody.fromString(
          jsonEncode(entry.value),
          200,
          headers: {
            Headers.contentTypeHeader: [Headers.jsonContentType]
          },
        );
      }
    }
    return ResponseBody.fromString(
      '{"success":false,"message":"forbidden"}',
      403,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

Map<String, Object> _envelope(List<Map<String, String>> users) =>
    {'success': true, 'data': users};

(Dio, _FakeAdapter) _dioWith(Map<String, Object> responses) {
  final adapter = _FakeAdapter(responses);
  return (Dio()..httpClientAdapter = adapter, adapter);
}

void main() {
  group('MentionCandidates.parse', () {
    test('reads the ApiResponse-wrapped /members shape', () {
      final data = {
        'success': true,
        'data': [
          {
            'userId': 'u1',
            'username': '땅콩',
            'role': 'MEMBER',
            'mutedUntil': null
          },
          {
            'userId': 'u2',
            'username': 'qaplay0721',
            'role': 'OWNER',
            'mutedUntil': null
          },
        ],
      };

      expect(MentionCandidates.parse(data), [
        {'userId': 'u1', 'username': '땅콩'},
        {'userId': 'u2', 'username': 'qaplay0721'},
      ]);
    });

    test('reads the /participants shape too (fallback path)', () {
      final data = {
        'success': true,
        'data': [
          {'userId': 'u1', 'username': '땅콩'},
        ],
      };

      expect(MentionCandidates.parse(data), [
        {'userId': 'u1', 'username': '땅콩'},
      ]);
    });

    test('reads a bare MEMBER_LIST_UPDATED members array', () {
      final members = [
        {'userId': 'u1', 'username': 'alice', 'role': 'OWNER'},
        {'userId': 'u2', 'username': 'bob', 'role': 'MEMBER'},
      ];

      expect(MentionCandidates.parse(members).map((e) => e['username']),
          ['alice', 'bob']);
    });

    test('collapses multiple sessions of the same user', () {
      final data = {
        'success': true,
        'data': [
          {'userId': 'u1', 'username': '땅콩'},
          {'userId': 'u1', 'username': '땅콩'},
          {'userId': 'u2', 'username': 'bob'},
        ],
      };

      expect(MentionCandidates.parse(data).length, 2);
    });

    test('drops entries without a username', () {
      final data = {
        'success': true,
        'data': [
          {'userId': 'u1'},
          {'userId': 'u2', 'username': ''},
          {'userId': 'u3', 'username': 'bob'},
        ],
      };

      expect(MentionCandidates.parse(data), [
        {'userId': 'u3', 'username': 'bob'},
      ]);
    });

    test('keeps distinct names when userId is missing', () {
      final data = {
        'success': true,
        'data': [
          {'username': 'alice'},
          {'username': 'bob'},
          {'username': 'alice'},
        ],
      };

      expect(MentionCandidates.parse(data).map((e) => e['username']),
          ['alice', 'bob']);
    });

    test('returns empty for malformed or error payloads', () {
      expect(MentionCandidates.parse(null), isEmpty);
      expect(MentionCandidates.parse({'success': false, 'message': 'nope'}),
          isEmpty);
      expect(MentionCandidates.parse('garbage'), isEmpty);
    });
  });

  group('MentionCandidates.filter', () {
    final candidates = [
      {'userId': 'u1', 'username': 'bobby'},
      {'userId': 'u2', 'username': 'alice'},
      {'userId': 'u3', 'username': 'xxbob'},
      {'userId': 'u4', 'username': '땅콩'},
    ];

    test('matches case-insensitively', () {
      expect(MentionCandidates.filter(candidates, 'ALI').single['username'],
          'alice');
    });

    test('lists prefix matches before substring matches', () {
      expect(
          MentionCandidates.filter(candidates, 'bob').map((e) => e['username']),
          ['bobby', 'xxbob']);
    });

    test('matches Hangul', () {
      expect(
          MentionCandidates.filter(candidates, '땅').single['username'], '땅콩');
    });

    test('empty query returns everything in order', () {
      expect(MentionCandidates.filter(candidates, '').map((e) => e['username']),
          ['bobby', 'alice', 'xxbob', '땅콩']);
    });

    test('no match returns empty', () {
      expect(MentionCandidates.filter(candidates, 'zzz'), isEmpty);
    });

    test('caps the suggestion list', () {
      final many =
          List.generate(50, (i) => {'userId': 'u$i', 'username': 'user$i'});

      expect(MentionCandidates.filter(many, '').length, 20);
      expect(MentionCandidates.filter(many, 'user').length, 20);
      expect(MentionCandidates.filter(many, '', limit: 3).length, 3);
    });
  });

  group('MentionCandidateSource', () {
    test('reads the member list, not the presence set', () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'offline_member'},
        ]),
        '/participants': _envelope([
          {'userId': 'u2', 'username': 'connected_only'},
        ]),
      });

      final users = await MentionCandidateSource(dio).candidates('r1');

      expect(users.single['username'], 'offline_member');
      expect(adapter.requested, ['/api/chat/rooms/r1/members']);
    });

    test('falls back to presence when /members is rejected', () async {
      final (dio, adapter) = _dioWith({
        '/participants': _envelope([
          {'userId': 'u2', 'username': 'connected_only'},
        ]),
      });

      final users = await MentionCandidateSource(dio).candidates('r1');

      expect(users.single['username'], 'connected_only');
      expect(adapter.requested, [
        '/api/chat/rooms/r1/members',
        '/api/chat/rooms/r1/participants',
      ]);
    });

    test('yields no suggestions when both endpoints fail', () async {
      final (dio, _) = _dioWith({});

      expect(await MentionCandidateSource(dio).candidates('r1'), isEmpty);
    });

    test('serves repeat lookups from cache', () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      for (final q in ['a', 'al', 'ali', 'alic', 'alice']) {
        expect((await source.search('r1', q)).single['username'], 'alice');
      }

      expect(adapter.requested, ['/api/chat/rooms/r1/members']);
    });

    test('collapses concurrent lookups into one request', () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      await Future.wait([
        source.search('r1', 'a'),
        source.search('r1', 'al'),
        source.search('r1', 'ali'),
      ]);

      expect(adapter.requested, ['/api/chat/rooms/r1/members']);
    });

    test('retries a failed fetch soon, without hammering per keystroke',
        () async {
      final (dio, adapter) = _dioWith({});
      var clock = DateTime(2026, 7, 28, 12, 0, 0);
      final source = MentionCandidateSource(dio, now: () => clock);

      await source.candidates('r1');
      expect(adapter.requested.length, 2, reason: 'members then participants');

      clock = clock.add(const Duration(seconds: 1));
      await source.candidates('r1');
      expect(adapter.requested.length, 2,
          reason: 'the rest of the keystroke burst rides the failed entry');

      clock = clock.add(const Duration(seconds: 5));
      await source.candidates('r1');
      expect(adapter.requested.length, 4,
          reason: 'recovers in seconds, not after the full 30s TTL');
    });

    test('a late empty result does not clobber a seed that landed first',
        () async {
      final (dio, _) = _dioWith({});
      final source = MentionCandidateSource(dio);

      final pending = source.candidates('r1');
      source.seed('r1', [
        {'userId': 'u1', 'username': 'alice'},
      ]);
      await pending;

      expect((await source.candidates('r1')).single['username'], 'alice');
    });

    test('re-fetches once the TTL expires', () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
        ]),
      });
      var clock = DateTime(2026, 7, 28, 12, 0, 0);
      final source = MentionCandidateSource(dio, now: () => clock);

      await source.candidates('r1');
      clock = clock.add(const Duration(seconds: 29));
      await source.candidates('r1');
      expect(adapter.requested.length, 1, reason: 'still inside the 30s TTL');

      clock = clock.add(const Duration(seconds: 2));
      await source.candidates('r1');
      expect(adapter.requested.length, 2);
    });

    test('caches per room', () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      await source.candidates('r1');
      await source.candidates('r2');

      expect(adapter.requested, [
        '/api/chat/rooms/r1/members',
        '/api/chat/rooms/r2/members',
      ]);
    });

    test('seed serves a MEMBER_LIST_UPDATED payload without a request',
        () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'stale'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      source.seed('r1', [
        {'userId': 'u1', 'username': 'renamed', 'role': 'MEMBER'},
      ]);

      expect((await source.candidates('r1')).single['username'], 'renamed');
      expect(adapter.requested, isEmpty);
    });

    test('seed replaces an already-cached list', () async {
      final (dio, _) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
          {'userId': 'u2', 'username': 'bob'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      await source.candidates('r1');
      source.seed('r1', [
        {'userId': 'u1', 'username': 'alice'},
      ]);

      expect(
          (await source.candidates('r1')).map((e) => e['username']), ['alice']);
    });

    test('an unusable seed drops the entry instead of caching nothing',
        () async {
      final (dio, adapter) = _dioWith({
        '/members': _envelope([
          {'userId': 'u1', 'username': 'alice'},
        ]),
      });
      final source = MentionCandidateSource(dio);

      await source.candidates('r1');
      source.seed('r1', null);

      expect((await source.candidates('r1')).single['username'], 'alice');
      expect(adapter.requested.length, 2, reason: 're-fetched after the drop');
    });
  });
}
