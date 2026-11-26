package course;
import java.nio.file.*;
import java.io.*;
import java.util.Properties;
public final class Config {
    public static final String ID = "2601210277";
    public static final String TASK = "rmqai-" + ID + "-review-task";
    public static final String REPLY = "rmqai-" + ID + "-review-reply";
    private final Properties values = new Properties();
    public Config(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) { values.load(reader); }
    }
    public String required(String key) {
        String value = values.getProperty(key, "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing local configuration: " + key);
        return value;
    }
    public String optional(String key) { return values.getProperty(key, "").trim(); }
    public String group(String role) { return "GID_" + ID + "_" + role; }
}
