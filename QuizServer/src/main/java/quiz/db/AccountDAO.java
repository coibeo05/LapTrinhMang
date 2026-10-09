package quiz.db;

import quiz.model.Account;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Data Access Object cho bảng Account (Mục 3.4 tài liệu đặc tả).
 */
public class AccountDAO {

    public Account findByUsername(String username) {
        String sql = "SELECT accountId, username, passwordHash, tongDiem, tongTranThang, ngayTao FROM Account WHERE username = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapResultSet(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi tìm account theo username: " + e.getMessage());
        }
        return null;
    }

    public Account findById(int accountId) {
        String sql = "SELECT accountId, username, passwordHash, tongDiem, tongTranThang, ngayTao FROM Account WHERE accountId = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapResultSet(rs);
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi tìm account theo id: " + e.getMessage());
        }
        return null;
    }

    public boolean existsByUsername(String username) {
        String sql = "SELECT 1 FROM Account WHERE username = ? LIMIT 1";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            System.err.println("Lỗi kiểm tra username tồn tại: " + e.getMessage());
        }
        return false;
    }

    public Account create(String username, String passwordHash) {
        String sql = "INSERT INTO Account (username, passwordHash, tongDiem, tongTranThang, ngayTao) VALUES (?, ?, 0, 0, NOW())";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            int affected = ps.executeUpdate();
            if (affected > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        int id = keys.getInt(1);
                        return findById(id);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi tạo account: " + e.getMessage());
        }
        return null;
    }

    public Account findByGoogleSub(String sub) {
        String sql = "SELECT accountId, username, passwordHash, tongDiem, tongTranThang, ngayTao FROM Account WHERE googleSub = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sub);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapResultSet(rs);
            }
        } catch (SQLException e) {
            System.err.println("Lỗi tìm account theo Google: " + e.getMessage());
        }
        return null;
    }

    /** Tạo tài khoản liên kết Google. Định danh bằng "sub" đã xác minh, KHÔNG dựa vào email hay tên. */
    public Account createGoogle(String username, String googleSub, String passwordHash) {
        String sql = "INSERT INTO Account (username, passwordHash, googleSub, tongDiem, tongTranThang, ngayTao) VALUES (?, ?, ?, 0, 0, NOW())";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, googleSub);
            if (ps.executeUpdate() > 0) {
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return findById(keys.getInt(1));
                }
            }
        } catch (SQLException e) {
            System.err.println("Lỗi tạo account Google: " + e.getMessage());
        }
        return null;
    }

    public void updateStats(int accountId, int scoreDelta, boolean isWin) {
        String sql = "UPDATE Account SET tongDiem = tongDiem + ?, tongTranThang = tongTranThang + ? WHERE accountId = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, scoreDelta);
            ps.setInt(2, isWin ? 1 : 0);
            ps.setInt(3, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Lỗi cập nhật điểm/thắng account: " + e.getMessage());
        }
    }

    public List<Account> getLeaderboardByScore(int limit) {
        String sql = "SELECT accountId, username, passwordHash, tongDiem, tongTranThang, ngayTao FROM Account ORDER BY tongDiem DESC, tongTranThang DESC LIMIT ?";
        List<Account> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lấy BXH theo điểm: " + e.getMessage());
        }
        return list;
    }

    public List<Account> getLeaderboardByWins(int limit) {
        String sql = "SELECT accountId, username, passwordHash, tongDiem, tongTranThang, ngayTao FROM Account ORDER BY tongTranThang DESC, tongDiem DESC LIMIT ?";
        List<Account> list = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            System.err.println("Lỗi lấy BXH theo trận thắng: " + e.getMessage());
        }
        return list;
    }

    public boolean updatePassword(int accountId, String newPasswordHash) {
        String sql = "UPDATE Account SET passwordHash = ? WHERE accountId = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newPasswordHash);
            ps.setInt(2, accountId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi cập nhật mật khẩu: " + e.getMessage());
            return false;
        }
    }

    public boolean updateUsername(int accountId, String newUsername) {
        String sql = "UPDATE Account SET username = ? WHERE accountId = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newUsername);
            ps.setInt(2, accountId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("Lỗi đổi tên tài khoản: " + e.getMessage());
            return false;
        }
    }

    private Account mapResultSet(ResultSet rs) throws SQLException {
        return new Account(
            rs.getInt("accountId"),
            rs.getString("username"),
            rs.getString("passwordHash"),
            rs.getInt("tongDiem"),
            rs.getInt("tongTranThang"),
            rs.getString("ngayTao")
        );
    }
}