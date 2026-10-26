package com.vodianoy.pgvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * PgGitVisualizer entry point: {@code java -jar pg-git-vc.jar}, then open http://localhost:8765.
 *
 * <p>There is no single application DataSource: every environment (Dev, Test, Prod ...) gets its own
 * connection pool from {@link com.vodianoy.pgvc.environment.EnvironmentManagerService}, so the
 * DataSource auto-configuration is switched off.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class PgGitVcApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgGitVcApplication.class, args);
    }
}
