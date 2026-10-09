package quiz.net;

import quiz.Conn;
import quiz.model.Account;
import quiz.model.Message;
import quiz.model.Session;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quản lý danh sách các phiên (Session) đang kết nối đồng thời trong bộ nhớ Server (Mục 1.1.3, 2.3.1).
 */
public class SessionManager {
    private static final AtomicInteger SEQ = new AtomicInteger(1000);
    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> ACCOUNT_TO_SESSION = new ConcurrentHashMap<>();

    /** Tạo một session mới khi client kết nối */
    public static Session createSession(Conn conn) {
        int sid = SEQ.incrementAndGet();
        Session s = new Session(sid, null, conn);
        SESSIONS.put(sid, s);
        return s;
    }

    /** Gán tài khoản đã đăng nhập vào session */
    public static void bindAccount(Session session, Account account) {
        // Nếu account đã online ở session cũ, ngắt session cũ để tránh xung đột
        Integer oldSid = ACCOUNT_TO_SESSION.get(account.getAccountId());
        if (oldSid != null && oldSid != session.getSessionId()) {
            Session oldSession = SESSIONS.get(oldSid);
            if (oldSession != null && oldSession.getConn() != null) {
                // Có thể gửi thông báo đăng nhập nơi khác nếu cần
                ACCOUNT_TO_SESSION.remove(account.getAccountId());
            }
        }
        account.setOnline(true);
        session.setAccount(account);
        ACCOUNT_TO_SESSION.put(account.getAccountId(), session.getSessionId());
    }

    /** Xóa session khi client ngắt kết nối */
    public static Session removeSession(int sessionId) {
        Session s = SESSIONS.remove(sessionId);
        if (s != null && s.getAccount() != null) {
            ACCOUNT_TO_SESSION.remove(s.getAccount().getAccountId(), sessionId);
            s.getAccount().setOnline(false);
        }
        return s;
    }

    public static Session getSession(int sessionId) {
        return SESSIONS.get(sessionId);
    }

    public static Session getSessionByAccount(int accountId) {
        Integer sid = ACCOUNT_TO_SESSION.get(accountId);
        return sid == null ? null : SESSIONS.get(sid);
    }

    public static Collection<Session> getAllSessions() {
        return SESSIONS.values();
    }

    /** Gửi thông điệp tới một session cụ thể */
    public static void send(int sessionId, Message msg) {
        Session s = SESSIONS.get(sessionId);
        if (s != null && s.getConn() != null) {
            s.getConn().sendLine(msg.toJsonString());
        }
    }

    /** Phát quảng bá (broadcast) tới toàn bộ các kết nối */
    public static void broadcast(Message msg) {
        String json = msg.toJsonString();
        for (Session s : SESSIONS.values()) {
            if (s.getConn() != null) {
                s.getConn().sendLine(json);
            }
        }
    }
}