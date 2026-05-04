// lib/core/constants/chat_strings.dart
class ChatStrings {
  ChatStrings._();

  // 공통 버튼
  static const String cancel = '취소';
  static const String save = '저장';
  static const String confirm = '확인';
  static const String close = '닫기';

  // 프로필
  static const String profileImageChanged = '프로필 이미지가 변경되었습니다.';
  static const String profileImageChangeFailed = '프로필 이미지 변경에 실패했습니다.';
  static const String passwordChanged = '비밀번호가 변경되었습니다.';
  static const String passwordChangeFailed = '비밀번호 변경에 실패했습니다.';
  static const String passwordMismatch = '새 비밀번호가 일치하지 않습니다.';

  // 초대
  static const String inviteLinkCopied = '초대 링크가 클립보드에 복사되었습니다 (24시간 유효)';

  // 방 설정
  static const String roomSettingsTitle = '채팅방 설정';
  static const String roomSettingsChanged = '채팅방 설정이 변경되었습니다.';

  // 동적 문자열
  static String roomFull(int max) => '이 채팅방은 만석입니다 (최대 $max명)';
  static String readByCount(int count) => '읽은 사람 ($count명)';
  static String participantCount(int count) => '$count명 참여 중';
}
