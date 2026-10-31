package com.discordmini.client.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Wire-format helpers for the line-based Client/Server protocol.
 *
 * Each frame is one UTF-8 line. The command name and control fields are plain
 * text; user-supplied fields are Base64-encoded UTF-8 so they cannot break the
 * pipe-delimited frame.
 */
public final class ProtocolHandler {
    public static final String REQUEST_ID_MARKER = "@RID";
    public static final int MAX_LINE_LENGTH = 65_536;

    private ProtocolHandler() {}

    public static String encode(String value) {
        if (value == null) return "";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            // Fallback an toàn nếu Server gửi plain text: trả về trực tiếp chuỗi gốc thay vì crash
            return value;
        }
    }

    public static String command(String type, String... fields) {
        if (type == null || type.isBlank() || type.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Invalid command type.");
        }
        StringBuilder sb = new StringBuilder(type);
        if (fields != null) {
            for (String field : fields) sb.append('|').append(encode(field));
        }
        String line = sb.toString();
        validateFrame(line);
        return line;
    }

    public static String commandWithRequestId(String type, String requestId, String... fields) {
        if (requestId == null || requestId.isBlank() || requestId.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Invalid request ID.");
        }
        String base = command(type, fields);
        String line = base + "|" + REQUEST_ID_MARKER + "|" + requestId;
        validateFrame(line);
        return line;
    }

    public static String[] split(String line) {
        validateFrame(line);
        return line.split("\\|", -1);
    }

    /** Returns the optional request ID appended by a modern client. */
    public static String requestId(String[] parts) {
        if (parts == null || parts.length < 3) return null;
        if (!REQUEST_ID_MARKER.equals(parts[parts.length - 2])) return null;
        String id = parts[parts.length - 1];
        return id.isBlank() ? null : id;
    }

    public static void validateFrame(String line) {
        if (line == null) throw new IllegalArgumentException("Protocol frame is null.");
        if (line.isEmpty()) throw new IllegalArgumentException("Protocol frame is empty.");
        if (line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("Protocol frame exceeds " + MAX_LINE_LENGTH + " characters.");
        }
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Protocol frame must contain exactly one line.");
        }
    }
}
