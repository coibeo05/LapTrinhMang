-- ============================================================
-- KỊCH BẢN KHỞI TẠO CƠ SỞ DỮ LIỆU MYSQL CHO GAME QUIZ
-- Theo đúng đặc tả thiết kế mục 3.4 (trang 22-23 trong tài liệu)
-- ============================================================

CREATE DATABASE IF NOT EXISTS `quiz_game` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `quiz_game`;

-- 1. Bảng Account (Tài khoản người chơi)
CREATE TABLE IF NOT EXISTS `Account` (
    `accountId` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(50) NOT NULL UNIQUE,
    `passwordHash` VARCHAR(255) NOT NULL,
    `tongDiem` INT DEFAULT 0,
    `tongTranThang` INT DEFAULT 0,
    `ngayTao` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Bảng Question (Ngân hàng câu hỏi)
CREATE TABLE IF NOT EXISTS `Question` (
    `questionId` INT AUTO_INCREMENT PRIMARY KEY,
    `chuDe` VARCHAR(100) NOT NULL,
    `noiDung` TEXT NOT NULL,
    `loaiCauHoi` VARCHAR(20) NOT NULL COMMENT 'BUTTONS, CHECKBOXES, REORDER, TYPE_ANSWER, RANGE',
    `duLieuDapAn` JSON NOT NULL COMMENT 'Dữ liệu đáp án và cấu hình theo từng dạng',
    `doKho` VARCHAR(20) DEFAULT 'DE' COMMENT 'DE, TRUNG_BINH, KHO',
    `nguoiTaoId` INT NULL,
    CONSTRAINT `fk_question_account` FOREIGN KEY (`nguoiTaoId`) REFERENCES `Account` (`accountId`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Bảng MatchResult (Kết quả chung trận đấu)
CREATE TABLE IF NOT EXISTS `MatchResult` (
    `matchId` INT AUTO_INCREMENT PRIMARY KEY,
    `chuDe` VARCHAR(100),
    `thoiGianKetThuc` DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Bảng MatchResultDetail (Chi tiết kết quả từng người chơi trong trận)
CREATE TABLE IF NOT EXISTS `MatchResultDetail` (
    `matchId` INT NOT NULL,
    `accountId` INT NOT NULL,
    `diemSo` INT DEFAULT 0,
    `thuHang` INT NOT NULL,
    PRIMARY KEY (`matchId`, `accountId`),
    CONSTRAINT `fk_mrd_match` FOREIGN KEY (`matchId`) REFERENCES `MatchResult` (`matchId`) ON DELETE CASCADE,
    CONSTRAINT `fk_mrd_account` FOREIGN KEY (`accountId`) REFERENCES `Account` (`accountId`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Bảng ChatMessage (Lịch sử tin nhắn chat theo trận)
CREATE TABLE IF NOT EXISTS `ChatMessage` (
    `chatMessageId` INT AUTO_INCREMENT PRIMARY KEY,
    `matchId` INT NOT NULL,
    `accountId` INT NOT NULL,
    `noiDung` TEXT NOT NULL,
    `thoiGianGui` DATETIME DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT `fk_chat_match` FOREIGN KEY (`matchId`) REFERENCES `MatchResult` (`matchId`) ON DELETE CASCADE,
    CONSTRAINT `fk_chat_account` FOREIGN KEY (`accountId`) REFERENCES `Account` (`accountId`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- DỮ LIỆU MẪU BAN ĐẦU:
-- Mật khẩu mặc định '123456' băm theo format salt:sha256(salt + password)
INSERT INTO `Account` (`username`, `passwordHash`, `tongDiem`, `tongTranThang`)
VALUES 
('admin', '0123456789abcdef0123456789abcdef:68a3563dc7dafa736d4bc8f0cbe3f7a28fe39a031952e41416bfb7f8ca31b672', 1500, 3)
ON DUPLICATE KEY UPDATE `username`=`username`;

