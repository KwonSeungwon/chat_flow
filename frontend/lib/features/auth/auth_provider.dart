import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show debugPrint;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../../core/constants/storage_keys.dart';
import '../../core/network/api_response.dart';
import '../../core/network/dio_client.dart';
import '../../core/services/fcm_service.dart';
import '../../core/services/web_unload_handler.dart';
import '../../core/utils/url_helper.dart';
import '../chat/chat_rooms_provider.dart' show appStompServiceProvider;

class AuthState {
  final String? token;
  final String? userId;
  final String username;
  final String? profileImageUrl;
  final bool isLoading;
  final String? error;
  final bool isHydrated;

  /// The user logged out on purpose (not a 401/expiry). The router then
  /// goes to a plain /login instead of offering to return to the old page.
  final bool loggedOut;

  const AuthState({
    this.token,
    this.userId,
    this.username = '',
    this.profileImageUrl,
    this.isLoading = false,
    this.error,
    this.isHydrated = false,
    this.loggedOut = false,
  });

  bool get isAuthenticated => token != null;

  AuthState copyWith({
    String? token,
    String? userId,
    String? username,
    String? profileImageUrl,
    bool clearProfileImageUrl = false,
    bool? isLoading,
    String? error,
    bool? isHydrated,
  }) {
    return AuthState(
      token: token ?? this.token,
      userId: userId ?? this.userId,
      username: username ?? this.username,
      profileImageUrl:
          clearProfileImageUrl ? null : (profileImageUrl ?? this.profileImageUrl),
      isLoading: isLoading ?? this.isLoading,
      error: error,
      isHydrated: isHydrated ?? this.isHydrated,
    );
  }
}

/// Outcome of a successful password change.
enum PasswordChangeResult {
  changed,

  /// Changed on the server, but the new token could not be stored. The old
  /// token is already revoked, so the session was ended: sign in again.
  reloginRequired,
}

class AuthNotifier extends StateNotifier<AuthState> {
  final DioClient _dioClient;
  final FlutterSecureStorage _storage;

  bool _fcmStopInFlight = false;

  /// 세션이 끝날 때(로그아웃/만료) 계정 범위 리소스(STOMP 연결 등)를 정리하는 훅.
  final void Function()? _onSessionEnded;

  AuthNotifier(this._dioClient,
      {void Function()? onSessionEnded,
      FlutterSecureStorage storage = const FlutterSecureStorage()})
      : _onSessionEnded = onSessionEnded,
        _storage = storage,
        super(const AuthState()) {
    _dioClient.onUnauthorized = () {
      // 애초에 세션이 없으면 리셋할 것도, 끊을 푸시도 없다. 여기서 리셋해버리면
      // 회원가입 실패 직후 튀어나간 요청의 401이 방금 띄운 실패 사유까지 지운다.
      if (state.token == null) return;
      state = const AuthState(isHydrated: true);
      setAuthTokenForFiles(null);
      _clearCredentials();
      _onSessionEnded?.call();
      // Session expired/invalidated server-side (401) — stop FCM pushes for this
      // device, otherwise notifications keep arriving after the token dies.
      WebUnloadHandler.unregister();
      _stopFcmPushes();
    };
    _hydrate();
  }

  @override
  set state(AuthState value) {
    super.state = value;
    // /api/files/* URL이 브라우저에서 동기적으로 token query param을 받도록 캐시 동기화
    setAuthTokenForFiles(value.token);
  }

  Future<void> _hydrate() async {
    try {
      final token = await _storage.read(key: StorageKeys.token);
      final userId = await _storage.read(key: StorageKeys.userId);
      final username = await _storage.read(key: StorageKeys.username);
      if (token != null && !_isJwtExpired(token)) {
        final profileImageUrl = await _storage.read(key: StorageKeys.profileImage);
        state = AuthState(
          token: token,
          userId: userId,
          username: username ?? '',
          profileImageUrl: profileImageUrl,
          isHydrated: true,
        );
      } else {
        // Token absent or already expired locally — clear and route to login.
        if (token != null) {
          await _clearCredentials();
          // The session's JWT expired while we were away — this device is still
          // subscribed to room topics, so pushes keep coming. Unsubscribe now.
          await _stopFcmPushes();
        }
        state = const AuthState(isHydrated: true);
      }
    } catch (e) {
      // Corrupted or locked secure storage — fall back to logged-out state
      debugPrint('[AuthNotifier] _hydrate error: $e');
      state = const AuthState(isHydrated: true);
    }
  }

  /// Decode JWT exp claim and check expiry. Conservative: any error → expired.
  static bool _isJwtExpired(String jwt) {
    try {
      final parts = jwt.split('.');
      if (parts.length != 3) return true;
      // base64Url 패딩 보정
      String payload = parts[1];
      switch (payload.length % 4) {
        case 2: payload += '=='; break;
        case 3: payload += '='; break;
      }
      final decoded = utf8.decode(base64Url.decode(payload));
      final claims = jsonDecode(decoded) as Map<String, dynamic>;
      final exp = claims['exp'];
      if (exp is! int) return true;
      final nowSec = DateTime.now().millisecondsSinceEpoch ~/ 1000;
      return nowSec >= exp;
    } catch (_) {
      return true;
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
      final profileImageUrl = resp.data['profileImageUrl']?.toString();
      await _saveCredentials(token: token, userId: userId, username: username, profileImageUrl: profileImageUrl);
      state = AuthState(token: token, userId: userId, username: username, profileImageUrl: profileImageUrl, isHydrated: true);
      // Re-arm the beforeunload handler — was unregistered on previous logout,
      // and main.dart only registers once at startup. Without this, FCM topics
      // would not be detached on tab close for the new session.
      _armBeforeUnloadHandler();
    } on DioException catch (e) {
      final code = e.response?.statusCode;
      final msg = code == 401
          ? '아이디 또는 비밀번호가 올바르지 않습니다.'
          : '로그인 실패. 잠시 후 다시 시도해주세요.';
      state = AuthState(isLoading: false, error: msg, isHydrated: true);
    } catch (e) {
      debugPrint('[AuthNotifier] login error: $e');
      state = AuthState(isLoading: false, error: '로그인 실패. 네트워크를 확인해주세요.', isHydrated: true);
    }
  }

  void _armBeforeUnloadHandler() {
    WebUnloadHandler.register(
      jwtProvider: () => state.token ?? '',
      fcmTokenProvider: FcmService.getToken,
      apiBaseUrl: '',
    );
  }

  /// Stop FCM pushes for this device when the session ends (logout / token
  /// expiry / 401). Unsubscribes the device's FCM token from every room topic
  /// server-side — the reliable way to stop pushes; deleting the token alone
  /// leaves the topic subscription in place until Firebase lazily evicts it.
  /// Then invalidates the local token. Best-effort: never throws.
  ///
  /// POST /api/fcm/unsubscribe-all is permitAll server-side, so it also works on
  /// the expiry paths where the JWT is already invalid. The in-flight guard
  /// prevents re-entrancy if the call itself were ever to surface a 401.
  Future<void> _stopFcmPushes() async {
    if (_fcmStopInFlight) return;
    _fcmStopInFlight = true;
    try {
      String? fcmToken;
      try {
        fcmToken = await FcmService.getToken();
      } catch (e) {
        debugPrint('[AuthNotifier] fcm getToken failed: $e');
      }
      // Server validates @Size(min=100); skip obviously-invalid tokens.
      if (fcmToken != null && fcmToken.length >= 100) {
        try {
          await _dioClient.dio
              .post('/api/fcm/unsubscribe-all', data: {'token': fcmToken});
        } catch (e) {
          debugPrint('[AuthNotifier] fcm unsubscribe-all failed: $e');
        }
      }
      await FcmService.deleteToken();
    } finally {
      _fcmStopInFlight = false;
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
      final profileImageUrl = resp.data['profileImageUrl']?.toString();
      await _saveCredentials(token: token, userId: userId, username: username, profileImageUrl: profileImageUrl);
      state = AuthState(token: token, userId: userId, username: username, profileImageUrl: profileImageUrl, isHydrated: true);
      _armBeforeUnloadHandler();
    } on DioException catch (e) {
      final code = e.response?.statusCode;
      // 400: the gateway says why (rule violation / duplicate) in `error`.
      // 409: two sign-ups raced for the same name (lock contention or duplicate).
      final body = e.response?.data;
      final serverMsg = body is Map ? body['error']?.toString() : null;
      final msg = (code == 400 || code == 409) &&
              serverMsg != null &&
              serverMsg.isNotEmpty
          ? serverMsg
          : code == 400 || code == 409
              ? '이미 사용 중인 아이디입니다.'
              : '회원가입 실패. 잠시 후 다시 시도해주세요.';
      state = AuthState(isLoading: false, error: msg, isHydrated: true);
    } catch (e) {
      debugPrint('[AuthNotifier] register error: $e');
      state = AuthState(isLoading: false, error: '회원가입 실패. 네트워크를 확인해주세요.', isHydrated: true);
    }
  }

  Future<void> logout() async {
    try {
      await _dioClient.dio.post('/api/auth/logout');
    } catch (e) {
      debugPrint('[AuthNotifier] logout error: $e');
    }
    await _endSession();
  }

  /// Local half of logging out, after the server was (or need not be) told.
  Future<void> _endSession() async {
    // Unsubscribe this device from all room topics server-side, then invalidate
    // the local FCM token. Unsubscribe-all (not just deleteToken) is what
    // reliably stops pushes — a deleted token lingers in the topic until Firebase
    // lazily evicts it. The call is permitAll, so a revoked JWT is fine.
    await _stopFcmPushes();
    // Detach the beforeunload handler so a future tab close after logout
    // doesn't fire POST /api/fcm/unsubscribe-all with a stale/empty JWT.
    WebUnloadHandler.unregister();
    await _clearCredentials();
    _onSessionEnded?.call();
    state = const AuthState(isHydrated: true, loggedOut: true);
  }

  Future<PasswordChangeResult> changePassword(
      String currentPassword, String newPassword) async {
    final resp = await _dioClient.dio.put('/api/auth/password', data: {
      'currentPassword': currentPassword,
      'newPassword': newPassword,
    });
    // The server revokes the old token and returns a new one. Older servers
    // send none: keep the current token.
    final token = apiResponseField<String>(resp.data, 'token');
    if (token == null || token.isEmpty) return PasswordChangeResult.changed;
    try {
      await _storage.write(key: StorageKeys.token, value: token);
    } catch (e) {
      // The change went through and the old token is revoked: end the
      // session cleanly instead of reporting a failure.
      debugPrint('[AuthNotifier] storing new token failed: $e');
      await _endSession();
      return PasswordChangeResult.reloginRequired;
    }
    // State change re-keys the STOMP connections (room + app-level), which
    // read the stored token on (re)connect.
    state = state.copyWith(token: token);
    return PasswordChangeResult.changed;
  }

  Future<void> updateProfileImage(String profileImageUrl) async {
    await _dioClient.dio.put('/api/auth/profile', data: {
      'username': state.username,
      'profileImageUrl': profileImageUrl,
    });
    await _storage.write(key: StorageKeys.profileImage, value: profileImageUrl);
    state = state.copyWith(profileImageUrl: profileImageUrl);
  }

  Future<void> _saveCredentials({
    required String token,
    required String userId,
    required String username,
    String? profileImageUrl,
  }) async {
    await _storage.write(key: StorageKeys.token, value: token);
    await _storage.write(key: StorageKeys.userId, value: userId);
    await _storage.write(key: StorageKeys.username, value: username);
    if (profileImageUrl != null) {
      await _storage.write(key: StorageKeys.profileImage, value: profileImageUrl);
    } else {
      await _storage.delete(key: StorageKeys.profileImage);
    }
  }

  Future<void> _clearCredentials() async {
    try {
      await clearStoredCredentials(_storage);
    } catch (e) {
      debugPrint('[AuthNotifier] clear credentials failed: $e');
    }
  }

  /// 프로필 화면에서 아바타를 바꿨을 때 auth state/저장소에 반영한다 (서버 호출 없음).
  Future<void> applyProfileImage(String? profileImageUrl) async {
    if (profileImageUrl == null || profileImageUrl.isEmpty) {
      await _storage.delete(key: StorageKeys.profileImage);
      state = state.copyWith(clearProfileImageUrl: true);
    } else {
      await _storage.write(key: StorageKeys.profileImage, value: profileImageUrl);
      state = state.copyWith(profileImageUrl: profileImageUrl);
    }
  }
}

final authProvider = StateNotifierProvider<AuthNotifier, AuthState>((ref) {
  return AuthNotifier(
    ref.watch(dioClientProvider),
    onSessionEnded: () => ref.read(appStompServiceProvider).disconnect(),
  );
});
