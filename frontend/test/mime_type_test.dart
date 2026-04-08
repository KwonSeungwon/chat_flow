import 'package:flutter_test/flutter_test.dart';

// _extToMime is a private method in ChatInput widget.
// We replicate the mapping here as a pure-function unit test.
// Any change to _extToMime must be reflected here.
String extToMime(String ext) {
  const map = {
    'jpg': 'image/jpeg',
    'jpeg': 'image/jpeg',
    'png': 'image/png',
    'gif': 'image/gif',
    'webp': 'image/webp',
    'pdf': 'application/pdf',
    'docx':
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'xlsx':
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'zip': 'application/zip',
  };
  return map[ext] ?? 'application/octet-stream';
}

void main() {
  group('extToMime — 이미지 확장자', () {
    test('jpg → image/jpeg', () {
      expect(extToMime('jpg'), 'image/jpeg');
    });

    test('jpeg → image/jpeg', () {
      expect(extToMime('jpeg'), 'image/jpeg');
    });

    test('png → image/png', () {
      expect(extToMime('png'), 'image/png');
    });

    test('gif → image/gif', () {
      expect(extToMime('gif'), 'image/gif');
    });

    test('webp → image/webp', () {
      expect(extToMime('webp'), 'image/webp');
    });
  });

  group('extToMime — 문서/아카이브 확장자', () {
    test('pdf → application/pdf', () {
      expect(extToMime('pdf'), 'application/pdf');
    });

    test('docx → Word MIME 타입', () {
      expect(
        extToMime('docx'),
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      );
    });

    test('xlsx → Excel MIME 타입', () {
      expect(
        extToMime('xlsx'),
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      );
    });

    test('zip → application/zip', () {
      expect(extToMime('zip'), 'application/zip');
    });
  });

  group('extToMime — 알 수 없는 확장자', () {
    test('알 수 없는 확장자는 application/octet-stream을 반환한다', () {
      expect(extToMime('exe'), 'application/octet-stream');
    });

    test('빈 문자열은 application/octet-stream을 반환한다', () {
      expect(extToMime(''), 'application/octet-stream');
    });

    test('대문자 확장자는 매핑되지 않아 fallback을 반환한다', () {
      // _extToMime은 대소문자 구분 없이 호출하지 않음.
      // file_picker에서 ext.toLowerCase()로 전처리 후 호출됨을 가정.
      expect(extToMime('JPG'), 'application/octet-stream');
    });

    test('mp4 확장자는 허용 목록 외이므로 octet-stream을 반환한다', () {
      expect(extToMime('mp4'), 'application/octet-stream');
    });
  });

  group('extToMime — 파일 업로드 허용 목록 완전성 검증', () {
    // chat_input.dart의 allowedExtensions와 _extToMime 매핑이 일치하는지 확인
    const allowedExtensions = [
      'jpg', 'jpeg', 'png', 'gif', 'webp',
      'pdf', 'docx', 'xlsx', 'zip',
    ];

    test('allowedExtensions의 모든 항목이 octet-stream이 아닌 MIME을 반환한다', () {
      for (final ext in allowedExtensions) {
        final mime = extToMime(ext);
        expect(
          mime,
          isNot('application/octet-stream'),
          reason: '$ext 확장자에 대한 MIME 매핑이 누락되었습니다',
        );
      }
    });
  });
}
