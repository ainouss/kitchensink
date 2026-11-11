package io.github.ainouss.kitchensink.local;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Live traffic through the facade: readers fetch members by id, writers register new members.
 * A writer that gets 503 (writes paused) waits as Retry-After says and sends the same registration again, like a
 * well-behaved client. Every registration it was told succeeded is counted, so a rehearsal can prove none was lost.
 * A writer gives up {@code grace} seconds after the end: a registration still refused then is "abandoned".
 */
final class Traffic {

    private final String facade;
    private final String prefix;
    private final long maxId;
    private final int readers;
    private final int writers;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private long reads;
    private long readErrors;
    private long writesConfirmed;
    private long pausedRetries;
    private long writesAbandoned;
    private long writeErrors;
    private long maxWriteMillis;
    private final List<String> errors = new ArrayList<>();

    private long end;
    private long giveUp;
    private final List<Thread> threads = new ArrayList<>();

    Traffic(String facade, String prefix, long maxId, int readers, int writers) {
        this.facade = facade;
        this.prefix = prefix;
        this.maxId = maxId;
        this.readers = readers;
        this.writers = writers;
    }

    /** Starts the traffic in the background for {@code seconds}. */
    Traffic start(long seconds, long graceSeconds) {
        end = System.currentTimeMillis() + seconds * 1000;
        giveUp = end + graceSeconds * 1000;
        for (int i = 0; i < readers; i++) {
            threads.add(Thread.ofPlatform().daemon().start(this::reader));
        }
        for (int i = 0; i < writers; i++) {
            int number = i;
            threads.add(Thread.ofPlatform().daemon().start(() -> writer(number)));
        }
        return this;
    }

    /** Waits for the traffic to end and returns its summary. */
    Map<String, Object> await() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
        return summary();
    }

    synchronized Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reads", reads);
        summary.put("readErrors", readErrors);
        summary.put("writesConfirmed", writesConfirmed);
        summary.put("pausedRetries", pausedRetries);
        summary.put("writesAbandoned", writesAbandoned);
        summary.put("writeErrors", writeErrors);
        summary.put("maxWriteMillis", maxWriteMillis);
        summary.put("errors", List.copyOf(errors.subList(0, Math.min(20, errors.size()))));
        return summary;
    }

    synchronized long errorCount() {
        return readErrors + writeErrors + writesAbandoned;
    }

    synchronized long writesConfirmed() {
        return writesConfirmed;
    }

    private void reader() {
        while (System.currentTimeMillis() < end) {
            long id = ThreadLocalRandom.current().nextLong(maxId + 1);
            Response response = call("GET", facade + "/kitchensink/rest/members/" + id, null);
            synchronized (this) {
                reads++;
                if (response.status != 200 && response.status != 404) {
                    readErrors++;
                    errors.add("GET " + response.status);
                }
            }
        }
    }

    private void writer(int number) {
        int n = 0;
        while (System.currentTimeMillis() < end) {
            n++;
            String email = prefix + "-" + number + "-" + n + "@mailinator.com";
            String body = "{\"name\":\"Rehearsal Member\",\"email\":\"" + email + "\",\"phoneNumber\":\"2125550000\"}";
            long first = System.currentTimeMillis();
            Response response;
            while (true) {
                response = call("POST", facade + "/kitchensink/rest/members", body);
                if (response.status == 503 && response.retryAfter > 0
                        && System.currentTimeMillis() + response.retryAfter * 1000 < giveUp) {
                    synchronized (this) {
                        pausedRetries++;
                    }
                    Api.sleep(response.retryAfter * 1000);
                    continue;
                }
                break;
            }
            long took = System.currentTimeMillis() - first;
            synchronized (this) {
                if (response.status == 503) {
                    writesAbandoned++;
                    errors.add("POST 503 until the end, abandoned " + email);
                } else if (response.status == 200) {
                    writesConfirmed++;
                    maxWriteMillis = Math.max(maxWriteMillis, took);
                } else {
                    writeErrors++;
                    errors.add("POST " + response.status + " " + email);
                }
            }
            Api.sleep(50);
        }
    }

    private record Response(int status, long retryAfter) {
    }

    private Response call(String method, String url, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(body));
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            long retryAfter = response.headers().firstValue("Retry-After").map(Long::parseLong).orElse(0L);
            return new Response(response.statusCode(), retryAfter);
        } catch (IOException e) {
            return new Response(0, 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response(0, 0);
        }
    }
}
