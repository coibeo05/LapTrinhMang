package quiz.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Lưu và nạp bộ câu hỏi do người dùng tạo (bảng QuestionSet). */
public class QuestionSetDAO {
    public record Row(int setId, int ownerId, String ten, String cauHoi) {}

    /** Thêm một bộ; trả về setId mới, hoặc -1 nếu không lưu được. */
    public int insert(int ownerId, String ten, String cauHoiJson) {
        String sql = "INSERT INTO QuestionSet (ownerId, ten, cauHoi, ngayTao) VALUES (?, ?, ?, NOW())";
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, ownerId);
            ps.setString(2, ten);
            ps.setString(3, cauHoiJson);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1;
    }

    /** Nạp toàn bộ bộ đã lưu (gọi một lần khi server khởi động). Lỗi CSDL thì trả danh sách rỗng. */
    public List<Row> loadAll() {
        List<Row> list = new ArrayList<>();
        String sql = "SELECT setId, ownerId, ten, cauHoi FROM QuestionSet ORDER BY setId";
        try (Connection c = DatabaseManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Row(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4)));
            }
        } catch (SQLException e) {
            System.err.println("Không nạp được bộ câu hỏi đã lưu: " + e.getMessage());
        }
        return list;
    }
}