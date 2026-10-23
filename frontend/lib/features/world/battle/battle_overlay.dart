import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../state/game_models.dart';

const _ink = Color(0xFF24304D);
const _muted = Color(0xFF6B7896);
const _line = Color(0xFFE3E8F2);
const _okBorder = Color(0xFF2FB88A);
const _okBg = Color(0xFFE9FBF3);
const _okText = Color(0xFF2F8F6A);
const _ngBorder = Color(0xFFE0485A);
const _ngBg = Color(0xFFFFF1EF);
const _ngText = Color(0xFFB5473B);
const _heart = Color(0xFFFF5F6D);
const _accent = Color(0xFF4F6EE6);

const _dungeonIds = {'k8s', 'jvm', 'db', 'net', 'build', 'ops', 'ai', 'shell'};
const _circled = ['①', '②', '③', '④'];

/// 서버가 준 spriteKey가 번들에 있는 SVG면 그 경로, 아니면 건물 기본 몬스터.
String monsterAsset(MonsterInfo m, String dungeonId) {
  final match = RegExp(r'^(monster|boss)_([a-z0-9]+)$').firstMatch(m.spriteKey);
  if (match != null && _dungeonIds.contains(match.group(2))) {
    return 'assets/world/${m.spriteKey}.svg';
  }
  final id = _dungeonIds.contains(dungeonId) ? dungeonId : 'k8s';
  return 'assets/world/${m.isBoss ? 'boss' : 'monster'}_$id.svg';
}

/// 고른 캐릭터의 전투 뒷모습.
String studentBackAsset(StudentLook look) =>
    'assets/world/student_${look.name}_back.svg';

/// 받침 있는 한글로 끝나면 [withFinal], 받침 없으면 [without]. 한글이 아니면 둘 다.
String _josa(String word, String withFinal, String without) {
  if (word.isEmpty) return without;
  final c = word.codeUnitAt(word.length - 1);
  if (c < 0xAC00 || c > 0xD7A3) return '$withFinal($without)';
  return (c - 0xAC00) % 28 == 0 ? without : withFinal;
}

/// 몬스터를 만났을 때의 문제 배틀 화면.
/// 위는 전투 장면(적·주인공 뒷모습·HP 상자·대사창), 아래는 문제 카드와 2×2 보기 버튼.
/// 화면 폭과 상관없이 세로로 쌓고 최대 폭 560으로 가운데 정렬한다.
class BattleOverlay extends StatelessWidget {
  const BattleOverlay({
    super.key,
    required this.dungeonId,
    required this.dungeonName,
    required this.encounter,
    required this.hearts,
    required this.kills,
    required this.killsToBoss,
    required this.onChoice,
    required this.onContinue,
    this.maxHearts = 3,
    this.selectedIndex,
    this.result,
    this.look = StudentLook.boy,
  });

  final String dungeonId;
  final String dungeonName;
  final Encounter encounter;
  final int hearts;
  final int maxHearts;
  final int kills;
  final int killsToBoss;
  final int? selectedIndex;
  final AnswerResult? result;
  final ValueChanged<int> onChoice;
  final VoidCallback onContinue;
  final StudentLook look;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withAlpha(140),
      child: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 560),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  _ScenePanel(
                    dungeonId: dungeonId,
                    encounter: encounter,
                    hearts: hearts,
                    maxHearts: maxHearts,
                    kills: kills,
                    killsToBoss: killsToBoss,
                    result: result,
                    look: look,
                  ),
                  const SizedBox(height: 12),
                  _QuestionCard(
                    dungeonName: dungeonName,
                    encounter: encounter,
                    kills: kills,
                    killsToBoss: killsToBoss,
                    selectedIndex: selectedIndex,
                    result: result,
                    onChoice: onChoice,
                    onContinue: onContinue,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

BoxDecoration _panel() => BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(20),
      boxShadow: const [
        BoxShadow(
            color: Color(0x33000000), blurRadius: 24, offset: Offset(0, 8)),
      ],
    );

/// 장면 좌표계(400×270). FittedBox로 패널 폭에 맞춰 늘리고 줄인다.
const _sceneW = 400.0;
const _sceneH = 270.0;
const _enemyBase = Offset(292, 118);
const _playerBase = Offset(112, 226);

class _ScenePanel extends StatelessWidget {
  const _ScenePanel({
    required this.dungeonId,
    required this.encounter,
    required this.hearts,
    required this.maxHearts,
    required this.kills,
    required this.killsToBoss,
    required this.result,
    required this.look,
  });

  final String dungeonId;
  final Encounter encounter;
  final int hearts, maxHearts, kills, killsToBoss;
  final AnswerResult? result;
  final StudentLook look;

  @override
  Widget build(BuildContext context) {
    final m = encounter.monster;
    final defeated = result?.correct == true;
    // 보스는 160 viewBox 안에 여백이 거의 없어 상자를 그대로 쓴다(잘리지 않게).
    final mSize = m.isBoss ? 122.0 : 104.0;
    return Container(
      decoration: _panel(),
      clipBehavior: Clip.antiAlias,
      child: AspectRatio(
        aspectRatio: _sceneW / _sceneH,
        child: FittedBox(
          child: SizedBox(
            width: _sceneW,
            height: _sceneH,
            child: Stack(
              children: [
                Positioned.fill(
                    child: CustomPaint(painter: _ScenePainter(boss: m.isBoss))),
                Positioned(
                  left: _enemyBase.dx - mSize / 2,
                  top: _enemyBase.dy + 4 - mSize,
                  width: mSize,
                  height: mSize,
                  child: AnimatedSlide(
                    offset: defeated ? const Offset(0, -0.25) : Offset.zero,
                    duration: const Duration(milliseconds: 600),
                    curve: Curves.easeOut,
                    child: AnimatedScale(
                      scale: defeated ? 0.3 : 1,
                      duration: const Duration(milliseconds: 600),
                      curve: Curves.easeIn,
                      child: AnimatedOpacity(
                        key: const ValueKey('monster'),
                        opacity: defeated ? 0 : 1,
                        duration: const Duration(milliseconds: 600),
                        child: SvgPicture.asset(monsterAsset(m, dungeonId)),
                      ),
                    ),
                  ),
                ),
                Positioned(
                  left: _playerBase.dx - 58,
                  top: _playerBase.dy - 120,
                  width: 116,
                  height: 135,
                  child: _Hop(
                    active: defeated,
                    child: SvgPicture.asset(
                      studentBackAsset(look),
                      key: const ValueKey('student_back'),
                    ),
                  ),
                ),
                Positioned(
                  left: 14,
                  top: 14,
                  width: 184,
                  child: _HpBox(
                    title: m.name,
                    badge: m.isBoss ? '보스' : null,
                    subtitle: '주제 · ${encounter.question.topicTitle}',
                    trailing: _HpBar(alive: !defeated),
                  ),
                ),
                Positioned(
                  left: 222,
                  top: 144,
                  width: 164,
                  child: _HpBox(
                    title: '나',
                    subtitle:
                        List.generate(maxHearts, (i) => i < hearts ? '♥' : '♡')
                            .join(' '),
                    subtitleColor: _heart,
                    trailing: Text(
                      m.isBoss ? '보스전' : '처치 $kills/$killsToBoss',
                      style: const TextStyle(fontSize: 10, color: _muted),
                    ),
                  ),
                ),
                Positioned(
                  left: 10,
                  right: 10,
                  bottom: 10,
                  height: 54,
                  child: _MessageBox(monster: m, result: result),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _HpBox extends StatelessWidget {
  const _HpBox({
    required this.title,
    required this.subtitle,
    required this.trailing,
    this.badge,
    this.subtitleColor = _muted,
  });

  final String title, subtitle;
  final String? badge;
  final Color subtitleColor;
  final Widget trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 7, 12, 8),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(240),
        borderRadius: BorderRadius.circular(14),
        boxShadow: const [
          BoxShadow(
              color: Color(0x2E000000), blurRadius: 10, offset: Offset(0, 4)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              if (badge != null) ...[
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                        colors: [Color(0xFFFF9A8B), Color(0xFFF7708A)]),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(badge!,
                      style: const TextStyle(
                          fontSize: 9,
                          fontWeight: FontWeight.w700,
                          color: Colors.white)),
                ),
                const SizedBox(width: 5),
              ],
              Expanded(
                child: Text(title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: _ink)),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(
                child: Text(subtitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 10, color: subtitleColor)),
              ),
              const SizedBox(width: 6),
              trailing,
            ],
          ),
        ],
      ),
    );
  }
}

class _HpBar extends StatelessWidget {
  const _HpBar({required this.alive});
  final bool alive;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 56,
      height: 7,
      alignment: Alignment.centerLeft,
      decoration:
          BoxDecoration(color: _line, borderRadius: BorderRadius.circular(4)),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 500),
        width: alive ? 56 : 0,
        decoration: BoxDecoration(
            gradient: const LinearGradient(
                colors: [Color(0xFF7FD9A4), Color(0xFF45C08A)]),
            borderRadius: BorderRadius.circular(4)),
      ),
    );
  }
}

/// 장면 아래 대사창. 등장 문구, 답한 뒤에는 결과 한 줄.
class _MessageBox extends StatelessWidget {
  const _MessageBox({required this.monster, required this.result});
  final MonsterInfo monster;
  final AnswerResult? result;

  @override
  Widget build(BuildContext context) {
    final name = monster.name;
    final boss = monster.isBoss;
    final r = result;
    final (lead, tail, sub, nameColor) = switch (r) {
      null when boss => (
          '보스 ',
          '${_josa(name, '이', '가')} 앞을 막아섰다!',
          '3연속으로 맞히면 이긴다!',
          const Color(0xFFD9466A),
        ),
      null => (
          '야생의 ',
          '${_josa(name, '이', '가')} 나타났다!',
          '어떻게 대답할까?',
          const Color(0xFFE0565B),
        ),
      AnswerResult(correct: true, :final cleared) => (
          '',
          '${_josa(name, '을', '를')} 물리쳤다!',
          cleared ? '던전을 정복했다!' : '정답이다!',
          _okText,
        ),
      AnswerResult(:final failed) => (
          '',
          '의 반격! ♥ -1',
          failed ? '하트를 모두 잃었다…' : '아래 해설을 읽어 보자.',
          _ngText,
        ),
    };
    const base = TextStyle(fontSize: 13, height: 1.35, color: _ink);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
      decoration: BoxDecoration(
        color: Colors.white.withAlpha(245),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
            color: boss ? const Color(0xFFF3B2C4) : const Color(0xFFC9D6F5),
            width: 1.5),
        boxShadow: const [
          BoxShadow(
              color: Color(0x26000000), blurRadius: 10, offset: Offset(0, 3)),
        ],
      ),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text.rich(
            key: const ValueKey('battle_message'),
            TextSpan(children: [
              TextSpan(text: lead),
              TextSpan(
                  text: name,
                  style:
                      TextStyle(color: nameColor, fontWeight: FontWeight.w700)),
              TextSpan(text: tail),
            ]),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: base.copyWith(
                fontWeight: boss ? FontWeight.w700 : FontWeight.w500),
          ),
          Text(sub,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: base.copyWith(fontSize: 12, color: _muted)),
        ],
      ),
    );
  }
}

/// [active]가 true가 되면 두 번 깡충 뛴다(학생 celebrate).
class _Hop extends StatefulWidget {
  const _Hop({required this.active, required this.child});
  final bool active;
  final Widget child;

  @override
  State<_Hop> createState() => _HopState();
}

class _HopState extends State<_Hop> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(
      vsync: this, duration: const Duration(milliseconds: 900));

  @override
  void didUpdateWidget(covariant _Hop old) {
    super.didUpdateWidget(old);
    if (widget.active && !old.active) _c.forward(from: 0);
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _c,
      builder: (context, child) {
        final t = _c.value;
        // 0..1 동안 두 번 뜀
        final lift =
            _c.isAnimating ? (t * 2 % 1 * (1 - t * 2 % 1)) * 4 * 16 : 0.0;
        return Transform.translate(offset: Offset(0, -lift), child: child);
      },
      child: widget.child,
    );
  }
}

/// 하늘·구름·먼 숲 띠·줄무늬 풀밭·발판. 보스는 저녁 하늘과 붉은 기운으로 강조한다.
class _ScenePainter extends CustomPainter {
  const _ScenePainter({required this.boss});
  final bool boss;

  static const _horizon = 112.0;

  @override
  void paint(Canvas canvas, Size size) {
    final sky = Rect.fromLTWH(0, 0, size.width, _horizon + 4);
    canvas.drawRect(
      sky,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: boss
              ? const [Color(0xFF8C7BC9), Color(0xFFF2B5C4)]
              : const [Color(0xFF9FD4FF), Color(0xFFDDF1FF)],
        ).createShader(sky),
    );

    final cloud = Paint()..color = Colors.white.withAlpha(boss ? 90 : 210);
    void puff(double x, double y, double s) {
      canvas.drawOval(
          Rect.fromCenter(center: Offset(x, y), width: 46 * s, height: 18 * s),
          cloud);
      canvas.drawCircle(Offset(x - 6 * s, y - 6 * s), 10 * s, cloud);
      canvas.drawCircle(Offset(x + 8 * s, y - 4 * s), 8 * s, cloud);
    }

    puff(230, 30, 1.1);
    puff(360, 58, 0.8);
    puff(150, 76, 0.7);

    // 먼 숲: 겹친 둥근 수관 두 겹
    final far = Paint()
      ..color = boss ? const Color(0xFF6E8C86) : const Color(0xFF8FCB9B);
    final near = Paint()
      ..color = boss ? const Color(0xFF5D7D74) : const Color(0xFF74B983);
    for (var x = -10.0; x < size.width + 20; x += 26) {
      canvas.drawCircle(Offset(x, _horizon - 4 + (x % 3) * 2), 18, far);
    }
    for (var x = 4.0; x < size.width + 20; x += 30) {
      canvas.drawCircle(Offset(x, _horizon + 4), 15, near);
    }

    // 줄무늬 풀밭
    final field = Rect.fromLTRB(0, _horizon + 8, size.width, size.height);
    canvas.drawRect(
      field,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: boss
              ? const [Color(0xFF9CC79A), Color(0xFF86B588)]
              : const [Color(0xFFB4E59C), Color(0xFF98D687)],
        ).createShader(field),
    );
    final stripe = Paint()..color = Colors.white.withAlpha(boss ? 22 : 40);
    for (var y = field.top + 6; y < size.height; y += 22) {
      canvas.drawRect(Rect.fromLTWH(0, y, size.width, 9), stripe);
    }

    if (boss) {
      canvas.drawCircle(
        _enemyBase.translate(0, -56),
        92,
        Paint()
          ..shader = const RadialGradient(colors: [
            Color(0x99FF8FA3),
            Color(0x00FF8FA3),
          ]).createShader(Rect.fromCircle(
              center: _enemyBase.translate(0, -56), radius: 92)),
      );
    }

    _platform(canvas, _enemyBase, 156, 40);
    _platform(canvas, _playerBase, 190, 48);
  }

  void _platform(Canvas canvas, Offset c, double w, double h) {
    canvas.drawOval(
        Rect.fromCenter(center: c.translate(0, 5), width: w + 6, height: h),
        Paint()..color = const Color(0x22000000));
    final top = Rect.fromCenter(center: c, width: w, height: h);
    canvas.drawOval(
      top,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: boss
              ? const [Color(0xFFC9E0B8), Color(0xFF9EC392)]
              : const [Color(0xFFDDF5C4), Color(0xFFA9DA8F)],
        ).createShader(top),
    );
    canvas.drawOval(
        Rect.fromCenter(
            center: c.translate(0, -3), width: w * 0.7, height: h * 0.45),
        Paint()..color = Colors.white.withAlpha(60));
  }

  @override
  bool shouldRepaint(covariant _ScenePainter old) => old.boss != boss;
}

class _QuestionCard extends StatelessWidget {
  const _QuestionCard({
    required this.dungeonName,
    required this.encounter,
    required this.kills,
    required this.killsToBoss,
    required this.selectedIndex,
    required this.result,
    required this.onChoice,
    required this.onContinue,
  });

  final String dungeonName;
  final Encounter encounter;
  final int kills, killsToBoss;
  final int? selectedIndex;
  final AnswerResult? result;
  final ValueChanged<int> onChoice;
  final VoidCallback onContinue;

  @override
  Widget build(BuildContext context) {
    final q = encounter.question;
    final r = result;
    final locked = selectedIndex != null || r != null;
    final isBoss = encounter.monster.isBoss;
    final left = (killsToBoss - kills).clamp(0, killsToBoss);
    Widget choice(int i) => _ChoiceButton(
          key: ValueKey('choice_$i'),
          number: i < 4 ? _circled[i] : '${i + 1}.',
          text: q.choices[i],
          palette: _palettes[i % _palettes.length],
          mark: _markFor(i),
          onPressed: locked ? null : () => onChoice(i),
        );
    return Container(
      decoration: _panel(),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            color: isBoss ? const Color(0xFFFFF1F4) : const Color(0xFFF6F8FC),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
            child: Row(
              children: [
                const Icon(Icons.castle_outlined, size: 16, color: _ink),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(dungeonName,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: _ink)),
                ),
                Text(
                  isBoss ? '보스전 · 3연속 정답' : '보스까지 $left마리',
                  style: TextStyle(
                      fontSize: 13,
                      color: isBoss ? _ngText : _ink,
                      fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 14, 14, 14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Container(
                  padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF7F9FE),
                    borderRadius: BorderRadius.circular(14),
                    border: Border.all(color: _line),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                            horizontal: 10, vertical: 3),
                        decoration: BoxDecoration(
                          color: const Color(0xFFEEF2FF),
                          borderRadius: BorderRadius.circular(999),
                        ),
                        child: Text(q.topicTitle,
                            style:
                                const TextStyle(fontSize: 12, color: _accent)),
                      ),
                      const SizedBox(height: 8),
                      Text(q.stem,
                          style: const TextStyle(
                              fontSize: 16,
                              height: 1.45,
                              fontWeight: FontWeight.w600,
                              color: _ink)),
                    ],
                  ),
                ),
                for (var i = 0; i < q.choices.length; i += 2) ...[
                  const SizedBox(height: 10),
                  // 한 줄 두 버튼 높이 맞춤. main.dart가 runApp 전에 Noto Sans KR
                  // w400·w600을 미리 받는 것에 기대고 있다. 글꼴이 첫 레이아웃 뒤에
                  // 늦게 오면 줄 높이가 다시 계산되지 않아 긴 보기가 잘릴 수 있다.
                  IntrinsicHeight(
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Expanded(child: choice(i)),
                        const SizedBox(width: 10),
                        Expanded(
                            child: i + 1 < q.choices.length
                                ? choice(i + 1)
                                : const SizedBox.shrink()),
                      ],
                    ),
                  ),
                ],
                if (r != null) ...[
                  const SizedBox(height: 12),
                  _Feedback(result: r, monsterName: encounter.monster.name),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerRight,
                    child: FilledButton(
                      key: const ValueKey('battle_continue'),
                      autofocus: true,
                      onPressed: onContinue,
                      child: Text(_continueLabel(r, isBoss)),
                    ),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }

  _Mark _markFor(int i) {
    final r = result;
    if (r == null) {
      return i == selectedIndex ? _Mark.pending : _Mark.none;
    }
    if (i == r.correctIndex) return _Mark.correct;
    if (i == selectedIndex) return _Mark.wrong;
    return _Mark.dim;
  }

  static String _continueLabel(AnswerResult r, bool isBoss) {
    if (r.cleared) return '깃발 꽂기';
    if (r.failed) return '마을로';
    if (isBoss) return '다음 문제';
    return '계속 걷기';
  }
}

enum _Mark { none, pending, correct, wrong, dim }

/// 보기 버튼 색: 위·아래 그러데이션, 아래 테두리(입체감), 글자색.
typedef _Palette = ({Color top, Color bottom, Color edge, Color text});

const List<_Palette> _palettes = [
  (
    top: Color(0xFFFFC2BA),
    bottom: Color(0xFFFF9C91),
    edge: Color(0xFFE77B6F),
    text: Color(0xFF6B211A)
  ),
  (
    top: Color(0xFFBCD3FF),
    bottom: Color(0xFF8FB0F7),
    edge: Color(0xFF6E90E0),
    text: Color(0xFF1D3270)
  ),
  (
    top: Color(0xFFB7EBC6),
    bottom: Color(0xFF86D39E),
    edge: Color(0xFF62B47F),
    text: Color(0xFF17502C)
  ),
  (
    top: Color(0xFFFFDCAA),
    bottom: Color(0xFFFFBC6E),
    edge: Color(0xFFE39C4A),
    text: Color(0xFF6A3A07)
  ),
];

class _ChoiceButton extends StatelessWidget {
  const _ChoiceButton({
    super.key,
    required this.number,
    required this.text,
    required this.palette,
    required this.mark,
    this.onPressed,
  });

  final String number, text;
  final _Palette palette;
  final _Mark mark;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final (ring, suffix) = switch (mark) {
      _Mark.correct => (_okBorder, ' ✓'),
      _Mark.wrong => (_ngBorder, ' ✗'),
      _Mark.pending => (_ink, ''),
      _Mark.none || _Mark.dim => (Colors.transparent, ''),
    };
    final radius = BorderRadius.circular(18);
    return AnimatedOpacity(
      opacity: mark == _Mark.dim ? 0.6 : 1,
      duration: const Duration(milliseconds: 250),
      // 정답·오답·선택 표시는 버튼 위에 겹친 테두리(배경 그러데이션에 가리지 않게).
      child: Container(
        foregroundDecoration: BoxDecoration(
          borderRadius: radius,
          border: Border.all(color: ring, width: 3),
        ),
        child: OutlinedButton(
          onPressed: onPressed,
          style: OutlinedButton.styleFrom(
            padding: EdgeInsets.zero,
            alignment: Alignment.topLeft,
            foregroundColor: palette.text,
            disabledForegroundColor: palette.text,
            // 아래 4px로 드러나는 진한 테두리(입체감)
            backgroundColor: palette.edge,
            disabledBackgroundColor: palette.edge,
            side: BorderSide.none,
            shape: RoundedRectangleBorder(borderRadius: radius),
            // 테마 글꼴을 이어받아야 CanvasKit에서 한글이 □로 남지 않는다.
            textStyle: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(fontSize: 14, height: 1.35),
            // 버튼 전체를 채우는 그러데이션 + 안쪽 하이라이트. Ink라 물결이 위에 그려진다.
            backgroundBuilder: (context, states, child) => Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Ink(
                decoration: BoxDecoration(
                  borderRadius: radius,
                  gradient: LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [palette.top, palette.bottom],
                  ),
                ),
                child: Stack(
                  children: [
                    Positioned(
                      left: 12,
                      right: 12,
                      top: 5,
                      height: 9,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: Colors.white.withAlpha(80),
                          borderRadius: BorderRadius.circular(999),
                        ),
                      ),
                    ),
                    if (child != null) child,
                  ],
                ),
              ),
            ),
          ),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 12, 12, 14),
            child: ConstrainedBox(
              constraints: const BoxConstraints(minHeight: 52),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(number,
                      style: TextStyle(
                          fontSize: 13,
                          fontWeight: FontWeight.w600,
                          color: palette.text.withAlpha(180))),
                  const SizedBox(height: 2),
                  Text('$text$suffix',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _Feedback extends StatelessWidget {
  const _Feedback({required this.result, required this.monsterName});
  final AnswerResult result;
  final String monsterName;

  @override
  Widget build(BuildContext context) {
    final ok = result.correct;
    final head = ok ? '정답! $monsterName 처치.' : '오답… ♥ -1.';
    final source = result.sourceRef;
    return Container(
      key: const ValueKey('battle_feedback'),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: ok ? _okBg : _ngBg,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('$head ${result.explanation}',
              style: TextStyle(
                  fontSize: 13, height: 1.5, color: ok ? _okText : _ngText)),
          const SizedBox(height: 6),
          SelectableText(
            '원문 주제 · ${result.topicTitle}'
            '${source != null && source.isNotEmpty ? ' ($source)' : ''}',
            style: const TextStyle(fontSize: 12, color: _muted),
          ),
        ],
      ),
    );
  }
}
