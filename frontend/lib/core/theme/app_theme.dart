import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// Gossiplog 파스텔 디자인 시스템.
///
/// 로그인 화면(`login_page.dart`)의 팔레트를 앱 전체 토큰으로 올린 것이다.
/// 잉크 남색 글자, 옅은 하늘→살구 배경, 파랑→보라 강조 그라데이션.
/// 채팅방 전용 색(말풍선, 배지, 방 배경)은 [ChatPalette]가 라이트/다크별로 갖는다.
class AppColors {
  AppColors._();

  // ── Pastel brand (login_page와 같은 값)
  static const ink      = Color(0xFF24304D);
  static const inkSoft  = Color(0xFF5B6788);
  static const muted    = Color(0xFF6B7896);
  static const hint     = Color(0xFF9AA6C2);
  static const line     = Color(0xFFE3E8F2);
  static const tabBg    = Color(0xFFF1F4FB);
  static const blue     = Color(0xFF6C8CFF);
  static const blueDeep = Color(0xFF4F6EE6);
  static const violet   = Color(0xFFA07BFF);
  static const skyTop   = Color(0xFFEEF4FF);
  static const peachBottom = Color(0xFFFDF3E7);

  // ── Dark (남색-라벤더)
  static const darkBg            = Color(0xFF12151F);
  static const darkSurface       = Color(0xFF1B1F2E);
  static const darkSurfaceHigh   = Color(0xFF212637);
  static const darkSurfaceHigher = Color(0xFF292F42);
  static const darkBorder        = Color(0xFF363D55);
  static const darkText          = Color(0xFFEEF1FA);
  static const darkTextSoft      = Color(0xFFA3ACC6);
  static const darkPrimary       = Color(0xFF8EA4FF);

  // ── Primary (라이트 기준 브랜드 파랑)
  static const primary   = blue;
  static const onPrimary = Color(0xFFFFFFFF);
  static const secondary = violet;

  // ── Semantic
  static const success = Color(0xFF4CAF83);
  static const error   = Color(0xFFE5484D);
  static const warning = Color(0xFFFFB347);

  // ── Chat
  /// 카카오톡형 내 말풍선 — 파스텔 버터.
  static const myBubble   = Color(0xFFFFE68A);
  /// 나를 부른 @멘션 글자 — 노란 말풍선 위에서도 읽히는 진한 빨강.
  static const mentionMe = Color(0xFFB42318);
  /// 안 읽은 수 배지 — 코랄 레드 알약.
  static const unreadBadge = Color(0xFFFF6B5B);

  /// 전송 버튼·새 메시지 알약 등 강조 액션. 로그인 버튼과 같은 그라데이션.
  static const accentGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [blue, violet],
  );

  // ── Avatar palette (흰 글자가 읽히는 파스텔 톤 10색)
  static const List<Color> avatarPalette = [
    Color(0xFF6C8CFF), Color(0xFFA07BFF), Color(0xFFFF8FB1),
    Color(0xFFFFA96B), Color(0xFF4FBFC6), Color(0xFF63C08A),
    Color(0xFF7FA7F5), Color(0xFFC58BEA), Color(0xFFF59377),
    Color(0xFF5AAEE6),
  ];
}

/// 채팅 화면 전용 색 토큰. `ChatPalette.of(context)`로 읽는다.
///
/// 테마에 확장이 없으면(단위 테스트의 맨 MaterialApp 등) 밝기에 맞는 기본값을 쓴다.
@immutable
class ChatPalette extends ThemeExtension<ChatPalette> {
  /// 메시지 영역 배경.
  final Gradient roomBackground;
  final Color myBubble;
  final Color onMyBubble;
  final Color otherBubble;
  final Color onOtherBubble;
  final Color bubbleShadow;
  /// 날짜선·시스템 메시지 알약.
  final Color pill;
  final Color onPill;
  /// 말풍선 옆 시간·수정됨 글자.
  final Color meta;
  /// 상대 이름 라벨.
  final Color name;
  final Color unreadBadge;
  /// 내 말풍선 옆 "읽음 N" 글자. 방 배경 위 4.5:1 이상.
  final Color readCount;
  /// 사이드바에서 선택된 방 배경.
  final Color selectedRoom;

  const ChatPalette({
    required this.roomBackground,
    required this.myBubble,
    required this.onMyBubble,
    required this.otherBubble,
    required this.onOtherBubble,
    required this.bubbleShadow,
    required this.pill,
    required this.onPill,
    required this.meta,
    required this.name,
    required this.unreadBadge,
    required this.readCount,
    required this.selectedRoom,
  });

  static const light = ChatPalette(
    roomBackground: LinearGradient(
      begin: Alignment.topCenter,
      end: Alignment.bottomCenter,
      colors: [AppColors.skyTop, AppColors.peachBottom],
    ),
    myBubble: AppColors.myBubble,
    onMyBubble: AppColors.ink,
    otherBubble: Color(0xFFFFFFFF),
    onOtherBubble: AppColors.ink,
    bubbleShadow: Color(0x1F3B4A6B),
    pill: Color(0xB3FFFFFF),
    onPill: AppColors.inkSoft,
    meta: AppColors.muted,
    name: AppColors.inkSoft,
    unreadBadge: AppColors.unreadBadge,
    // #EEF4FF 위 약 5.2:1 (blueDeep은 4.0:1이라 한 단계 진하게).
    readCount: Color(0xFF3F5BD0),
    selectedRoom: Color(0xFFEEF3FF),
  );

  static const dark = ChatPalette(
    roomBackground: LinearGradient(
      colors: [Color(0xFF161A26), Color(0xFF161A26)],
    ),
    myBubble: AppColors.myBubble,
    onMyBubble: AppColors.ink,
    otherBubble: Color(0xFF2A2F45),
    onOtherBubble: AppColors.darkText,
    bubbleShadow: Color(0x33000000),
    pill: Color(0x66000000),
    onPill: Color(0xFFC9D0E4),
    meta: Color(0xFF8A93AE),
    name: Color(0xFFB7BFD6),
    unreadBadge: AppColors.unreadBadge,
    readCount: AppColors.darkPrimary,
    selectedRoom: Color(0xFF262D47),
  );

  static ChatPalette of(BuildContext context) {
    final theme = Theme.of(context);
    return theme.extension<ChatPalette>() ??
        (theme.brightness == Brightness.dark ? dark : light);
  }

  @override
  ChatPalette copyWith({
    Gradient? roomBackground,
    Color? myBubble,
    Color? onMyBubble,
    Color? otherBubble,
    Color? onOtherBubble,
    Color? bubbleShadow,
    Color? pill,
    Color? onPill,
    Color? meta,
    Color? name,
    Color? unreadBadge,
    Color? readCount,
    Color? selectedRoom,
  }) =>
      ChatPalette(
        roomBackground: roomBackground ?? this.roomBackground,
        myBubble: myBubble ?? this.myBubble,
        onMyBubble: onMyBubble ?? this.onMyBubble,
        otherBubble: otherBubble ?? this.otherBubble,
        onOtherBubble: onOtherBubble ?? this.onOtherBubble,
        bubbleShadow: bubbleShadow ?? this.bubbleShadow,
        pill: pill ?? this.pill,
        onPill: onPill ?? this.onPill,
        meta: meta ?? this.meta,
        name: name ?? this.name,
        unreadBadge: unreadBadge ?? this.unreadBadge,
        readCount: readCount ?? this.readCount,
        selectedRoom: selectedRoom ?? this.selectedRoom,
      );

  @override
  ChatPalette lerp(ChatPalette? other, double t) {
    if (other == null) return this;
    return ChatPalette(
      roomBackground: Gradient.lerp(roomBackground, other.roomBackground, t)!,
      myBubble: Color.lerp(myBubble, other.myBubble, t)!,
      onMyBubble: Color.lerp(onMyBubble, other.onMyBubble, t)!,
      otherBubble: Color.lerp(otherBubble, other.otherBubble, t)!,
      onOtherBubble: Color.lerp(onOtherBubble, other.onOtherBubble, t)!,
      bubbleShadow: Color.lerp(bubbleShadow, other.bubbleShadow, t)!,
      pill: Color.lerp(pill, other.pill, t)!,
      onPill: Color.lerp(onPill, other.onPill, t)!,
      meta: Color.lerp(meta, other.meta, t)!,
      name: Color.lerp(name, other.name, t)!,
      unreadBadge: Color.lerp(unreadBadge, other.unreadBadge, t)!,
      readCount: Color.lerp(readCount, other.readCount, t)!,
      selectedRoom: Color.lerp(selectedRoom, other.selectedRoom, t)!,
    );
  }
}

class AppTheme {
  AppTheme._();

  static final _dark = ColorScheme(
    brightness: Brightness.dark,
    primary: AppColors.darkPrimary,
    onPrimary: const Color(0xFF10172E),
    primaryContainer: const Color(0xFF2E3A6B),
    onPrimaryContainer: const Color(0xFFDCE3FF),
    secondary: const Color(0xFFB89CFF),
    onSecondary: const Color(0xFF24133F),
    secondaryContainer: const Color(0xFF3A2D5E),
    onSecondaryContainer: const Color(0xFFE6DCFF),
    tertiary: const Color(0xFFF5B97A),
    onTertiary: const Color(0xFF3B2000),
    tertiaryContainer: const Color(0xFF4A3420),
    onTertiaryContainer: const Color(0xFFFFDDBA),
    error: const Color(0xFFFF6B70),
    onError: Colors.white,
    errorContainer: const Color(0xFF4A1F24),
    onErrorContainer: const Color(0xFFFFB3B5),
    surface: AppColors.darkSurface,
    onSurface: AppColors.darkText,
    surfaceContainerLowest: AppColors.darkBg,
    surfaceContainerLow: const Color(0xFF171B28),
    surfaceContainer: AppColors.darkSurfaceHigh,
    surfaceContainerHigh: AppColors.darkSurfaceHigher,
    surfaceContainerHighest: const Color(0xFF323950),
    onSurfaceVariant: AppColors.darkTextSoft,
    outline: AppColors.darkBorder,
    outlineVariant: const Color(0xFF262C3E),
    shadow: Colors.black,
    scrim: Colors.black87,
    inverseSurface: AppColors.darkText,
    onInverseSurface: AppColors.darkBg,
    inversePrimary: AppColors.blueDeep,
  );

  static final _light = ColorScheme(
    brightness: Brightness.light,
    primary: AppColors.blue,
    onPrimary: Colors.white,
    primaryContainer: const Color(0xFFE6ECFF),
    onPrimaryContainer: const Color(0xFF2C3E8F),
    secondary: AppColors.violet,
    onSecondary: Colors.white,
    secondaryContainer: const Color(0xFFF0EAFF),
    onSecondaryContainer: const Color(0xFF4B2E9A),
    tertiary: const Color(0xFFE8922A),
    onTertiary: Colors.white,
    tertiaryContainer: const Color(0xFFFFEBD6),
    onTertiaryContainer: const Color(0xFF6B3A0A),
    error: AppColors.error,
    onError: Colors.white,
    errorContainer: const Color(0xFFFFE4E4),
    onErrorContainer: const Color(0xFF7A1518),
    surface: Colors.white,
    onSurface: AppColors.ink,
    surfaceContainerLowest: Colors.white,
    surfaceContainerLow: const Color(0xFFF7F9FE),
    surfaceContainer: AppColors.tabBg,
    surfaceContainerHigh: const Color(0xFFEAEEF7),
    surfaceContainerHighest: AppColors.line,
    onSurfaceVariant: AppColors.inkSoft,
    outline: AppColors.line,
    outlineVariant: const Color(0xFFEEF1F7),
    shadow: const Color(0xFF3B4A6B),
    scrim: Colors.black54,
    inverseSurface: AppColors.ink,
    onInverseSurface: Colors.white,
    inversePrimary: const Color(0xFFB5C4FF),
  );

  static ThemeData _build(ColorScheme cs) {
    final isDark = cs.brightness == Brightness.dark;
    final baseTheme = ThemeData(brightness: isDark ? Brightness.dark : Brightness.light, useMaterial3: true);
    // Noto Sans KR — 한글 글리프를 테마 폰트가 직접 포함해야 한다. 라틴 전용
    // notoSans를 쓰면 웹(CanvasKit)에서 한글 IME 조합 글자가 런타임 폴백 해석
    // 전까지 □(tofu)로 번쩍이며 그려진다. KR 패밀리는 라틴도 포함한다.
    final textTheme = GoogleFonts.notoSansKrTextTheme(baseTheme.textTheme)
        .apply(bodyColor: cs.onSurface, displayColor: cs.onSurface);

    return baseTheme.copyWith(
      colorScheme: cs,
      textTheme: textTheme,
      extensions: [isDark ? ChatPalette.dark : ChatPalette.light],
      scaffoldBackgroundColor: isDark ? AppColors.darkBg : const Color(0xFFF7F9FE),
      appBarTheme: AppBarTheme(
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        foregroundColor: cs.onSurface,
        iconTheme: IconThemeData(color: cs.onSurfaceVariant, size: 22),
        actionsIconTheme: IconThemeData(color: cs.onSurfaceVariant, size: 22),
        titleTextStyle: GoogleFonts.notoSansKr(
          color: cs.onSurface,
          fontSize: 17,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.3,
        ),
      ),
      drawerTheme: DrawerThemeData(
        backgroundColor: cs.surface,
        elevation: 0,
        width: 280,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.horizontal(right: Radius.circular(28)),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: cs.surfaceContainer,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(18),
          borderSide: BorderSide(color: cs.outline),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(18),
          borderSide: BorderSide(color: cs.outline),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(18),
          borderSide: BorderSide(color: cs.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(18),
          borderSide: BorderSide(color: cs.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(18),
          borderSide: BorderSide(color: cs.error, width: 1.5),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        hintStyle: TextStyle(color: cs.onSurfaceVariant.withAlpha(150)),
        labelStyle: TextStyle(color: cs.onSurfaceVariant),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: cs.surface,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(20),
          side: BorderSide(color: cs.outline),
        ),
        margin: const EdgeInsets.symmetric(vertical: 4),
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: cs.primary,
          foregroundColor: cs.onPrimary,
          minimumSize: const Size.fromHeight(50),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          textStyle: GoogleFonts.notoSansKr(fontWeight: FontWeight.w600, fontSize: 15),
          elevation: 0,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          side: BorderSide(color: cs.outline),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: cs.outline,
        thickness: 1,
        space: 1,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: cs.inverseSurface,
        contentTextStyle: TextStyle(color: cs.onInverseSurface),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
        behavior: SnackBarBehavior.floating,
        elevation: 0,
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: cs.inverseSurface.withAlpha(235),
          borderRadius: BorderRadius.circular(10),
        ),
        textStyle: TextStyle(color: cs.onInverseSurface, fontSize: 12),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: cs.surface,
        surfaceTintColor: Colors.transparent,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(18),
          side: BorderSide(color: cs.outline),
        ),
        elevation: 4,
        shadowColor: cs.shadow.withAlpha(60),
        textStyle: TextStyle(color: cs.onSurface, fontSize: 14),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: cs.surface,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
        ),
        elevation: 0,
      ),
      listTileTheme: ListTileThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: cs.primary,
        linearTrackColor: cs.outline,
        linearMinHeight: 2,
      ),
      iconTheme: IconThemeData(color: cs.onSurfaceVariant, size: 22),
    );
  }

  static ThemeData get dark  => _build(_dark);
  static ThemeData get light => _build(_light);
}
