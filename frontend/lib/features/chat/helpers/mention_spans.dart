/// One `@mention` range inside a message body.
class MentionSpan {
  /// Index of the `@`.
  final int start;

  /// Exclusive end of the highlighted range.
  final int end;

  /// True when this names the signed-in user, which renders in the louder
  /// "you were mentioned" colours.
  final bool isMe;

  const MentionSpan(this.start, this.end, {required this.isMe});
}

final RegExp _nameChar = RegExp(r'[\p{L}\p{N}_]', unicode: true);

/// `.` and `-` appear in real usernames (`phill.park`, and the 36-char
/// hyphenated userId that stands in when a username is blank) but they are
/// also ordinary punctuation, so they extend a name without being able to
/// start or end one.
bool _isNameChar(String c) => _nameChar.hasMatch(c);
bool _isInnerNameChar(String c) => _isNameChar(c) || c == '.' || c == '-';

/// The `@mention` ranges to highlight in [content].
///
/// [me] is the signed-in username. It is matched **literally**, so a name with
/// spaces, hyphens, accents or 30+ characters still lights up — the server
/// resolves mentions against the room's member list, and usernames were never
/// validated at registration, so no character class can describe them.
///
/// Everyone else's mentions have no such anchor: this widget does not know the
/// room roster, so their extent is guessed from a permissive character class.
/// `@Phill Park` therefore under-highlights to `@Phill` for onlookers while
/// still highlighting in full for Phill. Cosmetic only — delivery is decided
/// server-side against `room_members`.
///
/// Boundary rules mirror the server: the `@` must not follow a name character
/// (`bob@phill.park` is an email, not a mention) and the name must not be
/// followed by one (`@김간호사님` names nobody).
///
/// What cannot be mirrored is the server's longest-match over the roster. With
/// members `phill` and `phill.park`, the server resolves `@phill.park` to
/// `phill.park`, while `me: 'phill'` still lights up here. So [MentionSpan.isMe]
/// is a strong hint, not a guarantee — the badge and the push come from the
/// server's own `room_members` pass.
///
/// The guess has no length cap on purpose: the old 30-char regex is exactly
/// what broke long usernames, and the server does not cap either.
List<MentionSpan> findMentionSpans(String content, {String? me}) {
  if (content.isEmpty || !content.contains('@')) return const [];
  final spans = <MentionSpan>[];

  var at = content.indexOf('@');
  while (at >= 0) {
    var next = at + 1;
    if (at == 0 || !_isNameChar(content[at - 1])) {
      // The exact self-match wins over the guess: `me` may contain characters
      // the guess stops at, and only this branch can claim isMe.
      if (me != null && me.isNotEmpty && _endsExactly(content, at + 1, me)) {
        next = at + 1 + me.length;
        spans.add(MentionSpan(at, next, isMe: true));
      } else {
        final end = _guessedNameEnd(content, at + 1);
        if (end != null) {
          next = end;
          spans.add(MentionSpan(at, end, isMe: false));
        }
      }
    }
    at = content.indexOf('@', next);
  }
  return spans;
}

/// True when [name] sits at [from] and stops at a real boundary.
bool _endsExactly(String content, int from, String name) {
  final end = from + name.length;
  if (end > content.length) return false;
  if (content.substring(from, end) != name) return false;
  return end == content.length || !_isNameChar(content[end]);
}

/// End of the longest username-shaped run at [from], or null if there is none.
int? _guessedNameEnd(String content, int from) {
  // `.` and `-` extend a name but cannot open one: "@.foo" is punctuation.
  if (from >= content.length || !_isNameChar(content[from])) return null;
  var i = from;
  while (i < content.length && _isInnerNameChar(content[i])) {
    i++;
  }
  // Trailing punctuation belongs to the sentence, not the name: "@bob." and
  // "@team-" end at "bob" / "team".
  while (i > from && !_isNameChar(content[i - 1])) {
    i--;
  }
  return i > from ? i : null;
}
