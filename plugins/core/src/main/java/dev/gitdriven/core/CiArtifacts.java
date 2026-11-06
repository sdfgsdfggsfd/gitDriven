package dev.gitdriven.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the plugin jars that CI built for a commit (.github/workflows/server-build.yml), so
 * Gradle never runs on the server machine. The zip holds the jars and a {@code COMMIT} file with
 * the full SHA they were built from.
 */
final class CiArtifacts {
    static final long MAX_BYTES = 256L * 1024 * 1024;
    /** Plain file names only: nothing can land outside the target folder. */
    private static final Pattern JAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.jar");

    private final String urlTemplate;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub redirects release downloads
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /** @param urlTemplate download URL, {@code {commit}} = full SHA */
    CiArtifacts(String urlTemplate) {
        this.urlTemplate = urlTemplate;
    }

    URI uri(String commit) {
        return URI.create(urlTemplate.replace("{commit}", commit));
    }

    /**
     * Puts the jars CI built for {@code commit} in {@code out} (emptied first).
     *
     * @return false if CI hasn't published that commit (yet)
     */
    boolean download(String commit, Path out) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(commit))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "gitDriven-Core")
                .GET()
                .build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() == 404) {
                return false;
            }
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + request.uri());
            }
            extract(body, commit, out);
            return true;
        }
    }

    /** Aborts a download in progress (plugin shutting down). */
    void shutdown() {
        http.shutdownNow();
    }

    static void extract(InputStream zipData, String commit, Path out) throws IOException {
        DeployState.deleteRecursively(out);
        Files.createDirectories(out);
        String builtFrom = null;
        int jars = 0;
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(zipData)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.equals("COMMIT")) {
                    builtFrom = new String(zip.readNBytes(100), StandardCharsets.US_ASCII).strip();
                } else if (JAR.matcher(name).matches()) {
                    total += copy(zip, out.resolve(name), MAX_BYTES - total);
                    jars++;
                } else {
                    throw new IOException("Unexpected file in the CI build: " + name);
                }
            }
        }
        if (builtFrom == null) {
            throw new IOException("The CI build has no COMMIT file");
        }
        if (!builtFrom.equals(commit)) {
            throw new IOException("The CI build is for commit " + builtFrom + ", not " + commit);
        }
        if (jars == 0) {
            throw new IOException("The CI build has no jars");
        }
    }

    /** Copies one zip entry, failing once {@code budget} bytes are exceeded (zip bombs). */
    private static long copy(InputStream in, Path file, long budget) throws IOException {
        long n = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream o = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            for (int r; (r = in.read(buf)) > 0; ) {
                n += r;
                if (n > budget) {
                    throw new IOException("The CI build is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
                }
                o.write(buf, 0, r);
            }
        }
        return n;
    }
}
