package com.vodianoy.pgvc.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings ({@code pgvc.*} in application.yml or command line). */
@Data
@ConfigurationProperties(prefix = "pgvc")
public class PgVcProperties {

    /** JSON file with the environment list, relative to the working directory (next to the jar). */
    private String environmentsFile = "./pgvc-environments.json";

    /** Timeout for opening a connection to an environment, in seconds. */
    private int connectTimeoutSeconds = 5;

    /** Maximum number of pooled connections per environment. */
    private int maxPoolSize = 4;
}
