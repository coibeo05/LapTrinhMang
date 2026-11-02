package quiz;

import jakarta.json.*;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import quiz.model.Account;
import quiz.model.Message;
import quiz.service.AuthService;

/**
 * SERVER TCP Game Quiz. Tích hợp Module 1 (Kết nối & Tài khoản - CSDL SQLite + SessionManager).
 * Gói tin: {"type": "...", "sessionId": "...", "payload": {...}}
 */
public class Game {
    private final AuthService authService = new AuthService();

    record Q(int id, String type, String text, JsonObject pub, JsonValue key, double tol, int time, String image, String audio) {
        Q(int id, String type, String text, JsonObject pub, JsonValue key, double tol) { this(id, type, text, pub, key, tol, TIME_SEC, null, null); }
    }

    private static final int TIME_SEC = 15;
    private static final int ME = 1;
    private static final List<Q> BANK = new ArrayList<>();   // câu mẫu, mỗi dạng một câu

    /** Bộ câu hỏi. owner = null là bộ có sẵn của hệ thống; khác null là bộ riêng của người tạo. */
    record QSet(int setId, String ten, String owner, List<Q> qs) {}
    private static final List<QSet> SETS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger SET_SEQ = new AtomicInteger(0);

    private static JsonArray opts(String... kv) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (int i = 0; i < kv.length; i += 2)
            a.add(Json.createObjectBuilder().add("id", kv[i]).add("text", kv[i + 1]));
        return a.build();
    }

    private static JsonArray strs(String... s) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (String x : s) a.add(x);
        return a.build();
    }

    static {
        // duLieuDapAn gửi cho Client (pub) CHỈ chứa phần hiển thị; đáp án đúng (key) giữ ở Server.
        BANK.add(new Q(1, "BUTTONS", "Thủ đô của Việt Nam là thành phố nào?",
                Json.createObjectBuilder().add("luaChon", opts("a", "Hà Nội", "b", "Huế", "c", "Đà Nẵng", "d", "Cần Thơ")).build(),
                Json.createValue("a"), 0));
        BANK.add(new Q(2, "CHECKBOXES", "Chọn tất cả các số nguyên tố:",
                Json.createObjectBuilder().add("luaChon", opts("a", "2", "b", "4", "c", "5", "d", "9")).build(),
                strs("a", "c"), 0));
        BANK.add(new Q(3, "REORDER", "Sắp xếp các hành tinh theo thứ tự từ gần đến xa Mặt Trời:",
                Json.createObjectBuilder().add("luaChon", opts("a", "Trái Đất", "b", "Sao Thủy", "c", "Sao Hỏa", "d", "Sao Kim")).build(),
                strs("b", "d", "a", "c"), 0));
        BANK.add(new Q(4, "TYPE_ANSWER", "Ngôn ngữ lập trình nào có biểu tượng là tách cà phê?",
                Json.createObjectBuilder().build(),
                strs("java"), 0));
        BANK.add(new Q(5, "RANGE", "Đỉnh Everest cao khoảng bao nhiêu mét?",
                Json.createObjectBuilder().add("min", 0).add("max", 10000).add("step", 50).build(),
                Json.createValue(8849), 100));
        SETS.add(new QSet(SET_SEQ.incrementAndGet(), "Kiến thức chung (mẫu)", null, new ArrayList<>(BANK)));
        loadSavedSets();   // nạp lại các bộ do người dùng tạo từ CSDL
    }

    // ---- Bộ câu hỏi riêng được lưu bền trong CSDL (bảng QuestionSet) ----
    // setId hiển thị = setId trong CSDL + 1, vì số 1 dành cho bộ mẫu của hệ thống.
    // Chủ sở hữu là accountId (dạng chữ), nên đăng nhập lại hay khởi động lại server vẫn thấy bộ của mình.

    /** Chủ sở hữu bộ riêng của kết nối này, hoặc null nếu chưa đăng nhập. */
    private static String ownerKey(Conn s) {
        return s.p == null || s.p.id <= 0 ? null : String.valueOf(s.p.id);
    }

    static JsonObject qToJson(Q q) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("type", q.type()).add("text", q.text())
                .add("pub", q.pub()).add("key", q.key())
                .add("tol", q.tol()).add("time", q.time());
        if (q.image() != null) b.add("image", q.image());
        if (q.audio() != null) b.add("audio", q.audio());
        return b.build();
    }

    static Q qFromJson(JsonObject o, int id) {
        return new Q(id, o.getString("type"), o.getString("text"), o.getJsonObject("pub"), o.get("key"),
                o.getJsonNumber("tol").doubleValue(), o.getInt("time"),
                o.containsKey("image") ? o.getString("image") : null,
                o.containsKey("audio") ? o.getString("audio") : null);
    }

    private static void loadSavedSets() {
        for (quiz.db.QuestionSetDAO.Row r : new quiz.db.QuestionSetDAO().loadAll()) {
            try (JsonReader jr = Json.createReader(new StringReader(r.cauHoi()))) {
                JsonArray arr = jr.readArray();
                List<Q> qs = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) qs.add(qFromJson(arr.getJsonObject(i), i + 1));
                if (!qs.isEmpty()) SETS.add(new QSet(r.setId() + 1, r.ten(), String.valueOf(r.ownerId()), qs));
            } catch (RuntimeException e) {
                System.err.println("Bỏ qua bộ câu hỏi #" + r.setId() + " bị hỏng: " + e.getMessage());
            }
        }
    }

    private static final AtomicInteger IDS = new AtomicInteger(0);

    private static Player me(Conn s) { return s.p; }

    public void onClose(Conn s) {
        Player p = me(s);
        boolean inRoom = p != null && p.room != null;
        if (inRoom) p.room.leave(p);
        // Mất kết nối (tải lại trang / rớt mạng): giữ mã khôi phục thêm một lúc để người chơi vào lại không cần mật khẩu
        String t = s.resumeToken;
        ResumeInfo r = t == null ? null : RESUME.get(t);
        if (r != null && r.conn == s) {   // nếu kết nối khác đã nhận mã này rồi thì không đụng vào
            r.leftRoom = inRoom;
            r.expireAt = System.currentTimeMillis() + RESUME_TTL_MS;
        }
    }

    public void onMessage(String msg, Conn s) {
        try {
            JsonObject in = Json.createReader(new StringReader(msg)).readObject();
            String type = in.getString("type");
            JsonObject p = (in.get("payload") instanceof JsonObject) ? in.getJsonObject("payload") : JsonValue.EMPTY_JSON_OBJECT;
            if (type.equals("PONG")) { PingService.onPong(s, p); return; }
            if (type.equals("REGISTER")) { register(s, p); return; }
            if (type.equals("LOGIN")) { login(s, p); return; }
            if (type.equals("GOOGLE_LOGIN")) { googleLogin(s, p); return; }
            if (type.equals("RESUME")) { resume(s, p); return; }
            if (type.startsWith("ADMIN_")) { AdminService.handle(type, p, s); return; }
            Player me = me(s);
            if (me == null) { error(s, "Bạn chưa đăng nhập"); return; }
            Room r = me.room;
            switch (type) {
                case "GET_PROFILE" -> getProfile(s);
                case "CHANGE_PASSWORD" -> changePassword(s, p);
                case "GET_HISTORY" -> getHistory(s);
                case "GET_RANKING" -> getRanking(s, me);
                case "LOGOUT" -> logout(s);
                case "LIST_SETS" -> sendSets(s);
                case "CREATE_SET" -> createSet(s, p);
                case "LIST_ROOMS" -> send(s, "ROOM_LIST", Json.createObjectBuilder().add("rooms", Room.list()));
                case "CREATE_ROOM" -> createRoom(s, me, p);
                case "JOIN_ROOM" -> {
                    Room t = Room.ALL.get(p.getString("roomId", "").trim().toUpperCase());
                    if (t == null) error(s, "Phòng không tồn tại"); else if (r == null) t.join(me);
                }
                case "ADD_BOT" -> { if (r != null) r.addBot(me); }
                case "UPDATE_ROOM" -> { if (r != null) updateRoom(s, me, r, p); }
                case "SET_READY" -> { if (r != null) r.setReady(me, p.getBoolean("ready", true)); }
                case "KICK_PLAYER" -> { if (r != null) r.kickBy(me, p.getInt("accountId", 0)); }
                case "START_MATCH" -> { if (r != null) r.start(me); }
                case "REQUEST_QUESTION" -> { if (r != null) r.request(me); }
                case "SUBMIT_ANSWER" -> { if (r != null) r.submit(me, p); }
                case "CHAT" -> { if (r != null) r.chat(me, p.getString("noiDung", "")); }
                case "LEAVE_MATCH", "LEAVE_ROOM" -> { if (r != null) r.leave(me); }
                default -> { }
            }
        } catch (RuntimeException e) {
            // gói tin sai định dạng: bỏ qua
        }
    }

    private void register(Conn s, JsonObject p) {
        int sid = s.session != null ? s.session.getSessionId() : -1;
        Message res = authService.handleRegister(p, sid);
        if ("REGISTER_SUCCESS".equals(res.type())) {
            send(s, "REGISTER_SUCCESS", Json.createObjectBuilder()
                    .add("thongBao", "Đăng ký thành công! Hãy đăng nhập.")
                    .add("username", res.payload().getString("username", "")));
        } else {
            String reason = res.payload().getString("lyDo", "Đăng ký thất bại");
            send(s, "REGISTER_FAIL", Json.createObjectBuilder()
                    .add("thongBao", reason)
                    .add("lyDo", reason));
        }
    }

    private void login(Conn s, JsonObject p) {
        Message res = authService.handleLogin(p, s, s.session);
        if ("LOGIN_SUCCESS".equals(res.type())) {
            Account acc = s.session.getAccount();
            Player old = me(s);
            if (old != null && old.room != null) old.room.leave(old);
            Player me = new Player(acc.getAccountId(), acc.getUsername(), s, acc);
            s.p = me;
            JsonObjectBuilder payload = Json.createObjectBuilder()
                    .add("sessionId", s.session.getSessionId())
                    .add("accountId", acc.getAccountId())
                    .add("ten", acc.getUsername())
                    .add("username", acc.getUsername())
                    .add("tongDiem", acc.getTongDiem())
                    .add("tongTranThang", acc.getTongTranThang())
                    .add("ngayTao", acc.getNgayTao() != null ? acc.getNgayTao() : "");
            payload.add("resumeToken", issueToken(s, acc.getAccountId(), null));
            quiz.util.ActivityLog.add(acc.getUsername() + " đăng nhập");
            send(s, "LOGIN_OK", payload);
        } else {
            error(s, res.payload().getString("lyDo", "Đăng nhập thất bại"));
        }
    }

    private void googleLogin(Conn s, JsonObject p) {
        Message res = authService.handleGoogleLogin(p, s, s.session);
        if ("LOGIN_SUCCESS".equals(res.type())) {
            Account acc = s.session.getAccount();
            Player old = me(s);
            if (old != null && old.room != null) old.room.leave(old);
            Player me = new Player(acc.getAccountId(), acc.getUsername(), s, acc);
            s.p = me;
            quiz.util.ActivityLog.add(acc.getUsername() + " đăng nhập (Google)");
            JsonObjectBuilder payload = Json.createObjectBuilder(res.payload())
                    .add("resumeToken", issueToken(s, acc.getAccountId(), res.payload().getString("email", "")));
            send(s, "LOGIN_OK", payload);
        } else {
            error(s, res.payload().getString("lyDo", "Đăng nhập Google thất bại"));
        }
    }

    /** Chủ phòng đổi cài đặt phòng chờ. Bộ câu hỏi mới chỉ được chọn trong các bộ người này nhìn thấy. */
    private void updateRoom(Conn s, Player me, Room r, JsonObject p) {
        QSet newSet = null;
        if (p.containsKey("setId")) {
            int setId = p.getInt("setId", -1);
            newSet = SETS.stream().filter(x -> x.setId() == setId && (x.owner() == null || x.owner().equals(ownerKey(s)))).findFirst().orElse(null);
            if (newSet == null) { error(s, "Bộ câu hỏi không tồn tại"); return; }
        }
        String err = r.configure(me, p, newSet);
        if (err != null) error(s, err);
    }

    private void changePassword(Conn s, JsonObject p) {
        Message res = authService.handleChangePassword(p, s.session);
        send(s, res.type(), res.payload());
    }

    private void getHistory(Conn s) {
        Message res = authService.handleGetHistory(s.session);
        send(s, res.type(), res.payload());
    }

    private void getProfile(Conn s) {
        Message res = authService.handleGetProfile(s.session);
        send(s, res.type(), res.payload());
    }

    /** Bảng xếp hạng toàn hệ thống: theo số trận chơi và theo chuỗi thắng liên tiếp, kèm vị trí của người đang xem. */
    private void getRanking(Conn s, Player me) {
        List<quiz.db.RankingDAO.Stat> all = new quiz.db.RankingDAO().loadAll();
        List<quiz.db.RankingDAO.Row> byPlayed = quiz.db.RankingDAO.sortByPlayed(all, Integer.MAX_VALUE);
        List<quiz.db.RankingDAO.Row> byStreak = quiz.db.RankingDAO.sortByStreak(all, Integer.MAX_VALUE);
        quiz.db.RankingDAO.Stat mine = all.stream().filter(x -> x.accountId() == me.id).findFirst()
                .orElse(new quiz.db.RankingDAO.Stat(me.id, me.ten, 0, 0, 0, 0));
        JsonObjectBuilder m = Json.createObjectBuilder()
                .add("soTran", mine.played()).add("soThang", mine.wins())
                .add("chuoiHienTai", mine.curStreak()).add("chuoiDaiNhat", mine.bestStreak())
                .add("hangTran", quiz.db.RankingDAO.rankOf(byPlayed, me.id))
                .add("hangChuoi", quiz.db.RankingDAO.rankOf(byStreak, me.id));
        send(s, "RANKING_DATA", Json.createObjectBuilder()
                .add("byPlayed", rankRows(byPlayed.subList(0, Math.min(50, byPlayed.size()))))
                .add("byStreak", rankRows(byStreak.subList(0, Math.min(50, byStreak.size()))))
                .add("me", m));
    }

    private static JsonArrayBuilder rankRows(List<quiz.db.RankingDAO.Row> rows) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (quiz.db.RankingDAO.Row r : rows) {
            quiz.db.RankingDAO.Stat x = r.stat();
            a.add(Json.createObjectBuilder().add("hang", r.hang()).add("accountId", x.accountId()).add("ten", x.username())
                    .add("soTran", x.played()).add("soThang", x.wins())
                    .add("chuoiHienTai", x.curStreak()).add("chuoiDaiNhat", x.bestStreak()));
        }
        return a;
    }

    private void logout(Conn s) {
        revoke(s);   // đăng xuất chủ động: mã khôi phục hết hiệu lực ngay
        Player p = me(s);
        if (p != null && p.room != null) p.room.leave(p);
        if (p != null) quiz.util.ActivityLog.add(p.ten + " đăng xuất");
        s.p = null;
        if (s.session != null) {
            quiz.net.SessionManager.removeSession(s.session.getSessionId());
            s.session = quiz.net.SessionManager.createSession(s);
        }
        send(s, "LOGOUT_OK", Json.createObjectBuilder().add("thongBao", "Đã đăng xuất"));
    }

    // ---- Khôi phục phiên: tải lại trang / mất mạng thì vào lại bằng mã, không phải đăng nhập lại ----
    private static final long RESUME_TTL_MS = 10 * 60_000L;   // giữ mã 10 phút kể từ lúc kết nối cuối cùng bị mất
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
    private static final Map<String, ResumeInfo> RESUME = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class ResumeInfo {
        final int accountId;
        final String connId;           // giữ nguyên Conn.id để vẫn thấy các bộ câu hỏi riêng đã tạo
        final String email;            // khác null nếu đăng nhập bằng Google
        volatile Conn conn;            // kết nối đang giữ mã này
        volatile long expireAt = Long.MAX_VALUE;   // chỉ đếm ngược khi không còn kết nối nào
        volatile boolean leftRoom;     // lần mất kết nối gần nhất có khiến người chơi rời phòng không

        ResumeInfo(int accountId, String connId, String email, Conn conn) {
            this.accountId = accountId;
            this.connId = connId;
            this.email = email;
            this.conn = conn;
        }
    }

    /** Cấp mã khôi phục (ngẫu nhiên 256 bit) cho kết nối vừa đăng nhập; mã cũ của kết nối này (nếu có) bị thu hồi. */
    private static String issueToken(Conn s, int accountId, String email) {
        revoke(s);
        purgeExpired();
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        RESUME.put(t, new ResumeInfo(accountId, s.id, email, s));
        s.resumeToken = t;
        return t;
    }

    /** Thu hồi mã khôi phục của kết nối (đăng xuất, bị quản trị viên ngắt...). */
    static void revoke(Conn s) {
        String t = s.resumeToken;
        if (t != null) {
            RESUME.remove(t);
            s.resumeToken = null;
        }
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        RESUME.values().removeIf(r -> r.expireAt < now);
    }

    private void resume(Conn s, JsonObject p) {
        if (s.p != null) return;   // kết nối này đã đăng nhập rồi
        purgeExpired();
        ResumeInfo r = RESUME.get(p.getString("token", ""));
        if (r == null) {
            send(s, "RESUME_FAIL", Json.createObjectBuilder().add("lyDo", "Phiên đăng nhập đã hết hạn, hãy đăng nhập lại"));
            return;
        }
        String token = p.getString("token", "");
        Message res;
        boolean left;
        synchronized (r) {
            Conn old = r.conn;
            left = r.leftRoom;
            r.conn = s;                          // đặt trước để onClose của kết nối cũ không đặt lại hạn dùng
            r.expireAt = Long.MAX_VALUE;
            r.leftRoom = false;
            if (old != null && old != s) {       // kết nối cũ có thể chưa bị phát hiện đã chết (rớt mạng) hoặc là tab nhân bản
                Player op = old.p;
                if (op != null && op.room != null) { left = true; op.room.leave(op); }
                old.sendLine("{\"type\":\"KICKED\",\"payload\":{\"thongBao\":\"Tài khoản đã được mở ở tab hoặc thiết bị khác\"}}");
                old.close();
            }
            res = authService.handleResume(r.accountId, s.session);
        }
        if (!"LOGIN_SUCCESS".equals(res.type())) {
            RESUME.remove(token);
            send(s, "RESUME_FAIL", Json.createObjectBuilder().add("lyDo", res.payload().getString("lyDo", "Không khôi phục được phiên")));
            return;
        }
        Account acc = s.session.getAccount();
        s.id = r.connId;
        s.p = new Player(acc.getAccountId(), acc.getUsername(), s, acc);
        s.resumeToken = token;
        JsonObjectBuilder b = Json.createObjectBuilder(res.payload())
                .add("resumeToken", token).add("daKhoiPhuc", true).add("daRoiPhong", left);
        if (r.email != null) b.add("isGoogle", true).add("email", r.email);
        quiz.util.ActivityLog.add(acc.getUsername() + " khôi phục phiên");
        send(s, "LOGIN_OK", b);
    }

    private void error(Conn s, String msg) {
        send(s, "ERROR", Json.createObjectBuilder().add("thongBao", msg));
    }

    // ---- Dựng câu hỏi từ nội dung người chơi soạn (kiểm tra lại ở Server, không tin Client) ----
    private record Opt(String text, int rank) {}   // rank: 1/0 = đúng/sai (Buttons, Checkboxes); vị trí đúng (Reorder)

    private static IllegalArgumentException bad(String m) { return new IllegalArgumentException(m); }

    private static String clean(String s, int max) {
        s = s.trim().replaceAll("\\s+", " ");
        if (s.length() > max) throw bad("Nội dung quá dài (tối đa " + max + " ký tự)");
        return s;
    }

    private static List<String> items(JsonObject o, String key, int maxItems) {
        List<String> r = new ArrayList<>();
        if (o.get(key) instanceof JsonArray a)
            for (JsonValue v : a)
                if (v instanceof JsonString js && !clean(js.getString(), 100).isEmpty()) r.add(clean(js.getString(), 100));
        if (r.size() > maxItems) throw bad("Tối đa " + maxItems + " mục");
        return r;
    }

    private static void noDup(List<String> l) {
        Set<String> seen = new HashSet<>();
        for (String t : l) if (!seen.add(norm(t))) throw bad("Có mục bị trùng: " + t);
    }

    private static double num(JsonObject o, String key) {
        if (o.get(key) instanceof JsonNumber n) return n.doubleValue();
        throw bad("Thiếu giá trị số: " + key);
    }

    private static String ids(int i) { return String.valueOf((char) ('a' + i)); }

    private static boolean inOrder(List<Opt> l) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).rank() != i) return false;
        return true;
    }

    private static String media(JsonObject o, String key, Set<String> exts) {
        String u = o.getString(key, "");
        if (u.isEmpty()) return null;
        if (!MediaStore.valid(u, exts)) throw bad("File ảnh/âm thanh không hợp lệ hoặc không tồn tại");
        return u;
    }

    private static Q build(JsonObject o, int id) {
        String type = o.getString("loaiCauHoi", ""), text = clean(o.getString("noiDung", ""), 200);
        if (text.isEmpty()) throw bad("Chưa nhập nội dung câu hỏi");
        double tg = num(o, "thoiGian");
        if (tg < 5 || tg > 90 || tg != Math.rint(tg)) throw bad("Thời gian phải là số nguyên từ 5 đến 90 giây");
        String image = media(o, "anh", MediaStore.IMG), audio = media(o, "amThanh", MediaStore.AUD);
        List<Opt> opts = new ArrayList<>();
        JsonObjectBuilder pub = Json.createObjectBuilder();
        JsonValue key;
        double tol = 0;
        switch (type) {
            case "BUTTONS", "CHECKBOXES" -> {
                List<String> right = items(o, "dung", 5), wrong = items(o, "sai", 5);
                if (right.isEmpty() || ("BUTTONS".equals(type) && right.size() > 1)) throw bad("Đáp án đúng không hợp lệ");
                if (wrong.isEmpty()) throw bad("Cần ít nhất 1 đáp án sai");
                List<String> all = new ArrayList<>(right);
                all.addAll(wrong);
                noDup(all);
                if (all.size() > 6) throw bad("Tối đa 6 phương án");
                right.forEach(t -> opts.add(new Opt(t, 1)));
                wrong.forEach(t -> opts.add(new Opt(t, 0)));
                Collections.shuffle(opts);   // id gán SAU khi trộn nên không lộ đáp án
                JsonArrayBuilder k = Json.createArrayBuilder();
                String one = "";
                for (int i = 0; i < opts.size(); i++) if (opts.get(i).rank() == 1) { k.add(ids(i)); one = ids(i); }
                key = "BUTTONS".equals(type) ? Json.createValue(one) : k.build();
            }
            case "REORDER" -> {
                List<String> order = items(o, "thuTu", 6);
                if (order.size() < 2) throw bad("Cần ít nhất 2 ô");
                noDup(order);
                for (int i = 0; i < order.size(); i++) opts.add(new Opt(order.get(i), i));
                for (int t = 0; t < 20 && inOrder(opts); t++) Collections.shuffle(opts);
                if (inOrder(opts)) Collections.swap(opts, 0, 1);
                String[] byRank = new String[opts.size()];
                for (int i = 0; i < opts.size(); i++) byRank[opts.get(i).rank()] = ids(i);
                key = strs(byRank);
            }
            case "TYPE_ANSWER" -> {
                List<String> ok = items(o, "chapNhan", 5);
                if (ok.isEmpty()) throw bad("Cần ít nhất 1 đáp án được chấp nhận");
                key = strs(ok.toArray(new String[0]));
            }
            case "RANGE" -> {
                double mn = num(o, "min"), mx = num(o, "max"), st = num(o, "step"), v = num(o, "dapAn");
                tol = num(o, "saiSo");
                if (mn >= mx || st <= 0 || tol < 0) throw bad("Cần nhỏ nhất < lớn nhất, bước > 0, sai số ≥ 0");
                if (v < mn || v > mx) throw bad("Đáp án phải nằm trong khoảng nhỏ nhất – lớn nhất");
                double k = (v - mn) / st;
                if (Math.abs(k - Math.rint(k)) > 1e-9 && tol < st / 2) throw bad("Đáp án không nằm trên bước nhảy");
                pub.add("min", mn).add("max", mx).add("step", st);
                key = Json.createValue(v);
            }
            default -> throw bad("Dạng câu hỏi không hợp lệ");
        }
        if (!opts.isEmpty()) {
            JsonArrayBuilder a = Json.createArrayBuilder();
            for (int i = 0; i < opts.size(); i++)
                a.add(Json.createObjectBuilder().add("id", ids(i)).add("text", opts.get(i).text()));
            pub.add("luaChon", a);
        }
        return new Q(id, type, text, pub.build(), key, tol, (int) tg, image, audio);
    }

    private void sendSets(Conn s) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (QSet x : SETS) {
            if (x.owner() != null && !x.owner().equals(ownerKey(s))) continue;   // bộ riêng chỉ chủ sở hữu thấy
            a.add(Json.createObjectBuilder().add("setId", x.setId()).add("ten", x.ten())
                    .add("soCau", x.qs().size()).add("laCuaToi", x.owner() != null));
        }
        send(s, "SET_LIST", Json.createObjectBuilder().add("sets", a));
    }

    private void createSet(Conn s, JsonObject p) {
        String ten = p.getString("ten", "").trim();
        JsonValue lv = p.get("cauHoi");
        JsonArray list = lv instanceof JsonArray arr ? arr : JsonValue.EMPTY_JSON_ARRAY;
        if (ten.isEmpty() || ten.length() > 60) { error(s, "Tên bộ câu hỏi không hợp lệ (1-60 ký tự)"); return; }
        if (list.size() > 50) { error(s, "Tối đa 50 câu mỗi bộ"); return; }
        List<Q> qs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                if (!(list.get(i) instanceof JsonObject o)) throw bad("Dữ liệu không hợp lệ");
                qs.add(build(o, i + 1));
            } catch (IllegalArgumentException e) {
                error(s, "Câu " + (i + 1) + ": " + e.getMessage());
                return;
            }
        }
        if (qs.isEmpty()) { error(s, "Bộ câu hỏi cần ít nhất 1 câu"); return; }
        String owner = ownerKey(s);
        if (owner == null) { error(s, "Bạn cần đăng nhập để tạo bộ câu hỏi"); return; }
        JsonArrayBuilder saved = Json.createArrayBuilder();
        for (Q q : qs) saved.add(qToJson(q));
        int dbId = new quiz.db.QuestionSetDAO().insert(s.p.id, ten, saved.build().toString());
        if (dbId <= 0) { error(s, "Không lưu được bộ câu hỏi vào cơ sở dữ liệu, hãy thử lại"); return; }
        SETS.add(new QSet(dbId + 1, ten, owner, qs));
        send(s, "SET_CREATED", Json.createObjectBuilder().add("ten", ten));
    }

    private void createRoom(Conn s, Player me, JsonObject p) {
        if (me.room != null) return;
        int setId = p.getInt("setId", -1);
        QSet set = SETS.stream()
                .filter(x -> x.setId() == setId && (x.owner() == null || x.owner().equals(ownerKey(s))))
                .findFirst().orElse(null);
        if (set == null) { error(s, "Bộ câu hỏi không tồn tại"); return; }
        String ten = p.getString("ten", "").trim();
        if (ten.isEmpty()) ten = "Phòng của " + me.ten;
        int soCau = Math.max(1, Math.min(p.getInt("soCau", set.qs().size()), set.qs().size()));
        new Room(me, ten, set, soCau, p.getBoolean("an", false)).update();
    }

    static boolean check(Q q, JsonValue a) {
        JsonValue.ValueType t = a.getValueType();
        switch (q.type()) {
            case "BUTTONS":
                return t == JsonValue.ValueType.STRING
                        && ((JsonString) a).getString().equals(((JsonString) q.key()).getString());
            case "CHECKBOXES": {
                if (t != JsonValue.ValueType.ARRAY) return false;
                List<String> x = strList(a), k = strList(q.key());
                return x.size() == k.size() && new HashSet<>(x).equals(new HashSet<>(k));
            }
            case "REORDER":
                return t == JsonValue.ValueType.ARRAY && strList(a).equals(strList(q.key()));
            case "TYPE_ANSWER": {
                if (t != JsonValue.ValueType.STRING) return false;
                String s = norm(((JsonString) a).getString());
                for (String k : strList(q.key())) if (norm(k).equals(s)) return true;
                return false;
            }
            case "RANGE":
                return t == JsonValue.ValueType.NUMBER
                        && Math.abs(((JsonNumber) a).doubleValue() - ((JsonNumber) q.key()).doubleValue()) <= q.tol();
            default:
                return false;
        }
    }

    private static List<String> strList(JsonValue v) {
        return ((JsonArray) v).getValuesAs(JsonString.class).stream().map(JsonString::getString).toList();
    }

    private static String norm(String s) {
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private void send(Conn s, String type, JsonObjectBuilder payload) {
        JsonObject m = Json.createObjectBuilder()
            .add("type", type)
            .add("payload", payload)
            .build();

        s.sendLine(m.toString());
    }

    private void send(Conn s, String type, JsonObject payload) {
        JsonObject m = Json.createObjectBuilder()
            .add("type", type)
            .add("payload", payload)
            .build();

        s.sendLine(m.toString());
    }
}