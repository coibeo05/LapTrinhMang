package quiz;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import quiz.model.Account;
import quiz.model.Session;
import quiz.net.SessionManager;
import quiz.util.ActivityLog;
import quiz.util.AdminConfig;

import java.lang.management.ManagementFactory;

/**
 * Trang quản trị (admin.html). Xác thực bằng mật khẩu quản trị, KHÔNG dùng tài khoản người chơi.
 * Mọi message ADMIN_* (trừ ADMIN_LOGIN) đều bị từ chối nếu kết nối chưa xác thực.
 */
final class AdminService {
    private AdminService() { }

    static void handle(String type, JsonObject p, Conn s) {
        if (type.equals("ADMIN_LOGIN")) { login(p, s); return; }
        if (!s.admin) { fail(s, "Chưa xác thực quản trị viên"); return; }
        switch (type) {
            case "ADMIN_OVERVIEW" -> overview(s);
            case "ADMIN_CLOSE_ROOM" -> {
                Room r = Room.ALL.get(p.getString("roomId", ""));
                if (r != null) r.adminClose("Quản trị viên đã đóng phòng");
                overview(s);
            }
            case "ADMIN_KICK_ROOM" -> {
                Conn c = connOf(p.getInt("accountId", -1));
                if (c != null && c.p != null && c.p.room != null) c.p.room.adminKick(c.p, "Quản trị viên đã mời bạn ra khỏi phòng");
                overview(s);
            }
            case "ADMIN_DISCONNECT" -> {
                Conn c = connOf(p.getInt("accountId", -1));
                if (c != null) {
                    ActivityLog.add("Quản trị viên ngắt kết nối " + (c.p != null ? c.p.ten : "một người chơi"));
                    c.sendLine(msg("ERROR", Json.createObjectBuilder().add("thongBao", "Bạn đã bị quản trị viên ngắt kết nối")));
                    c.close();
                }
                overview(s);
            }
            default -> { }
        }
    }

    private static void login(JsonObject p, Conn s) {
        if (AdminConfig.check(p.getString("password", ""))) {
            s.admin = true;
            s.adminFails = 0;
            s.sendLine(msg("ADMIN_OK", Json.createObjectBuilder()));
            ActivityLog.add("Quản trị viên đăng nhập");
        } else if (++s.adminFails >= 5) {
            fail(s, "Sai mật khẩu quá nhiều lần");
            s.close();
        } else {
            fail(s, "Sai mật khẩu quản trị");
        }
    }

    private static void overview(Conn s) {
        JsonArrayBuilder rooms = Json.createArrayBuilder();
        int nRooms = 0, playing = 0;
        for (Room r : Room.ALL.values()) {
            boolean pl = r.isPlaying();
            nRooms++;
            if (pl) playing++;
            rooms.add(Json.createObjectBuilder().add("roomId", r.id).add("ten", r.ten).add("tenBo", r.set.ten())
                    .add("soNguoi", r.count()).add("toiDa", r.maxPlayers).add("dangChoi", pl).add("an", r.hidden)
                    .add("chuPhong", r.host.ten));
        }
        JsonArrayBuilder players = Json.createArrayBuilder();
        int online = 0, pingN = 0;
        double pingSum = 0;
        long now = System.currentTimeMillis();
        for (Session se : SessionManager.getAllSessions()) {
            Account a = se.getAccount();
            if (a == null) continue;
            online++;
            Conn c = se.getConn();
            Room r = (c == null || c.p == null) ? null : c.p.room;
            if (c != null && c.rttMs > 0 && now - c.lastPongAt < 20_000) { pingSum += c.rttMs; pingN++; }
            players.add(Json.createObjectBuilder().add("accountId", a.getAccountId()).add("username", a.getUsername())
                    .add("roomId", r == null ? "" : r.id).add("roomTen", r == null ? "" : r.ten));
        }
        JsonArrayBuilder logs = Json.createArrayBuilder();
        for (String[] e : ActivityLog.recent(30)) logs.add(Json.createObjectBuilder().add("gio", e[0]).add("nd", e[1]));

        Runtime rt = Runtime.getRuntime();
        JsonObjectBuilder stats = Json.createObjectBuilder().add("phong", nRooms).add("dangThi", playing)
                .add("online", online).add("cpu", cpuPercent())
                .add("pingTB", pingN == 0 ? -1 : Math.round(pingSum / pingN)).add("pingMau", pingN)
                .add("uptimeGiay", ManagementFactory.getRuntimeMXBean().getUptime() / 1000)
                .add("boNhoMB", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        s.sendLine(msg("ADMIN_DATA", Json.createObjectBuilder().add("stats", stats).add("rooms", rooms)
                .add("players", players).add("logs", logs)));
    }

    private static int cpuPercent() {
        try {
            double d = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuLoad();
            return d < 0 ? 0 : (int) Math.round(d * 100);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Conn connOf(int accountId) {
        Session se = SessionManager.getSessionByAccount(accountId);
        return se == null ? null : se.getConn();
    }

    private static void fail(Conn s, String text) {
        s.sendLine(msg("ADMIN_FAIL", Json.createObjectBuilder().add("thongBao", text)));
    }

    private static String msg(String type, JsonObjectBuilder payload) {
        return Json.createObjectBuilder().add("type", type).add("payload", payload).build().toString();
    }
}