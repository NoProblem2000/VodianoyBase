package com.vodianoy.pgvc.web;

import com.vodianoy.pgvc.core.env.ConnectionFactory;
import com.vodianoy.pgvc.core.env.EnvironmentStore;
import com.vodianoy.pgvc.core.service.PgvcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the framework-independent core into Spring. */
@Configuration
public class AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfig.class);

    @Bean
    public EnvironmentStore environmentStore(@Value("${pgvc.environments-file}") String file) {
        EnvironmentStore store = new EnvironmentStore(Path.of(file));
        LOG.info("Environments file: {} ({} environment(s))", store.file(), store.list().size());
        return store;
    }

    @Bean
    public ConnectionFactory connectionFactory(@Value("${pgvc.connect-timeout-seconds:5}") int timeout) {
        return new ConnectionFactory(timeout);
    }

    @Bean
    public PgvcService pgvcService(EnvironmentStore store, ConnectionFactory connections) {
        return new PgvcService(store, connections);
    }

    @Bean
    public ApplicationListener<WebServerInitializedEvent> startupMessage() {
        return event -> LOG.info("PgGitVisualizer is running: http://localhost:{}", event.getWebServer().getPort());
    }
}
