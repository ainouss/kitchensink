package io.github.ainouss.kitchensink.member.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.github.ainouss.kitchensink.TestMongo;

/**
 * The session id is never put in a URL, and its cookie is not named JSESSIONID: the legacy uses that name on the
 * same host behind the facade. Runs the real server, because MockMvc does not rewrite URLs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestMongo.class)
class SessionTrackingTests {

    @Value("${local.server.port}")
    int port;

    @Test
    void the_first_redirect_after_a_registration_carries_the_session_in_a_cookie_not_in_the_url() throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest register = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/kitchensink"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "name=Session+Test&email=session.test%40mailinator.com&phoneNumber=2125550002"))
                .build();

        HttpResponse<Void> response = http.send(register, HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location)
                .endsWith("/kitchensink/").doesNotContainIgnoringCase("jsessionid"));
        assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
                .startsWith("KITCHENSINK_SESSION=").containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax"));
    }
}
