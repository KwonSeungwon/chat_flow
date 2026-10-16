import 'package:flutter_test/flutter_test.dart';
import 'package:chatflow/shared/models/chat_message.dart';

void main() {
  ChatMessage _make({
    String? fileUrl,
    String? fileName,
    String? fileContentType,
    String type = 'FILE',
    String content = '',
  }) =>
      ChatMessage(
        chatRoomId: 'room1',
        userId: 'u1',
        username: 'tester',
        content: content,
        timestamp: DateTime.now().toIso8601String(),
        type: type,
        fileUrl: fileUrl,
        fileName: fileName,
        fileContentType: fileContentType,
      );

  group('isFileMessage', () {
    test('FILE 타입 + fileUrl → true', () {
      final msg = _make(fileUrl: '/api/files/abc');
      expect(msg.isFileMessage, isTrue);
    });

    test('FILE 타입 + fileUrl null → false', () {
      final msg = _make();
      expect(msg.isFileMessage, isFalse);
    });

    test('CHAT 타입 → false', () {
      final msg = _make(type: 'CHAT', fileUrl: '/api/files/abc');
      expect(msg.isFileMessage, isFalse);
    });
  });

  group('isImageFile', () {
    test('image/jpeg → true', () {
      final msg = _make(fileContentType: 'image/jpeg');
      expect(msg.isImageFile, isTrue);
    });

    test('image/png → true', () {
      final msg = _make(fileContentType: 'image/png');
      expect(msg.isImageFile, isTrue);
    });

    test('application/pdf → false', () {
      final msg = _make(fileContentType: 'application/pdf');
      expect(msg.isImageFile, isFalse);
    });

    test('null → false', () {
      final msg = _make();
      expect(msg.isImageFile, isFalse);
    });
  });

  group('isPdfFile', () {
    test('application/pdf content type → true', () {
      final msg = _make(fileContentType: 'application/pdf');
      expect(msg.isPdfFile, isTrue);
    });

    test('.pdf 확장자 → true', () {
      final msg = _make(fileName: 'report.pdf');
      expect(msg.isPdfFile, isTrue);
    });

    test('.PDF 대문자 확장자 → true', () {
      final msg = _make(fileName: 'REPORT.PDF');
      expect(msg.isPdfFile, isTrue);
    });

    test('image/jpeg → false', () {
      final msg = _make(fileContentType: 'image/jpeg', fileName: 'photo.jpg');
      expect(msg.isPdfFile, isFalse);
    });

    test('application/zip → false', () {
      final msg = _make(fileContentType: 'application/zip', fileName: 'data.zip');
      expect(msg.isPdfFile, isFalse);
    });

    test('null content type + null fileName → false', () {
      final msg = _make();
      expect(msg.isPdfFile, isFalse);
    });

    test('pdf in fileName but not extension → false', () {
      final msg = _make(fileName: 'pdf-guide.docx');
      expect(msg.isPdfFile, isFalse);
    });
  });

  group('FILE 메시지 content 표시', () {
    test('사용자 텍스트 content → [파일] prefix 아님', () {
      final msg = _make(
        fileUrl: '/api/files/abc',
        fileName: 'test.png',
        content: '이 사진 봐',
      );
      expect(msg.content.startsWith('[파일]'), isFalse);
      expect(msg.content, '이 사진 봐');
    });

    test('기본 content → [파일] prefix', () {
      final msg = _make(
        fileUrl: '/api/files/abc',
        fileName: 'test.png',
        content: '[파일] test.png',
      );
      expect(msg.content.startsWith('[파일]'), isTrue);
    });
  });

  group('fromJson', () {
    test('파일 필드 파싱', () {
      final msg = ChatMessage.fromJson({
        'chatRoomId': 'r1',
        'userId': 'u1',
        'username': 'test',
        'content': '[파일] doc.pdf',
        'timestamp': '2026-01-01T00:00:00Z',
        'type': 'FILE',
        'fileUrl': '/api/files/uuid1',
        'fileName': 'doc.pdf',
        'fileContentType': 'application/pdf',
      });
      expect(msg.isFileMessage, isTrue);
      expect(msg.isPdfFile, isTrue);
      expect(msg.isImageFile, isFalse);
      expect(msg.fileUrl, '/api/files/uuid1');
      expect(msg.fileName, 'doc.pdf');
    });

    test('파일 필드 null 처리', () {
      final msg = ChatMessage.fromJson({
        'chatRoomId': 'r1',
        'userId': 'u1',
        'username': 'test',
        'content': 'hello',
        'timestamp': '2026-01-01T00:00:00Z',
        'type': 'CHAT',
      });
      expect(msg.isFileMessage, isFalse);
      expect(msg.isPdfFile, isFalse);
      expect(msg.isImageFile, isFalse);
      expect(msg.fileUrl, isNull);
    });
  });
}
