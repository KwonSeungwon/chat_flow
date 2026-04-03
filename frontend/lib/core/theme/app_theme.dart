import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';

/// ChatFlow "Nebula Dark" design system.
///
/// Color philosophy:
///   Electric Violet primary (#7C6FF7)  — distinctive identity, not generic blue
///   Mint Teal secondary (#4ECDC4)     — AI highlights & accents
///   Deep navy surfaces                 — depth without harsh pure black
class AppColors {
  AppColors._();

  // ── Backgrounds (3-level elevation)
  static const bg            = Color(0xFF0D0F14);
  static const surface       = Color(0xFF161920);
  static const surfaceHigh   = Color(0xFF1E2130);
  static const surfaceHigher = Color(0xFF252838);
  static const border        = Color(0xFF2A2D3E);

  // ── Primary: Electric Violet
  static const primary     = Color(0xFF7C6FF7);
  static const primaryDim  = Color(0xFF5B52C5);
  static const primaryGlow = Color(0xFF9D94F8);
  static const onPrimary   = Color(0xFFFFFFFF);

  // ── Secondary: Mint Teal (AI accents)
  static const secondary    = Color(0xFF4ECDC4);
  static const secondaryDim = Color(0xFF38A89D);

  // ── Text
  static const textPrimary   = Color(0xFFF0F2F5);
  static const textSecondary = Color(0xFF8892A4);
  static const textMuted     = Color(0xFF545F72);

  // ── Semantic
  static const success = Color(0xFF4CAF83);
  static const error   = Color(0xFFFF5C6C);
  static const warning = Color(0xFFFFB347);

  // ── My message bubble gradient
  static const myBubbleGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [Color(0xFF9D94F8), Color(0xFF6558E8)],
  );

  // ── Avatar palette (10 vivid colors)
  static const List<Color> avatarPalette = [
    Color(0xFF7C6FF7), Color(0xFF4ECDC4), Color(0xFFFF6B9D),
    Color(0xFFFFB347), Color(0xFF56CCF2), Color(0xFF6FCF97),
    Color(0xFFBB6BD9), Color(0xFFF2994A), Color(0xFF2F80ED),
    Color(0xFF9B51E0),
  ];
}

class AppTheme {
  AppTheme._();

  static final _dark = ColorScheme(
    brightness: Brightness.dark,
    primary: AppColors.primary,
    onPrimary: AppColors.onPrimary,
    primaryContainer: AppColors.primaryDim,
    onPrimaryContainer: AppColors.onPrimary,
    secondary: AppColors.secondary,
    onSecondary: const Color(0xFF0D2726),
    secondaryContainer: const Color(0xFF1D3D3B),
    onSecondaryContainer: AppColors.secondary,
    tertiary: AppColors.warning,
    onTertiary: AppColors.bg,
    tertiaryContainer: const Color(0xFF3A2E1A),
    onTertiaryContainer: AppColors.warning,
    error: AppColors.error,
    onError: Colors.white,
    errorContainer: const Color(0xFF3A1A1D),
    onErrorContainer: AppColors.error,
    surface: AppColors.surface,
    onSurface: AppColors.textPrimary,
    surfaceContainerLowest: AppColors.bg,
    surfaceContainerLow: AppColors.surface,
    surfaceContainer: AppColors.surfaceHigh,
    surfaceContainerHigh: AppColors.surfaceHigher,
    surfaceContainerHighest: const Color(0xFF2E3248),
    onSurfaceVariant: AppColors.textSecondary,
    outline: AppColors.border,
    outlineVariant: const Color(0xFF1A1D29),
    shadow: Colors.black,
    scrim: Colors.black87,
    inverseSurface: AppColors.textPrimary,
    onInverseSurface: AppColors.bg,
    inversePrimary: AppColors.primaryDim,
  );

  static final _light = ColorScheme(
    brightness: Brightness.light,
    primary: AppColors.primaryDim,
    onPrimary: Colors.white,
    primaryContainer: const Color(0xFFECEAFF),
    onPrimaryContainer: const Color(0xFF3A3095),
    secondary: AppColors.secondaryDim,
    onSecondary: Colors.white,
    secondaryContainer: const Color(0xFFD0F5F3),
    onSecondaryContainer: const Color(0xFF003836),
    tertiary: const Color(0xFFE8922A),
    onTertiary: Colors.white,
    tertiaryContainer: const Color(0xFFFFEDD0),
    onTertiaryContainer: const Color(0xFF3B2000),
    error: AppColors.error,
    onError: Colors.white,
    errorContainer: const Color(0xFFFFDAD8),
    onErrorContainer: const Color(0xFF3B000A),
    surface: const Color(0xFFF5F5FA),
    onSurface: const Color(0xFF18192A),
    surfaceContainerLowest: Colors.white,
    surfaceContainerLow: const Color(0xFFEFEFF7),
    surfaceContainer: const Color(0xFFE8E8F2),
    surfaceContainerHigh: const Color(0xFFE1E1EC),
    surfaceContainerHighest: const Color(0xFFD9D9E6),
    onSurfaceVariant: const Color(0xFF48485F),
    outline: const Color(0xFFB4B4CA),
    outlineVariant: const Color(0xFFD6D6E8),
    shadow: Colors.black,
    scrim: Colors.black87,
    inverseSurface: const Color(0xFF2E2E42),
    onInverseSurface: const Color(0xFFF3F0FF),
    inversePrimary: AppColors.primaryGlow,
  );

  static ThemeData _build(ColorScheme cs) {
    final isDark = cs.brightness == Brightness.dark;
    final baseTheme = ThemeData(brightness: isDark ? Brightness.dark : Brightness.light, useMaterial3: true);
    final textTheme = GoogleFonts.notoSansTextTheme(baseTheme.textTheme);

    return baseTheme.copyWith(
      colorScheme: cs,
      textTheme: textTheme,
      scaffoldBackgroundColor: cs.surfaceContainerLowest,
      appBarTheme: AppBarTheme(
        backgroundColor: cs.surfaceContainerLowest,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        foregroundColor: cs.onSurface,
        titleTextStyle: GoogleFonts.notoSans(
          color: cs.onSurface,
          fontSize: 16,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.2,
        ),
      ),
      drawerTheme: DrawerThemeData(
        backgroundColor: cs.surfaceContainerLow,
        elevation: 0,
        width: 280,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: cs.surfaceContainer,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: cs.outline.withAlpha(120)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: cs.outline.withAlpha(80)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: cs.primary, width: 1.5),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: cs.error),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: cs.error, width: 1.5),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        hintStyle: TextStyle(color: cs.onSurfaceVariant.withAlpha(130)),
        labelStyle: TextStyle(color: cs.onSurfaceVariant),
      ),
      cardTheme: CardThemeData(
        elevation: 0,
        color: cs.surfaceContainer,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
          side: BorderSide(color: cs.outline.withAlpha(60)),
        ),
        margin: const EdgeInsets.symmetric(vertical: 4),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: cs.primary,
          foregroundColor: Colors.white,
          minimumSize: const Size.fromHeight(50),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          textStyle: GoogleFonts.notoSans(fontWeight: FontWeight.w600, fontSize: 15),
          elevation: 0,
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(50),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
          side: BorderSide(color: cs.outline),
        ),
      ),
      dividerTheme: DividerThemeData(
        color: cs.outline.withAlpha(50),
        thickness: 1,
        space: 1,
      ),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: cs.surfaceContainerHighest,
        contentTextStyle: TextStyle(color: cs.onSurface),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        behavior: SnackBarBehavior.floating,
        elevation: 0,
      ),
      tooltipTheme: TooltipThemeData(
        decoration: BoxDecoration(
          color: cs.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: cs.outline.withAlpha(60)),
        ),
        textStyle: TextStyle(color: cs.onSurface, fontSize: 12),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      ),
      popupMenuTheme: PopupMenuThemeData(
        color: cs.surfaceContainer,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: BorderSide(color: cs.outline.withAlpha(80)),
        ),
        elevation: 4,
        textStyle: TextStyle(color: cs.onSurface, fontSize: 14),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: cs.surfaceContainer,
        surfaceTintColor: Colors.transparent,
        shape: const RoundedRectangleBorder(
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        elevation: 0,
      ),
      listTileTheme: ListTileThemeData(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      ),
      progressIndicatorTheme: ProgressIndicatorThemeData(
        color: cs.primary,
        linearTrackColor: cs.outline.withAlpha(40),
        linearMinHeight: 2,
      ),
      iconTheme: IconThemeData(color: cs.onSurfaceVariant, size: 22),
    );
  }

  static ThemeData get dark  => _build(_dark);
  static ThemeData get light => _build(_light);
}
