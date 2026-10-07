package quiz.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Tiện ích băm và xác thực mật khẩu an toàn sử dụng SHA-256 + Salt (Mục 1.1.2 tài liệu).
 */
public final class PasswordUtil {
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /** Tạo mã băm mật khẩu: salt:sha256(salt + password) */
    public static String hash(String plainPassword) {
        if (plainPassword == null) throw new IllegalArgumentException("Mật khẩu không được để trống");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String hashHex = sha256(saltHex + plainPassword);
        return saltHex + ":" + hashHex;
    }

    /** Kiểm tra mật khẩu gốc có khớp với chuỗi băm hay không */
    public static boolean verify(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null) return false;
        String[] parts = storedHash.split(":");
        if (parts.length != 2) return false;
        String saltHex = parts[0];
        String expectedHash = parts[1];
        String actualHash = sha256(saltHex + plainPassword);
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                                     actualHash.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

