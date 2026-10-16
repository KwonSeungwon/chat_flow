class StorageKeys {
  StorageKeys._();
  static const token = 'chatflow-token';
  static const userId = 'chatflow-userId';
  static const username = 'chatflow-username';
  /// 예전 버전이 저장하던 직군 키. 더 쓰지 않고, 남아 있는 값을 로그아웃 때 지우는 데만 쓴다.
  static const legacyRole = 'chatflow-role';
  static const profileImage = 'chatflow-profileImage';
  static const roomNotificationPolicies = 'chatflow.roomNotificationPolicies';
}
