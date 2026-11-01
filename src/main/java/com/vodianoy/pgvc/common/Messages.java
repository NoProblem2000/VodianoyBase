package com.vodianoy.pgvc.common;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Renders message keys of {@code i18n/messages_*.properties} without Spring (exception texts are built where no
 * request locale is known). Properties files are read as UTF-8 (Java 9+). Unknown keys are returned as they are.
 */
public final class Messages {

    public static final String BUNDLE = "i18n/messages";

    private Messages() {
    }

    public static String format(Locale locale, String code, Object... args) {
        String pattern;
        try {
            pattern = ResourceBundle.getBundle(BUNDLE, locale, ResourceBundle.Control.getNoFallbackControl(
                    ResourceBundle.Control.FORMAT_PROPERTIES)).getString(code);
        } catch (MissingResourceException e) {
            return code;
        }
        if (args == null || args.length == 0) {
            return pattern;
        }
        Object[] rendered = args.clone();
        for (int i = 0; i < rendered.length; i++) {
            if (rendered[i] instanceof org.springframework.context.MessageSourceResolvable r && r.getCodes() != null
                    && r.getCodes().length > 0) {
                rendered[i] = format(locale, r.getCodes()[0], r.getArguments());
            }
        }
        return new MessageFormat(pattern, locale).format(rendered);
    }
}
