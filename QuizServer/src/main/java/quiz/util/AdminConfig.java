package quiz.util;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Properties;

public final class AdminConfig {
    private static final String PASSWORD;
    private static final boolean GENERATED;
    private static final String SOURCE;
    private static final boolean FOUND;
    private static final String HINT;

    static {
        Properties props = new Properties();
        String source = "khong tim thay db.properties";
        try {
            Path p = Paths.get("db.properties");
            byte[] data = null;
            if (Files.exists(p)) {
                data = Files.readAllBytes(p);
                source = p.toAbsolutePath().toString();
            } else {
                try (java.io.InputStream is = AdminConfig.class.getClassLoader().getResourceAsStream("db.properties")) {
                    if (is != null) { data = is.readAllBytes(); source = "classpath:db.properties"; }
                }
            }
            if (data != null) props.load(new StringReader(decode(data)));
        } catch (Exception ignored) { }

        String sys = System.getProperty("quiz.admin.password");
        String v = (sys != null ? sys : props.getProperty("admin.password", "")).trim();
        boolean gen = v.isEmpty();
        if (gen) {
            String chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
            SecureRandom r = new SecureRandom();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(chars.charAt(r.nextInt(chars.length())));
            v = b.toString();
        }
        PASSWORD = v;
        GENERATED = gen;
        SOURCE = source;
        FOUND = sys != null || props.containsKey("admin.password");
        String dbPw = props.getProperty("db.password", "");
        HINT = dbPw.contains("admin.password") ? "Dong 'db.password' dang chua chu 'admin.password': hay xuong dong de admin.password nam tren dong rieng." : "";
    }

    private AdminConfig() { }

    /** Giải mã file cấu hình: UTF-8 (bỏ BOM) hoặc UTF-16 nếu có BOM UTF-16. */
    private static String decode(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xFE) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFE && (d[1] & 0xFF) == 0xFF) return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        if (d.length >= 3 && (d[0] & 0xFF) == 0xEF && (d[1] & 0xFF) == 0xBB && (d[2] & 0xFF) == 0xBF) return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        return new String(d, StandardCharsets.UTF_8);
    }

    public static boolean check(String input) {
        if (input == null) return false;
        return MessageDigest.isEqual(PASSWORD.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8));
    }

    /** In thông tin đăng nhập trang quản trị ra console (chỉ ASCII để khỏi lỗi font). */
    public static void announce() {
        System.out.println("Trang quan tri: /admin.html   Mat khau: " + PASSWORD
                + (GENERATED ? "   (tu sinh - dat admin.password trong db.properties de co dinh)" : ""));
        System.out.println("[admin] doc cau hinh tu: " + SOURCE + (FOUND ? "  -> tim thay admin.password" : "  -> KHONG co dong admin.password"));
        if (!HINT.isEmpty()) System.out.println("[admin] " + HINT);
    }
}