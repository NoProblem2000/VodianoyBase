package com.vodianoy.pgvc.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiControllerTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("pgvc-test");
        registry.add("pgvc.environments-file", () -> dir.resolve("environments.json").toString());
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void environmentCrudAndValidation() throws Exception {
        String body = """
                {"name": "Unit Test Env", "jdbcUrl": "jdbc:postgresql://127.0.0.1:1/nope", "username": "u", "password": "secret"}
                """;
        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("unit-test-env"))
                .andExpect(jsonPath("$.password").value("********"));

        mvc.perform(get("/api/environments/unit-test-env/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reachable").value(false));

        mvc.perform(get("/api/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environments[0].reachable").value(false));

        mvc.perform(post("/api/environments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"bad\", \"jdbcUrl\": \"jdbc:mysql://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        mvc.perform(get("/api/environments/missing/working-tree"))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/environments/unit-test-env"))
                .andExpect(status().isNoContent());
    }
}
