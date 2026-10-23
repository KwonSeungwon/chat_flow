import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'state/game_models.dart';

/// 마을에 처음 들어올 때(또는 마을 메뉴의 "캐릭터 바꾸기") 뜨는 주인공 선택
/// 카드. 화면 전체를 덮어 뒤의 마을이 탭을 받지 않게 한다.
class LookPicker extends StatefulWidget {
  const LookPicker({
    super.key,
    required this.initial,
    required this.onPick,
    this.onCancel,
  });

  final StudentLook initial;
  final ValueChanged<StudentLook> onPick;

  /// 있으면 닫기 버튼을 보인다(이미 고른 사람이 다시 열었을 때).
  final VoidCallback? onCancel;

  @override
  State<LookPicker> createState() => _LookPickerState();
}

class _LookPickerState extends State<LookPicker> {
  late StudentLook _look = widget.initial;

  static const _ink = Color(0xFF3A3550);

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        const ModalBarrier(color: Color(0x40283C5A), dismissible: false),
        Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Container(
                padding: const EdgeInsets.fromLTRB(22, 22, 22, 20),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topCenter,
                    end: Alignment.bottomCenter,
                    colors: [Color(0xFFFFFCF6), Color(0xFFFFF1E2)],
                  ),
                  borderRadius: BorderRadius.circular(26),
                  boxShadow: const [
                    BoxShadow(
                        color: Color(0x40283C5A),
                        blurRadius: 40,
                        offset: Offset(0, 12)),
                  ],
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        const Expanded(
                          child: Text(
                            '어떤 학생으로 입학할래?',
                            style: TextStyle(
                                fontSize: 20,
                                fontWeight: FontWeight.w700,
                                color: _ink),
                          ),
                        ),
                        if (widget.onCancel != null)
                          IconButton(
                            icon: const Icon(Icons.close_rounded),
                            color: const Color(0xFF8A86A0),
                            tooltip: '닫기',
                            onPressed: widget.onCancel,
                          ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    const Text(
                      '나중에 마을 메뉴에서 바꿀 수 있어요.',
                      style: TextStyle(fontSize: 13, color: Color(0xFF7A7690)),
                    ),
                    const SizedBox(height: 18),
                    Row(
                      children: [
                        for (final look in StudentLook.values) ...[
                          if (look != StudentLook.values.first)
                            const SizedBox(width: 14),
                          Expanded(
                            child: _LookCard(
                              look: look,
                              selected: look == _look,
                              onTap: () => setState(() => _look = look),
                            ),
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      style: FilledButton.styleFrom(
                        backgroundColor: const Color(0xFFFF8FA3),
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 14),
                        shape: const StadiumBorder(),
                        textStyle: const TextStyle(
                            fontSize: 16, fontWeight: FontWeight.w700),
                        elevation: 2,
                        shadowColor: const Color(0x55FF8FA3),
                      ),
                      onPressed: () => widget.onPick(_look),
                      child: const Text('이 학생으로 입학!'),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _LookCard extends StatelessWidget {
  const _LookCard(
      {required this.look, required this.selected, required this.onTap});

  final StudentLook look;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final (label, halo) = switch (look) {
      StudentLook.boy => ('남학생', const Color(0xFFDDEEFF)),
      StudentLook.girl => ('여학생', const Color(0xFFFFE3EA)),
    };
    return Semantics(
      button: true,
      selected: selected,
      child: GestureDetector(
        onTap: onTap,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding: const EdgeInsets.fromLTRB(10, 14, 10, 12),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFFFFF4F6) : Colors.white,
            borderRadius: BorderRadius.circular(20),
            border: Border.all(
              color:
                  selected ? const Color(0xFFFF9FB2) : const Color(0xFFEDE7F3),
              width: selected ? 2.5 : 1.5,
            ),
            boxShadow: [
              BoxShadow(
                color: selected
                    ? const Color(0x33FF8FA3)
                    : const Color(0x1A283C5A),
                blurRadius: selected ? 18 : 10,
                offset: const Offset(0, 6),
              ),
            ],
          ),
          child: Column(
            children: [
              Container(
                width: 104,
                height: 104,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: halo, shape: BoxShape.circle),
                child: SvgPicture.asset(
                  'assets/world/student_${look.name}_down.svg',
                  height: 84,
                ),
              ),
              const SizedBox(height: 10),
              Text(
                label,
                style: TextStyle(
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                  color: selected
                      ? const Color(0xFFE0607A)
                      : const Color(0xFF3A3550),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
