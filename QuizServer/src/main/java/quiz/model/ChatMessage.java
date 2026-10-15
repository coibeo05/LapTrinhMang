package quiz.model;

import java.time.LocalDateTime;

/** Một tin chat trong trận (bảng ChatMessage). */
public record ChatMessage(int accountId, String noiDung, LocalDateTime thoiGianGui) { }