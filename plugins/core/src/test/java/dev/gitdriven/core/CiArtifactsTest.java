package dev.gitdriven.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The zip CI publishes (see .github/workflows/server-build.yml) and how Core reads it. */
class CiArtifactsTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tmp;

    @Test
    void extractsTheJars() throws Exception {
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("stale.jar"), "from an older download");

        CiArtifacts.extract(zip(Map.of("COMMIT", SHA + "\n", "core.jar", "c", "playground.jar", "p")), SHA, out);

        assertEquals("c", Files.readString(out.resolve("core.jar")));
        assertEquals("p", Files.readString(out.resolve("playground.jar")));
        assertFalse(Files.exists(out.resolve("stale.jar")));
    }

    @Test
    void refusesABuildOfAnotherCommit() {
        IOException e = assertThrows(IOException.class, () -> CiArtifacts.extract(
                zip(Map.of("COMMIT", "ffff", "core.jar", "c")), SHA, tmp.resolve("out")));
        assertTrue(e.getMessage().contains("ffff"), e.getMessage());
        assertThrows(IOException.class, () -> CiArtifacts.extract(zip(Map.of("core.jar", "c")), SHA, tmp.resolve("out")));
        assertThrows(IOException.class, () -> CiArtifacts.extract(zip(Map.of("COMMIT", SHA)), SHA, tmp.resolve("out")));
    }

    /** Only plain jar names: nothing can be written outside the folder or be anything but a plugin. */
    @Test
    void refusesPathsAndOtherFiles() {
        for (String name : new String[] {"../evil.jar", "sub/x.jar", "/abs.jar", "run.sh", ".hidden.jar"}) {
            assertThrows(IOException.class, () -> CiArtifacts.extract(
                    zip(Map.of("COMMIT", SHA, name, "x")), SHA, tmp.resolve("out")), name);
        }
        assertFalse(Files.exists(tmp.resolve("evil.jar")));
    }

    @Test
    void downloadsOrReportsNotPublishedYet() throws Exception {
        byte[] body = zip(Map.of("COMMIT", SHA, "core.jar", "c")).readAllBytes();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            boolean known = exchange.getRequestURI().getPath().equals("/server-plugins-" + SHA + ".zip");
            exchange.sendResponseHeaders(known ? 200 : 404, known ? body.length : -1);
            try (OutputStream o = exchange.getResponseBody()) {
                if (known) {
                    o.write(body);
                }
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/server-plugins-{commit}.zip";
            CiArtifacts ci = new CiArtifacts(url);
            Path out = tmp.resolve("ci-plugins");
            assertTrue(ci.download(SHA, out));
            assertEquals("c", Files.readString(out.resolve("core.jar")));
            assertFalse(ci.download("f".repeat(40), out));
            ci.shutdown();
        } finally {
            server.stop(0);
        }
    }

    private static ByteArrayInputStream zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(entries).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
