// ignore: avoid_web_libraries_in_flutter, deprecated_member_use
import 'pasted_image.dart';

/// Web implementation: proactive clipboard reading is not used.
/// Image paste is handled by the document-level paste event listener
/// in [WebDropTarget] (web_drop_target_web.dart).
Future<PastedImage?> readClipboardImage() async => null;
