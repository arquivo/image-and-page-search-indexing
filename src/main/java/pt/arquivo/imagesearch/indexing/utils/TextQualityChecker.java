package pt.arquivo.imagesearch.indexing.utils;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristics that tell whether extracted text was decoded with the wrong charset. Finding no U+FFFD is not enough:
 * decoding UTF-8 as Latin-1 or cp1252 produces no replacement characters, only characteristic garbage.
 */
public final class TextQualityChecker {

    public enum Type {
        /** U+FFFD present: the decoder met bytes invalid for its charset. */
        REPLACEMENT_CHAR,
        /** "Ã©", "â€™", "Â " ...: UTF-8 bytes read as Latin-1/cp1252. */
        MOJIBAKE_UTF8_AS_LATIN,
        /** "Ð¿Ñ€Ð¸": UTF-8 Cyrillic read as Latin-1/cp1252. */
        MOJIBAKE_CYRILLIC,
        /** "ä¸­æ–‡": UTF-8 CJK read as Latin-1/cp1252. */
        MOJIBAKE_CJK,
        /** Text dominated by U+00C0-U+00FF: typical of Cyrillic/Greek single-byte text read as Latin-1. */
        LATIN_SUPPLEMENT_OVERLOAD,
        /** C1 control characters (U+0080-U+009F): cp1252 bytes decoded as ISO-8859-1. */
        C1_CONTROLS,
        /** Control characters or private-use characters: binary data treated as text. */
        BINARY_GARBAGE,
        /** The chosen charset belongs to a script that the text barely uses. */
        SCRIPT_MISMATCH
    }

    public static final class Issue {
        public final Type type;
        public final String snippet;

        Issue(Type type, String snippet) {
            this.type = type;
            this.snippet = snippet;
        }

        @Override
        public String toString() {
            return type + ": " + snippet;
        }
    }

    // Characters that UTF-8 continuation bytes (0x80-0xBF) become when read as Latin-1 or cp1252
    private static final String CONT = "[\\u0080-\\u00BF\\u0152\\u0153\\u0160\\u0161\\u0178\\u017D\\u017E\\u0192\\u02C6\\u02DC"
            + "\\u2013\\u2014\\u2018-\\u201E\\u2020-\\u2022\\u2026\\u2030\\u2039\\u203A\\u20AC\\u2122]";
    private static final Pattern MOJIBAKE_LATIN = Pattern.compile("[\\u00C2\\u00C3]" + CONT + "|\\u00E2\\u20AC" + CONT + "?");
    private static final Pattern MOJIBAKE_CYRILLIC = Pattern.compile("(?:[\\u00D0\\u00D1]" + CONT + "){3,}");
    private static final Pattern MOJIBAKE_CJK = Pattern.compile("[\\u00E3-\\u00E9]" + CONT + "{2}");
    private static final Pattern C1 = Pattern.compile("[\\u0080-\\u009F]");
    private static final Pattern REPLACEMENT = Pattern.compile("\\uFFFD");

    private static final int MIN_LETTERS_FOR_RATIOS = 50;

    private TextQualityChecker() {
    }

    /**
     * @param text          decoded text
     * @param chosenCharset charset the text was decoded with, may be null (enables SCRIPT_MISMATCH when given)
     */
    public static List<Issue> check(String text, Charset chosenCharset) {
        List<Issue> issues = new ArrayList<>();
        if (text == null || text.isEmpty())
            return issues;

        add(issues, Type.REPLACEMENT_CHAR, REPLACEMENT, text);
        add(issues, Type.MOJIBAKE_UTF8_AS_LATIN, MOJIBAKE_LATIN, text);
        add(issues, Type.MOJIBAKE_CYRILLIC, MOJIBAKE_CYRILLIC, text);
        add(issues, Type.MOJIBAKE_CJK, MOJIBAKE_CJK, text);
        add(issues, Type.C1_CONTROLS, C1, text);

        int letters = 0, latinSupplement = 0, control = 0, privateUse = 0, replacement = 0;
        int cyrillic = 0, greek = 0, cjk = 0, hangul = 0, kana = 0, arabic = 0, hebrew = 0, thai = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFD')
                replacement++;
            if (c < 0x20 || c == 0x7F)
                control++;
            if (c >= 0xE000 && c <= 0xF8FF)
                privateUse++;
            if (!Character.isLetter(c))
                continue;
            letters++;
            if (c >= 0xC0 && c <= 0xFF)
                latinSupplement++;
            else if (c >= 0x0400 && c <= 0x04FF)
                cyrillic++;
            else if (c >= 0x0370 && c <= 0x03FF)
                greek++;
            else if (c >= 0x0590 && c <= 0x05FF)
                hebrew++;
            else if (c >= 0x0600 && c <= 0x06FF)
                arabic++;
            else if (c >= 0x0E00 && c <= 0x0E7F)
                thai++;
            else if (c >= 0x3040 && c <= 0x30FF)
                kana++;
            else if (c >= 0xAC00 && c <= 0xD7AF)
                hangul++;
            else if (c >= 0x4E00 && c <= 0x9FFF)
                cjk++;
        }

        int length = text.length();
        if (control >= 5 || (privateUse >= 5 && privateUse > length * 0.02) || replacement > length * 0.05
                || control > length * 0.005)
            issues.add(new Issue(Type.BINARY_GARBAGE, "control=" + control + " privateUse=" + privateUse
                    + " replacement=" + replacement + " length=" + length + " : " + snippet(text, 0)));

        if (letters >= MIN_LETTERS_FOR_RATIOS && latinSupplement > letters * 0.25)
            issues.add(new Issue(Type.LATIN_SUPPLEMENT_OVERLOAD, String.format("%.0f%% of letters in U+00C0-U+00FF : %s",
                    100.0 * latinSupplement / letters, snippet(text, 0))));

        if (chosenCharset != null && letters >= MIN_LETTERS_FOR_RATIOS) {
            String n = chosenCharset.name();
            String expected = null;
            int hits = 0;
            if (n.equals("windows-1251") || n.equals("KOI8-R") || n.equals("KOI8-U") || n.equals("ISO-8859-5") || n.equals("IBM866")) {
                expected = "Cyrillic";
                hits = cyrillic;
            } else if (n.equals("windows-1253") || n.equals("ISO-8859-7")) {
                expected = "Greek";
                hits = greek;
            } else if (n.equals("windows-1255") || n.equals("ISO-8859-8")) {
                expected = "Hebrew";
                hits = hebrew;
            } else if (n.equals("windows-1256") || n.equals("ISO-8859-6")) {
                expected = "Arabic";
                hits = arabic;
            } else if (n.equals("windows-874")) {
                expected = "Thai";
                hits = thai;
            } else if (n.equals("GB18030") || n.startsWith("Big5") || n.equals("windows-31j") || n.equals("Shift_JIS")
                    || n.startsWith("EUC-") || n.startsWith("x-windows-949") || n.equals("EUC-KR")) {
                expected = "CJK";
                hits = cjk + kana + hangul;
            }
            if (expected != null && hits < letters * 0.3)
                issues.add(new Issue(Type.SCRIPT_MISMATCH, n + " but only " + hits + "/" + letters + " letters are "
                        + expected + " : " + snippet(text, 0)));
        }
        return issues;
    }

    private static void add(List<Issue> issues, Type type, Pattern p, String text) {
        Matcher m = p.matcher(text);
        if (!m.find())
            return;
        int count = 1;
        int first = m.start();
        while (m.find())
            count++;
        issues.add(new Issue(type, "x" + count + " : " + snippet(text, first)));
    }

    private static String snippet(String text, int at) {
        int from = Math.max(0, at - 30);
        int to = Math.min(text.length(), at + 50);
        return text.substring(from, to).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
