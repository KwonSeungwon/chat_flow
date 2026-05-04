// lib/core/constants/ui_constants.dart
class UIConstants {
  UIConstants._();

  // 레이아웃
  static const double sidebarWidth = 280.0;
  static const double dialogMaxWidth = 500.0;
  static const double dialogMaxHeight = 600.0;
  static const double borderRadius = 12.0;
  static const double smallBorderRadius = 8.0;

  // 참여자
  static const int maxParticipants = 10;
  static const int maxBookmarks = 50;
  static const int maxChatLength = 1000;
  static const int chatWarnThreshold = 800;

  // 스크롤 임계값
  static const double scrollThresholdBottom = 80.0;
  static const double scrollThresholdTop = 100.0;

  // 애니메이션
  static const Duration shortAnimation = Duration(milliseconds: 200);
  static const Duration mediumAnimation = Duration(milliseconds: 350);
}
