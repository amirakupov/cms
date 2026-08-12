package com.cms.util;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds URL slugs from titles.
 *
 * <p>Cyrillic is transliterated to Latin first. Without that step a Russian title yields an
 * empty slug, because {@code \w} in Java is ASCII-only unless UNICODE_CHARACTER_CLASS is set
 * and NFD normalization does not decompose Cyrillic into Latin.
 */
public final class SlugUtil {

    private static final Map<Character, String> CYRILLIC = buildCyrillicMap();
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9-]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern REPEATED_DASH = Pattern.compile("-{2,}");
    private static final Pattern EDGE_DASH = Pattern.compile("^-|-$");

    private static final int MAX_LENGTH = 80;

    private SlugUtil() {
    }

    public static String toSlug(String input) {
        if (input == null || input.isBlank()) {
            return "post";
        }

        String transliterated = transliterate(input);

        // Strip diacritics from Latin text (é -> e) now that Cyrillic is already Latin.
        String normalized = Normalizer.normalize(transliterated, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        String slug = WHITESPACE.matcher(normalized).replaceAll("-").toLowerCase(Locale.ROOT);
        slug = NON_SLUG.matcher(slug).replaceAll("");
        slug = REPEATED_DASH.matcher(slug).replaceAll("-");
        slug = EDGE_DASH.matcher(slug).replaceAll("");

        if (slug.length() > MAX_LENGTH) {
            slug = slug.substring(0, MAX_LENGTH);
            int lastDash = slug.lastIndexOf('-');
            if (lastDash > MAX_LENGTH / 2) {
                slug = slug.substring(0, lastDash);
            }
            slug = EDGE_DASH.matcher(slug).replaceAll("");
        }

        return slug.isEmpty() ? "post" : slug;
    }

    private static String transliterate(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            String mapped = CYRILLIC.get(Character.toLowerCase(c));
            if (mapped != null) {
                sb.append(mapped);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<Character, String> buildCyrillicMap() {
        Map<Character, String> m = new LinkedHashMap<>();
        m.put('а', "a");  m.put('б', "b");  m.put('в', "v");  m.put('г', "g");
        m.put('д', "d");  m.put('е', "e");  m.put('ё', "e");  m.put('ж', "zh");
        m.put('з', "z");  m.put('и', "i");  m.put('й', "y");  m.put('к', "k");
        m.put('л', "l");  m.put('м', "m");  m.put('н', "n");  m.put('о', "o");
        m.put('п', "p");  m.put('р', "r");  m.put('с', "s");  m.put('т', "t");
        m.put('у', "u");  m.put('ф', "f");  m.put('х', "h");  m.put('ц', "ts");
        m.put('ч', "ch"); m.put('ш', "sh"); m.put('щ', "sch"); m.put('ъ', "");
        m.put('ы', "y");  m.put('ь', "");   m.put('э', "e");  m.put('ю', "yu");
        m.put('я', "ya");
        return m;
    }
}
