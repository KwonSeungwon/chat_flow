// Conditional import: web → web_prefs_sync_web.dart, otherwise stub.
export 'web_prefs_sync_stub.dart'
    if (dart.library.html) 'web_prefs_sync_web.dart';
