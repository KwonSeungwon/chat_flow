Object? unwrapApiResponse(Object? payload) {
  if (payload is Map && payload.containsKey('data')) {
    return payload['data'];
  }
  return payload;
}

Map<String, dynamic>? apiResponseMap(Object? payload) {
  final data = unwrapApiResponse(payload);
  if (data is Map) return data.cast<String, dynamic>();
  return null;
}

List<dynamic> apiResponseList(Object? payload) {
  final data = unwrapApiResponse(payload);
  if (data is List) return data;
  // Spring Page shape: {content: [...]} (possibly nested under data:)
  if (data is Map && data['content'] is List) return data['content'] as List;
  return const [];
}

/// Extracts a single field from the unwrapped API response map.
///
/// Callers MUST supply an explicit, non-nullable type argument
/// (e.g. `apiResponseField<num>(data, 'reportId')`). When [T] is left to
/// inference it resolves to `dynamic` or `Object?`, making the internal
/// `value is T` guard vacuously true and bypassing the type check entirely.
T? apiResponseField<T>(Object? payload, String key) {
  final data = apiResponseMap(payload);
  final value = data?[key];
  return value is T ? value : null;
}
