import 'package:chatflow/features/world/admin/admin_game_provider.dart';
import 'package:chatflow/features/world/state/game_api.dart';
import 'package:chatflow/features/world/state/game_models.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

import '../fake_game_api.dart';

class _FailingMe extends FakeGameApi {
  @override
  Future<GameMe> me() async => throw GameApiException('UNKNOWN', statusCode: 401);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  Future<bool> isAdmin(FakeGameApi api) async {
    final c = ProviderContainer(overrides: [gameApiProvider.overrideWithValue(api)]);
    addTearDown(c.dispose);
    final sub = c.listen(isGameAdminProvider, (_, __) {});
    addTearDown(sub.close);
    return c.read(isGameAdminProvider.future);
  }

  test('reads admin from /api/game/me', () async {
    final api = FakeGameApi()
      ..meResult =
          const GameMe(clearedCount: 0, total: 8, graduated: false, admin: true);
    expect(await isAdmin(api), isTrue);
  });

  test('non-admin me → false', () async {
    expect(await isAdmin(FakeGameApi()), isFalse);
  });

  test('me failure → false', () async {
    expect(await isAdmin(_FailingMe()), isFalse);
  });
}
