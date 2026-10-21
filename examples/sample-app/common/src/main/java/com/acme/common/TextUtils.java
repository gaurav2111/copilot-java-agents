package com.acme.common;

import org.apache.commons.text.StringSubstitutor;
import java.util.Map;

/** Small template helper shared by the services. Uses commons-text (deliberately old version in the catalog). */
public final class TextUtils {
    private TextUtils() {}

    public static String fill(String template, Map<String, String> values) {
        if (template == null) return "";
        return new StringSubstitutor(values).replace(template);
    }

    public static String truncate(String s, int max) {
        if (s == null) return null;
        if (max < 0) throw new IllegalArgumentException("max must be >= 0");
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
