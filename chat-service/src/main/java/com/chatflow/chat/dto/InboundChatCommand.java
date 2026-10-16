package com.chatflow.chat.dto;

import com.chatflow.common.dto.BaseMessage.MessageType;
import com.chatflow.common.dto.ChatMessage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Set;

/**
 * What a client may send on {@code /app/chat.sendMessage}. Identity comes from the
 * session, never from here: any {@code userId}/{@code username}/{@code isAiGenerated}/
 * {@code timestamp} the client puts in the payload is dropped at deserialization.
 *
 * <p>{@code fileSize} has no server-side counterpart yet and is accepted only so the
 * contract is stable; {@code fileType} is a legacy alias for {@code fileContentType}
 * (the key the Flutter client actually sends).
 *
 * @param type            a {@link MessageType} name, one of {@link #ALLOWED_TYPES}; {@code null}
 *                        or blank means CHAT. Kept as a string so an unknown or retired name
 *                        (e.g. PATIENT_CARD from an old client) reaches the rejection path
 *                        instead of failing deserialization silently.
 * @param clientMessageId optional client-side id, echoed back on the broadcast
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatCommand(
        String chatRoomId, String content, String type,
        String parentMessageId, String fileUrl, String fileName, Long fileSize, String fileType,
        String clientMessageId,
        String forwardedFrom,
        String fileContentType) {

    /** The only message types a user may author. Everything else is server-generated. */
    public static final Set<MessageType> ALLOWED_TYPES =
            Set.of(MessageType.CHAT, MessageType.FILE);

    /** The requested type, CHAT when absent, or {@code null} when the name is unknown. */
    public MessageType effectiveType() {
        if (type == null || type.isBlank()) return MessageType.CHAT;
        try {
            return MessageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean hasAllowedType() {
        MessageType effective = effectiveType();
        return effective != null && ALLOWED_TYPES.contains(effective);
    }

    /** Builds the outbound message stamped with the session identity. */
    public ChatMessage toChatMessage(String userId, String username) {
        ChatMessage message = new ChatMessage();
        message.setChatRoomId(chatRoomId);
        message.setUserId(userId);
        message.setUsername(username);
        message.setContent(content);
        message.setType(effectiveType());
        message.setParentMessageId(parentMessageId);
        message.setFileUrl(fileUrl);
        message.setFileName(fileName);
        message.setFileContentType(fileContentType != null ? fileContentType : fileType);
        message.setForwardedFrom(forwardedFrom);
        message.setClientMessageId(clientMessageId);
        return message;
    }
}
