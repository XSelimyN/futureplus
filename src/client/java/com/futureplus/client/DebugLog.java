package com.futureplus.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A per-account file for debug lines: logs/futureplus-&lt;user&gt;.log (appended, every line flushed).
 * When two clients are started from the same game folder, the second one rotates the first one's latest.log and the first one's lines
 * are lost; this file is separate per account, so nothing is lost.
 */
final class DebugLog {
    private DebugLog() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);
    private static BufferedWriter out;
    private static String user;

    static synchronized void write(String source, String message) {
        try {
            String name = Minecraft.getInstance().getUser().getName();
            if (out == null || !name.equals(user)) {
                if (out != null) out.close();
                user = name;
                Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("futureplus-" + name + ".log");
                Files.createDirectories(file.getParent());
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            out.write(LocalTime.now().format(TIME) + " [" + source + "] " + message);
            out.newLine();
            out.flush();
        } catch (Throwable t) {
            System.err.println("[Futureplus] debug log yazilamadi: " + t);
            out = null;
        }
    }
}
