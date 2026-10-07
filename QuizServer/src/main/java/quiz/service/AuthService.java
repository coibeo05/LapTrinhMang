package quiz.service;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import quiz.Conn;
import quiz.db.AccountDAO;
import quiz.db.MatchResultDAO;
import quiz.model.Account;
import quiz.model.Message;
import quiz.model.Session;
import quiz.net.SessionManager;
import quiz.util.PasswordUtil;
import java.util.List;

/**
 * Xử lý nghiệp vụ Xác thực, Tài khoản, Lịch sử đấu và Hồ sơ người chơi (Mục 1.1.2, 1.1.3, 4.1 Hình 3).
 */
public class AuthService {
    private final AccountDAO accountDAO;
    private final MatchResultDAO matchResultDAO;

    public AuthService() {
        this.accountDAO = new AccountDAO();
        this.matchResultDAO = new MatchResultDAO();
    }

    /**
     * Xử lý ĐĂNG KÝ (Mục 1.1.2)
     */
    public Message handleRegister(JsonObject payload, int clientSessionId) {
        String username = payload.getString("username", "").trim();
        String password = payload.getString("password", "");
        String confirmPassword = payload.getString("confirmPassword", "");

        if (username.isEmpty() || username.length() < 3 || username.length() > 30) {
            return fail("REGISTER_FAIL", "Tên đăng nhập phải từ 3 đến 30 ký tự", clientSessionId);
        }
        if (password.isEmpty() || password.length() < 4) {
            return fail("REGISTER_FAIL", "Mật khẩu phải từ 4 ký tự trở lên", clientSessionId);
        }
        if (!confirmPassword.isEmpty() && !password.equals(confirmPassword)) {
            return fail("REGISTER_FAIL", "Mật khẩu xác nhận không khớp", clientSessionId);
        }

        if (accountDAO.existsByUsername(username)) {
            return fail("REGISTER_FAIL", "Tên đăng nhập đã tồn tại", clientSessionId);
        }

        String hash = PasswordUtil.hash(password);
        Account acc = accountDAO.create(username, hash);
        if (acc == null) {
            return fail("REGISTER_FAIL", "Lỗi tạo tài khoản trên cơ sở dữ liệu", clientSessionId);
        }

        JsonObject resPayload = Json.createObjectBuilder()
                .add("thongBao", "Đăng ký tài khoản thành công! Hãy đăng nhập.")
                .add("username", acc.getUsername())
                .add("accountId", acc.getAccountId())
                .build();
        return new Message("REGISTER_SUCCESS", clientSessionId, resPayload);
    }

    /**
     * Xử lý ĐĂNG NHẬP (Mục 1.1.3)
     */
    public Message handleLogin(JsonObject payload, Conn conn, Session session) {
        String username = payload.getString("username", "").trim();
        String password = payload.getString("password", "");

        if (username.isEmpty() && payload.containsKey("ten")) {
            username = payload.getString("ten", "").trim();
        }

        if (username.isEmpty()) {
            return fail("LOGIN_FAIL", "Vui lòng nhập tên đăng nhập", session.getSessionId());
        }

        Account acc = accountDAO.findByUsername(username);

        if (!password.isEmpty()) {
            if (acc == null || !PasswordUtil.verify(password, acc.getPasswordHash())) {
                return fail("LOGIN_FAIL", "Sai tên đăng nhập hoặc mật khẩu", session.getSessionId());
            }
        } else {
            // Đăng nhập nhanh
            if (acc == null) {
                acc = accountDAO.create(username, PasswordUtil.hash("123456"));
            }
        }

        if (acc == null) {
            return fail("LOGIN_FAIL", "Không thể xác thực tài khoản", session.getSessionId());
        }

        SessionManager.bindAccount(session, acc);

        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("sessionId", session.getSessionId())
                .add("accountId", acc.getAccountId())
                .add("username", acc.getUsername())
                .add("ten", acc.getUsername())
                .add("tongDiem", acc.getTongDiem())
                .add("tongTranThang", acc.getTongTranThang())
                .add("ngayTao", acc.getNgayTao() != null ? acc.getNgayTao() : "");

        return new Message("LOGIN_SUCCESS", session.getSessionId(), b.build());
    }

    /**
     * Xử lý ĐĂNG NHẬP BẰNG GOOGLE
     */
    public Message handleGoogleLogin(JsonObject payload, Conn conn, Session session) {
        String email = payload.getString("email", "").trim();
        String name = payload.getString("name", "").trim();

        if (email.isEmpty()) {
            return fail("LOGIN_FAIL", "Thông tin tài khoản Google không hợp lệ", session.getSessionId());
        }

        String username = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
        if (username.length() > 25) username = username.substring(0, 25);

        Account acc = accountDAO.findByUsername(username);
        if (acc == null) {
            // Tạo tài khoản mới liên kết với Google
            acc = accountDAO.create(username, PasswordUtil.hash("google_oauth_" + email));
        }

        if (acc == null) {
            return fail("LOGIN_FAIL", "Lỗi tạo tài khoản từ Google", session.getSessionId());
        }

        SessionManager.bindAccount(session, acc);

        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("sessionId", session.getSessionId())
                .add("accountId", acc.getAccountId())
                .add("username", acc.getUsername())
                .add("ten", !name.isEmpty() ? name : acc.getUsername())
                .add("email", email)
                .add("isGoogle", true)
                .add("tongDiem", acc.getTongDiem())
                .add("tongTranThang", acc.getTongTranThang())
                .add("ngayTao", acc.getNgayTao() != null ? acc.getNgayTao() : "");

        return new Message("LOGIN_SUCCESS", session.getSessionId(), b.build());
    }

    /**
     * Xử lý ĐỔI MẬT KHẨU / SỬA THÔNG TIN
     */
    public Message handleChangePassword(JsonObject payload, Session session) {
        if (session == null || session.getAccount() == null) {
            return fail("CHANGE_PASSWORD_FAIL", "Bạn chưa đăng nhập", -1);
        }
        Account acc = session.getAccount();
        String oldPass = payload.getString("oldPassword", "");
        String newPass = payload.getString("newPassword", "");

        if (newPass.length() < 4) {
            return fail("CHANGE_PASSWORD_FAIL", "Mật khẩu mới phải từ 4 ký tự trở lên", session.getSessionId());
        }

        // Kiểm tra mật khẩu cũ
        Account freshAcc = accountDAO.findById(acc.getAccountId());
        if (freshAcc == null || !PasswordUtil.verify(oldPass, freshAcc.getPasswordHash())) {
            return fail("CHANGE_PASSWORD_FAIL", "Mật khẩu hiện tại không chính xác", session.getSessionId());
        }

        String newHash = PasswordUtil.hash(newPass);
        boolean ok = accountDAO.updatePassword(acc.getAccountId(), newHash);
        if (ok) {
            return new Message("CHANGE_PASSWORD_SUCCESS", session.getSessionId(),
                    Json.createObjectBuilder().add("thongBao", "Đổi mật khẩu thành công").build());
        } else {
            return fail("CHANGE_PASSWORD_FAIL", "Lỗi cập nhật CSDL", session.getSessionId());
        }
    }

    /**
     * Lấy lịch sử thi đấu cá nhân (Mục 4.1 Hình 3)
     */
    public Message handleGetHistory(Session session) {
        if (session == null || session.getAccount() == null) {
            return fail("HISTORY_FAIL", "Bạn chưa đăng nhập", -1);
        }
        Account acc = session.getAccount();
        List<MatchResultDAO.HistoryItem> history = matchResultDAO.getPersonalHistory(acc.getAccountId(), 20);

        JsonArrayBuilder arr = Json.createArrayBuilder();
        for (MatchResultDAO.HistoryItem item : history) {
            arr.add(Json.createObjectBuilder()
                    .add("matchId", item.matchId())
                    .add("chuDe", item.chuDe())
                    .add("thoiGian", item.thoiGian())
                    .add("diemSo", item.diemSo())
                    .add("thuHang", item.thuHang()));
        }

        Account fresh = accountDAO.findById(acc.getAccountId());
        int totalMatches = history.size();
        int wins = fresh != null ? fresh.getTongTranThang() : 0;
        int totalPoints = fresh != null ? fresh.getTongDiem() : 0;
        int winRate = totalMatches > 0 ? (wins * 100 / totalMatches) : 0;

        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("history", arr)
                .add("totalMatches", totalMatches)
                .add("totalPoints", totalPoints)
                .add("wins", wins)
                .add("winRate", winRate);

        return new Message("HISTORY_DATA", session.getSessionId(), b.build());
    }

    /**
     * Lấy thông tin tài khoản mới nhất
     */
    public Message handleGetProfile(Session session) {
        if (session == null || session.getAccount() == null) {
            return fail("PROFILE_FAIL", "Bạn chưa đăng nhập", -1);
        }
        Account acc = accountDAO.findById(session.getAccount().getAccountId());
        if (acc == null) return fail("PROFILE_FAIL", "Không tìm thấy tài khoản", session.getSessionId());

        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("accountId", acc.getAccountId())
                .add("username", acc.getUsername())
                .add("tongDiem", acc.getTongDiem())
                .add("tongTranThang", acc.getTongTranThang())
                .add("ngayTao", acc.getNgayTao() != null ? acc.getNgayTao() : "");

        return new Message("PROFILE_DATA", session.getSessionId(), b.build());
    }

    private Message fail(String type, String reason, int sid) {
        JsonObject p = Json.createObjectBuilder().add("lyDo", reason).add("thongBao", reason).build();
        return new Message(type, sid, p);
    }
}
