package io.github.ainouss.kitchensink.facade.proxy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.ainouss.kitchensink.facade.FacadeProperties;
import io.github.ainouss.kitchensink.facade.control.ControlStore;
import io.github.ainouss.kitchensink.facade.shadow.DualRun;
import io.github.ainouss.kitchensink.facade.shadow.ShadowComparator;

/**
 * Proxies {@code /kitchensink/**} and {@code /css/*}. Other paths ({@code /facade/*}, {@code /actuator/*}) are the
 * facade's own.
 */
@Configuration(proxyBeanMethods = false)
class ProxyConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService shadowExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    ServletRegistrationBean<ProxyServlet> proxyServlet(ControlStore control, Upstreams upstreams,
                                                       ShadowComparator comparator, DualRun dualRun,
                                                       MeterRegistry meters, ExecutorService shadowExecutor,
                                                       FacadeProperties properties) {
        ProxyServlet servlet = new ProxyServlet(control, upstreams, comparator, dualRun, meters, shadowExecutor,
                properties.shadow().timeout(), (int) properties.shadow().maxBody().toBytes());
        ServletRegistrationBean<ProxyServlet> registration =
                new ServletRegistrationBean<>(servlet, "/kitchensink", "/kitchensink/*", "/css/*");
        registration.setName("proxy");
        registration.setLoadOnStartup(1);
        return registration;
    }
}
