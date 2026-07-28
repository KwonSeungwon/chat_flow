package com.chatflow.chat.service.message;

import com.chatflow.chat.entity.ChatMessageEntity;
import com.chatflow.chat.entity.RoomMemberEntity;
import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 본문의 @멘션을 실제 알림 대상(room_members 행)으로 확정한다.
 *
 * <p>{@link MentionExtractor}는 "본문에서 어떤 이름이 불렸나"만 다루는 순수 텍스트 유틸이고,
 * 여기는 "그래서 누구에게 알림이 가나"를 다룬다. 전송(MessageSenderService)과
 * 수정 재동기화(MessageEditService)가 같은 규칙을 쓰도록 한 곳에 모았다.
 */
public final class MentionTargets {

    /**
     * 캡션 없이 파일을 올리면 프론트가 본문을 이 접두사 + 파일명으로 채운다
     * ({@code message_send_helper.dart} {@code uploadAndSendFile}).
     * {@code file_bubble.dart} 도 접두사만으로 "사용자가 친 캡션인가"를 판정하지만,
     * 여기서는 파일명까지 붙여 완전일치로 본다 — 그래야 사용자가 진짜로
     * "[파일] @bob 봐줘" 라고 친 캡션을 잘라먹지 않는다.
     */
    private static final String AUTO_FILE_CAPTION = "[파일] ";

    private MentionTargets() {}

    /**
     * [content]에서 호명된 [members] 중 작성자 본인을 뺀 목록.
     *
     * <p>결과 순서는 본문 등장 순서가 아니라 [members] 순서다
     * ({@link MentionExtractor#resolve}는 등장 순서지만 여기서 멤버 목록으로 되돌린다).
     * 소비자(멘션 행 저장, FCM, UNREAD_INCREMENT 페이로드)는 모두 포함 여부만 보므로 무방하다.
     *
     * <p>이름 비교는 멤버 목록과 직접 대조한다 — 사용자명에 허용되는 문자셋이 정의된 적이
     * 없어서(gateway {@code AuthService.register}에 검증 없음) 정규식으로는 잘라먹는다.
     * 방 정원이 10명(DM 2명) 하드 캡이라 목록을 통째로 훑어도 비용이 없다.
     *
     * @param members       그 방의 room_members 전체. username이 null인 행이 있어도 안전하다.
     * @param authorUsername 발신자/작성자. 자기 자신 멘션은 알림 대상이 아니다.
     */
    static List<RoomMemberEntity> resolve(List<RoomMemberEntity> members, String content,
                                          String authorUsername) {
        if (members == null || members.isEmpty()) return List.of();

        // 작성자는 매칭 *전에* 후보에서 뺀다. 뒤에서 빼면 작성자 "bob smith"가
        // "@bob smith 확인" 의 최장일치를 가져간 뒤 제거돼서, 정작 호명된 멤버 "bob"이
        // 통째로 사라진다.
        List<String> candidates = members.stream()
                .map(RoomMemberEntity::getUsername)
                .filter(name -> !Objects.equals(name, authorUsername))
                .toList();

        Set<String> mentioned = new HashSet<>(MentionExtractor.resolve(content, candidates));
        if (mentioned.isEmpty()) return List.of();

        // HashSet.contains(null) 은 안전 — Set.of/copyOf 였다면 NPE.
        return members.stream().filter(m -> mentioned.contains(m.getUsername())).toList();
    }

    /**
     * 이 메시지의 본문을 멘션 해석에 돌릴 가치가 있는가.
     *
     * <p>{@code false}면 room_members 조회조차 하지 않는다. 걸러지는 경우는 셋:
     * 서버가 만든 문구(JOIN/LEAVE/SYSTEM), {@code @}가 아예 없는 본문,
     * 그리고 캡션 없이 올린 파일의 자동 본문.
     *
     * <p>뒤의 둘이 핵심이다. 파일명은 사용자가 이 방에서 친 문장이 아니라서
     * {@code @bob-review.pdf} 를 올렸다고 bob에게 푸시가 가면 안 되고, 전달된 글은
     * 남이 쓴 문장이라 전달자가 bob을 부른 게 아니다 — 멘션 행의 {@code fromUsername}은
     * 전달자로 찍히므로, 부르지도 않은 사람이 부른 것처럼 기록된다.
     */
    static boolean shouldResolveMentions(ChatMessage message) {
        return carriesUserText(message.getType())
                && namesSomeone(message.getContent(), MessageType.FILE.equals(message.getType()),
                        message.getFileName(), message.getForwardedFrom());
    }

    /** 수정 경로용 — 본문만 새 것이고 타입/파일명/전달여부는 저장된 행에서 온다. */
    static boolean shouldResolveMentions(ChatMessageEntity entity, String newContent) {
        return carriesUserText(entity.getType())
                && namesSomeone(newContent, MessageType.FILE.name().equals(entity.getType()),
                        entity.getFileName(), entity.getForwardedFrom());
    }

    private static boolean namesSomeone(String content, boolean isFile, String fileName,
                                        String forwardedFrom) {
        if (content == null || content.indexOf('@') < 0) return false;

        // 전달은 프론트가 "[전달] <보낸이>: <원문>" 으로 다시 조립한 남의 글이다
        // (message_send_helper.dart forwardMessage). 전달자가 친 글자는 하나도 없다.
        // 이 규칙이 없으면 캡션 없는 파일을 전달할 때 재조립된 본문이 자동 캡션 판정을
        // 빠져나가, 파일명 속 @가 다시 멘션이 된다.
        if (forwardedFrom != null && !forwardedFrom.isBlank()) return false;

        // 완전일치라, 사용자가 캡션을 한 글자라도 보태면 그건 사용자가 친 문장이다.
        return !(isFile && content.equals(AUTO_FILE_CAPTION + fileName));
    }

    /**
     * 사용자가 직접 친 본문을 갖는 타입인가 — CHAT과 FILE뿐이다.
     *
     * <p>FILE도 캡션을 함께 보낸다(프론트 {@code message_send_helper.dart}). 캡션의
     * 멘션이 없으면 {@code NotificationPolicy.mentionsOnly}로 설정된 방은 뱃지 자체를
     * 띄우지 않으므로, "@bob 차트 확인" + 파일이 bob에게 영영 닿지 않는다.
     * JOIN/LEAVE/SYSTEM 문구는 서버가 만든 것이라 멘션 대상이 아니다.
     *
     * <p>세 소비자가 이 판정에서 어긋나면 안 되므로 한 곳에 둔다: 전송
     * (MessageSenderService), 수정 재동기화(MessageEditService — 어긋나면 FILE 캡션에서
     * {@code @bob}을 지워도 멘션 행이 남는다), 그리고 UNREAD_INCREMENT 브로드캐스트
     * (MessageEventListener — 어긋나면 클라이언트 뱃지가 멘션 행과 따로 논다).
     */
    public static boolean carriesUserText(MessageType type) {
        return MessageType.CHAT.equals(type) || MessageType.FILE.equals(type);
    }

    /** 엔티티는 타입을 문자열로 들고 있다 ({@code ChatMessageEntity.getType()}). */
    static boolean carriesUserText(String typeName) {
        return MessageType.CHAT.name().equals(typeName)
                || MessageType.FILE.name().equals(typeName);
    }
}
