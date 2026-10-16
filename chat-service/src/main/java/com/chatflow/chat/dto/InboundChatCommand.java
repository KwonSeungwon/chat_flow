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
 * @param type            one of {@link #ALLOWED_TYPES}; {@code null} means CHAT
 * @param clientMessageId optional client-side id, echoed back on the broadcast
 * @param priority        ROUTINE / URGENT ...; {@code null} or blank means ROUTINE
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundChatCommand(
        String chatRoomId, String content, MessageType type,
        String parentMessageId, String fileUrl, String fileName, Long fileSize, String fileType,
        String clientMessageId,
        String forwardedFrom,
        String priority, String fileContentType) {

    /** The only message types a user may author. Everything else is server-generated. */
    public static final Set<MessageType> ALLOWED_TYPES =
            Set.of(MessageType.CHAT, MessageType.FILE, MessageType.PATIENT_CARD);

    private static final String DEFAULT_PRIORITY = "ROUTINE";

    public MessageType effectiveType() {
        return type != null ? type : MessageType.CHAT;
    }

    public boolean hasAllowedType() {
        return ALLOWED_TYPES.contains(effectiveType());
    }

    /** Builds the outbound message stamped with the session identity. */
    public ChatMessage toChatMessage(String userId, String username) {
        ChatMessage message = new ChatMessage();
        message.setChatRoomId(chatRoomId);
        message.setUserId(userId);
        message.setUsername(username);
        message.setContent(content);
        message.setType(effectiveType());
        message.setPriority(priority != null && !priority.isBlank() ? priority : DEFAULT_PRIORITY);
        message.setParentMessageId(parentMessageId);
        message.setFileUrl(fileUrl);
        message.setFileName(fileName);
        message.setFileContentType(fileContentType != null ? fileContentType : fileType);
        message.setForwardedFrom(forwardedFrom);
        message.setClientMessageId(clientMessageId);
        return message;
    }
}
