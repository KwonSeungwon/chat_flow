package com.gossiplog.chat.event;

/**
 * Published when a user stops being a member of a room (kick, ban, voluntary leave).
 * Listeners react after the removing transaction commits — e.g. FCM unsubscribes the
 * user's device tokens from the room topic so removed users stop receiving previews.
 */
public record RoomMemberRemovedEvent(String roomId, String userId, Reason reason) {

    public enum Reason { KICKED, BANNED, LEFT }
}
