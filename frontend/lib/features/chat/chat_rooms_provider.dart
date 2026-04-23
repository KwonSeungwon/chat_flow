import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/app_stomp_service.dart';
import '../../core/network/dio_client.dart';
import '../../shared/models/chat_room.dart';

// ---------------------------------------------------------------------------
// Room Sort
// ---------------------------------------------------------------------------

enum RoomSortOption { recent, unread, name }

final roomSortProvider = StateProvider<RoomSortOption>((ref) => RoomSortOption.recent);

// ---------------------------------------------------------------------------
// Chat Rooms
// ---------------------------------------------------------------------------

final chatRoomsProvider =
    StateNotifierProvider<ChatRoomsNotifier, AsyncValue<List<ChatRoom>>>((ref) {
  return ChatRoomsNotifier(ref.watch(dioClientProvider));
});

class ChatRoomsNotifier extends StateNotifier<AsyncValue<List<ChatRoom>>> {
  final DioClient _dioClient;

  ChatRoomsNotifier(this._dioClient) : super(const AsyncValue.loading()) {
    fetchRooms();
  }

  Future<void> fetchRooms() async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms');
      final data = resp.data;
      List<dynamic> list;
      if (data is List) {
        list = data;
      } else if (data is Map && data['data'] is List) {
        list = data['data'] as List;
      } else if (data is Map && data['content'] is List) {
        list = data['content'] as List;
      } else {
        list = [];
      }
      final rooms =
          list
              .map((e) => ChatRoom.fromJson(e as Map<String, dynamic>))
              .toList();
      state = AsyncValue.data(rooms);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<bool> deleteRoom(String id) async {
    try {
      await _dioClient.dio.delete('/api/chat/rooms/$id');
      await fetchRooms();
      return true;
    } on DioException {
      return false;
    }
  }

  Future<Map<String, int>> fetchUnreadCounts() async {
    try {
      final resp = await _dioClient.dio.get('/api/chat/rooms/unread-counts');
      final data = resp.data;
      if (data is Map && data['data'] is Map) {
        final raw = data['data'] as Map;
        return raw.map((k, v) => MapEntry(k.toString(), (v as num).toInt()));
      }
    } catch (_) {}
    return {};
  }

  /// Update participant count for a specific room (from real-time presence events).
  void updateParticipantCount(String roomId, int count) {
    final current = state.valueOrNull;
    if (current == null) return;
    final updated = current.map((r) {
      if (r.id == roomId) {
        return r.copyWith(participantCount: count);
      }
      return r;
    }).toList();
    state = AsyncValue.data(updated);
  }

  /// Creates a room and returns its ID for navigation.
  Future<String?> createRoom({
    required String name,
    String? description,
    String? color,
    String roomType = 'GENERAL',
    bool isPrivate = false,
    String? password,
    String? allowedRoles,
  }) async {
    try {
      final resp = await _dioClient.dio.post('/api/chat/rooms', data: {
        'name': name,
        if (description != null) 'description': description,
        if (color != null) 'color': color,
        'roomType': roomType,
        'isPrivate': isPrivate,
        if (password != null) 'password': password,
        if (allowedRoles != null) 'allowedRoles': allowedRoles,
      });
      // Extract room ID first — fetchRooms failure must not mask successful creation
      final data = resp.data;
      final roomId = data is Map
          ? (data['data']?['id'] ?? data['id'])?.toString()
          : null;
      try {
        await fetchRooms();
      } catch (_) {
        // Best-effort refresh — room was created regardless
      }
      return roomId;
    } on DioException {
      return null;
    }
  }
}

// ---------------------------------------------------------------------------
// Global unread counts (per room) — updated by ChatNotifier, read by sidebar
// ---------------------------------------------------------------------------

final roomUnreadCountsProvider = StateProvider<Map<String, int>>((ref) => {});

// ---------------------------------------------------------------------------
// App-level STOMP service (single connection, survives room changes)
// ---------------------------------------------------------------------------

final appStompServiceProvider = Provider<AppStompService>((ref) {
  final service = AppStompService();
  ref.onDispose(service.disconnect);
  return service;
});

// ---------------------------------------------------------------------------
// Active room ID — set by ChatPage, cleared on leave. Used by AppStompService
// to skip unread increments for the room the user is currently viewing.
// ---------------------------------------------------------------------------

final activeRoomIdProvider = StateProvider<String?>((ref) => null);
