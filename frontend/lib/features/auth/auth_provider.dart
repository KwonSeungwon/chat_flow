import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';

class AuthState {
  final String? token;
  final String? userId;
  final String username;
  final String role;
  final bool isLoading;
  final String? error;

  const AuthState({
    this.token,
    this.userId,
    this.username = '',
    this.role = 'NURSE',
    this.isLoading = false,
    this.error,
  });

  bool get isAuthenticated => token != null;

  AuthState copyWith({
    String? token,
    String? userId,
    String? username,
    String? role,
    bool? isLoading,
    String? error,
  }) {
    return AuthState(
      token: token ?? this.token,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      role: role ?? this.role,
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
    try {
      final token = await _storage.read(key: 'chatflow-token');
      final userId = await _storage.read(key: 'chatflow-userId');
      final username = await _storage.read(key: 'chatflow-username');
      if (token != null) {
        final role = await _storage.read(key: 'chatflow-role');
        state = AuthState(
          token: token,
          userId: userId,
          username: username ?? '',
          role: role ?? 'NURSE',
        );
      }
    } catch (_) {
      // Corrupted or locked secure storage — fall back to logged-out state
      state = const AuthState();
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
      final role = resp.data['role']?.toString() ?? 'NURSE';
      await _saveCredentials(token: token, userId: userId, username: username, role: role);
      state = AuthState(token: token, userId: userId, username: username, role: role);
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

  Future<void> register(String username, String password, {String role = 'NURSE'}) async {
    state = state.copyWith(isLoading: true, error: null);
    try {
      final resp = await _dioClient.dio.post(
        '/api/auth/register',
        data: {'username': username, 'password': password, 'role': role},
      );
      final token = resp.data['token'] as String;
      final userId = resp.data['userId']?.toString() ?? '';
      final respRole = resp.data['role']?.toString() ?? role;
      await _saveCredentials(token: token, userId: userId, username: username, role: respRole);
      state = AuthState(token: token, userId: userId, username: username, role: respRole);
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
    await _storage.delete(key: 'chatflow-token');
    await _storage.delete(key: 'chatflow-userId');
    await _storage.delete(key: 'chatflow-username');
    state = const AuthState();
  }

  Future<void> _saveCredentials({
    required String token,
    required String userId,
    required String username,
    String role = 'NURSE',
  }) async {
    await _storage.write(key: 'chatflow-token', value: token);
    await _storage.write(key: 'chatflow-userId', value: userId);
    await _storage.write(key: 'chatflow-username', value: username);
    await _storage.write(key: 'chatflow-role', value: role);
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  return AuthNotifier(ref.watch(dioClientProvider));
});
