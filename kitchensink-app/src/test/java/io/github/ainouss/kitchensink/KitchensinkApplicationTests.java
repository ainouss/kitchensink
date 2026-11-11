package io.github.ainouss.kitchensink;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestMongo.class)
class KitchensinkApplicationTests {

    @Value("${local.server.port}")
    int port;

    @Autowired
    MongoTemplate mongo;

    @Test
    void starts_on_mongodb_and_reports_up() throws Exception {
        Document ping = mongo.getDb().runCommand(new Document("ping", 1));
        assertThat(((Number) ping.get("ok")).intValue()).isEqualTo(1);

        HttpResponse<String> health = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
    }
}
