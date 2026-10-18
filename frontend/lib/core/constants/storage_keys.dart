class StorageKeys {
  StorageKeys._();
  static const token = 'gossiplog-token';
  static const userId = 'gossiplog-userId';
  static const username = 'gossiplog-username';
  /// 예전 버전이 저장하던 직군 키. 더 쓰지 않고, 남아 있는 값을 로그아웃 때 지우는 데만 쓴다.
  static const legacyRole = 'gossiplog-role';
  static const profileImage = 'gossiplog-profileImage';
  static const roomNotificationPolicies = 'gossiplog.roomNotificationPolicies';
}
