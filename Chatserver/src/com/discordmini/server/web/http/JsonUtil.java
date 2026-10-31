package com.discordmini.server.web.http;

import com.discordmini.server.model.Attachment;
import com.discordmini.server.model.DirectConversation;
import com.discordmini.server.model.DirectMessage;
import com.discordmini.server.model.Guild;
import com.discordmini.server.model.Message;
import com.discordmini.server.model.ReactionSummary;
import com.discordmini.server.model.Room;
import com.discordmini.server.model.User;
import com.discordmini.server.session.EventBus;
import com.discordmini.server.session.Session;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON serialization, CORS handling, query parameter parsing, and DTO mapping utilities
 * for the JDK HttpServer REST API and WebSocket gateway.
 */
public final class JsonUtil {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private JsonUtil() {}

    public static String toJson(Object src) {
        return GSON.toJson(src);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return GSON.fromJson(json, clazz);
    }

    public static String readBodyString(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static <T> T readBody(HttpExchange exchange, Class<T> clazz) throws IOException {
        String body = readBodyString(exchange);
        if (body.isBlank()) {
            return null;
        }
        return GSON.fromJson(body, clazz);
    }

    public static JsonObject readJsonObject(HttpExchange exchange) throws IOException {
        String body = readBodyString(exchange);
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public static void addCorsHeaders(HttpExchange exchange) {
        Headers reqHeaders = exchange.getRequestHeaders();
        Headers respHeaders = exchange.getResponseHeaders();
        String origin = reqHeaders.getFirst("Origin");
        respHeaders.set("Access-Control-Allow-Origin", (origin != null && !origin.isBlank()) ? origin : "*");
        respHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
        respHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-File-Name, X-Requested-With");
        respHeaders.set("Access-Control-Expose-Headers", "Content-Disposition, Content-Length, Content-Type");
        respHeaders.set("Access-Control-Max-Age", "86400");
    }

    public static boolean handlePreflight(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    public static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        String json = payload instanceof String s ? s : GSON.toJson(payload);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void sendError(HttpExchange exchange, int statusCode, String code, String message) throws IOException {
        Map<String, Object> errorDetail = new LinkedHashMap<>();
        errorDetail.put("code", code != null ? code : "ERROR");
        errorDetail.put("message", message != null ? message : "Request failed");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("error", errorDetail);
        envelope.put("code", errorDetail.get("code"));
        envelope.put("message", errorDetail.get("message"));
        sendJson(exchange, statusCode, envelope);
    }

    public static Map<String, String> parseQueryParams(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return Collections.emptyMap();
        }
        return parseQueryString(uri.getRawQuery());
    }

    public static Map<String, String> parseQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        String q = rawQuery.startsWith("?") ? rawQuery.substring(1) : rawQuery;
        for (String pair : q.split("&")) {
            if (pair.isBlank()) continue;
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String k = urlDecode(pair.substring(0, idx));
                String v = urlDecode(pair.substring(idx + 1));
                map.put(k, v);
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        return map;
    }

    public static String urlDecode(String value) {
        if (value == null) return "";
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    public static String getString(JsonObject obj, String key, String defaultVal) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return defaultVal;
        }
    }

    public static int getInt(JsonObject obj, String key, int defaultVal) {
        Integer v = getInteger(obj, key);
        return v != null ? v : defaultVal;
    }

    public static Integer getInteger(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            try {
                return Integer.parseInt(obj.get(key).getAsString().trim());
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static long getLong(JsonObject obj, String key, long defaultVal) {
        Long v = getLongObj(obj, key);
        return v != null ? v : defaultVal;
    }

    public static Long getLongObj(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception e) {
            try {
                return Long.parseLong(obj.get(key).getAsString().trim());
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static boolean getBoolean(JsonObject obj, String key, boolean defaultVal) {
        if (obj == null || key == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return defaultVal;
        }
        try {
            JsonElement el = obj.get(key);
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) {
                return el.getAsBoolean();
            }
            String s = el.getAsString().trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    public static List<Long> getLongList(JsonObject obj, String key) {
        if (obj == null || key == null || !obj.has(key) || !obj.get(key).isJsonArray()) {
            return Collections.emptyList();
        }
        List<Long> list = new ArrayList<>();
        JsonArray arr = obj.getAsJsonArray(key);
        for (JsonElement el : arr) {
            if (el == null || el.isJsonNull()) continue;
            try {
                list.add(el.getAsLong());
            } catch (Exception ignored) {}
        }
        return list;
    }

    public static String toIsoTimestamp(Timestamp ts) {
        if (ts == null) return Instant.now().toString();
        return ts.toInstant().toString();
    }

    public static String normalizeTimestampString(String raw) {
        if (raw == null || raw.isBlank()) return Instant.now().toString();
        String trimmed = raw.trim();
        if (trimmed.contains("T") && (trimmed.endsWith("Z") || trimmed.contains("+"))) {
            return trimmed;
        }
        try {
            Timestamp ts = Timestamp.valueOf(trimmed);
            return ts.toInstant().toString();
        } catch (Exception e) {
            return trimmed;
        }
    }

    public static Map<String, Object> toUserDto(User u, EventBus eventBus) {
        if (u == null) return null;
        boolean isOnline = (eventBus != null && eventBus.isUserOnline(u.getId())) || u.isOnline();
        String presence = u.getPresenceStatus();
        if (presence == null || presence.isBlank()) {
            presence = isOnline ? "ONLINE" : "OFFLINE";
        } else if (!isOnline) {
            presence = "OFFLINE";
        }
        boolean visibleOnline = isOnline && !"OFFLINE".equalsIgnoreCase(presence);

        String transport = null;
        if (eventBus != null) {
            List<Session> sessions = eventBus.getSessionsByUser(u.getId());
            for (Session s : sessions) {
                if (s != null && s.isOpen() && s.getTransport() != null) {
                    transport = s.getTransport().name();
                    if ("WS".equals(transport)) break;
                }
            }
        }

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", u.getId());
        dto.put("userId", u.getId());
        dto.put("username", u.getUsername());
        dto.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        dto.put("avatarColor", u.getAvatarColor() != null ? u.getAvatarColor() : "#2563EB");
        dto.put("status", presence.toUpperCase());
        dto.put("presenceStatus", presence.toUpperCase());
        dto.put("customStatus", u.getCustomStatus() != null ? u.getCustomStatus() : "");
        dto.put("role", u.getRole() != null ? u.getRole() : "MEMBER");
        dto.put("online", visibleOnline);
        if (transport != null) {
            dto.put("transport", transport);
        }
        dto.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toInstant().toString() : null);
        return dto;
    }

    public static Map<String, Object> toChannelDto(Room r) {
        if (r == null) return null;
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", r.getId());
        dto.put("channelId", r.getId());
        dto.put("roomId", r.getId());
        dto.put("guildId", r.getGuildId() > 0 ? r.getGuildId() : 1);
        dto.put("name", r.getName());
        String type = (r.getChannelType() != null && !r.getChannelType().isBlank()) ? r.getChannelType().toLowerCase() : "text";
        dto.put("type", type);
        dto.put("channelType", type);
        dto.put("topic", r.getTopic() != null ? r.getTopic() : "");
        dto.put("position", r.getPosition());
        dto.put("unreadCount", r.getUnreadCount());
        dto.put("createdBy", r.getCreatedBy());
        dto.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toInstant().toString() : null);
        return dto;
    }

    public static Map<String, Object> toGuildDto(Guild g) {
        if (g == null) return null;
        List<Map<String, Object>> channelDtos = new ArrayList<>();
        int totalUnread = 0;
        if (g.getChannels() != null) {
            for (Room r : g.getChannels()) {
                channelDtos.add(toChannelDto(r));
                totalUnread += Math.max(0, r.getUnreadCount());
            }
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", g.getId());
        dto.put("name", g.getName());
        dto.put("iconText", g.getIconText() != null ? g.getIconText() : "DM");
        dto.put("ownerId", g.getOwnerId());
        dto.put("createdAt", normalizeTimestampString(g.getCreatedAt()));
        dto.put("channels", channelDtos);
        dto.put("rooms", channelDtos);
        dto.put("unreadCount", totalUnread);
        return dto;
    }

    public static Map<String, Object> toAttachmentDto(Attachment a) {
        if (a == null) return null;
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", a.getId());
        dto.put("attachmentId", a.getId());
        dto.put("messageId", a.getMessageId());
        dto.put("roomId", a.getRoomId());
        dto.put("channelId", a.getRoomId());
        dto.put("directConversationId", a.getDirectConversationId());
        dto.put("filename", a.getOriginalName());
        dto.put("originalName", a.getOriginalName());
        dto.put("contentType", a.getContentType());
        dto.put("sizeBytes", a.getSizeBytes());
        dto.put("url", a.getUrl());
        dto.put("createdAt", normalizeTimestampString(a.getCreatedAt()));
        return dto;
    }

    public static Map<String, Object> toReactionDto(ReactionSummary r, Integer currentUserId) {
        if (r == null) return null;
        List<Integer> userIds = r.getUserIds() != null ? r.getUserIds() : Collections.emptyList();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("emoji", r.getEmoji());
        dto.put("count", r.getCount());
        dto.put("userIds", userIds);
        dto.put("me", currentUserId != null && userIds.contains(currentUserId));
        return dto;
    }

    public static Map<String, Object> toMessageDto(Message m, Integer currentUserId, String transport) {
        if (m == null) return null;
        List<Map<String, Object>> attList = new ArrayList<>();
        if (m.getAttachments() != null) {
            for (Attachment a : m.getAttachments()) {
                attList.add(toAttachmentDto(a));
            }
        }
        List<Map<String, Object>> reactList = new ArrayList<>();
        if (m.getReactions() != null) {
            for (ReactionSummary r : m.getReactions()) {
                reactList.add(toReactionDto(r, currentUserId));
            }
        }

        Map<String, Object> replyTo = null;
        if (m.getReplyToId() != null && m.getReplyToId() > 0) {
            replyTo = new LinkedHashMap<>();
            replyTo.put("id", m.getReplyToId());
            replyTo.put("senderId", 0);
            replyTo.put("senderUsername", m.getReplyToSender() != null ? m.getReplyToSender() : "");
            replyTo.put("senderDisplayName", m.getReplyToSender() != null ? m.getReplyToSender() : "");
            replyTo.put("content", m.getReplyToContent() != null ? m.getReplyToContent() : "");
        }

        String isoCreated = normalizeTimestampString(m.getTimestamp());
        String isoEdited = m.getEditedAt() != null ? normalizeTimestampString(m.getEditedAt()) : null;
        String isoDeleted = m.getDeletedAt() != null ? normalizeTimestampString(m.getDeletedAt()) : null;

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", m.getId());
        dto.put("channelId", m.getRoomId());
        dto.put("roomId", m.getRoomId());
        dto.put("conversationId", null);
        dto.put("senderId", m.getSenderId());
        dto.put("userId", m.getSenderId());
        dto.put("senderUsername", m.getSender());
        dto.put("senderDisplayName", m.getSender());
        dto.put("sender", m.getSender());
        dto.put("content", m.getContent());
        dto.put("replyToId", m.getReplyToId());
        dto.put("replyToSender", m.getReplyToSender());
        dto.put("replyToContent", m.getReplyToContent());
        dto.put("replyTo", replyTo);
        dto.put("attachments", attList);
        dto.put("reactions", reactList);
        dto.put("pinned", m.isPinned());
        dto.put("createdAt", isoCreated);
        dto.put("sentAt", isoCreated);
        dto.put("timestamp", m.getTimestamp());
        dto.put("editedAt", isoEdited);
        dto.put("deletedAt", isoDeleted);
        dto.put("transport", transport != null ? transport : "WS");
        return dto;
    }

    public static Map<String, Object> toDirectMessageDto(DirectMessage dm) {
        return toDirectMessageDto(dm, null);
    }

    public static Map<String, Object> toDirectMessageDto(DirectMessage dm, Integer currentUserId) {
        if (dm == null) return null;
        List<Map<String, Object>> attList = new ArrayList<>();
        if (dm.getAttachments() != null) {
            for (Attachment a : dm.getAttachments()) {
                attList.add(toAttachmentDto(a));
            }
        }
        List<Map<String, Object>> reactionList = new ArrayList<>();
        if (dm.getReactions() != null) {
            for (ReactionSummary reaction : dm.getReactions()) {
                reactionList.add(toReactionDto(reaction, currentUserId));
            }
        }
        String isoSent = normalizeTimestampString(dm.getSentAt());
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", dm.getId());
        dto.put("channelId", null);
        dto.put("conversationId", dm.getConversationId());
        dto.put("senderId", dm.getSenderId());
        dto.put("senderUsername", dm.getSenderDisplayName());
        dto.put("senderDisplayName", dm.getSenderDisplayName());
        dto.put("senderAvatarColor", dm.getSenderAvatarColor());
        dto.put("content", dm.getContent());
        dto.put("replyToMessageId", dm.getReplyToMessageId());
        dto.put("replyTo", null);
        dto.put("attachments", attList);
        dto.put("reactions", reactionList);
        dto.put("pinned", false);
        dto.put("createdAt", isoSent);
        dto.put("sentAt", isoSent);
        dto.put("editedAt", dm.getEditedAt() != null ? normalizeTimestampString(dm.getEditedAt()) : null);
        dto.put("deletedAt", dm.getDeletedAt() != null ? normalizeTimestampString(dm.getDeletedAt()) : null);
        dto.put("transport", "WS");
        return dto;
    }

    public static Map<String, Object> toDirectConversationDto(DirectConversation dc, EventBus eventBus) {
        if (dc == null) return null;
        boolean peerOnline = eventBus != null && eventBus.isUserOnline(dc.getPeerUserId());
        String status = dc.getPeerPresenceStatus() != null ? dc.getPeerPresenceStatus() : "OFFLINE";
        if (!peerOnline) {
            status = "OFFLINE";
        }
        boolean visiblePeerOnline = peerOnline && !"OFFLINE".equalsIgnoreCase(status);

        Map<String, Object> peerUser = new LinkedHashMap<>();
        peerUser.put("id", dc.getPeerUserId());
        peerUser.put("username", dc.getPeerUsername());
        peerUser.put("displayName", dc.getPeerDisplayName());
        peerUser.put("avatarColor", dc.getPeerAvatarColor());
        peerUser.put("status", status.toUpperCase());
        peerUser.put("presenceStatus", status.toUpperCase());
        peerUser.put("online", visiblePeerOnline);

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", dc.getId());
        dto.put("userLowId", dc.getUserLowId());
        dto.put("userHighId", dc.getUserHighId());
        dto.put("peerUserId", dc.getPeerUserId());
        dto.put("peerUsername", dc.getPeerUsername());
        dto.put("peerDisplayName", dc.getPeerDisplayName());
        dto.put("peerAvatarColor", dc.getPeerAvatarColor());
        dto.put("peerPresenceStatus", status.toUpperCase());
        dto.put("peerUser", peerUser);
        dto.put("lastMessagePreview", dc.getLastMessagePreview() != null ? dc.getLastMessagePreview() : "");
        dto.put("unreadCount", dc.getUnreadCount());
        String isoCreated = normalizeTimestampString(dc.getCreatedAt());
        dto.put("createdAt", isoCreated);
        dto.put("updatedAt", isoCreated);
        return dto;
    }
}
