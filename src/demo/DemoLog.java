package demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class DemoLog {
    private static final Object LOCK = new Object();
    private static final String SERVICE_NAME = env("SERVICE_NAME", "");
    private static final Path LOG_FILE = logFile();

    private DemoLog() {
    }

    static void log(String event, Object... pairs) {
        int serviceFields = SERVICE_NAME.isBlank() ? 0 : 2;
        Object[] all = new Object[pairs.length + 4 + serviceFields];
        all[0] = "ts";
        all[1] = Instant.now().toString();
        all[2] = "event";
        all[3] = event;
        int offset = 4;
        if (!SERVICE_NAME.isBlank()) {
            all[offset++] = "service";
            all[offset++] = SERVICE_NAME;
        }
        System.arraycopy(pairs, 0, all, offset, pairs.length);
        write(DemoJson.object(all));
    }

    private static void write(String line) {
        System.out.println(line);
        if (LOG_FILE == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Path parent = LOG_FILE.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(LOG_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println(DemoJson.object("ts", Instant.now().toString(),
                        "event", "log_file_write_error", "error", e.getClass().getSimpleName()));
            }
        }
    }

    private static Path logFile() {
        String value = env("LOG_FILE", "");
        return value.isBlank() ? null : Path.of(value);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
