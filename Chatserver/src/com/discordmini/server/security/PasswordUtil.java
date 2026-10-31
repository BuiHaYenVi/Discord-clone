package com.discordmini.server.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Tiện ích băm và xác thực mật khẩu an toàn sử dụng PBKDF2WithHmacSHA256 với Salt ngẫu nhiên.
 * Định dạng chuẩn: pbkdf2$<iterations>$<base64-salt>$<base64-hash>
 * Hỗ trợ tương thích ngược cho định dạng PBKDF2 hex cũ, SHA-256 hex cũ và plaintext.
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2$";
    private static final String LEGACY_PBKDF2_PREFIX = "PBKDF2$";
    private static final int ITERATIONS = 65_536;
    private static final int LEGACY_ITERATIONS = 310_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /**
     * Băm mật khẩu với PBKDF2WithHmacSHA256 (65,536 vòng lặp, 16-byte salt, 256-bit key).
     * Định dạng trả về: pbkdf2$<iterations>$<base64-salt>$<base64-hash>
     */
    public static String hashPassword(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Mật khẩu không được rỗng.");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(plainPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        String saltB64 = Base64.getEncoder().encodeToString(salt);
        String hashB64 = Base64.getEncoder().encodeToString(hash);
        return PREFIX + ITERATIONS + "$" + saltB64 + "$" + hashB64;
    }

    /**
     * Xác thực mật khẩu nhập vào với giá trị lưu trong cơ sở dữ liệu.
     * Hỗ trợ:
     * 1. pbkdf2$<iterations>$<base64-salt>$<base64-hash>
     * 2. PBKDF2$<saltHex>$<hashHex> (tương thích ngược)
     * 3. SHA-256 hex (64 ký tự hex)
     * 4. Plaintext (tài khoản seed cũ)
     */
    public static boolean verifyPassword(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) {
            return false;
        }
        if (storedHash.startsWith(PREFIX)) {
            return verifyPbkdf2Base64(plainPassword, storedHash);
        }
        if (storedHash.startsWith(LEGACY_PBKDF2_PREFIX)) {
            return verifyLegacyPbkdf2Hex(plainPassword, storedHash);
        }
        if (isSha256Hex(storedHash)) {
            byte[] candidateSha = sha256(plainPassword);
            byte[] storedBytes = HexFormat.of().parseHex(storedHash.toLowerCase());
            if (MessageDigest.isEqual(candidateSha, storedBytes)) {
                return true;
            }
        }
        return MessageDigest.isEqual(
            plainPassword.getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Kiểm tra xem mật khẩu lưu trữ có cần nâng cấp lên định dạng pbkdf2$ mới hay không.
     */
    public static boolean needsRehash(String storedPassword) {
        return storedPassword == null || !storedPassword.startsWith(PREFIX);
    }

    private static boolean verifyPbkdf2Base64(String plainPassword, String storedHash) {
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0) {
                return false;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[3]);
            byte[] actualHash = pbkdf2(plainPassword.toCharArray(), salt, iterations, expectedHash.length * 8);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean verifyLegacyPbkdf2Hex(String plainPassword, String storedHash) {
        String[] parts = storedHash.split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            byte[] salt = HexFormat.of().parseHex(parts[1]);
            byte[] expectedHash = HexFormat.of().parseHex(parts[2]);
            byte[] actualHash = pbkdf2(plainPassword.toCharArray(), salt, LEGACY_ITERATIONS, expectedHash.length * 8);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSha256Hex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean isHex = (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
            if (!isHex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Không hỗ trợ SHA-256", e);
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Không hỗ trợ thuật toán băm " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }
}
