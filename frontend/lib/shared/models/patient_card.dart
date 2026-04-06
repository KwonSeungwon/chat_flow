import 'dart:convert';

class PatientCard {
  final String patientName;
  final String roomNumber;
  final String diagnosis;
  final String? allergies;
  final String? bloodPressure;
  final int? heartRate;
  final int? respiratoryRate;
  final double? bodyTemperature;
  final int? spO2;
  final String? notes;

  const PatientCard({
    required this.patientName,
    required this.roomNumber,
    required this.diagnosis,
    this.allergies,
    this.bloodPressure,
    this.heartRate,
    this.respiratoryRate,
    this.bodyTemperature,
    this.spO2,
    this.notes,
  });

  factory PatientCard.fromJson(Map<String, dynamic> json) {
    return PatientCard(
      patientName:     json['patientName']?.toString() ?? '',
      roomNumber:      json['roomNumber']?.toString() ?? '',
      diagnosis:       json['diagnosis']?.toString() ?? '',
      allergies:       json['allergies']?.toString(),
      bloodPressure:   json['bloodPressure']?.toString(),
      heartRate:       (json['heartRate'] as num?)?.toInt(),
      respiratoryRate: (json['respiratoryRate'] as num?)?.toInt(),
      bodyTemperature: (json['bodyTemperature'] as num?)?.toDouble(),
      spO2:            (json['spO2'] as num?)?.toInt(),
      notes:           json['notes']?.toString(),
    );
  }

  Map<String, dynamic> toJson() => {
    'patientName':     patientName,
    'roomNumber':      roomNumber,
    'diagnosis':       diagnosis,
    if (allergies       != null) 'allergies':       allergies,
    if (bloodPressure   != null) 'bloodPressure':   bloodPressure,
    if (heartRate       != null) 'heartRate':       heartRate,
    if (respiratoryRate != null) 'respiratoryRate': respiratoryRate,
    if (bodyTemperature != null) 'bodyTemperature': bodyTemperature,
    if (spO2            != null) 'spO2':            spO2,
    if (notes           != null) 'notes':           notes,
  };

  /// Parse from a JSON string embedded in ChatMessage.content
  static PatientCard? tryParseContent(String content) {
    try {
      final decoded = jsonDecode(content);
      if (decoded is Map<String, dynamic>) {
        return PatientCard.fromJson(decoded);
      }
    } catch (_) {}
    return null;
  }
}
