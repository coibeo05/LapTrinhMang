package quiz;

import jakarta.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import quiz.Game.Q;
import quiz.Game.QSet;
import quiz.model.Account;
import quiz.db.AccountDAO;
import quiz.db.MatchResultDAO;
import quiz.db.ChatMessageDAO;
import quiz.model.ChatMessage;
import quiz.util.ActivityLog;
import java.time.LocalDateTime;

/** Người chơi đang online. Tích hợp Account từ CSDL (Module 1). */
class Player {
    final int id;
    final String ten;
    final Conn ws;
    Account account;
    Room room;
    int score;

    Player(int id, String ten, Conn ws) { this(id, ten, ws, null); }
    Player(int id, String ten, Conn ws, Account account) {
        this.id = id;
        this.ten = ten;
        this.ws = ws;
        this.account = account;
    }

    void send(String type, JsonObjectBuilder b) {
        send(Json.createObjectBuilder().add("type", type).add("payload", b).build().toString());
    }

    void send(String json) {
        if (ws != null) ws.sendLine(json);   // bot không có kết nối
    }
}

/** Phòng + điều phối trận (Module 3): timer, chấm điểm, xếp hạng, thoát trận đều do Server quyết định. */
class Room {
    static final Map<String, Room> ALL = new ConcurrentHashMap<>();
    static final int MAX = 8;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // bỏ 0/O, 1/I cho dễ đọc
    private static final java.security.SecureRandom RND = new java.security.SecureRandom();

    /** Mã phòng 5 ký tự ngẫu nhiên, không trùng phòng đang mở. */
    private static synchronized String newCode() {
        String c;
        do {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 5; i++) b.append(CODE_CHARS.charAt(RND.nextInt(CODE_CHARS.length())));
            c = b.toString();
        } while (ALL.containsKey(c));
        return c;
    }
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "room-timer"); t.setDaemon(true); return t;
    });

    final String id = newCode();
    volatile String ten;
    volatile boolean hidden;   // true: không hiện trong danh sách, chỉ vào được bằng mã
    volatile QSet set;
    volatile int total;
    volatile int maxPlayers = MAX;   // số người tối đa của phòng (chủ phòng chỉnh được, 2..MAX)
    volatile int giay;               // 0: dùng thời gian riêng của từng câu; >0: mọi câu dùng số giây này
    final Player host;
    final List<Player> ps = new ArrayList<>();
    private boolean playing, open;
    private int idx = -1, round;
    private String prevType;
    private long startNs;
    private final Map<Player, JsonValue> ans = new HashMap<>();
    private final Map<Player, Double> at = new HashMap<>();
    private final Set<Player> ready = new HashSet<>();
    private final Set<Player> waitReady = new HashSet<>();   // người chơi đã bấm "Sẵn sàng" ở phòng chờ
    private final List<ChatMessage> chatLog = new ArrayList<>();   // chat của trận, lưu DB khi kết thúc
    private final Map<Player, Integer> okCount = new HashMap<>();   // số câu đúng
    private final Map<Player, Integer> answered = new HashMap<>();  // số câu đã trả lời
    private final Map<Player, Double> timeSum = new HashMap<>();    // tổng thời gian trả lời (giây)
    private ScheduledFuture<?> tm;

    Room(Player host, String ten, QSet set, int soCau, boolean hidden) {
        this.hidden = hidden;
        this.host = host; this.ten = ten; this.set = set; this.total = soCau;
        ps.add(host); host.room = this; host.score = 0;
        ALL.put(id, this);
        ActivityLog.add(host.ten + " tạo phòng " + id + " (" + ten + ")");
    }

    static JsonArrayBuilder list() {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (Room r : ALL.values())
            if (!r.hidden) a.add(Json.createObjectBuilder().add("roomId", r.id).add("ten", r.ten)
                    .add("tenBo", r.set.ten()).add("soNguoi", r.count()).add("toiDa", r.maxPlayers).add("soCau", r.total)
                    .add("giay", r.secsAvg())
                    .add("trangThai", r.isPlaying() ? "CHOI" : "CHO").add("chuPhong", r.host.ten));
        return a;
    }

    // ---------- Phòng chờ ----------
    synchronized void update() {
        JsonArrayBuilder a = Json.createArrayBuilder();
        int readyN = 0;
        for (Player p : ps) {
            boolean sr = p == host || p.ws == null || waitReady.contains(p);   // chủ phòng và bot luôn sẵn sàng
            if (sr) readyN++;
            a.add(Json.createObjectBuilder().add("accountId", p.id).add("ten", p.ten).add("sanSang", sr));
        }
        all("ROOM_UPDATE", Json.createObjectBuilder().add("roomId", id).add("ten", ten).add("tenBo", set.ten())
                .add("setId", set.setId()).add("tongCauBo", set.qs().size())
                .add("soCau", total).add("giay", giay).add("chuPhong", host.id).add("toiDa", maxPlayers).add("an", hidden)
                .add("soSanSang", readyN).add("nguoiChoi", a));
    }

    synchronized void join(Player p) {
        if (playing) { p.send("ERROR", Json.createObjectBuilder().add("thongBao", "Phòng đã bắt đầu thi đấu")); return; }
        if (ps.size() >= maxPlayers) { p.send("ERROR", Json.createObjectBuilder().add("thongBao", "Phòng đã đầy")); return; }
        ps.add(p); p.room = this; p.score = 0;
        ActivityLog.add(p.ten + " vào phòng " + id);
        update();   // TODO: chủ phòng đồng ý/từ chối (hiện cho vào thẳng)
    }

    synchronized void chat(Player p, String t) {
        t = t.trim();
        if (t.isEmpty() || t.length() > 200) return;
        if (p.id > 0) chatLog.add(new ChatMessage(p.id, t, LocalDateTime.now()));   // bot không chat
        LocalDateTime now = LocalDateTime.now();
        all("CHAT", Json.createObjectBuilder().add("accountId", p.id).add("ten", p.ten).add("noiDung", t)
                .add("gio", String.format("%02d:%02d", now.getHour(), now.getMinute())));
    }

    synchronized void start(Player p) {
        if (p != host || playing) return;
        if (ps.size() < 2) { p.send("ERROR", Json.createObjectBuilder().add("thongBao", "Cần ít nhất 2 người chơi")); return; }
        playing = true;
        ps.forEach(x -> x.score = 0);
        okCount.clear(); answered.clear(); timeSum.clear();
        ActivityLog.add("Phòng " + id + " bắt đầu trận (" + ps.size() + " người)");
        next();
    }

    // ---------- Vòng chơi ----------
    private void next() {
        if (idx + 1 >= total) { finish(); return; }
        Q q = qs().get(++idx);
        round++; open = true; ans.clear(); at.clear(); ready.clear();
        boolean intro = !q.type().equals(prevType);
        prevType = q.type();
        int delay = intro ? 3 : 0;   // timer câu hỏi chỉ chạy sau màn giới thiệu 3s
        startNs = System.nanoTime() + delay * 1_000_000_000L;
        JsonObjectBuilder b = Json.createObjectBuilder().add("questionId", q.id()).add("loaiCauHoi", q.type())
                .add("noiDung", q.text()).add("duLieuDapAn", q.pub()).add("showIntro", intro).add("thoiGianCauHoi", secs(q))
                .add("cauSo", idx + 1).add("tongCau", total);
        if (q.image() != null) b.add("anh", q.image());
        if (q.audio() != null) b.add("amThanh", q.audio());
        all("QUESTION_DATA", b);
        for (Player bt : ps) if (bt.ws == null) {   // bot trả lời sau vài giây, đúng khoảng 60%
            int w = (int) (delay + 1 + Math.random() * Math.max(1, Math.min(secs(q) - 2, 8)));
            int rr = round;
            TIMER.schedule(() -> { synchronized (this) {
                if (open && round == rr && ps.contains(bt) && !ans.containsKey(bt))
                    store(bt, Math.random() < 0.6 ? botAnswer(q) : JsonValue.NULL);
            } }, w, TimeUnit.SECONDS);
        }
        int r = round;
        tm = TIMER.schedule(() -> { synchronized (this) { if (open && round == r) close(); } },
                delay + secs(q) + 2, TimeUnit.SECONDS);
    }

    synchronized void submit(Player p, JsonObject d) {
        if (!open || !ps.contains(p) || ans.containsKey(p) || d.getInt("questionId", -1) != qs().get(idx).id()) return;
        store(p, d.getOrDefault("dapAnChon", JsonValue.NULL));
    }

    private void store(Player p, JsonValue a) {
        at.put(p, Math.max(0, (System.nanoTime() - startNs) / 1e9));   // thời điểm do Server ghi
        ans.put(p, a);
        if (ans.keySet().containsAll(ps)) close();   // mọi người đã gửi -> kết thúc sớm
    }

    // ---------- Bot để thử một mình ----------
    private static final AtomicInteger BOTS = new AtomicInteger(0);

    synchronized void addBot(Player p) {
        if (p != host || playing) return;
        if (ps.size() >= maxPlayers) { p.send("ERROR", Json.createObjectBuilder().add("thongBao", "Phòng đã đầy")); return; }
        int n = BOTS.incrementAndGet();
        ps.add(new Player(-n, "Bot " + n, null));
        update();
    }

    private static JsonValue botAnswer(Q q) {   // đáp án đúng, dùng cho bot trả lời đúng
        JsonValue k = q.key();
        if (q.type().equals("TYPE_ANSWER") && k instanceof JsonArray a) return a.get(0);
        return k;
    }

    private void close() {
        open = false;
        if (tm != null) tm.cancel(false);
        Q q = qs().get(idx);
        JsonArrayBuilder rs = Json.createArrayBuilder();
        for (Player p : ps) {
            boolean ok;
            try { ok = ans.containsKey(p) && at.get(p) <= secs(q) + 1 && Game.check(q, ans.get(p)); }
            catch (RuntimeException e) { ok = false; }
            int pts = ok ? Math.max(400, 1000 - 100 * (int) (at.get(p) / (2.0 * secs(q) / 15))) : 0;
            p.score += pts;
            if (ok) okCount.merge(p, 1, Integer::sum);
            if (ans.containsKey(p) && at.containsKey(p)) {
                timeSum.merge(p, Math.min(at.get(p), (double) secs(q)), Double::sum);
                answered.merge(p, 1, Integer::sum);
            }
            rs.add(Json.createObjectBuilder().add("accountId", p.id).add("dungSai", ok).add("diemVuaCong", pts));
        }
        all("ANSWER_RESULT", Json.createObjectBuilder().add("questionId", q.id()).add("dapAnDung", q.key()).add("danhSachKetQua", rs));
        all("RANKING_UPDATE", Json.createObjectBuilder().add("danhSachXepHang", ranking()));
        int r = round;   // phòng hờ có Client không gửi REQUEST_QUESTION
        tm = TIMER.schedule(() -> { synchronized (this) { if (!open && round == r && ALL.containsKey(id)) next(); } }, 9, TimeUnit.SECONDS);
    }

    synchronized void request(Player p) {
        if (!playing || open || !ps.contains(p)) return;
        ready.add(p);
        if (ready.containsAll(ps)) next();
    }

    private void finish() {
        ActivityLog.add("Phòng " + id + " kết thúc trận");
        all("MATCH_END", Json.createObjectBuilder().add("lastQues", 1).add("ketQuaCuoi", ranking()));
        // Cập nhật điểm tích lũy và lưu lịch sử trận đấu (Module 1 + 4)
        List<Player> sorted = new ArrayList<>(ps);
        sorted.sort((a, b) -> b.score - a.score);
        AccountDAO dao = new AccountDAO();
        MatchResultDAO mDao = new MatchResultDAO();
        int matchId = mDao.createMatch(set != null ? set.ten() : ten);
        for (int i = 0; i < sorted.size(); i++) {
            Player p = sorted.get(i);
            if (p.id > 0 && p.ws != null) {
                boolean isWinner = (i == 0 && p.score > 0);
                dao.updateStats(p.id, p.score, isWinner);
                if (matchId > 0) {
                    mDao.addDetail(matchId, p.id, p.score, i + 1);
                }
            }
        }
        if (matchId > 0) new ChatMessageDAO().insertAll(matchId, new ArrayList<>(chatLog));
        drop();
    }

    // ---------- Cài đặt phòng chờ (chỉ chủ phòng) ----------
    private int secs(Q q) { return giay > 0 ? giay : q.time(); }

    int secsAvg() { return giay > 0 ? giay : (int) Math.round(set.qs().stream().mapToInt(Q::time).average().orElse(15)); }

    /**
     * Chủ phòng chỉnh cài đặt khi phòng đang chờ. d chứa các khóa muốn đổi (ten, soCau, giay, toiDa, an);
     * newSet là bộ câu hỏi mới đã được kiểm tra quyền xem (null nếu không đổi bộ).
     * @return thông báo lỗi, hoặc null nếu thành công
     */
    synchronized String configure(Player by, JsonObject d, QSet newSet) {
        if (by != host) return "Chỉ chủ phòng được chỉnh cài đặt";
        if (playing) return "Phòng đã bắt đầu thi đấu";
        if (d.containsKey("ten")) {
            String t = d.getString("ten", "").trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) return "Tên phòng không được để trống";
            ten = t.length() > 40 ? t.substring(0, 40) : t;
        }
        if (newSet != null) set = newSet;
        int size = set.qs().size();
        total = d.containsKey("soCau") ? Math.max(1, Math.min(d.getInt("soCau", total), size)) : Math.min(total, size);
        if (d.containsKey("giay")) { int g = d.getInt("giay", 0); giay = (g >= 5 && g <= 90) ? g : 0; }
        if (d.containsKey("toiDa")) maxPlayers = Math.max(Math.max(2, ps.size()), Math.min(MAX, d.getInt("toiDa", maxPlayers)));
        if (d.containsKey("an")) hidden = d.getBoolean("an", hidden);
        ActivityLog.add(by.ten + " chỉnh cài đặt phòng " + id);
        update();
        return null;
    }

    synchronized void setReady(Player p, boolean r) {
        if (playing || !ps.contains(p) || p == host) return;
        if (r) waitReady.add(p); else waitReady.remove(p);
        update();
    }

    /** Chủ phòng mời một người (hoặc bot) ra khỏi phòng chờ. */
    synchronized void kickBy(Player by, int targetId) {
        if (by != host || playing || targetId == host.id) return;
        Player t = null;
        for (Player x : ps) if (x.id == targetId) t = x;
        if (t == null) return;
        ActivityLog.add(by.ten + " mời " + t.ten + " ra khỏi phòng " + id);
        if (t.ws != null) t.send("ROOM_CLOSED", Json.createObjectBuilder().add("lyDo", "Chủ phòng đã mời bạn ra khỏi phòng"));
        leave(t);
    }

    // ---------- Dành cho trang quản trị ----------
    synchronized int count() { return ps.size(); }

    synchronized boolean isPlaying() { return playing; }

    /** Quản trị viên đóng phòng: mọi người về sảnh. */
    synchronized void adminClose(String reason) {
        ActivityLog.add("Quản trị viên đóng phòng " + id);
        all("ROOM_CLOSED", Json.createObjectBuilder().add("lyDo", reason));
        drop();
    }

    /** Quản trị viên đuổi một người khỏi phòng (vẫn online). */
    synchronized void adminKick(Player p, String reason) {
        if (!ps.contains(p)) return;
        ActivityLog.add("Quản trị viên mời " + p.ten + " ra khỏi phòng " + id);
        p.send("ROOM_CLOSED", Json.createObjectBuilder().add("lyDo", reason));
        leave(p);
    }

    synchronized void leave(Player p) {
        if (!ps.remove(p)) return;
        p.room = null; p.score = 0;
        waitReady.remove(p);
        ActivityLog.add(p.ten + " rời phòng " + id);
        if (ps.stream().allMatch(x -> x.ws == null)) { drop(); return; }   // hết người thật thì đóng phòng
        if (!playing) {
            if (p == host) { all("ROOM_CLOSED", Json.createObjectBuilder().add("lyDo", "Chủ phòng đã rời, phòng bị giải tán")); drop(); }
            else update();
            return;
        }
        if (ps.size() < 2) { all("MATCH_CANCELLED", Json.createObjectBuilder().add("lyDo", "Không đủ người chơi, trận bị hủy")); drop(); return; }
        all("PLAYER_LEFT_NOTICE", Json.createObjectBuilder().add("accountId", p.id).add("soNguoiConLai", ps.size()));
        if (open && ans.keySet().containsAll(ps)) close();
        else if (!open && ready.containsAll(ps)) next();
    }

    // ---------- Tiện ích ----------
    private List<Q> qs() { return set.qs(); }

    private void drop() {
        if (tm != null) tm.cancel(false);
        ALL.remove(id);
        ps.forEach(x -> { x.room = null; x.score = 0; });
        ps.clear();
    }

    private JsonArrayBuilder ranking() {
        List<Player> l = new ArrayList<>(ps);
        l.sort((a, b) -> b.score - a.score);
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (int i = 0; i < l.size(); i++) {
            Player pl = l.get(i);
            int n = answered.getOrDefault(pl, 0);
            a.add(Json.createObjectBuilder().add("accountId", pl.id).add("ten", pl.ten)
                    .add("diemHienTai", pl.score).add("thuHang", i + 1)
                    .add("soDung", okCount.getOrDefault(pl, 0)).add("tongCau", total)
                    .add("tbGiay", n == 0 ? 0.0 : Math.round(timeSum.getOrDefault(pl, 0.0) / n * 10) / 10.0));
        }
        return a;
    }

    private void all(String type, JsonObjectBuilder b) {
        String m = Json.createObjectBuilder().add("type", type).add("payload", b).build().toString();
        for (Player p : new ArrayList<>(ps)) p.send(m);
    }
}