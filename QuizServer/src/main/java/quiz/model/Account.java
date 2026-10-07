package quiz.model;

/**
 * Thực thể Account theo thiết kế CSDL (Mục 3.4 tài liệu đặc tả).
 * Lưu thông tin tài khoản người chơi bền vững trong cơ sở dữ liệu.
 */
public class Account {
    private final int accountId;
    private final String username;
    private final String passwordHash;
    private int tongDiem;
    private int tongTranThang;
    private final String ngayTao;
    private boolean online;

    public Account(int accountId, String username, String passwordHash, int tongDiem, int tongTranThang, String ngayTao) {
        this.accountId = accountId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.tongDiem = tongDiem;
        this.tongTranThang = tongTranThang;
        this.ngayTao = ngayTao;
        this.online = false;
    }

    public int getAccountId() { return accountId; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public int getTongDiem() { return tongDiem; }
    public void setTongDiem(int tongDiem) { this.tongDiem = tongDiem; }
    public int getTongTranThang() { return tongTranThang; }
    public void setTongTranThang(int tongTranThang) { this.tongTranThang = tongTranThang; }
    public String getNgayTao() { return ngayTao; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public void addScore(int delta) { this.tongDiem += delta; }
    public void addWin() { this.tongTranThang += 1; }
}

