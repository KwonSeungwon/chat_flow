package com.chatflow.chat.service.message;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단일 @멘션 문법. MessageSenderService(FCM)와 MessageEventListener
 * (UNREAD_INCREMENT)가 서로 다른 정규식을 쓰던 것을 통일한다.
 * 추출 결과는 후보일 뿐이며, 호출자가 room_members와 대조해 확정한다.
 */
public final class MentionExtractor {

    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([A-Za-z0-9_\\.\\uac00-\\ud7a3]{1,30})");

    private MentionExtractor() {}

    public static List<String> extract(String content) {
        if (content == null || content.indexOf('@') < 0) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher m = MENTION_PATTERN.matcher(content);
        while (m.find()) names.add(m.group(1));
        return List.copyOf(names);
    }
}
