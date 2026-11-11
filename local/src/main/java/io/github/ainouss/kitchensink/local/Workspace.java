package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Where things are: the repository's jars, and the local data in {@code .local/}. */
final class Workspace {

    static final int FACADE_PORT = 8000;
    static final int APP_PORT = 8080;
    static final int SYNC_PORT = 8081;
    static final int LEGACY_PORT = 8180;
    static final int POSTGRES_PORT = 5432;
    static final int MONGO_PORT = 27017;

    final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    /** The repository root: the current directory or one of its parents, else where this jar lives. */
    static Workspace find() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("local")) && Files.exists(p.resolve("pom.xml"))) {
                return new Workspace(p);
            }
        }
        try {
            Path jar = Path.of(Ks.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new Workspace(jar.getParent().getParent().getParent());
        } catch (Exception e) {
            return new Workspace(dir);
        }
    }

    Path local() {
        return root.resolve(".local");
    }

    Path postgresData() {
        return local().resolve("postgres");
    }

    Path mongoData() {
        return local().resolve("mongo");
    }

    Path logs() {
        return local().resolve("logs");
    }

    Path rehearsals() {
        return local().resolve("rehearsals");
    }

    Path rehearsedMarker() {
        return local().resolve("rehearsed");
    }

    Path jar(String module, String name) {
        return root.resolve(module).resolve("target").resolve(name);
    }

    String facadeUrl() {
        return "http://localhost:" + FACADE_PORT;
    }

    String syncUrl() {
        return "http://localhost:" + SYNC_PORT;
    }

    /** The facade's admin token: made on first use, kept in .local/admin-token. */
    String adminToken() throws IOException {
        Path file = local().resolve("admin-token");
        if (Files.exists(file)) {
            return Files.readString(file).strip();
        }
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        Files.createDirectories(local());
        Files.writeString(file, token);
        return token;
    }
}
