package com.discordmini.server.auth;

import com.discordmini.server.model.User;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Pure JDK HMAC-SHA256 (HS256) JWT generator and verifier.
 * Zero external dependencies required.
 */
public final class JwtUtil {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final int MIN_SECRET_LENGTH = 32;
    private static final long DEFAULT_TTL_MINUTES = 1440L; // 24 hours

    public record JwtClaims(int userId, String username, String displayName, long exp) {}

    private JwtUtil() {}

    private static byte[] getSecretBytes() {
        return secretBytesFrom(System.getenv("JWT_SECRET"));
    }

    static byte[] secretBytesFrom(String envSecret) {
        if (envSecret == null || envSecret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JWT_SECRET must be configured with at least 32 characters.");
        }
        return envSecret.trim().getBytes(StandardCharsets.UTF_8);
    }

    private static long getTtlMinutes() {
        String envTtl = System.getenv("JWT_TTL_MINUTES");
        if (envTtl != null && !envTtl.isBlank()) {
            try {
                long parsed = Long.parseLong(envTtl.trim());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_TTL_MINUTES;
    }

    public static String generateToken(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        return generateToken(user.getId(), user.getUsername(), user.getDisplayName());
    }

    public static String generateToken(int userId, String username, String displayName) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long expSec = nowSec + (getTtlMinutes() * 60L);

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String safeUsername = escapeJson(username != null ? username : "");
        String safeDisplayName = escapeJson(displayName != null ? displayName : safeUsername);

        String payloadJson = "{"
                + "\"sub\":" + userId + ","
                + "\"userId\":" + userId + ","
                + "\"username\":\"" + safeUsername + "\","
                + "\"displayName\":\"" + safeDisplayName + "\","
                + "\"iat\":" + nowSec + ","
                + "\"exp\":" + expSec
                + "}";

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String headerB64 = encoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;
        String signatureB64 = sign(signingInput);

        return signingInput + "." + signatureB64;
    }

    public static JwtClaims verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = token.trim();
            if (raw.regionMatches(true, 0, "Bearer ", 0, 7)) {
                raw = raw.substring(7).trim();
            }
            String[] parts = raw.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            String signingInput = parts[0] + "." + parts[1];
            String expectedSig = sign(signingInput);
            if (!MessageDigest.isEqual(
                    expectedSig.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);

            int userId = extractInt(payloadJson, "userId", extractInt(payloadJson, "sub", -1));
            if (userId <= 0) {
                return null;
            }
            String username = extractString(payloadJson, "username");
            String displayName = extractString(payloadJson, "displayName");
            long exp = extractLong(payloadJson, "exp", -1L);
            if (exp <= 0) {
                return null;
            }

            long nowSec = System.currentTimeMillis() / 1000L;
            long expSec = exp > 100_000_000_000L ? (exp / 1000L) : exp;
            if (nowSec >= expSec) {
                return null;
            }

            return new JwtClaims(
                    userId,
                    username != null ? username : "",
                    displayName != null ? displayName : (username != null ? username : ""),
                    expSec
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(getSecretBytes(), HMAC_ALGO);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT with " + HMAC_ALGO, e);
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        StringBuilder sb = new StringBuilder();
        boolean escaping = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaping) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static int extractInt(String json, String key, int defaultValue) {
        long val = extractLong(json, key, defaultValue);
        return (int) val;
    }

    private static long extractLong(String json, String key, long defaultValue) {
        String pattern = "\"" + key + "\":";
        int start = json.indexOf(pattern);
        if (start < 0) return defaultValue;
        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) return defaultValue;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
