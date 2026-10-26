package com.vodianoy.pgvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point: {@code java -jar pg-git-vc.jar}, then open http://localhost:8765. */
@SpringBootApplication
public class PgGitVcApplication {

    public static void main(String[] args) {
        SpringApplication.run(PgGitVcApplication.class, args);
    }
}
