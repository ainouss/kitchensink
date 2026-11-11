package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Calls to the facade's control API and the sync's operator API. */
final class Api {

    static final JsonMapper JSON = JsonMapper.builder().build();

    final String facade;
    final String sync;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    Api(Workspace workspace) throws IOException {
        this.facade = workspace.facadeUrl();
        this.sync = workspace.syncUrl();
        this.token = workspace.adminToken();
    }

    /** An HTTP error from one of the APIs. */
    static final class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    JsonNode get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    JsonNode post(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** Sets who answers reads and who takes writes. */
    void control(String reads, String writes, String by) {
        String body = JSON.writeValueAsString(java.util.Map.of("reads", reads, "writes", writes, "by", by));
        send(HttpRequest.newBuilder(URI.create(facade + "/facade/control"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
    }

    JsonNode control() {
        return get(facade + "/facade/control");
    }

    JsonNode syncStatus() {
        return get(sync + "/sync/status");
    }

    JsonNode shadowTotals() {
        return get(facade + "/facade/shadow").path("totals");
    }

    /** Runs a reconciliation, waiting while a scheduled one is running. */
    Optional<JsonNode> reconcile() {
        for (int attempt = 0; attempt < 60; attempt++) {
            try {
                return Optional.of(post(sync + "/sync/reconcile"));
            } catch (ApiException e) {
                if (e.status != 409) {
                    return Optional.empty();
                }
                sleep(2_000);
            }
        }
        return Optional.empty();
    }

    /** A registration through the facade, as a legacy client sends it. Returns the HTTP status. */
    int register(String name, String email) {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "name", name, "email", email, "phoneNumber", "2125550000"));
        return status(HttpRequest.newBuilder(URI.create(facade + "/kitchensink/rest/members"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    boolean writesRefusedWith503() {
        return status(HttpRequest.newBuilder(URI.create(facade + "/kitchensink/rest/members"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))) == 503;
    }

    boolean healthy(String url) {
        try {
            return status(HttpRequest.newBuilder(URI.create(url)).GET()) == 200;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int status(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (IOException e) {
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    private JsonNode send(HttpRequest.Builder request) {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(Duration.ofSeconds(120)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ApiException(0, "unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "interrupted");
        }
        if (response.statusCode() >= 300) {
            throw new ApiException(response.statusCode(), "HTTP " + response.statusCode() + " " + response.body());
        }
        String body = response.body();
        return body == null || body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
