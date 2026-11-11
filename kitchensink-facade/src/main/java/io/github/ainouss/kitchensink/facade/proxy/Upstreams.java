package io.github.ainouss.kitchensink.facade.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import io.github.ainouss.kitchensink.facade.FacadeProperties;
import io.github.ainouss.kitchensink.facade.control.Control.Side;

/**
 * HTTP client for the two sides. Redirects are not followed here: the browser follows them through the facade.
 */
@Component
public class Upstreams {

    /** Never forwarded: hop-by-hop headers and headers the JDK client sets itself. */
    static final Set<String> NOT_FORWARDED = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "host",
            "content-length", "expect", "http2-settings");

    private final HttpClient http;
    private final URI legacy;
    private final URI newer;
    private final Duration timeout;

    public Upstreams(FacadeProperties properties) {
        this.legacy = withoutTrailingSlash(properties.legacyUrl());
        this.newer = withoutTrailingSlash(properties.newUrl());
        this.timeout = properties.upstreamTimeout();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public URI base(Side side) {
        return side == Side.LEGACY ? legacy : newer;
    }

    /** A request as the facade forwards it. */
    public record Forwarded(String method, String pathAndQuery, Map<String, List<String>> headers, byte[] body) {
    }

    public HttpResponse<InputStream> send(Side side, Forwarded request) throws IOException, InterruptedException {
        return send(side, request, timeout);
    }

    public HttpResponse<InputStream> send(Side side, Forwarded request, Duration within)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base(side) + request.pathAndQuery()))
                .timeout(within)
                .method(request.method(), request.body() == null || request.body().length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        request.headers().forEach((name, values) -> {
            if (!NOT_FORWARDED.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static URI withoutTrailingSlash(URI uri) {
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }
}
