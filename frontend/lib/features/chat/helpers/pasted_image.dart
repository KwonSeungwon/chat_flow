import 'dart:typed_data';

/// Represents an image read from the clipboard or dropped onto the chat input.
class PastedImage {
  final String name;
  final Uint8List bytes;
  final String mimeType;

  const PastedImage({
    required this.name,
    required this.bytes,
    required this.mimeType,
  });
}
