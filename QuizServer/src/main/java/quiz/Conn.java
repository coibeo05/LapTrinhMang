package quiz;

import quiz.model.Session;
import quiz.net.SessionManager;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Một kết nối TCP tới Server (Mục 1.1.1, 1.1.4 tài liệu đặc tả).
 * Tích hợp SessionManager để quản lý phiên kết nối theo thời gian thực.
 */
public class Conn {
    public final String id = UUID.randomUUID().toString();
    public volatile Player p;          // Người chơi đã đăng nhập trên kết nối này
    public volatile Session session;   // Phiên làm việc (Session) của kết nối này
    private final Socket sock;
    private final BufferedWriter out;

    Conn(Socket s) throws IOException {
        this.sock = s;
        this.out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        this.session = SessionManager.createSession(this);
    }

    /** Đóng kết nối (dùng khi người chơi tải lại trang và kết nối cũ chưa kịp đóng). */
    public void close() {
        try { sock.close(); } catch (IOException ignored) { }
    }

    public void sendLine(String json) {
        try {
            synchronized (out) {
                out.write(json);
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            /* Client đã đóng kết nối */
        }
    }

    void run(Game g) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                g.onMessage(line, this);
            }
        } catch (IOException e) {
            // Mất kết nối đột ngột (rớt mạng / crash / đóng ứng dụng) - Mục 1.1.4
        } finally {
            if (session != null) {
                SessionManager.removeSession(session.getSessionId());
            }
            g.onClose(this);
            try { sock.close(); } catch (IOException ignored) { }
        }
    }
}
