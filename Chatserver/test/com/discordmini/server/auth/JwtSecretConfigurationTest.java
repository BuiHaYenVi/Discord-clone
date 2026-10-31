package com.discordmini.server.auth;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Regression coverage for fail-closed JWT signing configuration. */
public final class JwtSecretConfigurationTest {
    public static void main(String[] args) {
        expectInvalid(null);
        expectInvalid("short-secret");
        byte[] configured = JwtUtil.secretBytesFrom("0123456789abcdef0123456789abcdef");
        if (!Arrays.equals(configured, "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8))) {
            throw new AssertionError("Configured JWT secret bytes changed unexpectedly");
        }
        System.out.println("JwtSecretConfigurationTest: 3 assertions passed");
    }

    private static void expectInvalid(String secret) {
        try {
            JwtUtil.secretBytesFrom(secret);
            throw new AssertionError("Missing or short JWT_SECRET must fail closed");
        } catch (IllegalStateException expected) {
            // expected
        }
    }
}
