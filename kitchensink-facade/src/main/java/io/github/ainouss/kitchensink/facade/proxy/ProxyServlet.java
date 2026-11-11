package io.github.ainouss.kitchensink.facade.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.ainouss.kitchensink.facade.control.Control;
import io.github.ainouss.kitchensink.facade.control.Control.Side;
import io.github.ainouss.kitchensink.facade.control.ControlStore;
import io.github.ainouss.kitchensink.facade.shadow.DualRun;
import io.github.ainouss.kitchensink.facade.shadow.ShadowComparator;
import io.github.ainouss.kitchensink.facade.shadow.SideResponse;

/**
 * Forwards each request unchanged to the side the {@link ControlStore control document} names. Writes get 503 while
 * they are paused. In shadow mode, REST {@code GET}s also go to the other side in the background and are compared;
 * the user never waits for that call.
 */
public class ProxyServlet extends HttpServlet {

    public static final String CORRELATION = "X-Correlation-Id";
    static final int MAX_SHADOWS_IN_FLIGHT = 64;
    private static final int MAX_REQUEST_BODY = 10 * 1024 * 1024;
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Logger log = LoggerFactory.getLogger(ProxyServlet.class);

    private final transient ControlStore control;
    private final transient Upstreams upstreams;
    private final transient ShadowComparator comparator;
    private final transient DualRun dualRun;
    private final transient MeterRegistry meters;
    private final transient ExecutorService shadows;
    private final transient Semaphore shadowPermits = new Semaphore(MAX_SHADOWS_IN_FLIGHT);
    private final Duration shadowTimeout;
    private final int maxCompared;

    public ProxyServlet(ControlStore control, Upstreams upstreams, ShadowComparator comparator, DualRun dualRun,
                        MeterRegistry meters, ExecutorService shadows, Duration shadowTimeout, int maxCompared) {
        this.control = control;
        this.upstreams = upstreams;
        this.comparator = comparator;
        this.dualRun = dualRun;
        this.meters = meters;
        this.shadows = shadows;
        this.shadowTimeout = shadowTimeout;
        this.maxCompared = maxCompared;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String correlationId = correlationId(request);
        response.setHeader(CORRELATION, correlationId);
        Control now = control.current();
        String method = request.getMethod();
        String path = request.getRequestURI();
        String pathAndQuery = path + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        boolean read = READS.contains(method);

        Side primary;
        Side shadow = null;
        if (read) {
            primary = now.reads().primary();
            if ("GET".equals(method) && path.startsWith("/kitchensink/rest/")) {
                shadow = now.reads().shadow();
            }
        } else if (now.writes() == Control.Writes.PAUSED) {
            paused(response);
            meters.counter("facade.requests", "kind", "write", "side", "none", "outcome", "paused").increment();
            return;
        } else {
            primary = now.writes() == Control.Writes.NEW ? Side.NEW : Side.LEGACY;
        }

        byte[] body = null;
        if (!read) {
            body = request.getInputStream().readNBytes(MAX_REQUEST_BODY + 1);
            if (body.length > MAX_REQUEST_BODY) {
                problem(response, 413, "too-large", "Request too large", "The request body exceeds 10 MB.");
                return;
            }
        }
        Map<String, List<String>> headers = headers(request, correlationId);
        if (shadow != null) {
            // Ask both sides for uncompressed bodies, so they can be compared as JSON.
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("Accept-Encoding"));
        }
        Upstreams.Forwarded forwarded = new Upstreams.Forwarded(method, pathAndQuery, headers, body);
        CompletableFuture<SideResponse> shadowCall = shadow == null ? null : startShadow(shadow, forwarded, path);

        HttpResponse<InputStream> answer;
        try {
            answer = upstreams.send(primary, forwarded);
        } catch (HttpTimeoutException e) {
            problem(response, 504, "upstream-timeout", "Upstream timeout", "The " + primary + " side did not answer.");
            return;
        } catch (ConnectException e) {
            problem(response, 502, "upstream-unreachable", "Upstream unreachable",
                    "The " + primary + " side cannot be reached.");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            problem(response, 503, "interrupted", "Interrupted", "The facade is stopping.");
            return;
        }
        meters.counter("facade.requests", "kind", read ? "read" : "write", "side", primary.name().toLowerCase(),
                "outcome", Integer.toString(answer.statusCode() / 100) + "xx").increment();

        response.setStatus(answer.statusCode());
        copyHeaders(answer, response, primary);
        try (InputStream in = answer.body(); OutputStream out = response.getOutputStream()) {
            if (shadowCall == null) {
                in.transferTo(out);
                return;
            }
            byte[] buffered = in.readNBytes(maxCompared + 1);
            out.write(buffered);
            if (buffered.length > maxCompared) {
                in.transferTo(out);
                dualRun.count(path, DualRun.Outcome.TOO_LARGE);
                return;
            }
            SideResponse primaryResponse = new SideResponse(primary, answer.statusCode(),
                    answer.headers().firstValue("Content-Type").orElse(null), buffered, null);
            String reads = now.reads().name();
            shadowCall.thenAcceptAsync(shadowResponse -> record(correlationId, method, pathAndQuery, path, reads,
                    primaryResponse, shadowResponse), shadows);
        }
    }

    private CompletableFuture<SideResponse> startShadow(Side side, Upstreams.Forwarded forwarded, String path) {
        if (!shadowPermits.tryAcquire()) {
            dualRun.count(path, DualRun.Outcome.SKIPPED);
            return null;
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<InputStream> answer = upstreams.send(side, forwarded, shadowTimeout);
                try (InputStream in = answer.body()) {
                    byte[] body = in.readNBytes(maxCompared + 1);
                    if (body.length > maxCompared) {
                        return SideResponse.failed(side, "too large");
                    }
                    return new SideResponse(side, answer.statusCode(),
                            answer.headers().firstValue("Content-Type").orElse(null), body, null);
                }
            } catch (Exception e) {
                return SideResponse.failed(side, e.toString());
            } finally {
                shadowPermits.release();
            }
        }, shadows);
    }

    private void record(String correlationId, String method, String pathAndQuery, String path, String reads,
                        SideResponse primary, SideResponse shadow) {
        try {
            if (shadow.isFailure()) {
                dualRun.count(path, "too large".equals(shadow.error())
                        ? DualRun.Outcome.TOO_LARGE : DualRun.Outcome.SHADOW_FAILED);
                log.debug("Shadow {} {} on {} failed: {}", method, pathAndQuery, shadow.side(), shadow.error());
                return;
            }
            SideResponse legacy = primary.side() == Side.LEGACY ? primary : shadow;
            SideResponse newer = primary.side() == Side.NEW ? primary : shadow;
            dualRun.compared(correlationId, method, pathAndQuery, path, reads, primary, shadow,
                    comparator.compare(method, path, legacy, newer));
        } catch (RuntimeException e) {
            log.warn("A shadow compare failed: {}", e.toString());
        }
    }

    private static String correlationId(HttpServletRequest request) {
        String given = request.getHeader(CORRELATION);
        if (given != null && !given.isBlank() && given.length() <= 128 && given.matches("[A-Za-z0-9._:-]+")) {
            return given;
        }
        return UUID.randomUUID().toString();
    }

    private static Map<String, List<String>> headers(HttpServletRequest request, String correlationId) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!Upstreams.NOT_FORWARDED.contains(lower) && !lower.equals("x-correlation-id")
                    && !lower.startsWith("x-forwarded-")) {
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        headers.put("X-Forwarded-For", List.of(forwardedFor == null
                ? request.getRemoteAddr() : forwardedFor + ", " + request.getRemoteAddr()));
        headers.put("X-Forwarded-Proto", List.of(request.getScheme()));
        if (request.getHeader("Host") != null) {
            headers.put("X-Forwarded-Host", List.of(request.getHeader("Host")));
        }
        headers.put(CORRELATION, List.of(correlationId));
        return headers;
    }

    private void copyHeaders(HttpResponse<InputStream> answer, HttpServletResponse response, Side side) {
        String base = upstreams.base(side).toString();
        answer.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(":") || Upstreams.NOT_FORWARDED.contains(lower) || lower.equals("x-correlation-id")) {
                return;
            }
            List<String> copied = new ArrayList<>(values);
            if (lower.equals("location")) {
                copied.replaceAll(location -> location.startsWith(base)
                        ? (location.length() == base.length() ? "/" : location.substring(base.length()))
                        : location);
            }
            copied.forEach(value -> response.addHeader(name, value));
        });
        answer.headers().firstValueAsLong("Content-Length").ifPresent(response::setContentLengthLong);
    }

    private static void paused(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "5");
        problem(response, 503, "writes-paused", "Writes paused",
                "The members' data is moving to its new home: retry in a few seconds.");
    }

    private static void problem(HttpServletResponse response, int status, String type, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        byte[] body = ("{\"type\":\"urn:kitchensink:problem:" + type + "\",\"title\":\"" + title + "\",\"status\":"
                + status + ",\"detail\":\"" + detail + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
