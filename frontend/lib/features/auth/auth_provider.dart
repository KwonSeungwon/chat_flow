import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';

class AuthState {
  final String? token;
  final String? userId;
  final String username;
  final bool isLoading;
  final String? error;

  const AuthState({
    this.token,
    this.userId,
    this.username = '',
    this.isLoading = false,
    this.error,
  });

  bool get isAuthenticated => token != null;

  AuthState copyWith({
    String? token,
    String? userId,
    String? username,
    bool? isLoading,
    String? error,
  }) {
    return AuthState(
      token: token ?? this.token,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      isLoading: isLoading ?? this.isLoading,
      error: error,
    );
  }
}

class AuthNotifier extends StateNotifier<AuthState> {
  final DioClient _dioClient;
  static const _storage = FlutterSecureStorage();

  AuthNotifier(this._dioClient) : super(const AuthState()) {
    _dioClient.onUnauthorized = () => state = const AuthState();
    _hydrate();
  }

  Future<void> _hydrate() async {
    final token = await _storage.read(key: 'chatflow-token');
    final userId = await _storage.read(key: 'chatflow-userId');
    final username = await _storage.read(key: 'chatflow-username');
    if (token != null) {
      state = AuthState(
        token: token,
        userId: userId,
        username: username ?? '',
      );
    }
  }

  Future<void> login(String username, String password) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      final resp = await _dioClient.dio.post(
        '/api/auth/login',
        data: {'username': username, 'password': password},
      );
      final token = resp.data['token'] as String;
      final userId = resp.data['userId']?.toString() ?? '';
      await _saveCredentials(token: token, userId: userId, username: username);
      state = AuthState(token: token, userId: userId, username: username);
    } on DioException catch (e) {
      final code = e.response?.statusCode;
      final msg = code == 401
          ? '아이디 또는 비밀번호가 올바르지 않습니다.'
          : '로그인 실패. 잠시 후 다시 시도해주세요.';
      state = state.copyWith(isLoading: false, error: msg);
    } catch (_) {
      state = state.copyWith(
        isLoading: false,
        error: '로그인 실패. 네트워크를 확인해주세요.',
      );
    }
  }

  Future<void> register(String username, String password) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      final resp = await _dioClient.dio.post(
        '/api/auth/register',
        data: {'username': username, 'password': password},
      );
      final token = resp.data['token'] as String;
      final userId = resp.data['userId']?.toString() ?? '';
      await _saveCredentials(token: token, userId: userId, username: username);
      state = AuthState(token: token, userId: userId, username: username);
    } on DioException catch (e) {
      final code = e.response?.statusCode;
      final msg = code == 400
          ? '이미 사용 중인 아이디입니다.'
          : '회원가입 실패. 잠시 후 다시 시도해주세요.';
      state = state.copyWith(isLoading: false, error: msg);
    } catch (_) {
      state = state.copyWith(
        isLoading: false,
        error: '회원가입 실패. 네트워크를 확인해주세요.',
      );
    }
  }

  Future<void> logout() async {
    try {
      await _dioClient.dio.post('/api/auth/logout');
    } catch (_) {}
    await _storage.deleteAll();
    state = const AuthState();
  }

  Future<void> _saveCredentials({
    required String token,
    required String userId,
    required String username,
  }) async {
    await _storage.write(key: 'chatflow-token', value: token);
    await _storage.write(key: 'chatflow-userId', value: userId);
    await _storage.write(key: 'chatflow-username', value: username);
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  return AuthNotifier(ref.watch(dioClientProvider));
});
