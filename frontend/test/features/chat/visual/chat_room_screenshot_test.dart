// 채팅방 디자인 확인용 스크린샷 하네스. 골든 비교가 아니라 PNG를 떨어뜨리기만 한다.
//
// 기본 테스트 실행에서는 건너뛴다. 찍을 때:
//   CHAT_SCREENSHOT_DIR=/path/to/out flutter test test/features/chat/visual
//
// 한글은 macOS 시스템 폰트(AppleGothic)를, 아이콘은 Flutter SDK의 MaterialIcons를
// 읽어 넣는다. 다른 OS에서는 CHAT_SCREENSHOT_KR_FONT로 한글 TTF 경로를 준다.
import 'dart:async';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:gossiplog/core/theme/app_theme.dart';
import 'package:gossiplog/features/chat/chat_provider.dart' show NotificationPolicy;
import 'package:gossiplog/features/chat/mentions_provider.dart';
import 'package:gossiplog/features/chat/widgets/chat_input.dart';
import 'package:gossiplog/features/chat/widgets/chat_messages_list.dart';
import 'package:gossiplog/features/chat/widgets/connection_dot.dart';
import 'package:gossiplog/features/chat/widgets/room_tile.dart';
import 'package:gossiplog/features/chat/widgets/sidebar_header.dart';
import 'package:gossiplog/shared/models/chat_message.dart';
import 'package:gossiplog/shared/models/chat_room.dart';
import 'package:gossiplog/shared/widgets/user_avatar.dart';

const _fontFamily = 'HarnessKR';
final _outDir = Platform.environment['CHAT_SCREENSHOT_DIR'];

class _FakeMentions extends MentionsNotifier {
  _FakeMentions() : super(Dio());
  @override
  Future<void> refresh({int days = 30}) async {}
  @override
  Future<void> refreshUnreadCount({int days = 30}) async {}
}

Future<void> _loadFonts() async {
  final krPath = Platform.environment['CHAT_SCREENSHOT_KR_FONT'] ??
      '/System/Library/Fonts/Supplemental/AppleGothic.ttf';
  final kr = FontLoader(_fontFamily)
    ..addFont(Future.value(ByteData.sublistView(File(krPath).readAsBytesSync())));
  await kr.load();
  final flutterRoot = Platform.environment['FLUTTER_ROOT'];
  if (flutterRoot != null) {
    final icons = File('$flutterRoot/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf');
    if (icons.existsSync()) {
      final loader = FontLoader('MaterialIcons')
        ..addFont(Future.value(ByteData.sublistView(icons.readAsBytesSync())));
      await loader.load();
    }
  }
}

ThemeData _withHarnessFont(ThemeData t) => t.copyWith(
      textTheme: t.textTheme.apply(fontFamily: _fontFamily),
      appBarTheme: t.appBarTheme.copyWith(
        titleTextStyle: t.appBarTheme.titleTextStyle?.copyWith(fontFamily: _fontFamily),
      ),
    );

String _iso(DateTime t) => t.toUtc().toIso8601String();

List<ChatMessage> _messages() {
  final now = DateTime.now();
  final today = DateTime(now.year, now.month, now.day, 9, 12);
  final yesterday = today.subtract(const Duration(days: 1));
  ChatMessage m(String id, String user, String content, DateTime at,
          {String type = 'CHAT',
          String? parentId,
          String? parentPreview,
          String? forwardedFrom,
          String? fileUrl,
          String? fileName,
          String? fileType,
          bool edited = false}) =>
      ChatMessage(
        messageId: id,
        chatRoomId: 'r1',
        userId: '$user-id',
        username: user,
        content: content,
        timestamp: _iso(at),
        type: type,
        parentMessageId: parentId,
        parentMessagePreview: parentPreview,
        forwardedFrom: forwardedFrom,
        fileUrl: fileUrl,
        fileName: fileName,
        fileContentType: fileType,
        editedAt: edited ? _iso(at) : null,
      );
  return [
    m('y1', '민지', '내일 브런치 몇 시에 볼까?', yesterday),
    m('y2', 'seungwon', '11시 괜찮아!', yesterday.add(const Duration(minutes: 2))),
    m('s1', '하준', '', today, type: 'JOIN'),
    m('t1', '민지', '좋은 아침~', today.add(const Duration(minutes: 1))),
    m('t2', '민지', '나 지금 출발했어', today.add(const Duration(minutes: 1))),
    m('t3', '민지', '@seungwon 오늘 자리 예약했지?', today.add(const Duration(minutes: 1))),
    m('t4', 'seungwon', '응 창가 자리로 해뒀어', today.add(const Duration(minutes: 3)),
        parentId: 't3', parentPreview: '@seungwon 오늘 자리 예약했지?'),
    m('t5', 'seungwon', '메뉴판도 미리 받아뒀지', today.add(const Duration(minutes: 3))),
    m('f1', '하준', '[파일] menu.pdf', today.add(const Duration(minutes: 5)),
        type: 'FILE', fileUrl: '/api/files/menu.pdf', fileName: '브런치_메뉴판.pdf',
        fileType: 'application/pdf'),
    m('t6', '하준', '@민지 이거 보고 골라봐', today.add(const Duration(minutes: 5))),
    m('t7', 'seungwon', '하준이 보낸 링크 다시 공유할게', today.add(const Duration(minutes: 7)),
        forwardedFrom: '하준', edited: true),
    m('f2', 'seungwon', '[파일] 지도.zip', today.add(const Duration(minutes: 8)),
        type: 'FILE', fileUrl: '/api/files/map.zip', fileName: '오시는길_지도.zip',
        fileType: 'application/zip'),
  ];
}

List<ChatRoom> _rooms() => [
      ChatRoom(id: 'r1', name: '주말 브런치 모임', participantCount: 3, maxParticipants: 10),
      ChatRoom(id: 'r2', name: '위키 던전 공략', participantCount: 7, maxParticipants: 10),
      ChatRoom(id: 'r3', name: 'DM:seungwon,민지', roomType: 'DIRECT', participantCount: 2, maxParticipants: 2),
      ChatRoom(id: 'r4', name: '독서 클럽', participantCount: 10, maxParticipants: 10),
      ChatRoom(id: 'r5', name: '비밀 기지', isPrivate: true, participantCount: 4, maxParticipants: 10),
    ];

Widget _sidebar() {
  final unread = {'r2': 5, 'r3': 128};
  return Builder(builder: (context) {
    final cs = Theme.of(context).colorScheme;
    return Container(
      width: 280,
      color: cs.surface,
      child: Column(children: [
        SidebarHeader(onCreateTap: () {}, onSortSelected: (_) {}),
        Divider(height: 1, color: cs.outline, thickness: 1),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 8),
            children: [
              for (final (i, r) in _rooms().indexed)
                RoomTile(
                  room: r,
                  color: AppColors.avatarPalette[i * 3 % AppColors.avatarPalette.length],
                  isSelected: r.id == 'r1',
                  isFull: r.isFull,
                  policy: r.id == 'r5' ? NotificationPolicy.mentionsOnly : NotificationPolicy.all,
                  unreadCount: unread[r.id] ?? 0,
                  onTap: () {},
                ),
            ],
          ),
        ),
      ]),
    );
  });
}

Widget _screen({required bool wide}) {
  return Builder(builder: (context) {
    return Scaffold(
      appBar: AppBar(
        leading: wide ? null : IconButton(icon: const Icon(Icons.menu), onPressed: () {}),
        title: const Row(mainAxisSize: MainAxisSize.min, children: [
          Flexible(child: Text('주말 브런치 모임', overflow: TextOverflow.ellipsis)),
          SizedBox(width: 6),
          ConnectionDot(connected: true),
        ]),
        actions: [
          if (wide) ...[
            IconButton(icon: const Icon(Icons.public, size: 20), onPressed: () {}),
            IconButton(icon: const Icon(Icons.settings_outlined, size: 20), onPressed: () {}),
            IconButton(icon: const Icon(Icons.person_add_outlined, size: 20), onPressed: () {}),
          ] else
            IconButton(icon: const Icon(Icons.more_vert, size: 22), onPressed: () {}),
          const Padding(
            padding: EdgeInsets.only(right: 12, left: 4),
            child: UserAvatar(fallbackName: 'seungwon', radius: 16),
          ),
        ],
      ),
      body: Row(children: [
        if (wide) _sidebar(),
        if (wide) const VerticalDivider(width: 1, thickness: 1),
        Expanded(
          child: DecoratedBox(
            decoration: BoxDecoration(gradient: ChatPalette.of(context).roomBackground),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 1100),
                child: Column(children: [
                  Expanded(
                    child: ChatMessagesList(
                      messages: _messages(),
                      currentUsername: 'seungwon',
                      readCounts: const {'t5': 2, 'f2': 1},
                      hasMoreHistory: false,
                      onReplySelected: (_) {},
                      onOpenThread: (_) {},
                      replyCountFor: (id) => id == 't3' ? 2 : 0,
                    ),
                  ),
                  ChatInput(
                    isConnected: true,
                    roomId: 'r1',
                    onSend: (_) {},
                    onFilePick: (_, __, ___, ____) async {},
                  ),
                ]),
              ),
            ),
          ),
        ),
      ]),
    );
  });
}

void main() {
  final skip = _outDir == null ? 'CHAT_SCREENSHOT_DIR 미설정' : null;
  final themes = <bool, ThemeData>{};

  setUpAll(() async {
    if (skip != null) return;
    dotenv.testLoad(fileInput: '');
    await _loadFonts();
    // AppTheme은 GoogleFonts(Noto Sans KR)를 쓰는데 테스트에서는 내려받을 수 없다.
    // 테마를 한 번만 만들고 그때 나는 폰트 로드 실패는 삼킨다 — 글꼴은 하네스
    // 폰트로 덮어쓴다.
    GoogleFonts.config.allowRuntimeFetching = false;
    await runZonedGuarded(() async {
      themes[false] = _withHarnessFont(AppTheme.light);
      themes[true] = _withHarnessFont(AppTheme.dark);
      await Future<void>.delayed(const Duration(milliseconds: 50));
    }, (_, __) {});
  });

  for (final dark in [false, true]) {
    for (final size in const [Size(1200, 800), Size(400, 800)]) {
      final name = 'chat-${dark ? 'dark' : 'light'}-${size.width.toInt()}x${size.height.toInt()}';
      testWidgets(name, skip: skip != null, (tester) async {
        SharedPreferences.setMockInitialValues({});
        tester.view.physicalSize = size * 2;
        tester.view.devicePixelRatio = 2;
        addTearDown(tester.view.reset);

        final key = GlobalKey();
        final theme = themes[dark]!;
        await tester.pumpWidget(ProviderScope(
          overrides: [mentionsProvider.overrideWith((_) => _FakeMentions())],
          child: RepaintBoundary(
            key: key,
            child: MaterialApp(
              debugShowCheckedModeBanner: false,
              theme: theme,
              home: _screen(wide: size.width >= 768),
            ),
          ),
        ));
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 300));

        final boundary = key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
        Future<void> capture(String file) => tester.runAsync(() async {
              final image = await boundary.toImage(pixelRatio: 2);
              final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
              File('$_outDir/$file.png').writeAsBytesSync(bytes!.buffer.asUint8List());
            });
        await capture(name);
        // 아래쪽(파일·전달·읽음 메시지)도 한 장 더.
        await tester.drag(find.byType(ListView).last, const Offset(0, -3000));
        await tester.pumpAndSettle();
        await capture('$name-bottom');
      });
    }
  }
}
