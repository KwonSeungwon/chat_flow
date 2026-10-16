package com.chatflow.aisummary.service;

/**
 * Redis key names shared by every reader and writer in ai-summary-service.
 * Keep reads and writes on these constants so a prefix can never drift apart again.
 */
public final class RedisKeys {
    private RedisKeys() {}

    /** LIST of buffered CHAT message JSON per room, written by AiSummaryService. */
    public static final String BUFFER_PREFIX = "chatflow:buffer:";
    /** SET of messageIds already buffered per room (at-least-once delivery dedup). */
    public static final String BUFFER_IDS_PREFIX = "chatflow:buffer:ids:";
    public static final String BUFFER_TIME_PREFIX = "chatflow:buffer-time:";
    public static final String ACTIVE_ROOMS_KEY = "chatflow:active-rooms";
    /** LIST of summary message JSON per room (RPUSH + LTRIM; was a JSON string under chatflow:summary:). */
    public static final String SUMMARY_LIST_PREFIX = "chatflow:summary-list:";
    public static final String SUMMARY_HASH_PREFIX = "chatflow:summary-hash:";
}
