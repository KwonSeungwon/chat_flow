import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import 'room_admin_api.dart';

final roomAdminApiProvider = Provider<RoomAdminApi>((ref) {
  final dioClient = ref.watch(dioClientProvider);
  return RoomAdminApi(dioClient.dio);
});
