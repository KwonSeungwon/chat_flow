import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../state/game_models.dart';

const _ink = Color(0xFF24304D);
const _muted = Color(0xFF6B7896);
const _line = Color(0xFFE3E8F2);
const _okBorder = Color(0xFF2FB88A);
const _okBg = Color(0xFFE9FBF3);
const _okText = Color(0xFF2F8F6A);
const _ngBorder = Color(0xFFFF7A6B);
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

/// 몬스터를 만났을 때의 문제 배틀 화면(battle-screen 시안).
/// 왼쪽은 몬스터와 학생 뒷모습, 오른쪽은 문제 카드. 768px 미만은 세로로 쌓는다.
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

  @override
  Widget build(BuildContext context) {
    final wide = MediaQuery.sizeOf(context).width >= 768;
    final scene = _ScenePanel(
      dungeonId: dungeonId,
      encounter: encounter,
      hearts: hearts,
      maxHearts: maxHearts,
      kills: kills,
      killsToBoss: killsToBoss,
      defeated: result?.correct == true,
    );
    final card = _QuestionCard(
      dungeonName: dungeonName,
      encounter: encounter,
      kills: kills,
      killsToBoss: killsToBoss,
      selectedIndex: selectedIndex,
      result: result,
      onChoice: onChoice,
      onContinue: onContinue,
    );
    return Material(
      color: Colors.black.withAlpha(140),
      child: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1040),
              child: wide
                  ? Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Expanded(flex: 100, child: scene),
                        const SizedBox(width: 16),
                        Expanded(flex: 115, child: card),
                      ],
                    )
                  : Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [scene, const SizedBox(height: 12), card],
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
      borderRadius: BorderRadius.circular(14),
      boxShadow: const [
        BoxShadow(color: Color(0x263B4A6B), blurRadius: 24, offset: Offset(0, 8)),
      ],
    );

class _ScenePanel extends StatelessWidget {
  const _ScenePanel({
    required this.dungeonId,
    required this.encounter,
    required this.hearts,
    required this.maxHearts,
    required this.kills,
    required this.killsToBoss,
    required this.defeated,
  });

  final String dungeonId;
  final Encounter encounter;
  final int hearts, maxHearts, kills, killsToBoss;
  final bool defeated;

  @override
  Widget build(BuildContext context) {
    final m = encounter.monster;
    final mSize = m.isBoss ? 150.0 : 112.0;
    return Container(
      decoration: _panel(),
      clipBehavior: Clip.antiAlias,
      child: AspectRatio(
        aspectRatio: 420 / 300,
        child: FittedBox(
          child: SizedBox(
            width: 420,
            height: 300,
            child: Stack(
              children: [
                Positioned.fill(
                    child: CustomPaint(painter: _ScenePainter(boss: m.isBoss))),
                Positioned(
                  left: 290 - mSize / 2,
                  top: 150 - mSize - 2,
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
                  left: 92,
                  top: 122,
                  width: 52,
                  height: 106,
                  child: _Hop(
                    active: defeated,
                    child: SvgPicture.asset('assets/world/student_back.svg'),
                  ),
                ),
                Positioned(
                  left: 24,
                  top: 22,
                  width: 176,
                  child: _NameCard(
                    title: m.isBoss ? '보스 ${m.name}' : '야생의 ${m.name}',
                    subtitle: '주제 · ${encounter.question.topicTitle}',
                    trailing: _HpBar(alive: !defeated),
                  ),
                ),
                Positioned(
                  left: 236,
                  top: 226,
                  width: 164,
                  child: _NameCard(
                    title: '나',
                    subtitle: List.generate(
                            maxHearts, (i) => i < hearts ? '♥' : '♡')
                        .join(' '),
                    subtitleColor: _heart,
                    trailing: Text(
                      m.isBoss ? '보스전' : '처치 $kills/$killsToBoss',
                      style: const TextStyle(fontSize: 10, color: _muted),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _NameCard extends StatelessWidget {
  const _NameCard({
    required this.title,
    required this.subtitle,
    required this.trailing,
    this.subtitleColor = _muted,
  });

  final String title, subtitle;
  final Color subtitleColor;
  final Widget trailing;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 7, 12, 7),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(12),
        boxShadow: const [
          BoxShadow(color: Color(0x590B1028), blurRadius: 12, offset: Offset(0, 6)),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                  fontSize: 13, fontWeight: FontWeight.w700, color: _ink)),
          const SizedBox(height: 3),
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
      width: 48,
      height: 7,
      alignment: Alignment.centerLeft,
      decoration: BoxDecoration(
          color: _line, borderRadius: BorderRadius.circular(4)),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 500),
        width: alive ? 48 : 0,
        decoration: BoxDecoration(
            color: _ngBorder, borderRadius: BorderRadius.circular(4)),
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
        final lift = _c.isAnimating ? (t * 2 % 1 * (1 - t * 2 % 1)) * 4 * 16 : 0.0;
        return Transform.translate(offset: Offset(0, -lift), child: child);
      },
      child: widget.child,
    );
  }
}

class _ScenePainter extends CustomPainter {
  const _ScenePainter({required this.boss});
  final bool boss;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    canvas.drawRect(
      rect,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: boss
              ? const [Color(0xFF141830), Color(0xFF3A2F52)]
              : const [Color(0xFF2A3358), Color(0xFF4A5A8C)],
        ).createShader(rect),
    );
    Path diamond(double cx, double cy, double hw, double hh) => Path()
      ..moveTo(cx, cy - hh)
      ..lineTo(cx + hw, cy)
      ..lineTo(cx, cy + hh)
      ..lineTo(cx - hw, cy)
      ..close();
    canvas.drawPath(diamond(210, 245, 190, 95),
        Paint()..color = boss ? const Color(0xFF3F4570) : const Color(0xFF5D6EA3));
    canvas.drawPath(diamond(210, 245, 150, 75),
        Paint()..color = boss ? const Color(0xFF4B5080) : const Color(0xFF6B7CB3));
    final pillar = Paint()..color = const Color(0xFF3A4675);
    canvas.drawRRect(
        RRect.fromLTRBR(40, 60, 66, 210, const Radius.circular(6)), pillar);
    canvas.drawRRect(
        RRect.fromLTRBR(354, 60, 380, 210, const Radius.circular(6)), pillar);
    final platform = Paint()..color = const Color(0xFF7F8FC4);
    canvas.drawOval(
        Rect.fromCenter(center: const Offset(290, 150), width: 140, height: 44),
        platform);
    canvas.drawCircle(
      const Offset(290, 105),
      60,
      Paint()
        ..shader = RadialGradient(colors: [
          (boss ? const Color(0xFFFF7A6B) : const Color(0xFF9FB4FF)).withAlpha(150),
          const Color(0x009FB4FF),
        ]).createShader(
            Rect.fromCircle(center: const Offset(290, 105), radius: 60)),
    );
    canvas.drawOval(
        Rect.fromCenter(center: const Offset(120, 250), width: 128, height: 40),
        platform);
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
    return Container(
      decoration: _panel(),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            color: const Color(0xFFF6F8FC),
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
            padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
                  decoration: BoxDecoration(
                    color: const Color(0xFFEEF2FF),
                    borderRadius: BorderRadius.circular(999),
                  ),
                  child: Text(q.topicTitle,
                      style: const TextStyle(fontSize: 12, color: _accent)),
                ),
                const SizedBox(height: 8),
                Text(q.stem,
                    style: const TextStyle(
                        fontSize: 17,
                        height: 1.45,
                        fontWeight: FontWeight.w600,
                        color: _ink)),
                const SizedBox(height: 4),
                for (var i = 0; i < q.choices.length; i++)
                  _ChoiceButton(
                    key: ValueKey('choice_$i'),
                    label: '${i < 4 ? _circled[i] : '${i + 1}.'} ${q.choices[i]}',
                    mark: _markFor(i),
                    onPressed: locked ? null : () => onChoice(i),
                  ),
                if (r != null) ...[
                  const SizedBox(height: 10),
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
    return _Mark.none;
  }

  static String _continueLabel(AnswerResult r, bool isBoss) {
    if (r.cleared) return '깃발 꽂기';
    if (r.failed) return '마을로';
    if (isBoss) return '다음 문제';
    return '계속 걷기';
  }
}

enum _Mark { none, pending, correct, wrong }

class _ChoiceButton extends StatelessWidget {
  const _ChoiceButton(
      {super.key, required this.label, required this.mark, this.onPressed});

  final String label;
  final _Mark mark;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final (border, bg, suffix) = switch (mark) {
      _Mark.correct => (_okBorder, _okBg, ' ✓'),
      _Mark.wrong => (_ngBorder, _ngBg, ' ✗'),
      _Mark.pending => (const Color(0xFF6C8CFF), const Color(0xFFF0F3FF), ''),
      _Mark.none => (_line, const Color(0xFFFAFBFF), ''),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: SizedBox(
        width: double.infinity,
        child: OutlinedButton(
          onPressed: onPressed,
          style: OutlinedButton.styleFrom(
            alignment: Alignment.centerLeft,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
            foregroundColor: _ink,
            disabledForegroundColor: _ink,
            backgroundColor: bg,
            disabledBackgroundColor: bg,
            side: BorderSide(color: border, width: 2),
            shape:
                RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            // 테마 글꼴을 이어받아야 CanvasKit에서 한글이 □로 남지 않는다.
            textStyle: Theme.of(context)
                .textTheme
                .bodyMedium
                ?.copyWith(fontSize: 14, height: 1.35),
          ),
          child: Text('$label$suffix'),
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
        borderRadius: BorderRadius.circular(10),
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
