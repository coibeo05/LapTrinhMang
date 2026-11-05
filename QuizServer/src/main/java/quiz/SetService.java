package quiz;

import jakarta.json.*;
import java.io.StringReader;
import java.util.*;
import quiz.Game.Q;
import quiz.Game.QSet;
import quiz.db.QuestionSetDAO;
import quiz.net.SessionManager;
import quiz.util.ActivityLog;

/**
 * Bộ câu hỏi: tạo, sửa, xoá, bật/tắt công khai (người chơi) và duyệt công khai (quản trị viên).
 *
 * Trạng thái (cột QuestionSet.trangThai):
 *   PRIVATE  - riêng tư, chỉ chủ sở hữu thấy
 *   PENDING  - chủ sở hữu yêu cầu công khai, chờ quản trị viên duyệt (vẫn chỉ chủ sở hữu thấy)
 *   APPROVED - đã duyệt, mọi người thấy và tạo phòng được
 *   REJECTED - bị từ chối, kèm lý do; chủ sở hữu sửa rồi gửi lại
 * daTungDuyet: bộ đã từng được duyệt mà chủ sở hữu tạm ẩn thì bật lại công khai ngay, không phải duyệt lại.
 * Sửa nội dung hoặc bị quản trị viên gỡ thì mất dấu này.
 *
 * Mọi thay đổi ghi CSDL trước; ghi lỗi thì không đổi dữ liệu trong bộ nhớ.
 * setId hiển thị = setId trong CSDL + 1 (số 1 dành cho bộ mẫu của hệ thống).
 */
final class SetService {
    static final String PRIVATE = "PRIVATE", PENDING = "PENDING", APPROVED = "APPROVED", REJECTED = "REJECTED";
    private static final Object LOCK = new Object();
    private static final QuestionSetDAO DAO = new QuestionSetDAO();

    private SetService() { }

    private static List<QSet> sets() { return Game.SETS; }

    private static int dbId(QSet x) { return x.setId() - 1; }

    static QSet find(int setId) {
        for (QSet x : sets()) if (x.setId() == setId) return x;
        return null;
    }

    private static int me(Conn s) { return s.p == null ? 0 : s.p.id; }

    static boolean isOwner(QSet x, Conn s) { return !x.system() && me(s) > 0 && x.ownerId() == me(s); }

    /** Bộ công khai hoặc bộ của chính mình. Dùng cho danh sách, tạo phòng, đổi bộ trong phòng chờ. */
    static boolean canUse(QSet x, Conn s) { return x.isPublic() || isOwner(x, s); }

    static QSet usable(int setId, Conn s) {
        ensureLoaded();
        QSet x = find(setId);
        return x != null && canUse(x, s) ? x : null;
    }

    static int pendingCount() {
        ensureLoaded();
        int n = 0;
        for (QSet x : sets()) if (PENDING.equals(x.trangThai())) n++;
        return n;
    }

    private static void replace(QSet old, QSet now) {
        int i = sets().indexOf(old);
        if (i >= 0) sets().set(i, now);
    }

    // ---------- Lưu trữ ----------

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

    private static String toDb(List<Q> qs) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (Q q : qs) a.add(qToJson(q));
        return a.build().toString();
    }

    /** false: lần nạp lúc khởi động lỗi (MySQL chưa bật), sẽ nạp lại ở lần truy cập sau. */
    private static volatile boolean loaded;

    /** Nạp lại nếu lúc khởi động chưa nạp được. Gọi trước mọi thao tác cần danh sách bộ. */
    private static void ensureLoaded() {
        if (!loaded) synchronized (LOCK) { if (!loaded) loadSaved(); }
    }

    /** Nạp các bộ đã lưu (gọi từ khối static của Game, và thử lại nếu lần đó lỗi). Bỏ qua bộ đã có trong bộ nhớ. */
    static void loadSaved() {
        List<QuestionSetDAO.Row> rows = DAO.loadAll();
        if (rows == null) return;
        for (QuestionSetDAO.Row r : rows) {
            if (find(r.setId() + 1) != null) continue;
            try (JsonReader jr = Json.createReader(new StringReader(r.cauHoi()))) {
                JsonArray arr = jr.readArray();
                List<Q> qs = new ArrayList<>();
                for (int i = 0; i < arr.size(); i++) qs.add(qFromJson(arr.getJsonObject(i), i + 1));
                String st = r.trangThai() == null ? PRIVATE : r.trangThai();
                if (!qs.isEmpty())
                    sets().add(new QSet(r.setId() + 1, r.ten(), r.ownerId(), r.tacGia() == null ? "?" : r.tacGia(),
                            st, r.daTungDuyet(), r.lyDoTuChoi(), qs));
            } catch (RuntimeException e) {
                System.err.println("Bỏ qua bộ câu hỏi #" + r.setId() + " bị hỏng: " + e.getMessage());
            }
        }
        sets().sort(Comparator.comparingInt(QSet::setId));
        loaded = true;
    }

    /**
     * Dựng lại dữ liệu dạng trình soạn (loaiCauHoi, noiDung, dung/sai, thuTu, chapNhan, min/max...) từ câu hỏi đã lưu.
     * Dùng khi chủ sở hữu mở lại bộ để sửa và khi quản trị viên xem trước (thấy rõ đáp án đúng/sai).
     */
    static JsonObject qToEditor(Q q) {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("loaiCauHoi", q.type()).add("noiDung", q.text()).add("thoiGian", q.time())
                .add("anh", q.image() == null ? "" : q.image()).add("amThanh", q.audio() == null ? "" : q.audio());
        Map<String, String> text = new LinkedHashMap<>();
        if (q.pub().get("luaChon") instanceof JsonArray a)
            for (JsonObject o : a.getValuesAs(JsonObject.class)) text.put(o.getString("id"), o.getString("text"));
        switch (q.type()) {
            case "BUTTONS", "CHECKBOXES" -> {
                Set<String> right = new HashSet<>();
                if (q.key() instanceof JsonString js) right.add(js.getString());
                else for (JsonString js : ((JsonArray) q.key()).getValuesAs(JsonString.class)) right.add(js.getString());
                JsonArrayBuilder d = Json.createArrayBuilder(), w = Json.createArrayBuilder();
                text.forEach((id, t) -> { if (right.contains(id)) d.add(t); else w.add(t); });
                b.add("dung", d).add("sai", w);
            }
            case "REORDER" -> {
                JsonArrayBuilder o = Json.createArrayBuilder();
                for (JsonString js : ((JsonArray) q.key()).getValuesAs(JsonString.class)) o.add(text.getOrDefault(js.getString(), ""));
                b.add("thuTu", o);
            }
            case "TYPE_ANSWER" -> b.add("chapNhan", q.key());
            case "RANGE" -> b.add("min", q.pub().get("min")).add("max", q.pub().get("max")).add("step", q.pub().get("step"))
                    .add("dapAn", q.key()).add("saiSo", q.tol());
            default -> { }
        }
        return b.build();
    }

    private static JsonArrayBuilder editorList(QSet x) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (Q q : x.qs()) a.add(qToEditor(q));
        return a;
    }

    /** Kiểm tra tên và danh sách câu hỏi người chơi gửi lên; lỗi thì báo cho người gửi và trả null. */
    private static List<Q> parse(Conn s, String ten, JsonObject p) {
        JsonArray list = p.get("cauHoi") instanceof JsonArray arr ? arr : JsonValue.EMPTY_JSON_ARRAY;
        if (ten.isEmpty() || ten.length() > 60) { error(s, "Tên bộ câu hỏi không hợp lệ (1-60 ký tự)"); return null; }
        if (list.size() > 50) { error(s, "Tối đa 50 câu mỗi bộ"); return null; }
        List<Q> qs = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                if (!(list.get(i) instanceof JsonObject o)) throw new IllegalArgumentException("Dữ liệu không hợp lệ");
                qs.add(Game.build(o, i + 1));
            } catch (IllegalArgumentException e) {
                error(s, "Câu " + (i + 1) + ": " + e.getMessage());
                return null;
            }
        }
        if (qs.isEmpty()) { error(s, "Bộ câu hỏi cần ít nhất 1 câu"); return null; }
        return qs;
    }

    // ---------- Người chơi ----------

    static void handle(String type, JsonObject p, Conn s) {
        ensureLoaded();
        switch (type) {
            case "LIST_SETS" -> sendList(s);
            case "CREATE_SET" -> create(s, p);
            case "UPDATE_SET" -> update(s, p);
            case "GET_SET_DETAIL" -> detail(s, p);
            case "TOGGLE_SET_PUBLIC" -> toggle(s, p);
            case "DELETE_SET" -> delete(s, p);
            default -> { }
        }
    }

    static void sendList(Conn s) {
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (QSet x : sets()) {
            boolean mine = isOwner(x, s);
            if (!x.isPublic() && !mine) continue;
            JsonObjectBuilder o = Json.createObjectBuilder().add("setId", x.setId()).add("ten", x.ten())
                    .add("soCau", x.qs().size()).add("laCuaToi", mine).add("laHeThong", x.system())
                    .add("tacGia", x.tacGia()).add("trangThai", x.trangThai()).add("daTungDuyet", x.daTungDuyet());
            if (mine && x.lyDoTuChoi() != null) o.add("lyDoTuChoi", x.lyDoTuChoi());
            a.add(o);
        }
        send(s, "SET_LIST", Json.createObjectBuilder().add("sets", a));
    }

    private static void create(Conn s, JsonObject p) {
        if (me(s) <= 0) { error(s, "Bạn cần đăng nhập để tạo bộ câu hỏi"); return; }
        String ten = p.getString("ten", "").trim();
        List<Q> qs = parse(s, ten, p);
        if (qs == null) return;
        String st = p.getBoolean("congKhai", false) ? PENDING : PRIVATE;
        synchronized (LOCK) {
            int dbId = DAO.insert(me(s), ten, toDb(qs), st);
            if (dbId <= 0) { error(s, "Không lưu được bộ câu hỏi vào cơ sở dữ liệu, hãy thử lại"); return; }
            sets().add(new QSet(dbId + 1, ten, me(s), s.p.ten, st, false, null, qs));
        }
        if (PENDING.equals(st)) ActivityLog.add(s.p.ten + " gửi bộ câu hỏi \"" + ten + "\" (" + qs.size() + " câu) chờ duyệt công khai");
        send(s, "SET_CREATED", Json.createObjectBuilder().add("ten", ten).add("trangThai", st));
    }

    /** Sửa nội dung: luôn về PRIVATE hoặc PENDING (nội dung mới phải được duyệt lại mới công khai). */
    private static void update(Conn s, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || !isOwner(x, s)) { error(s, "Không tìm thấy bộ câu hỏi của bạn"); return; }
        String ten = p.getString("ten", "").trim();
        List<Q> qs = parse(s, ten, p);
        if (qs == null) return;
        boolean pub = p.getBoolean("congKhai", false);
        String st = pub ? PENDING : PRIVATE;
        synchronized (LOCK) {
            QSet cur = find(x.setId());
            if (cur == null) { error(s, "Bộ câu hỏi đã bị xoá"); return; }
            if (!DAO.updateContent(dbId(cur), me(s), ten, toDb(qs), st)) { error(s, "Không lưu được thay đổi, hãy thử lại"); return; }
            replace(cur, new QSet(cur.setId(), ten, cur.ownerId(), cur.tacGia(), st, false, null, qs));
        }
        ActivityLog.add(s.p.ten + " sửa bộ câu hỏi \"" + ten + "\"" + (pub ? " và gửi duyệt công khai" : ""));
        send(s, "SET_UPDATED", Json.createObjectBuilder().add("setId", x.setId()).add("trangThai", st).add("tuTrinhSoan", true)
                .add("thongBao", pub ? "Đã lưu thay đổi và gửi quản trị viên duyệt công khai" : "Đã lưu thay đổi (riêng tư)"));
    }

    private static void detail(Conn s, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || !isOwner(x, s)) { error(s, "Không tìm thấy bộ câu hỏi của bạn"); return; }
        JsonObjectBuilder o = Json.createObjectBuilder().add("setId", x.setId()).add("ten", x.ten())
                .add("trangThai", x.trangThai()).add("cauHoi", editorList(x));
        if (x.lyDoTuChoi() != null) o.add("lyDoTuChoi", x.lyDoTuChoi());
        send(s, "SET_DETAIL", o);
    }

    private static void toggle(Conn s, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || !isOwner(x, s)) { error(s, "Không tìm thấy bộ câu hỏi của bạn"); return; }
        String st, msg, log;
        synchronized (LOCK) {
            QSet cur = find(x.setId());
            if (cur == null) { error(s, "Bộ câu hỏi đã bị xoá"); return; }
            switch (cur.trangThai()) {
                case APPROVED -> { st = PRIVATE; msg = "Đã ẩn bộ câu hỏi. Bật lại công khai sẽ không cần chờ duyệt"; log = "tạm ẩn"; }
                case PENDING -> { st = PRIVATE; msg = "Đã huỷ yêu cầu công khai"; log = "huỷ yêu cầu duyệt"; }
                default -> {
                    if (PRIVATE.equals(cur.trangThai()) && cur.daTungDuyet()) { st = APPROVED; msg = "Đã bật lại công khai"; log = "bật lại công khai"; }
                    else { st = PENDING; msg = "Đã gửi yêu cầu công khai, chờ quản trị viên duyệt"; log = "gửi yêu cầu duyệt công khai"; }
                }
            }
            String reason = PENDING.equals(st) ? null : cur.lyDoTuChoi();
            if (!DAO.updateStatus(dbId(cur), st, cur.daTungDuyet(), reason)) { error(s, "Không lưu được thay đổi, hãy thử lại"); return; }
            replace(cur, cur.with(st, cur.daTungDuyet(), reason));
        }
        ActivityLog.add(s.p.ten + " " + log + " bộ câu hỏi \"" + x.ten() + "\"");
        send(s, "SET_UPDATED", Json.createObjectBuilder().add("setId", x.setId()).add("trangThai", st).add("thongBao", msg));
        sendList(s);
    }

    private static void delete(Conn s, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || !isOwner(x, s)) { error(s, "Không tìm thấy bộ câu hỏi của bạn"); return; }
        synchronized (LOCK) {
            if (!DAO.delete(dbId(x), me(s))) { error(s, "Không xoá được bộ câu hỏi, hãy thử lại"); return; }
            sets().remove(x);   // phòng đang dùng bộ này vẫn giữ bản đã nạp nên trận không bị ảnh hưởng
        }
        ActivityLog.add(s.p.ten + " xoá bộ câu hỏi \"" + x.ten() + "\"");
        send(s, "SET_UPDATED", Json.createObjectBuilder().add("setId", x.setId()).add("trangThai", "DELETED")
                .add("thongBao", "Đã xoá bộ câu hỏi \"" + x.ten() + "\""));
        sendList(s);
    }

    // ---------- Quản trị viên (đã xác thực ở AdminService) ----------

    static void adminList(Conn s) {
        ensureLoaded();
        JsonArrayBuilder a = Json.createArrayBuilder();
        for (QSet x : sets()) {
            if (x.system()) continue;
            JsonObjectBuilder o = Json.createObjectBuilder().add("setId", x.setId()).add("ten", x.ten())
                    .add("soCau", x.qs().size()).add("tacGia", x.tacGia()).add("ownerId", x.ownerId())
                    .add("trangThai", x.trangThai()).add("daTungDuyet", x.daTungDuyet());
            if (x.lyDoTuChoi() != null) o.add("lyDoTuChoi", x.lyDoTuChoi());
            a.add(o);
        }
        s.sendLine(msg("ADMIN_SETS", Json.createObjectBuilder().add("sets", a)));
    }

    static void adminDetail(Conn s, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || x.system()) { adminFail(s, "Không tìm thấy bộ câu hỏi"); return; }
        s.sendLine(msg("ADMIN_SET_DETAIL", Json.createObjectBuilder().add("setId", x.setId()).add("ten", x.ten())
                .add("tacGia", x.tacGia()).add("trangThai", x.trangThai()).add("cauHoi", editorList(x))));
    }

    /** action: APPROVE (chỉ bộ đang chờ), REJECT (chỉ bộ đang chờ, kèm lý do), REVOKE (gỡ bộ đang công khai). */
    static void adminReview(Conn s, String action, JsonObject p) {
        QSet x = find(p.getInt("setId", -1));
        if (x == null || x.system()) { adminFail(s, "Không tìm thấy bộ câu hỏi"); return; }
        String st, reason = null, notice, log;
        boolean ever;
        synchronized (LOCK) {
            QSet cur = find(x.setId());
            if (cur == null) { adminFail(s, "Bộ câu hỏi đã bị xoá"); return; }
            switch (action) {
                case "APPROVE" -> {
                    if (!PENDING.equals(cur.trangThai())) { adminFail(s, "Bộ này không còn ở trạng thái chờ duyệt"); adminList(s); return; }
                    st = APPROVED; ever = true;
                    notice = "Bộ câu hỏi \"" + cur.ten() + "\" đã được duyệt công khai";
                    log = "DUYỆT";
                }
                case "REJECT" -> {
                    if (!PENDING.equals(cur.trangThai())) { adminFail(s, "Bộ này không còn ở trạng thái chờ duyệt"); adminList(s); return; }
                    reason = p.getString("lyDo", "").trim().replaceAll("\\s+", " ");
                    if (reason.isEmpty()) reason = "Nội dung chưa phù hợp";
                    if (reason.length() > 200) reason = reason.substring(0, 200);
                    st = REJECTED; ever = false;
                    notice = "Bộ câu hỏi \"" + cur.ten() + "\" bị từ chối công khai: " + reason;
                    log = "TỪ CHỐI";
                }
                case "REVOKE" -> {
                    if (!APPROVED.equals(cur.trangThai())) { adminFail(s, "Bộ này không ở trạng thái công khai"); adminList(s); return; }
                    st = PRIVATE; ever = false;
                    notice = "Bộ câu hỏi \"" + cur.ten() + "\" đã bị quản trị viên gỡ công khai";
                    log = "GỠ CÔNG KHAI";
                }
                default -> { return; }
            }
            if (!DAO.updateStatus(dbId(cur), st, ever, reason)) { adminFail(s, "Không lưu được vào cơ sở dữ liệu"); return; }
            replace(cur, cur.with(st, ever, reason));
        }
        ActivityLog.add("Quản trị viên " + log + " bộ câu hỏi \"" + x.ten() + "\" (" + x.tacGia() + ")"
                + (reason != null ? " - Lý do: " + reason : ""));
        notifyOwner(x.ownerId(), notice);
        s.sendLine(msg("ADMIN_NOTICE", Json.createObjectBuilder().add("thongBao", "Đã cập nhật \"" + x.ten() + "\"")));
        adminList(s);
    }

    /** Báo cho chủ sở hữu (nếu đang online) kết quả duyệt. */
    private static void notifyOwner(int accountId, String text) {
        quiz.model.Session se = SessionManager.getSessionByAccount(accountId);
        Conn c = se == null ? null : se.getConn();
        if (c != null) c.sendLine(msg("SET_NOTICE", Json.createObjectBuilder().add("thongBao", text)));
    }

    // ---------- Tiện ích ----------

    private static String msg(String type, JsonObjectBuilder payload) {
        return Json.createObjectBuilder().add("type", type).add("payload", payload).build().toString();
    }

    private static void send(Conn s, String type, JsonObjectBuilder payload) { s.sendLine(msg(type, payload)); }

    private static void error(Conn s, String text) { send(s, "ERROR", Json.createObjectBuilder().add("thongBao", text)); }

    private static void adminFail(Conn s, String text) { send(s, "ADMIN_FAIL", Json.createObjectBuilder().add("thongBao", text)); }
}
