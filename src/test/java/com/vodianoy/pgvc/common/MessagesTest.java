package com.vodianoy.pgvc.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** The English and Russian message bundles are complete, valid and rendered correctly. */
class MessagesTest {

    private static final Locale RU = Locale.forLanguageTag("ru");

    private static Properties load(String lang) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessagesTest.class.getResourceAsStream("/i18n/messages_" + lang + ".properties")) {
            assertThat(in).as("messages_" + lang).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void bothLanguagesHaveTheSameKeysAndValidPatterns() throws IOException {
        Properties en = load("en");
        Properties ru = load("ru");
        assertThat(ru.stringPropertyNames()).containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
        for (String key : en.stringPropertyNames()) {
            for (Properties p : new Properties[]{en, ru}) {
                // throws IllegalArgumentException on a broken pattern
                MessageFormat format = new MessageFormat(p.getProperty(key));
                assertThat(format.getFormats().length).as(key + " argument count")
                        .isEqualTo(new MessageFormat(en.getProperty(key)).getFormats().length);
            }
        }
        // Russian texts are really Russian (file read as UTF-8, not mojibake)
        assertThat(ru.getProperty("env.notFound")).isEqualTo("Окружение не найдено: {0}");
    }

    @Test
    void exceptionTextIsEnglishAndQuotesAreRendered() {
        ApiException e = ApiException.conflict("sync.alreadyAt", "prod", "abc1234");
        assertThat(e.getMessage()).isEqualTo("Environment 'prod' is already at abc1234");
        assertThat(e.getCode()).isEqualTo("sync.alreadyAt");
        assertThat(Messages.format(RU, "sync.alreadyAt", "prod", "abc1234"))
                .isEqualTo("Окружение «prod» уже находится на abc1234");
        assertThat(Messages.format(Locale.ENGLISH, "commit.nothing"))
                .isEqualTo("Nothing to commit: the database schema matches HEAD");
        assertThat(Messages.format(Locale.ENGLISH, "no.such.key")).isEqualTo("no.such.key");
    }
}
