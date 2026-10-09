package com.example.hyperion.ui;

import com.example.hyperion.HyperionCache;

import java.awt.Desktop;
import java.net.URI;
import java.time.Duration;

/**
 * Entry point for the Hyperion dashboard.
 *
 * <pre>
 *   mvn -q compile
 *   java -cp target/classes com.example.hyperion.ui.UiLauncher [port] [capacity] [ttlMs]
 * </pre>
 *
 * Defaults: port 7373, capacity 1000 entries, no default TTL.
 */
public final class UiLauncher {

    private UiLauncher() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : CacheServer.defaultPort();
        long capacity = args.length > 1 ? Long.parseLong(args[1]) : 1000L;
        long ttlMs = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        CacheServer server = new CacheServer(port, capacity, ttlMs);

        // Seed a little data so the dashboard is alive the moment it opens.
        seed(server);

        server.start();

        System.out.println();
        System.out.println("  Hyperion dashboard is live");
        System.out.println("  ---------------------------------");
        System.out.println("  URL       : " + server.url());
        System.out.println("  capacity  : " + capacity + " entries");
        System.out.println("  defaultTTL: " + (ttlMs > 0 ? ttlMs + " ms" : "none"));
        System.out.println();
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println();

        openBrowser(server.url());
    }

    private static void seed(CacheServer server) {
        // Reach into the same cache the server owns via a tiny, purpose-built HTTP-free path:
        // the server's cache is private, so instead we seed by issuing puts through a fresh
        // reference is impossible. We therefore expose seeding through the public API of the
        // server rather than reflecting. See CacheServer#seed for the hook.
        server.seed(cache -> {
            String[] hot = {"session:alice", "session:bob", "config:features", "config:limits",
                    "user:1", "user:2", "user:3", "product:sku-100", "product:sku-200", "rate:global"};
            for (int i = 0; i < hot.length; i++) {
                cache.put(hot[i], "value-" + i);
            }
            // Make a few of them visibly "hot" by reading them repeatedly.
            for (int r = 0; r < 25; r++) {
                cache.getIfPresent("session:alice");
                cache.getIfPresent("config:features");
                cache.getIfPresent("user:1");
            }
            // And a couple with TTLs so the timer wheel has something to chew on.
            cache.put("session:ephemeral", "expires-soon", Duration.ofSeconds(30));
            cache.put("otp:987654", "123456", Duration.ofMinutes(2));
        });
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.out.println("  (could not open a browser automatically; visit " + url + ")");
        }
    }
}
