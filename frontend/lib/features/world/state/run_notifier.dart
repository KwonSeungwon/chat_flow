import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'game_api.dart';
import 'game_models.dart';

enum RunPhase {
  /// 판을 여는 중.
  starting,

  /// 던전을 걷는 중(배틀 없음).
  exploring,

  /// 인카운터가 나서 문제를 받는 중.
  loadingEncounter,

  /// 문제를 보여 주는 중. [RunState.result]가 있으면 답한 뒤다.
  battle,

  /// 답을 보내는 중.
  answering,
  cleared,
  failed,

  /// 서버에 판이 없다(`RUN_EXPIRED`): "던전이 무너졌다".
  expired,

  /// 답을 보낸 뒤 `RUN_EXPIRED`: 재시도 전에 판이 이미 끝나(클리어/실패) 지워졌다.
  /// 화면은 진행도를 다시 읽어 클리어인지 가린다.
  ended,

  /// 공사 중인 건물(`DUNGEON_CLOSED`).
  closed,

  /// 판을 열지 못했다.
  error,
}

class RunState {
  final RunPhase phase;
  final String? runId;
  final int hearts;
  final int hp;
  final int maxHp;
  final int kills;
  final int killsToBoss;
  final bool bossUnlocked;

  /// 도감 복습 판. [reviewAnswered]는 지금까지 답한 문제 수.
  final bool review;
  final int reviewTotal;
  final int reviewAnswered;
  final Encounter? encounter;
  final int? selectedIndex;
  final AnswerResult? result;

  /// 스낵바로 한 번 보여 줄 안내. 보여 준 뒤 [RunNotifier.clearNotice].
  final String? notice;

  const RunState({
    this.phase = RunPhase.starting,
    this.runId,
    this.hearts = 3,
    this.hp = defaultMaxHp,
    this.maxHp = defaultMaxHp,
    this.kills = 0,
    this.killsToBoss = 5,
    this.bossUnlocked = false,
    this.review = false,
    this.reviewTotal = 0,
    this.reviewAnswered = 0,
    this.encounter,
    this.selectedIndex,
    this.result,
    this.notice,
  });

  bool get inBattle =>
      phase == RunPhase.battle || phase == RunPhase.answering;

  RunState copyWith({
    RunPhase? phase,
    String? runId,
    int? hearts,
    int? hp,
    int? maxHp,
    int? kills,
    int? killsToBoss,
    bool? bossUnlocked,
    int? reviewTotal,
    int? reviewAnswered,
    Encounter? Function()? encounter,
    int? Function()? selectedIndex,
    AnswerResult? Function()? result,
    String? Function()? notice,
  }) =>
      RunState(
        phase: phase ?? this.phase,
        runId: runId ?? this.runId,
        hearts: hearts ?? this.hearts,
        hp: hp ?? this.hp,
        maxHp: maxHp ?? this.maxHp,
        kills: kills ?? this.kills,
        killsToBoss: killsToBoss ?? this.killsToBoss,
        bossUnlocked: bossUnlocked ?? this.bossUnlocked,
        review: review,
        reviewTotal: reviewTotal ?? this.reviewTotal,
        reviewAnswered: reviewAnswered ?? this.reviewAnswered,
        encounter: encounter != null ? encounter() : this.encounter,
        selectedIndex:
            selectedIndex != null ? selectedIndex() : this.selectedIndex,
        result: result != null ? result() : this.result,
        notice: notice != null ? notice() : this.notice,
      );
}

const _offlineNotice = '연결이 끊겼다. 네트워크를 확인해 줘.';
const _unavailableNotice = '던전 관리인이 자리를 비웠다. 잠시 뒤 다시 시도해 줘.';
const _genericNotice = '알 수 없는 오류가 났다. 다시 시도해 줘.';

String _noticeFor(GameApiException e) => switch (e.code) {
      GameApiException.offline => _offlineNotice,
      GameApiException.unavailable => _unavailableNotice,
      GameApiException.notCaught => '아직 잡지 못한 몬스터다. 던전에서 정답을 맞혀 잡아 봐.',
      GameApiException.topicNotFound => '도감에 없는 몬스터다.',
      _ => _genericNotice,
    };

class RunNotifier extends StateNotifier<RunState> {
  /// [startRun]을 주면 판을 그것으로 연다(도감 복습). 없으면 [dungeonId] 던전 판.
  RunNotifier(this._api, this.dungeonId,
      {this.retryDelay = const Duration(milliseconds: 400),
      Future<RunStart> Function()? startRun})
      : _startRun = startRun,
        super(const RunState());

  final GameApi _api;
  final String dungeonId;
  final Duration retryDelay;
  final Future<RunStart> Function()? _startRun;

  Future<void> start() async {
    state = const RunState(phase: RunPhase.starting);
    try {
      final run = await (_startRun?.call() ?? _api.startRun(dungeonId));
      if (!mounted) return;
      state = RunState(
        phase: RunPhase.exploring,
        runId: run.runId,
        hearts: run.hearts,
        hp: run.hp,
        maxHp: run.maxHp,
        kills: run.kills,
        killsToBoss: run.killsToBoss,
        review: run.review,
        reviewTotal: run.reviewTotal,
      );
    } on GameApiException catch (e) {
      if (!mounted) return;
      state = e.code == GameApiException.dungeonClosed
          ? const RunState(phase: RunPhase.closed)
          : RunState(phase: RunPhase.error, notice: _noticeFor(e));
    }
  }

  /// 걷다가 몬스터를 만났다. 문제를 받으면 true. 오프라인 등으로 못 받으면
  /// [RunState.notice]를 채우고 false(계속 걷는다).
  Future<bool> encounter() async {
    if (state.phase != RunPhase.exploring) return false;
    return _loadNext();
  }

  Future<bool> _loadNext() async {
    final runId = state.runId;
    if (runId == null) return false;
    state = state.copyWith(phase: RunPhase.loadingEncounter);
    try {
      final enc = await _api.next(runId);
      if (!mounted) return false;
      state = state.copyWith(
        phase: RunPhase.battle,
        encounter: () => enc,
        selectedIndex: () => null,
        result: () => null,
      );
      return true;
    } on GameApiException catch (e) {
      if (!mounted) return false;
      if (e.code == GameApiException.runExpired) {
        state = state.copyWith(phase: RunPhase.expired);
      } else {
        state = state.copyWith(
          phase: RunPhase.exploring,
          encounter: () => null,
          result: () => null,
          selectedIndex: () => null,
          notice: () => _noticeFor(e),
        );
      }
      return false;
    }
  }

  /// 선택지 [choice]로 답한다. 이미 답했거나 보내는 중이면 무시한다.
  Future<void> answer(int choice) async {
    final enc = state.encounter;
    if (state.phase != RunPhase.battle || state.result != null || enc == null) {
      return;
    }
    state = state.copyWith(
        phase: RunPhase.answering, selectedIndex: () => choice);
    await _submit(enc.seq, choice, retried: false);
  }

  Future<void> _submit(int seq, int choice, {required bool retried}) async {
    try {
      final r = await _api.answer(state.runId!, seq, choice);
      if (!mounted) return;
      state = state.copyWith(
        phase: RunPhase.battle,
        result: () => r,
        hearts: r.hearts,
        hp: r.hp,
        maxHp: r.maxHp,
        kills: r.kills,
        bossUnlocked: r.bossUnlocked,
        reviewTotal: r.reviewTotal > 0 ? r.reviewTotal : null,
        reviewAnswered: state.review ? state.reviewAnswered + 1 : null,
      );
    } on GameApiException catch (e) {
      if (!mounted) return;
      if (e.code == GameApiException.runExpired) {
        state = state.copyWith(phase: RunPhase.ended);
      } else if (e.code == GameApiException.answerInProgress && !retried) {
        await Future<void>.delayed(retryDelay);
        if (!mounted) return;
        await _submit(seq, choice, retried: true);
      } else if (e.code == GameApiException.seqMismatch && !retried) {
        // 서버가 다른 문제를 기다린다. 그 문제를 다시 받아 보여 준다.
        if (await _loadNext()) {
          state = state.copyWith(notice: () => '문제가 바뀌었다. 다시 골라 줘.');
        }
      } else {
        state = state.copyWith(
          phase: RunPhase.battle,
          selectedIndex: () => null,
          notice: () => _noticeFor(e),
        );
      }
    }
  }

  /// 결과를 본 뒤 "계속". 클리어/실패로 끝내거나, 몬스터가 아직 살아 있거나
  /// 보스전·복습이면 다음 문제를 바로 받고, 아니면 다시 걷는다.
  Future<void> finishBattle() async {
    final r = state.result;
    if (state.phase != RunPhase.battle || r == null) return;
    if (r.cleared) {
      state = state.copyWith(phase: RunPhase.cleared);
    } else if (r.failed) {
      state = state.copyWith(phase: RunPhase.failed);
    } else if (!r.monsterDefeated ||
        state.review ||
        state.encounter?.monster.isBoss == true) {
      await _loadNext();
    } else {
      state = state.copyWith(
        phase: RunPhase.exploring,
        encounter: () => null,
        selectedIndex: () => null,
        result: () => null,
      );
    }
  }

  void clearNotice() {
    if (state.notice != null) state = state.copyWith(notice: () => null);
  }
}

final runNotifierProvider = StateNotifierProvider.autoDispose
    .family<RunNotifier, RunState, String>((ref, dungeonId) {
  return RunNotifier(ref.watch(gameApiProvider), dungeonId);
});

/// 도감 복습 판(주제 [topicId]).
final reviewRunProvider = StateNotifierProvider.autoDispose
    .family<RunNotifier, RunState, int>((ref, topicId) {
  final api = ref.watch(gameApiProvider);
  return RunNotifier(api, '', startRun: () => api.startReview(topicId));
});
