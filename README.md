# HƯỚNG DẪN CHẠY DỰ ÁN BTL LẬP TRÌNH MẠNG - QUIZ GAME TRỰC TUYẾN
## 💻 HƯỚNG DẪN CHI TIẾT TRÊN VISUAL STUDIO CODE (VS CODE)

Hệ thống trò chơi trắc nghiệm đối kháng trực tuyến (Quiz Game) hỗ trợ nhiều người chơi theo thời gian thực qua giao thức mạng TCP Socket và WebSocket.

---

## 1. KIẾN TRÚC HỆ THỐNG

Dự án gồm 3 tầng thành phần chính:

```
[ Trình duyệt Web (Chrome / Edge) ] 
        ↕ (WebSocket: ws://localhost:8080/QuizClientWeb/ws)
[ QuizClientWeb (Tomcat 10.1) ] --- Cầu nối Bridge.java ---
        ↕ (TCP Socket: localhost:9000)
[ QuizServer (Java Socket TCP) ]
        ↕ (JDBC: localhost:3306)
[ MySQL Database: quiz_game ]
```

1. **MySQL Database (`quiz_game`)**: Lưu trữ tài khoản người chơi, ngân hàng câu hỏi, kết quả trận đấu và lịch sử chat (cổng mặc định: `3306`).
2. **QuizServer**: Server ứng dụng Java TCP Socket xử lý logic nghiệp vụ, quản lý phòng thi đấu, tính điểm xếp hạng và tương tác CSDL (cổng mặc định: `9000`).
3. **QuizClientWeb**: Ứng dụng web chạy trên **Apache Tomcat 10.1+**, tích hợp cầu nối WebSocket (`Bridge.java`) chuyển tiếp dữ liệu 2 chiều giữa trình duyệt và QuizServer (cổng mặc định: `8080`).

---

## 2. YÊU CẦU MÔI TRƯỜNG & EXTENSION VS CODE

### Yêu cầu phần mềm:
- **Hệ điều hành**: Windows 10 / 11.
- **Java JDK**: JDK 21 hoặc mới hơn (ví dụ: JDK 25 tại `C:\Program Files\Apache NetBeans\jdk`).
- **Apache Maven**: Phiên bản 3.9+ để build mã nguồn.
- **MySQL Server**: Đã cài đặt và đang chạy dịch vụ MySQL trên cổng `3306`.
- **Apache Tomcat 10.1+**: Hỗ trợ Jakarta EE 10 (đường dẫn: `C:\Users\ASUS\Downloads\apache-tomcat-10.1.52-windows-x64\apache-tomcat-10.1.52`).

### Tiện ích mở rộng (Extensions) khuyên dùng trên VS Code:
Vào mục **Extensions** trên thanh bên trái (`Ctrl + Shift + X`) và cài đặt:
1. **Extension Pack for Java** (Microsoft) — Hỗ trợ biên dịch, gợi ý code, chạy và debug ứng dụng Java.
2. **Maven for Java** (Microsoft) — Quản lý dependencies, lifecycle và build dự án Maven.

---

## 3. CẤU HÌNH CƠ SỞ DỮ LIỆU MYSQL

File cấu hình kết nối CSDL nằm tại:
- `QuizServer/src/main/resources/db.properties`
- `QuizServer/db.properties`

Nội dung cấu hình mẫu:
```properties
db.host=localhost
db.port=3306
db.name=quiz_game
db.user=root
db.password=220625
```

> **Lưu ý**: Khi `QuizServer` khởi chạy, hệ thống sẽ tự động tạo database `quiz_game` và 5 bảng dữ liệu nếu chưa tồn tại. Bạn cũng có thể xem và nạp dữ liệu mẫu từ file SQL: `QuizServer/schema_mysql.sql`.

---

## 4. HƯỚNG DẪN KHỞI CHẠY TRÊN VISUAL STUDIO CODE

### 🚀 CÁCH 1: Khởi chạy bằng VS Code Tasks (Nhanh nhất - Khuyên dùng)

Dự án đã được tích hợp sẵn file `.vscode/tasks.json`. Bạn có thể chạy toàn bộ hệ thống bằng phím tắt:

1. Nhấn tổ hợp phím **`Ctrl + Shift + B`** (hoặc vào menu **Terminal** -> **Run Build Task...**).
2. Hệ thống sẽ tự động thực thi task **`1. Khởi chạy toàn bộ (Server + Tomcat)`**:
   - Tự động bật `QuizServer` (Port 9000).
   - Tự động bật `Apache Tomcat 10` (Port 8080).
   - Tự động mở trình duyệt web tới địa chỉ `http://localhost:8080/QuizClientWeb/`.

> **Các Task tiện ích khác trong VS Code:**
> Vào menu **Terminal** -> **Run Task...**:
> - `1. Khởi chạy toàn bộ (Server + Tomcat)`
> - `2. Chạy QuizServer (Port 9000)`
> - `3. Chạy Tomcat Client (Port 8080)`
> - `4. Build All (Maven package)`: Tự động build lại cả Server và Client, copy `.war` vào Tomcat.
> - `5. Dừng toàn bộ (Stop All)`: Tắt cả Tomcat và QuizServer.

---

### 🐞 CÁCH 2: Chạy và Debug QuizServer bằng Run & Debug (F5)

Nếu muốn đặt breakpoint để debug code Server trong VS Code:

1. Mở thanh **Run and Debug** (`Ctrl + Shift + D`) ở cột bên trái VS Code.
2. Tại menu thả xuống phía trên cùng, chọn cấu hình: **`Debug QuizServer`**.
3. Nhấn phím **`F5`** (hoặc bấm nút **Play** màu xanh).
4. Quan sát cửa sổ **DEBUG CONSOLE** / **TERMINAL** hiển thị:
   ```
   QuizServer đang lắng nghe cổng 9000
   ```
5. Tiếp theo, khởi chạy Tomcat Client bằng cách vào **Terminal** -> **Run Task...** -> chọn **`3. Chạy Tomcat Client (Port 8080)`** (hoặc chạy file `run_client.bat`).
6. Mở trình duyệt truy cập: `http://localhost:8080/QuizClientWeb/`.

---

### 💻 CÁCH 3: Chạy thủ công từ Terminal tích hợp trong VS Code

Mở Terminal trong VS Code bằng phím tắt **`Ctrl + ` `** (hoặc vào menu **Terminal** -> **New Terminal**):

#### Bước 1: Build mã nguồn bằng Maven
```powershell
# Build QuizServer (tạo file jar)
cd QuizServer
mvn clean package -DskipTests
cd ..

# Build QuizClientWeb (tạo file war)
cd QuizClientWeb
mvn clean package -DskipTests
cd ..
```

#### Bước 2: Chạy QuizServer
Mở một tab terminal mới (`Terminal -> New Terminal`):
```powershell
cd QuizServer
java -jar target\QuizServer.jar 9000
```
*(Nếu biến môi trường Java chưa trỏ tới JDK, bạn có thể gọi trực tiếp: `& "C:\Program Files\Apache NetBeans\jdk\bin\java.exe" -jar target\QuizServer.jar 9000`)*

#### Bước 3: Deploy file war và khởi chạy Tomcat
Mở thêm một tab terminal mới:
```powershell
# Copy file war vừa build vào thư mục webapps của Tomcat
Copy-Item -Force "QuizClientWeb\target\QuizClientWeb.war" "C:\Users\ASUS\Downloads\apache-tomcat-10.1.52-windows-x64\apache-tomcat-10.1.52\webapps\QuizClientWeb.war"

# Chạy Tomcat
cd "C:\Users\ASUS\Downloads\apache-tomcat-10.1.52-windows-x64\apache-tomcat-10.1.52\bin"
.\startup.bat
```

#### Bước 4: Mở ứng dụng trên trình duyệt
Truy cập: 👉 **[http://localhost:8080/QuizClientWeb/](http://localhost:8080/QuizClientWeb/)**

---

### ⚡ CÁCH 4: Chạy bằng các file Script `.bat` tiện ích

Tại thư mục gốc của dự án, bạn cũng có thể mở terminal trong VS Code hoặc nhấp đúp trực tiếp vào các file script:

| Tên File | Chức Năng |
|---|---|
| **`run_all.bat`** | **1-Click khởi chạy toàn bộ**: Bật Server + Bật Tomcat + Mở trình duyệt |
| **`run_server.bat`** | Chỉ bật QuizServer (Port 9000) |
| **`run_client.bat`** | Chỉ bật Tomcat Client Web (Port 8080) và mở trang web |
| **`build_all.bat`** | Build lại cả Server và Client, tự copy `.war` vào thư mục `webapps` của Tomcat |
| **`stop_all.bat`** | Dừng hoàn toàn Tomcat (8080) và tắt tiến trình QuizServer (9000) |

---

## 5. THÔNG TIN ĐĂNG NHẬP & TEST ĐỐI KHÁNG

- **Tài khoản quản trị viên mẫu**:
  - Tên đăng nhập: `admin`
  - Mật khẩu: `123456`
- **Tạo tài khoản mới**: Bấm nút **"Đăng ký ngay"** trên giao diện web để tạo tài khoản người chơi.
- **Thử nghiệm đối kháng 2 người chơi (Multiplayer 1v1)**:
  1. Mở tab trình duyệt thường: Đăng nhập tài khoản `admin`. Bấm **Tạo phòng**.
  2. Mở tab trình duyệt ẩn danh (`Ctrl + Shift + N` hoặc `Ctrl + Shift + P`): Đăng nhập tài khoản thứ hai (hoặc đăng ký nick mới).
  3. Ở tab thứ hai, chọn phòng vừa tạo để vào (hoặc nhập mã phòng gồm 6 ký tự).
  4. Tab chủ phòng bấm **Bắt đầu** để thi đấu trắc nghiệm trực tuyến theo thời gian thực.

---

## 6. DANH SÁCH CỔNG MẠNG (PORTS)

| Cổng (Port) | Dịch vụ | Ghi chú |
|---|---|---|
| **`3306`** | MySQL Database | CSDL `quiz_game` |
| **`9000`** | QuizServer | Giao thức TCP Socket |
| **`8080`** | Apache Tomcat 10 | HTTP Web Client & WebSocket Bridge (`/QuizClientWeb/ws`) |

---

## 7. XỬ LÝ SỰ CỐ THƯỜNG GẶP (FAQ)

### 1. Báo lỗi "Không kết nối được Server" trên Web:
- **Nguyên nhân**: `QuizServer` chưa chạy trên cổng 9000 hoặc MySQL chưa khởi động.
- **Khắc phục**: 
  - Đảm bảo MySQL đang chạy dịch vụ.
  - Chạy lại task `2. Chạy QuizServer (Port 9000)` hoặc kiểm tra log xem có báo kết nối database thành công không.

### 2. Báo lỗi "Port 9000 / 8080 already in use" (Cổng bị chiếm):
- **Khắc phục**: Chạy script **`stop_all.bat`** (hoặc chạy task **`5. Dừng toàn bộ (Stop All)`** trong VS Code) để giải phóng tiến trình cũ, sau đó khởi động lại.

### 3. Sửa đổi giao diện web HTML/CSS/JS không thấy cập nhật:
- **Khắc phục**: Chạy task **`4. Build All (Maven package)`** để Maven đóng gói lại file WAR và copy sang thư mục `webapps` của Tomcat, sau đó nhấn `Ctrl + F5` trên trình duyệt để xóa cache.
