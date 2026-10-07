package quiz.model;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Gói tin chuẩn theo thiết kế Mục 1.1.5, 3.1.1, 3.3.4:
 * Header: type, length (độ dài payload bytes), sessionId.
 * Body: payload (JsonObject).
 */
public record Message(String type, int length, int sessionId, JsonObject payload) {

    public Message(String type, int sessionId, JsonObject payload) {
        this(type, calculateLength(payload), sessionId, payload);
    }

    public Message(String type, JsonObject payload) {
        this(type, calculateLength(payload), -1, payload);
    }

    private static int calculateLength(JsonObject p) {
        return p == null ? 0 : p.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** Chuyển đổi message thành JSON string một dòng để gửi qua socket. */
    public String toJsonString() {
        JsonObjectBuilder b = Json.createObjectBuilder()
                .add("type", type)
                .add("length", length)
                .add("sessionId", sessionId);
        if (payload != null) {
            b.add("payload", payload);
        } else {
            b.add("payload", JsonValue.EMPTY_JSON_OBJECT);
        }
        return b.build().toString();
    }

    /** Phân tích cú pháp JSON string nhận từ socket thành Message. */
    public static Message parse(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return null;
        try (JsonReader reader = Json.createReader(new StringReader(jsonStr))) {
            JsonObject obj = reader.readObject();
            String type = obj.getString("type", "");
            int sessionId = -1;
            if (obj.containsKey("sessionId")) {
                if (obj.get("sessionId").getValueType() == JsonValue.ValueType.NUMBER) {
                    sessionId = obj.getInt("sessionId");
                } else if (obj.get("sessionId").getValueType() == JsonValue.ValueType.STRING) {
                    try {
                        sessionId = Integer.parseInt(obj.getString("sessionId"));
                    } catch (NumberFormatException ignored) {
                        sessionId = -1;
                    }
                }
            }
            JsonObject payload = obj.containsKey("payload") && obj.get("payload") instanceof JsonObject
                    ? obj.getJsonObject("payload")
                    : JsonValue.EMPTY_JSON_OBJECT;
            int length = obj.getInt("length", calculateLength(payload));
            return new Message(type, length, sessionId, payload);
        } catch (Exception e) {
            return null;
        }
    }
}

