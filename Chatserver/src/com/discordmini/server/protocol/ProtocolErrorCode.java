package com.discordmini.server.protocol;

/** Stable machine-readable error codes returned by the server (Protocol v1 + v2). */
public enum ProtocolErrorCode {
    // Protocol v1
    AUTH_FAILED,
    AUTH_REQUIRED,
    ALREADY_AUTHENTICATED,
    ALREADY_ONLINE,
    USERNAME_TAKEN,
    INVALID_ROOM_NAME,
    ROOM_NAME_TAKEN,
    ROOM_NOT_FOUND,
    NOT_IN_ROOM,
    INVALID_RECIPIENT,
    USER_OFFLINE,
    BAD_REQUEST,
    DATABASE_ERROR,
    INVALID_STATE,
    INTERNAL_ERROR,
    PERMISSION_DENIED,
    USER_NOT_FOUND,
    // Protocol v2
    FORBIDDEN,
    MESSAGE_NOT_FOUND,
    CHANNEL_NOT_FOUND,
    GUILD_NOT_FOUND,
    INVITE_EXPIRED,
    PAYLOAD_TOO_LARGE,
    RATE_LIMITED,
    INVALID_TOKEN
}
