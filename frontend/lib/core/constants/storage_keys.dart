class StorageKeys {
  StorageKeys._();
  static const token = 'gossiplog-token';
  static const userId = 'gossiplog-userId';
  static const username = 'gossiplog-username';
  /// 예전 버전이 저장하던 직군 키. 더 쓰지 않고, 남아 있는 값을 로그아웃 때 지우는 데만 쓴다.
  static const legacyRole = 'chatflow-role';
  static const profileImage = 'gossiplog-profileImage';
  static const roomNotificationPolicies = 'gossiplog.roomNotificationPolicies';

  /// keys from before the gossiplog rename — removed once on startup.
  /// secure storage의 정확한 키 목록이다.
  static const legacyKeys = [
    'chatflow-token',
    'chatflow-userId',
    'chatflow-username',
    'chatflow-role',
    'chatflow-profileImage',
  ];

  /// keys from before the gossiplog rename — removed once on startup.
  /// SharedPreferences는 roomId·userId가 붙은 키(bookmarks, roomKeywords, draft 등)가 있어
  /// 이 접두사로 시작하는 키를 모두 지운다.
  /// (theme-mode, fontScale, roomNotificationPolicies 포함)
  static const legacyPrefsPrefixes = ['chatflow-', 'chatflow.'];
}
