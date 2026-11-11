package io.github.ainouss.kitchensink.member.adapter.in.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The title and description of the versioned API's OpenAPI document. */
@Configuration(proxyBeanMethods = false)
class ApiDocumentation {

    @Bean
    OpenAPI kitchensinkApi() {
        return new OpenAPI().info(new Info()
                .title("kitchensink members API")
                .version("v1")
                .description("""
                        The versioned API of the modernized kitchensink. Errors are RFC 9457 problem \
                        details (application/problem+json), their messages in the client's language (en, es, fr). \
                        The legacy contract, /rest/members, is kept unchanged beside it and is not described here.""")
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
