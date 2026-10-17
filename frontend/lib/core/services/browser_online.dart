import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'browser_online_stub.dart'
    if (dart.library.html) 'browser_online_web.dart' as impl;

/// Fires when the browser regains network (`window` `online` event).
/// Never fires off the web.
final browserOnlineProvider =
    Provider<Stream<void>>((ref) => impl.browserOnlineEvents());
