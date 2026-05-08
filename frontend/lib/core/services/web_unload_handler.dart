// Conditional import: web → web_unload_handler_web.dart, otherwise stub.
export 'web_unload_handler_stub.dart'
    if (dart.library.html) 'web_unload_handler_web.dart';
