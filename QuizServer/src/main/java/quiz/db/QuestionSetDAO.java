package quiz.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Lưu và nạp bộ câu hỏi do người dùng tạo (bảng QuestionSet), gồm cả trạng thái công khai/duyệt.
 * Mọi hàm ghi trả về false/-1 khi lỗi CSDL để tầng trên không đổi dữ liệu trong bộ nhớ.
 */
public class QuestionSetDAO {
    /** tacGia lấy từ Account.username (JOIN) để luôn đúng tên hiện tại của chủ sở hữu. */
    public record Row(int setId, int ownerId, String tacGia, String ten, String cauHoi,
                      String trangThai, boolean daTungDuyet, String lyDoTuChoi) {}

    /** Thêm một bộ; trả về setId mới, hoặc -1 nếu không lưu được. */
    public int insert(int ownerId, String ten, String cauHoiJson, String trangThai) {
        String sql = "INSERT INTO QuestionSet (ownerId, ten, cauHoi, trangThai, ngayTao) VALUES (?, ?, ?, ?, NOW())";
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, ownerId);
            ps.setString(2, ten);
            ps.setString(3, cauHoiJson);
            ps.setString(4, trangThai);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("[QuestionSet] Lỗi CSDL: " + e.getMessage());
        }
        return -1;
    }

    /** Chủ sở hữu sửa nội dung bộ. Sửa nội dung thì mất dấu "đã từng duyệt" và lý do từ chối cũ. */
    public boolean updateContent(int setId, int ownerId, String ten, String cauHoiJson, String trangThai) {
        String sql = "UPDATE QuestionSet SET ten = ?, cauHoi = ?, trangThai = ?, daTungDuyet = 0, lyDoTuChoi = NULL, ngayCapNhat = NOW()"
                + " WHERE setId = ? AND ownerId = ?";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ten);
            ps.setString(2, cauHoiJson);
            ps.setString(3, trangThai);
            ps.setInt(4, setId);
            ps.setInt(5, ownerId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[QuestionSet] Lỗi CSDL: " + e.getMessage());
            return false;
        }
    }

    /** Đổi trạng thái (chủ sở hữu bật/tắt công khai, hoặc quản trị viên duyệt/từ chối/gỡ). */
    public boolean updateStatus(int setId, String trangThai, boolean daTungDuyet, String lyDoTuChoi) {
        String sql = "UPDATE QuestionSet SET trangThai = ?, daTungDuyet = ?, lyDoTuChoi = ?, ngayCapNhat = NOW() WHERE setId = ?";
        try (Connection c = DatabaseManager.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, trangThai);
            ps.setBoolean(2, daTungDuyet);
            ps.setString(3, lyDoTuChoi);
            ps.setInt(4, setId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[QuestionSet] Lỗi CSDL: " + e.getMessage());
            return false;
        }
    }

    /** Chủ sở hữu xoá bộ của mình. */
    public boolean delete(int setId, int ownerId) {
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM QuestionSet WHERE setId = ? AND ownerId = ?")) {
            ps.setInt(1, setId);
            ps.setInt(2, ownerId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            System.err.println("[QuestionSet] Lỗi CSDL: " + e.getMessage());
            return false;
        }
    }

    /** Nạp toàn bộ bộ đã lưu. Lỗi CSDL thì trả null (khác với danh sách rỗng) để tầng trên thử lại sau. */
    public List<Row> loadAll() {
        List<Row> list = new ArrayList<>();
        String sql = "SELECT q.setId, q.ownerId, a.username, q.ten, q.cauHoi, q.trangThai, q.daTungDuyet, q.lyDoTuChoi"
                + " FROM QuestionSet q LEFT JOIN Account a ON a.accountId = q.ownerId ORDER BY q.setId";
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Row(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getBoolean(7), rs.getString(8)));
            }
        } catch (SQLException e) {
            System.err.println("Không nạp được bộ câu hỏi đã lưu (sẽ thử lại khi có người xem danh sách): " + e.getMessage());
            return null;
        }
        return list;
    }
}
