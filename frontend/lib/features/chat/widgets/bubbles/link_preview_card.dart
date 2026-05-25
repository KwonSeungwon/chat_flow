import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

class LinkPreviewCard extends StatefulWidget {
  final String url;
  const LinkPreviewCard({super.key, required this.url});

  @override
  State<LinkPreviewCard> createState() => LinkPreviewCardState();
}

class LinkPreviewCardState extends State<LinkPreviewCard> {
  /// Static memory cache to avoid redundant API calls for the same URL.
  static final Map<String, Map<String, String>> _cache = {};

  /// Sentinel value stored in cache when the API fails, to avoid retrying.
  static const Map<String, String> _empty = {};

  Map<String, String>? _ogData;
  bool _loading = true;

  static String _apiBase() {
    if (kIsWeb) {
      final uri = Uri.base;
      final port = (uri.hasPort && uri.port != 80 && uri.port != 443) ? ':${uri.port}' : '';
      return '${uri.scheme}://${uri.host}$port';
    }
    // Native: rely on the same env-based base URL as DioClient.
    // flutter_dotenv may not be initialized here, so fall back to production URL.
    try {
      // ignore: undefined_prefixed_name
      return const String.fromEnvironment('API_BASE_URL', defaultValue: 'https://app.chatflow.ai.kr');
    } catch (_) {
      return 'https://app.chatflow.ai.kr';
    }
  }

  @override
  void initState() {
    super.initState();
    _fetchOg();
  }

  Future<void> _fetchOg() async {
    final url = widget.url;

    if (_cache.containsKey(url)) {
      final cached = _cache[url]!;
      if (mounted) setState(() { _ogData = cached.isEmpty ? null : cached; _loading = false; });
      return;
    }

    try {
      final dio = Dio(BaseOptions(
        baseUrl: _apiBase(),
        connectTimeout: const Duration(seconds: 5),
        receiveTimeout: const Duration(seconds: 10),
      ));
      final resp = await dio.get(
        '/api/chat/rooms/link-preview',
        queryParameters: {'url': url},
      );
      final data = resp.data;
      Map<String, String>? parsed;
      if (data is Map && data['data'] is Map) {
        parsed = Map<String, String>.from((data['data'] as Map).map(
          (k, v) => MapEntry(k.toString(), v?.toString() ?? ''),
        ));
      }
      _cache[url] = parsed ?? _empty;
      if (mounted) setState(() { _ogData = (parsed?.isNotEmpty == true) ? parsed : null; _loading = false; });
    } catch (_) {
      _cache[url] = _empty;
      if (mounted) setState(() { _loading = false; });
    }
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final host = Uri.tryParse(widget.url)?.host ?? widget.url;
    final maxWidth = MediaQuery.of(context).size.width < 600
        ? MediaQuery.of(context).size.width * 0.65
        : 300.0;

    Widget cardContent;
    if (!_loading && _ogData != null) {
      final title = _ogData!['title'] ?? '';
      final description = _ogData!['description'] ?? '';
      final image = _ogData!['image'] ?? '';
      cardContent = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (image.isNotEmpty)
            ClipRRect(
              borderRadius: const BorderRadius.vertical(top: Radius.circular(7)),
              child: Image.network(
                image,
                height: 120,
                width: double.infinity,
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => const SizedBox.shrink(),
              ),
            ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(host,
                  style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
                if (title.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(title,
                    style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: cs.onSurface),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                if (description.isNotEmpty) ...[
                  const SizedBox(height: 2),
                  Text(description,
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ],
            ),
          ),
        ],
      );
    } else {
      // Fallback: host-only display (also used while loading)
      cardContent = Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.link, size: 16, color: cs.primary),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(host, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: cs.primary)),
                  Text(widget.url, maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: cs.onSurfaceVariant)),
                ],
              ),
            ),
            Icon(Icons.open_in_new, size: 14, color: cs.onSurfaceVariant.withAlpha(120)),
          ],
        ),
      );
    }

    return GestureDetector(
      onTap: () async {
        final uri = Uri.parse(widget.url);
        if (await canLaunchUrl(uri)) await launchUrl(uri, mode: LaunchMode.externalApplication);
      },
      child: Container(
        margin: const EdgeInsets.only(top: 6),
        constraints: BoxConstraints(maxWidth: maxWidth),
        decoration: BoxDecoration(
          color: cs.surfaceContainer,
          borderRadius: BorderRadius.circular(8),
          border: Border(left: BorderSide(color: cs.primary, width: 3)),
        ),
        child: cardContent,
      ),
    );
  }
}
