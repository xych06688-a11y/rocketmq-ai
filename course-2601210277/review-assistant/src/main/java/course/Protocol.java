package course;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public final class Protocol {
    private Protocol() { }
    public static String request(String id, String session, String text) throws Exception {
        return Model.JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", "message/send",
            "params", Map.of("message", Map.of("kind", "message", "role", "user", "messageId", id,
                "contextId", session, "parts", List.of(Map.of("kind", "text", "text", text))))));
    }
    public static String input(String body) throws Exception {
        JsonNode node = Model.JSON.readTree(body);
        if (!"message/send".equals(node.path("method").asText())) throw new IllegalArgumentException("Unsupported A2A method");
        JsonNode parts = node.path("params").path("message").path("parts");
        if (!parts.isArray() || parts.isEmpty()) throw new IllegalArgumentException("Missing text part");
        return parts.path(0).path("text").asText();
    }
    public static String response(String id, String session, JsonNode review) throws Exception {
        return Model.JSON.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id,
            "result", Map.of("kind", "message", "role", "agent", "messageId", id + "-reply",
                "contextId", session, "parts", List.of(Map.of("kind", "text", "text", review.toString())))));
    }
    public static JsonNode result(String body) throws Exception {
        JsonNode response = Model.JSON.readTree(body);
        if (response.has("error")) throw new IllegalStateException("Agent returned error: " + response.path("error").path("message").asText());
        return Model.JSON.readTree(response.path("result").path("parts").path(0).path("text").asText());
    }
}
