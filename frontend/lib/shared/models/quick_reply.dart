class QuickReplySuggestions {
  final List<String> suggestions;
  final String latestMessageId;

  const QuickReplySuggestions({
    required this.suggestions,
    required this.latestMessageId,
  });

  factory QuickReplySuggestions.fromJson(
      Map<String, dynamic> json, String latestMessageId) {
    final raw = json['suggestions'];
    final list = raw is List
        ? raw
            .map((e) => e?.toString() ?? '')
            .where((s) => s.isNotEmpty)
            .toList()
        : <String>[];
    return QuickReplySuggestions(
      suggestions: list,
      latestMessageId: latestMessageId,
    );
  }

  static const empty =
      QuickReplySuggestions(suggestions: [], latestMessageId: '');

  bool get isEmpty => suggestions.isEmpty;
}
