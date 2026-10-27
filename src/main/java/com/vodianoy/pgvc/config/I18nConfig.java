package com.vodianoy.pgvc.config;

import com.vodianoy.pgvc.common.Messages;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Server messages in English and Russian. The language comes from the {@code Accept-Language} header sent by the UI;
 * anything else (or no header) gets English.
 */
@Configuration
public class I18nConfig {

    public static final Locale RUSSIAN = Locale.forLanguageTag("ru");
    public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, RUSSIAN);

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(Messages.BUNDLE);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(SUPPORTED);
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
