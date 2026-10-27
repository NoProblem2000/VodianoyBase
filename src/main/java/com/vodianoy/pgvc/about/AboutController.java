package com.vodianoy.pgvc.about;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** {@code GET /api/about}: name, version and license of the running build (for the About dialog). */
@RestController
@RequestMapping("/api")
public class AboutController {

    public static final String NAME = "PgGitVisualizer";
    public static final String LICENSE_NAME = "Apache License 2.0";
    public static final String LICENSE_SPDX = "Apache-2.0";
    public static final String LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
    public static final String COPYRIGHT = "Copyright 2026 NoProblem2000";
    public static final String REPOSITORY = "https://github.com/NoProblem2000/VodianoyBase";

    private final AboutInfo info;

    public AboutController(@Value("${pgvc.version:dev}") String version) {
        String v = version == null || version.isBlank() || version.startsWith("@") ? "dev" : version;
        this.info = new AboutInfo(NAME, v, LICENSE_NAME, LICENSE_SPDX, LICENSE_URL, COPYRIGHT, REPOSITORY,
                readLicense());
    }

    @GetMapping("/about")
    public AboutInfo about() {
        return info;
    }

    /** Full license text bundled into the jar as META-INF/LICENSE (null when running without it). */
    private static String readLicense() {
        ClassPathResource resource = new ClassPathResource("META-INF/LICENSE");
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @param licenseText full text of the license, or null if it is not bundled
     */
    public record AboutInfo(String name, String version, String license, String licenseSpdx, String licenseUrl,
                            String copyright, String repository, String licenseText) {
    }
}
