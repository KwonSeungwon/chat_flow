package com.chatflow.chat.service.message;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 단일 @멘션 문법. MessageSenderService(FCM), MessageEditService(재동기화),
 * MessageEventListener(UNREAD_INCREMENT)가 같은 결과를 보도록 한 곳에 모은다.
 *
 * <p>예전에는 {@code @([A-Za-z0-9_.가-힣]{1,30})} 정규식으로 "사용자명처럼 생긴 것"을
 * 뽑아낸 뒤 room_members와 대조했다. 그런데 가입 시 사용자명 검증이 전혀 없어서
 * (gateway {@code AuthService.register}) 하이픈·공백·악센트·30자 초과 이름이 실제로
 * 존재한다 — 특히 username이 비면 userId(36자 UUID)로 대체되는 경로가 있다.
 * 정규식이 이름을 잘라내면 대조에 실패하고, 멘션은 <b>아무 오류 없이 아무에게도</b>
 * 전달되지 않았다.
 *
 * <p>그래서 문자셋을 추측하는 대신 그 방의 실제 멤버 이름과 직접 대조한다. 방 정원이
 * 10명(DM 2명)으로 하드 캡이라 목록을 통째로 넘겨도 비용이 없다.
 */
public final class MentionExtractor {

    private MentionExtractor() {}

    /**
     * [content]에서 [knownUsernames]에 실제로 존재하는 이름만 골라낸다.
     * 결과는 본문에 처음 등장한 순서, 중복 제거.
     *
     * <p>매칭 규칙:
     * <ul>
     *   <li>{@code @} 앞은 문자열 시작이거나 이름 문자가 아니어야 한다 —
     *       {@code bob@phill.park} 같은 이메일이 멘션으로 잡히지 않는다.</li>
     *   <li>같은 위치에서 여러 이름이 걸리면 <b>가장 긴 것</b>이 이긴다
     *       ({@code bob}과 {@code bobby}가 모두 멤버면 {@code @bobby}는 bobby).</li>
     *   <li>이름 뒤는 문자열 끝이거나 이름 문자가 아니어야 한다 — 멤버가 {@code bob}뿐일 때
     *       {@code @bobby}가 bob을 호출하지 않는다. 구두점·공백은 허용.</li>
     *   <li>대소문자를 구분한다 (DB 조회와 동일).</li>
     * </ul>
     */
    public static List<String> resolve(String content, Collection<String> knownUsernames) {
        if (content == null || content.indexOf('@') < 0
                || knownUsernames == null || knownUsernames.isEmpty()) {
            return List.of();
        }

        List<String> byLengthDesc = knownUsernames.stream()
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        if (byLengthDesc.isEmpty()) return List.of();

        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (int at = content.indexOf('@'); at >= 0; ) {
            String match = isMentionStart(content, at)
                    ? longestNameAt(content, at + 1, byLengthDesc)
                    : null;
            // 매치된 구간 안쪽은 다시 훑지 않는다 (이름 자체가 '@'를 품을 수 있다).
            int next = match != null ? at + 1 + match.length() : at + 1;
            if (match != null) found.add(match);
            at = content.indexOf('@', next);
        }
        return List.copyOf(found);
    }

    private static boolean isMentionStart(String content, int atIndex) {
        return atIndex == 0 || !isNameChar(content.charAt(atIndex - 1));
    }

    private static String longestNameAt(String content, int from, List<String> byLengthDesc) {
        for (String name : byLengthDesc) {
            int end = from + name.length();
            if (end <= content.length()
                    && content.regionMatches(from, name, 0, name.length())
                    && (end == content.length() || !isNameChar(content.charAt(end)))) {
                return name;
            }
        }
        return null;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
