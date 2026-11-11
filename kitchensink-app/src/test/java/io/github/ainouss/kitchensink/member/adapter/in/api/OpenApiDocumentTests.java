package io.github.ainouss.kitchensink.member.adapter.in.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.ainouss.kitchensink.TestMongo;

/**
 * Checks the OpenAPI document and saves it to {@code target/openapi/kitchensink-api-v1.json}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestMongo.class)
class OpenApiDocumentTests {

    @Autowired
    MockMvc mvc;

    @Test
    void the_document_describes_the_versioned_api_only_and_is_written_to_the_build() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode document = JsonMapper.builder().build().readTree(json);

        assertThat(document.get("openapi").stringValue()).startsWith("3.");
        assertThat(document.at("/info/title").stringValue()).isEqualTo("kitchensink members API");
        JsonNode paths = document.get("paths");
        assertThat(paths.has("/api/v1/members")).isTrue();
        assertThat(paths.has("/api/v1/members/{id}")).isTrue();
        assertThat(paths.has("/rest/members")).as("the legacy contract is not part of it").isFalse();
        assertThat(document.at("/paths/~1api~1v1~1members/post/responses").has("201")).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1members/post/responses").has("409")).isTrue();
        assertThat(document.at("/components/schemas").has("NewMember")).isTrue();

        Path out = Path.of("target", "openapi", "kitchensink-api-v1.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);

        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }
}
