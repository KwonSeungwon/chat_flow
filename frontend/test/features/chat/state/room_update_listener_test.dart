import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/core/network/app_stomp_service.dart';
import 'package:chatflow/core/network/dio_client.dart';
import 'package:chatflow/features/auth/auth_provider.dart';
import 'package:chatflow/features/chat/chat_rooms_provider.dart';
import 'package:chatflow/features/chat/state/room_update_listener.dart';

import '../../../core/network/fake_stomp_client.dart';

/// AuthNotifier whose state the test drives directly.
class _TestAuth extends AuthNotifier {
  _TestAuth() : super(DioClient());

  void set(AuthState s) => state = s;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    dotenv.testLoad(fileInput: '');
    FlutterSecureStorage.setMockInitialValues({});
  });

  test(
      'follows auth: login connects, same user new token keeps socket, '
      'logout disconnects', () async {
    final clients = <FakeStompClient>[];
    final auth = _TestAuth();
    final container = ProviderContainer(overrides: [
      authProvider.overrideWith((ref) => auth),
      appStompServiceProvider
          .overrideWithValue(AppStompService(clientFactory: (config) {
        final c = FakeStompClient(config);
        clients.add(c);
        return c;
      })),
    ]);
    addTearDown(container.dispose);
    await Future<void>.delayed(Duration.zero); // let hydration settle

    container.read(roomUpdateListenerProvider);
    expect(clients, isEmpty, reason: 'logged out: no socket');

    auth.set(const AuthState(token: 't1', userId: 'u1', username: 'alice'));
    container.read(roomUpdateListenerProvider);
    expect(clients, hasLength(1));
    expect(clients.single.config.stompConnectHeaders?['Authorization'],
        'Bearer t1');
    clients.single.config.onConnect(connectedFrame());

    auth.set(const AuthState(token: 't2', userId: 'u1', username: 'alice'));
    container.read(roomUpdateListenerProvider);
    expect(clients, hasLength(1), reason: 'same user keeps the live socket');
    expect(clients.single.deactivated, isFalse);

    auth.set(const AuthState(isHydrated: true));
    container.read(roomUpdateListenerProvider);
    expect(clients.single.deactivated, isTrue);
    expect(container.read(appStompServiceProvider).isConnected, isFalse);
  });
}
