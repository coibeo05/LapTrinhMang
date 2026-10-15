package quiz;

import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import quiz.model.Session;
import quiz.net.SessionManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Đo độ trễ (RTT) tới từng người chơi đã đăng nhập: Server gửi PING {n}, trình duyệt trả PONG {n},
 * Server lấy thời gian khứ hồi theo đồng hồ của chính Server (không tin số liệu từ Client).
 */
final class PingService {
    private static final long INTERVAL_SEC = 5;

    private PingService() { }

    static void start() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ping-service");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(PingService::pingAll, 2, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    static void pingAll() {
        try {
            for (Session s : SessionManager.getAllSessions()) {
                Conn c = s.getConn();
                if (c == null || s.getAccount() == null) continue;
                int n = ++c.pingSeq;
                c.pingSentNs = System.nanoTime();
                c.sendLine("{\"type\":\"PING\",\"payload\":{\"n\":" + n + "}}");
            }
        } catch (Throwable ignored) {
            // lần sau đo lại
        }
    }

    static void onPong(Conn c, JsonObject p) {
        JsonValue v = p.get("n");
        long sent = c.pingSentNs;
        if (!(v instanceof JsonNumber num) || num.intValue() != c.pingSeq || sent == 0) return;   // PONG cũ hoặc giả
        double ms = (System.nanoTime() - sent) / 1e6;
        if (ms < 0 || ms > 60_000) return;
        c.pingSentNs = 0;
        c.rttMs = c.rttMs > 0 ? c.rttMs * 0.7 + ms * 0.3 : ms;   // làm mượt
        c.lastPongAt = System.currentTimeMillis();
    }
}