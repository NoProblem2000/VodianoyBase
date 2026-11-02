package com.vodianoy.pgvc;

import com.vodianoy.pgvc.cli.CliCommandRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Map;

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
 *
 * <p>With {@code --cli} the jar runs one command for CI/CD instead ({@link CliCommandRunner}).
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class PgGitVcApplication {

    public static void main(String[] args) {
        if (CliCommandRunner.isCli(args)) {
            // CI/CD mode: no web server, quiet logs, the command's result is the exit code
            SpringApplication app = new SpringApplication(PgGitVcApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            app.setBannerMode(Banner.Mode.OFF);
            app.setLogStartupInfo(false);
            app.setDefaultProperties(Map.of("logging.level.root", "WARN", "logging.level.com.vodianoy", "WARN",
                    "logging.level.com.zaxxer.hikari", "ERROR"));
            ConfigurableApplicationContext context = app.run(args);
            System.exit(SpringApplication.exit(context));
            return;
        }
        SpringApplication.run(PgGitVcApplication.class, args);
    }
}
