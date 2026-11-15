package com.chichijik.broadcast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 송출 키: 방송을 시작한 기기에만 주는 비밀 값. 사람을 식별하지 않고, 그 방송을 송출·종료할 권리만 나타냅니다.
 * DB에는 SHA-256 해시만 저장합니다.
 */
final class StreamKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private StreamKeys() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 타이밍 공격을 피하기 위해 상수 시간 비교 */
    static boolean matches(String key, String expectedHash) {
        if (key == null || key.isBlank() || expectedHash == null) return false;
        return MessageDigest.isEqual(
                hash(key).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
