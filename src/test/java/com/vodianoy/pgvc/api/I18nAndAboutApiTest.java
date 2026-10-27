package com.vodianoy.pgvc.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Server messages follow Accept-Language (ru / en, English by default); GET /api/about. */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class I18nAndAboutApiTest {

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("pgvc-i18n-test");
        registry.add("pgvc.environments-file", () -> dir.resolve("pgvc-environments.json").toString());
    }

    @Test
    void errorMessagesFollowAcceptLanguage() throws Exception {
        mvc.perform(get("/api/environments/nope").header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Окружение не найдено: nope"));
        mvc.perform(get("/api/environments/nope").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.message").value("Environment not found: nope"));
        // no header and unsupported languages: English
        mvc.perform(get("/api/environments/nope"))
                .andExpect(jsonPath("$.message").value("Environment not found: nope"));
        mvc.perform(get("/api/environments/nope").header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE,de;q=0.9"))
                .andExpect(jsonPath("$.message").value("Environment not found: nope"));
        mvc.perform(get("/api/environments/nope").header(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8"))
                .andExpect(jsonPath("$.message").value("Окружение не найдено: nope"));

        mvc.perform(post("/api/environments").header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"X\", \"jdbcUrl\": \"jdbc:mysql://h/db\", \"username\": \"u\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("JDBC URL должен начинаться с jdbc:postgresql:"));
        mvc.perform(get("/api/vc/compare").param("source", "a").param("target", "a")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Исходное и целевое окружения должны различаться"));
        mvc.perform(get("/api/vc/commits/abc123/deletion-plan").header(HttpHeaders.ACCEPT_LANGUAGE, "ru"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Коммит abc123 не найден ни в одном доступном окружении"));
        mvc.perform(get("/api/vc/commits/abc123/deletion-plan").param("mode", "NOPE"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/environments").header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(startsWith("Некорректный запрос")));
    }

    @Test
    void aboutReportsVersionAndLicense() throws Exception {
        mvc.perform(get("/api/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("PgGitVisualizer"))
                .andExpect(jsonPath("$.version").value(not(startsWith("@"))))
                .andExpect(jsonPath("$.version").value(not("dev")))
                .andExpect(jsonPath("$.licenseSpdx").value("Apache-2.0"))
                .andExpect(jsonPath("$.copyright").value("Copyright 2026 NoProblem2000"))
                .andExpect(jsonPath("$.licenseText").value(containsString("Apache License")))
                .andExpect(jsonPath("$.licenseText").value(containsString("Version 2.0, January 2004")));
    }
}
