enum ReportReason { spam, harassment, inappropriate, other }

extension ReportReasonX on ReportReason {
  String get apiValue => name.toUpperCase();

  static ReportReason fromString(String s) => ReportReason.values.firstWhere(
        (r) => r.name.toUpperCase() == s.toUpperCase(),
        orElse: () => ReportReason.other,
      );
}

enum ReportStatus { pending, resolved, dismissed }

extension ReportStatusX on ReportStatus {
  String get apiValue => name.toUpperCase();

  static ReportStatus fromString(String s) => ReportStatus.values.firstWhere(
        (r) => r.name.toUpperCase() == s.toUpperCase(),
        orElse: () => ReportStatus.pending,
      );
}

class MessageReport {
  final int id;
  final String messageId;
  final String messageContent;
  final String messageAuthor;
  final String? messageAuthorUserId;
  final String reportedBy;
  final String? reportedByUserId;
  final ReportReason reason;
  final String? comment;
  final ReportStatus status;
  final DateTime createdAt;

  MessageReport({
    required this.id,
    required this.messageId,
    required this.messageContent,
    required this.messageAuthor,
    this.messageAuthorUserId,
    required this.reportedBy,
    this.reportedByUserId,
    required this.reason,
    this.comment,
    required this.status,
    required this.createdAt,
  });

  factory MessageReport.fromJson(Map<String, dynamic> json) {
    return MessageReport(
      id: (json['id'] as num?)?.toInt() ?? 0,
      messageId: json['messageId']?.toString() ?? '',
      messageContent: json['messageContent']?.toString() ?? '',
      messageAuthor: json['messageAuthor']?.toString() ?? '',
      messageAuthorUserId: json['messageAuthorUserId']?.toString(),
      reportedBy: json['reportedBy']?.toString() ?? '',
      reportedByUserId: json['reportedByUserId']?.toString(),
      reason:
          ReportReasonX.fromString(json['reason']?.toString() ?? 'OTHER'),
      comment: json['comment']?.toString(),
      status:
          ReportStatusX.fromString(json['status']?.toString() ?? 'PENDING'),
      createdAt: DateTime.tryParse(json['createdAt']?.toString() ?? '') ??
          DateTime.now(),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'messageId': messageId,
        'messageContent': messageContent,
        'messageAuthor': messageAuthor,
        if (messageAuthorUserId != null) 'messageAuthorUserId': messageAuthorUserId,
        'reportedBy': reportedBy,
        if (reportedByUserId != null) 'reportedByUserId': reportedByUserId,
        'reason': reason.apiValue,
        if (comment != null) 'comment': comment,
        'status': status.apiValue,
        'createdAt': createdAt.toIso8601String(),
      };
}
