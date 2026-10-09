package quiz.service;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xác minh "ID token" mà Google trả về khi người dùng bấm "Đăng nhập bằng Google".
 *
 * Token là một JWT ký bằng RS256. Server kiểm tra:
 *  1. chữ ký, bằng khóa công khai của Google (https://www.googleapis.com/oauth2/v3/certs);
 *  2. iss là Google, aud đúng là Client ID của game, token còn hạn;
 *  3. email đã được Google xác minh.
 * Chỉ khi cả ba đều đạt thì mới tin nội dung token (mã người dùng "sub", email, tên).
 *
 * Client ID lấy từ -Dquiz.google.clientId, biến môi trường QUIZ_GOOGLE_CLIENT_ID,
 * nếu không có thì dùng giá trị mặc định bên dưới (Client ID không phải bí mật).
 */
public final class GoogleTokenVerifier {
    public static final String DEFAULT_CLIENT_ID =
            "360362842720-ge5oh5ptjin31t0k9vvp8jd1124ti3os.apps.googleusercontent.com";
    private static final String CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final long LEEWAY_SEC = 60;        // cho phép lệch đồng hồ 60 giây
    private static final long MIN_REFETCH_SEC = 60;   // không tải lại khóa quá 1 lần/phút
    private static final int MAX_TOKEN_LEN = 8192;

    public record GoogleUser(String sub, String email, String name) { }

    /** Lỗi xác minh. Thông điệp dùng được để hiện cho người chơi. */
    public static final class VerifyException extends Exception {
        private final boolean network;
        VerifyException(String message, boolean network) { super(message); this.network = network; }
        /** true nếu lỗi do server không kết nối được Google (chứ không phải token sai). */
        public boolean isNetwork() { return network; }
    }

    public record Keys(Map<String, PublicKey> byKid, long maxAgeSec) { }

    /** Nguồn khóa công khai của Google. Tách ra để có thể kiểm thử mà không cần mạng. */
    public interface KeySource { Keys fetch() throws IOException; }

    private final String clientId;
    private final KeySource keySource;
    private final LongSupplier nowSec;
    private Map<String, PublicKey> cache = Map.of();
    private long cacheExpires = 0;
    private long lastFetch = 0;

    public GoogleTokenVerifier(String clientId, KeySource keySource, LongSupplier nowSec) {
        this.clientId = clientId;
        this.keySource = keySource;
        this.nowSec = nowSec;
    }

    public static GoogleTokenVerifier createDefault() {
        String id = System.getProperty("quiz.google.clientId");
        if (id == null || id.isBlank()) id = System.getenv("QUIZ_GOOGLE_CLIENT_ID");
        if (id == null || id.isBlank()) id = DEFAULT_CLIENT_ID;
        return new GoogleTokenVerifier(id.trim(), new HttpKeySource(), () -> System.currentTimeMillis() / 1000);
    }

    public String clientId() { return clientId; }

    // ------------------------------------------------------------------ xác minh

    public GoogleUser verify(String token) throws VerifyException {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LEN) throw invalid();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) throw invalid();

        Map<String, Object> header;
        byte[] signature;
        try {
            header = Json.parseObject(utf8(b64(parts[0])));
            signature = b64(parts[2]);
        } catch (RuntimeException e) {
            throw invalid();
        }
        // Chỉ nhận RS256 (từ chối "none", HS256...). Đây là chốt chặn quan trọng.
        if (!"RS256".equals(header.get("alg"))) throw invalid();
        if (!(header.get("kid") instanceof String kid) || kid.isEmpty()) throw invalid();

        PublicKey key = findKey(kid);
        if (key == null) throw invalid();

        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(signature)) throw invalid();
        } catch (GeneralSecurityException e) {
            throw invalid();
        }

        // Từ đây chữ ký đã đúng, mới đọc nội dung.
        Map<String, Object> payload;
        try {
            payload = Json.parseObject(utf8(b64(parts[1])));
        } catch (RuntimeException e) {
            throw invalid();
        }

        String iss = str(payload.get("iss"));
        if (!iss.equals("https://accounts.google.com") && !iss.equals("accounts.google.com")) throw invalid();
        if (!audienceMatches(payload.get("aud"))) throw invalid();
        if (!(payload.get("exp") instanceof Double exp)) throw invalid();
        if (nowSec.getAsLong() > exp.longValue() + LEEWAY_SEC) {
            throw new VerifyException("Phiên đăng nhập Google đã hết hạn, hãy thử lại", false);
        }

        String sub = str(payload.get("sub"));
        String email = str(payload.get("email"));
        if (sub.isEmpty() || email.isEmpty()) throw invalid();
        Object ev = payload.get("email_verified");
        if (!(Boolean.TRUE.equals(ev) || "true".equals(ev))) {
            throw new VerifyException("Email Google của bạn chưa được xác minh", false);
        }
        return new GoogleUser(sub, email, str(payload.get("name")));
    }

    private boolean audienceMatches(Object aud) {
        if (aud instanceof String s) return clientId.equals(s);
        if (aud instanceof List<?> l) return l.contains(clientId);
        return false;
    }

    private static VerifyException invalid() {
        return new VerifyException("Đăng nhập Google không hợp lệ hoặc đã hết hạn", false);
    }

    private static String str(Object o) { return o instanceof String s ? s : ""; }
    private static byte[] b64(String s) { return Base64.getUrlDecoder().decode(s); }
    private static String utf8(byte[] b) { return new String(b, StandardCharsets.UTF_8); }

    // ------------------------------------------------------------------ khóa của Google

    private synchronized PublicKey findKey(String kid) throws VerifyException {
        long now = nowSec.getAsLong();
        PublicKey known = cache.get(kid);
        boolean fresh = now < cacheExpires;
        if (known != null && fresh) return known;
        // Tải lại khi cache hết hạn, hoặc gặp kid lạ (tối đa 1 lần/phút để không bị lợi dụng).
        if (!fresh || now - lastFetch >= MIN_REFETCH_SEC) {
            lastFetch = now;
            try {
                Keys ks = keySource.fetch();
                cache = ks.byKid();
                cacheExpires = now + Math.max(300, Math.min(86400, ks.maxAgeSec()));
            } catch (IOException e) {
                if (known != null) return known; // khóa cũ vẫn dùng được nếu Google tạm thời không truy cập được
                throw new VerifyException("Máy chủ không kết nối được Google để xác minh. Hãy kiểm tra mạng.", true);
            }
            return cache.get(kid);
        }
        return known;
    }

    /** Đọc danh sách khóa dạng JWKS của Google: {"keys":[{"kid":..,"kty":"RSA","n":..,"e":..},...]}. */
    static Map<String, PublicKey> parseJwks(String json) throws IOException {
        Map<String, PublicKey> out = new HashMap<>();
        try {
            Map<String, Object> root = Json.parseObject(json);
            if (!(root.get("keys") instanceof List<?> list)) throw new IOException("JWKS không hợp lệ");
            KeyFactory kf = KeyFactory.getInstance("RSA");
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> k)) continue;
                try {
                    if (!"RSA".equals(k.get("kty"))) continue;
                    String kid = str(k.get("kid")), n = str(k.get("n")), e = str(k.get("e"));
                    if (kid.isEmpty() || n.isEmpty() || e.isEmpty()) continue;
                    out.put(kid, kf.generatePublic(new RSAPublicKeySpec(
                            new BigInteger(1, b64(n)), new BigInteger(1, b64(e)))));
                } catch (GeneralSecurityException | RuntimeException ignored) {
                    // bỏ qua khóa hỏng, dùng các khóa còn lại
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("JWKS không đọc được", e);
        }
        if (out.isEmpty()) throw new IOException("JWKS không có khóa nào dùng được");
        return out;
    }

    /** Tải khóa công khai từ Google qua HTTPS. */
    static final class HttpKeySource implements KeySource {
        private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        @Override public Keys fetch() throws IOException {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(CERTS_URL))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (res.statusCode() != 200) throw new IOException("Google trả về HTTP " + res.statusCode());
                String body = res.body();
                if (body.length() > 200_000) throw new IOException("Phản hồi quá lớn");
                long maxAge = 3600;
                Matcher m = MAX_AGE.matcher(res.headers().firstValue("Cache-Control").orElse(""));
                if (m.find()) maxAge = Long.parseLong(m.group(1));
                return new Keys(parseJwks(body), maxAge);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Bị ngắt khi tải khóa Google", e);
            }
        }
    }

    // ------------------------------------------------------------------ JSON tối giản (chỉ đủ cho JWT/JWKS)

    private static final class Json {
        private final String s;
        private int i = 0;
        private Json(String s) { this.s = s; }

        static Map<String, Object> parseObject(String text) {
            Json p = new Json(text);
            Object v = p.value(0);
            p.ws();
            if (p.i != p.s.length() || !(v instanceof Map)) throw bad();
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
            return m;
        }

        private static IllegalArgumentException bad() { return new IllegalArgumentException("json"); }
        private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        private char peek() { if (i >= s.length()) throw bad(); return s.charAt(i); }
        private char next() { if (i >= s.length()) throw bad(); return s.charAt(i++); }
        private void expect(char c) { if (next() != c) throw bad(); }

        private Object value(int depth) {
            if (depth > 16) throw bad();
            ws();
            switch (peek()) {
                case '{': return object(depth);
                case '[': return array(depth);
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default: return number();
            }
        }

        private Object literal(String word, Object val) {
            if (!s.startsWith(word, i)) throw bad();
            i += word.length();
            return val;
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw bad();
                String k = string();
                ws();
                expect(':');
                m.put(k, value(depth + 1));
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw bad();
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> l = new java.util.ArrayList<>();
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value(depth + 1));
                ws();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw bad();
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': case '\\': case '/': b.append(e); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw bad();
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw bad();
                    }
                } else if (c < 0x20) {
                    throw bad();
                } else {
                    b.append(c);
                }
            }
        }

        private Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw bad();
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
