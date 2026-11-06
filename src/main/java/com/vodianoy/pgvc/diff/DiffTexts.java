package com.vodianoy.pgvc.diff;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texts of the diff engine (change details, warnings) in the language of the current request
 * ({@code Accept-Language}): {@code i18n/diff_en.properties} / {@code i18n/diff_ru.properties}. Without a request
 * (tests, CLI) and for other languages the English texts are used.
 */
final class DiffTexts {

    private static final String BUNDLE = "i18n/diff";
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
    private static final Map<String, DiffTexts> CACHE = new ConcurrentHashMap<>();

    private final Locale locale;
    private final ResourceBundle bundle;
    private final ResourceBundle english;

    private DiffTexts(Locale locale) {
        this.locale = locale;
        this.english = ResourceBundle.getBundle(BUNDLE, Locale.ENGLISH, DiffTexts.class.getClassLoader(), NO_FALLBACK);
        ResourceBundle b;
        try {
            b = ResourceBundle.getBundle(BUNDLE, locale, DiffTexts.class.getClassLoader(), NO_FALLBACK);
        } catch (MissingResourceException e) {
            b = english;
        }
        this.bundle = b;
    }

    /** Texts in the language of the current request (English when there is none). */
    static DiffTexts current() {
        LocaleContext context = LocaleContextHolder.getLocaleContext();
        Locale locale = context == null || context.getLocale() == null ? Locale.ENGLISH : context.getLocale();
        return of(locale);
    }

    static DiffTexts of(Locale locale) {
        String language = "ru".equals(locale.getLanguage()) ? "ru" : "en";
        return CACHE.computeIfAbsent(language, l -> new DiffTexts(Locale.forLanguageTag(l)));
    }

    /** The text of {@code key} with {@code args} inserted ({@link MessageFormat}; arguments are inserted as text). */
    String t(String key, Object... args) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            pattern = english.getString(key);
        }
        Object[] text = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            text[i] = args[i] == null ? "" : String.valueOf(args[i]);
        }
        return new MessageFormat(pattern, locale).format(text);
    }

    /** First letter upper-case ("таблица x" -> "Таблица x"). */
    static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
