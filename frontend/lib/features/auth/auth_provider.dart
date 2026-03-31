import 'dart:math';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/network/dio_client.dart';

class AuthState {
  final String? token;
  final String? userId;
  final String username;
  final bool isGuest;
  final bool isLoading;
  final String? error;

  const AuthState({
    this.token,
    this.userId,
    this.username = '',
    this.isGuest = false,
    this.isLoading = false,
    this.error,
  });

  bool get isAuthenticated => token != null;

  AuthState copyWith({
    String? token,
    String? userId,
    String? username,
    bool? isGuest,
    bool? isLoading,
    String? error,
  }) {
    return AuthState(
      token: token ?? this.token,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      isGuest: isGuest ?? this.isGuest,
      isLoading: isLoading ?? this.isLoading,
      error: error,
    );
  }
}

class AuthNotifier extends StateNotifier<AuthState> {
  final DioClient _dioClient;
  static const _storage = FlutterSecureStorage();

  AuthNotifier(this._dioClient) : super(const AuthState()) {
    _hydrate();
  }

  Future<void> _hydrate() async {
    final token = await _storage.read(key: 'chatflow-token');
    final userId = await _storage.read(key: 'chatflow-userId');
    final username = await _storage.read(key: 'chatflow-username');
    final isGuest = await _storage.read(key: 'chatflow-isGuest') == 'true';
    if (token != null) {
      state = AuthState(
        token: token,
        userId: userId,
        username: username ?? '',
        isGuest: isGuest,
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
      await _saveCredentials(
        token: token,
        userId: userId,
        username: username,
        isGuest: false,
      );
      state = AuthState(token: token, userId: userId, username: username);
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: '로그인 실패. 아이디/비밀번호를 확인해주세요.',
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
      await _saveCredentials(
        token: token,
        userId: userId,
        username: username,
        isGuest: false,
      );
      state = AuthState(token: token, userId: userId, username: username);
    } catch (e) {
      state = state.copyWith(
        isLoading: false,
        error: '회원가입 실패. 이미 사용 중인 아이디일 수 있습니다.',
      );
    }
  }

  Future<void> guestLogin() async {
    state = state.copyWith(isLoading: true, error: null);
    await _doGuestRegister(0);
  }

  Future<void> _doGuestRegister(int retryCount) async {
    // Timestamp (13 digits) + 4-digit secure random → collision probability ≈ 0
    final ts = DateTime.now().millisecondsSinceEpoch;
    final rand = Random.secure().nextInt(9000) + 1000;
    final username = 'Guest_$ts$rand';
    final password = 'gp_${ts}_$rand';
    try {
      final resp = await _dioClient.dio.post(
        '/api/auth/register',
        data: {'username': username, 'password': password},
      );
      final token = resp.data['token'] as String;
      final userId = resp.data['userId']?.toString() ?? '';
      await _saveCredentials(
        token: token,
        userId: userId,
        username: username,
        isGuest: true,
      );
      state = AuthState(
        token: token,
        userId: userId,
        username: username,
        isGuest: true,
      );
    } on DioException catch (e) {
      // 400 = duplicate username (near-impossible but retry once)
      if (e.response?.statusCode == 400 && retryCount < 2) {
        return _doGuestRegister(retryCount + 1);
      }
      final code = e.response?.statusCode;
      final msg = code != null
          ? '서버 오류 ($code). 잠시 후 다시 시도해주세요.'
          : '네트워크 오류. 인터넷 연결을 확인해주세요.';
      state = state.copyWith(isLoading: false, error: msg);
    } catch (_) {
      state = state.copyWith(isLoading: false, error: '게스트 로그인 실패. 다시 시도해주세요.');
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
    required bool isGuest,
  }) async {
    await _storage.write(key: 'chatflow-token', value: token);
    await _storage.write(key: 'chatflow-userId', value: userId);
    await _storage.write(key: 'chatflow-username', value: username);
    await _storage.write(key: 'chatflow-isGuest', value: isGuest.toString());
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  return AuthNotifier(ref.watch(dioClientProvider));
});
