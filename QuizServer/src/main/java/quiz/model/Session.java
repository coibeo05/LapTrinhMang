package quiz.model;

import quiz.Conn;

/**
 * Phiên làm việc (Session) của một kết nối (Mục 1.1.3, 1.1.6 tài liệu đặc tả).
 * Ánh xạ giữa sessionId <-> accountId <-> Socket (Conn).
 */
public class Session {
    private final int sessionId;
    private volatile Account account;
    private final Conn conn;
    private final long createdAt;

    public Session(int sessionId, Account account, Conn conn) {
        this.sessionId = sessionId;
        this.account = account;
        this.conn = conn;
        this.createdAt = System.currentTimeMillis();
    }

    public int getSessionId() { return sessionId; }
    public Account getAccount() { return account; }
    public void setAccount(Account account) { this.account = account; }
    public Conn getConn() { return conn; }
    public long getCreatedAt() { return createdAt; }

    public boolean isAuthenticated() {
        return account != null;
    }
}

