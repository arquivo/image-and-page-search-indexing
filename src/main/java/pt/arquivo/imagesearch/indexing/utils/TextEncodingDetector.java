package pt.arquivo.imagesearch.indexing.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.html.HtmlEncodingDetector;
import org.mozilla.universalchardet.UniversalDetector;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;

/**
 * Chooses the charset used to decode a text document.
 * <p>
 * Order of decisions: BOM; ASCII only; bytes that are valid UTF-8 (a strong signal, single byte charsets accept
 * any input so a declared Latin-1 cannot disprove it); binary data; charset declared by HTTP Content-Type, then by
 * HTML meta, accepted if it strictly decodes every byte; the same declarations accepted if only a tiny share of the
 * content is undecodable, which is what browsers do with pages that mix encodings; ICU4J / juniversalchardet
 * guesses, only if strictly valid; windows-1252 as last resort.
 */
public class TextEncodingDetector implements EncodingDetector {

    /** Metadata key the caller uses to hand over the charset declared in the HTTP Content-Type header. */
    public static final String DECLARED_CHARSET = "X-Declared-Charset";
    /** Metadata key set to the name of the chosen charset. */
    public static final String RESOLVED_CHARSET = "X-Charset-Resolved";
    /** Metadata key set to the {@link Reason} name. */
    public static final String RESOLUTION_REASON = "X-Charset-Reason";

    public enum Reason {
        BOM, ASCII, UTF8_VALID, UTF8_MOSTLY, DECLARED_HTTP, DECLARED_META,
        /** Declared charset kept although a few bytes do not decode (mixed-encoding page), as browsers do. */
        DECLARED_LENIENT,
        STATISTICAL, FALLBACK,
        /** Nothing decodes it and it is full of control bytes: a font, image, etc. mislabelled as text. */
        BINARY
    }

    public static final class Resolution {
        public final Charset charset;
        public final Reason reason;

        Resolution(Charset charset, Reason reason) {
            this.charset = charset;
            this.reason = reason;
        }
    }

    private static final int SAMPLE_LIMIT = 2 * 1024 * 1024;
    private static final int STATISTICAL_SAMPLE = 64 * 1024;
    private static final int MIN_ICU_CONFIDENCE = 10;
    /** Largest share of non-ASCII bytes that may fail to decode for a declared charset to still be trusted. */
    private static final double LENIENT_MAX_BAD_RATIO = 0.02;
    private static final double BINARY_CONTROL_RATIO = 0.02;
    private static final int MIN_CJK_HIGH_BYTES = 16;
    private static final double MIN_CJK_AVG_RUN = 2.2;
    /** Portuguese "ção/ções" gives runs of exactly two high bytes, so a plain average of 2.0 proves nothing. */
    private static final double MIN_CJK_AVG_RUN_SHIFT_JIS = 2.0;
    /** Latin text with a few accents (Portuguese menus, German umlauts) has about 0.1% high bytes; CJK text far more. */
    private static final double MIN_CJK_HIGH_FRACTION = 0.003;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    @Override
    public Charset detect(InputStream input, Metadata metadata) throws IOException {
        if (input == null || !input.markSupported())
            return null;
        input.mark(SAMPLE_LIMIT + 1);
        byte[] data = new byte[SAMPLE_LIMIT];
        int len = 0;
        try {
            int n;
            while (len < data.length && (n = input.read(data, len, data.length - len)) > 0)
                len += n;
        } finally {
            input.reset();
        }
        Resolution r = resolve(Arrays.copyOf(data, len), metadata.get(DECLARED_CHARSET));
        metadata.set(RESOLVED_CHARSET, r.charset.name());
        metadata.set(RESOLUTION_REASON, r.reason.name());
        return r.charset;
    }

    public static Resolution resolve(byte[] data, String declaredHttp) {
        int len = data.length;

        Charset bom = bomCharset(data);
        if (bom != null)
            return new Resolution(bom, Reason.BOM);

        if (isPlainAscii(data))
            return new Resolution(StandardCharsets.UTF_8, Reason.ASCII);

        boolean looksWide = nulRatio(data) > 0.2;
        Charset http = normalize(declaredHttp);
        Charset metaRaw = metaCharset(data);
        Charset meta = metaRaw == null ? null : normalize(metaRaw.name());

        int[] utf8 = scanUtf8(data);
        int multibyte = utf8[0];
        int invalid = utf8[1];
        if (multibyte > 0 && invalid == 0)
            return new Resolution(StandardCharsets.UTF_8, Reason.UTF8_VALID);
        // Latin-1/cp1252 text almost never contains well-formed multi-byte sequences, so a few stray bytes are tolerated
        if (multibyte >= 4 && invalid * 4 <= multibyte)
            return new Resolution(StandardCharsets.UTF_8, Reason.UTF8_MOSTLY);
        // A page that says it is UTF-8 and is mostly valid UTF-8 keeps it; the stray bytes become U+FFFD like in a browser
        boolean declaresUtf8 = StandardCharsets.UTF_8.equals(http) || (http == null && StandardCharsets.UTF_8.equals(meta));
        if (declaresUtf8 && multibyte > 0 && invalid <= multibyte)
            return new Resolution(StandardCharsets.UTF_8, Reason.UTF8_MOSTLY);

        byte[] sample = len > STATISTICAL_SAMPLE ? Arrays.copyOf(data, STATISTICAL_SAMPLE) : data;

        // Fonts, images and the like served as text. Checked before the declarations, which would happily "decode" them
        // (NULs alone are no sign of binary: UTF-16 without BOM is full of them)
        if (controlRatio(sample) > BINARY_CONTROL_RATIO)
            return new Resolution(WINDOWS_1252, Reason.BINARY);

        // A Latin declaration always "decodes cleanly", so it must not outvote bytes that are plainly Shift_JIS/EUC/GBK/Big5
        if (isLatinDeclaration(http) && !looksWide) {
            Charset cjk = hasIso2022JpEscape(sample) && decodesCleanly(Charset.forName("ISO-2022-JP"), data)
                    ? Charset.forName("ISO-2022-JP") : cjkMisdeclaredAsLatin(sample, data);
            if (cjk != null)
                return new Resolution(cjk, Reason.STATISTICAL);
        }

        // Bytes windows-1252 leaves undefined cannot be Latin text: old Mac / DOS code pages are the usual cause
        if (!looksWide && (http == null || isLatinDeclaration(http)) && (meta == null || isLatinDeclaration(meta))
                && multibyte == 0 && hasCp1252UndefinedByte(data)) {
            Charset legacy = legacyCodePage(sample);
            if (legacy != null)
                return new Resolution(legacy, Reason.STATISTICAL);
        }

        Charset strictHttp = accept(http, looksWide, data);
        if (strictHttp != null)
            return new Resolution(strictHttp, Reason.DECLARED_HTTP);

        Charset strictMeta = accept(meta, looksWide, data);
        if (strictMeta != null)
            return new Resolution(strictMeta, Reason.DECLARED_META);

        Charset lenient = acceptLenient(http, looksWide, data);
        if (lenient == null)
            lenient = acceptLenient(meta, looksWide, data);
        if (lenient != null)
            return new Resolution(lenient, Reason.DECLARED_LENIENT);

        try {
            CharsetDetector icu = new CharsetDetector();
            icu.enableInputFilter(true);
            if (declaredHttp != null)
                icu.setDeclaredEncoding(declaredHttp);
            icu.setText(sample);
            CharsetMatch[] matches = icu.detectAll();
            if (matches != null) {
                for (CharsetMatch m : matches) {
                    if (m.getConfidence() < MIN_ICU_CONFIDENCE)
                        break;
                    Charset c = accept(normalize(m.getName()), looksWide, data);
                    if (c != null)
                        return new Resolution(c, Reason.STATISTICAL);
                }
            }
        } catch (RuntimeException e) {
            // ICU failing on odd input must not abort the document; fall through
        }

        try {
            UniversalDetector uni = new UniversalDetector(null);
            uni.handleData(sample, 0, sample.length);
            uni.dataEnd();
            Charset c = accept(normalize(uni.getDetectedCharset()), looksWide, data);
            if (c != null)
                return new Resolution(c, Reason.STATISTICAL);
        } catch (RuntimeException e) {
            // fall through
        }

        Charset fallback = decodesCleanly(WINDOWS_1252, data) ? WINDOWS_1252 : StandardCharsets.ISO_8859_1;
        return new Resolution(fallback, Reason.FALLBACK);
    }

    private static final Charset[] LEGACY_CODE_PAGES = {Charset.forName("x-MacRoman"), Charset.forName("IBM850"), Charset.forName("IBM860")};
    private static final int LEGACY_MIN_HIGH_BYTES = 6;
    /** Text has next to no NULs; an Office document served as text has a third of its bytes NUL and must not be edited. */
    private static final double MAX_NUL_RATIO_FOR_TEXT_REPAIR = 0.01;
    private static final double LEGACY_MIN_SCORE = 0.85;
    private static final double LEGACY_MIN_MARGIN = 0.3;
    private static final String TYPOGRAPHIC = "\u2018\u2019\u201C\u201D\u2013\u2014\u2026\u2022\u00AB\u00BB\u00BF\u00A1\u20AC\u00A0";

    private static boolean isCp1252Undefined(int v) {
        return v == 0x81 || v == 0x8D || v == 0x8F || v == 0x90 || v == 0x9D;
    }

    private static boolean hasCp1252UndefinedByte(byte[] data) {
        for (byte b : data)
            if (isCp1252Undefined(b & 0xFF))
                return true;
        return false;
    }

    /**
     * Mac Roman or DOS CP850 when it reads clearly better than windows-1252, else null. Only for data with no valid
     * UTF-8 sequence (a Latin page that pasted some UTF-8 is not a legacy page). Each candidate is scored by the share
     * of its accented characters and symbols that are plausible in text (see {@link #plausibility}); windows-1252
     * must read poorly and the legacy page well, so Latin text that merely has a stray undefined byte keeps
     * windows-1252.
     */
    private static Charset legacyCodePage(byte[] sample) {
        int high = 0;
        for (byte b : sample)
            if ((b & 0x80) != 0)
                high++;
        if (high < LEGACY_MIN_HIGH_BYTES)
            return null;
        double base = plausibility(new String(sample, WINDOWS_1252));
        Charset best = null;
        double bestScore = 0, second = 0;
        for (Charset c : LEGACY_CODE_PAGES) {
            double s = plausibility(new String(sample, c));
            if (s > bestScore) {
                second = bestScore;
                best = c;
                bestScore = s;
            } else if (s > second) {
                second = s;
            }
        }
        return best != null && bestScore >= LEGACY_MIN_SCORE && bestScore - base >= LEGACY_MIN_MARGIN
                && bestScore - second >= 0.05 && lowercaseShare(new String(sample, best)) >= 0.5 ? best : null;
    }

    /** Share of the non-ASCII letters that are lowercase or the sentence-initial É/À; prose has mostly those, noise has other capitals. */
    private static double lowercaseShare(String text) {
        int letters = 0, lower = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < 0x80 || !Character.isLetter(ch))
                continue;
            letters++;
            if (Character.isLowerCase(ch) || ch == '\u00C9' || ch == '\u00C0')
                lower++;
        }
        return letters == 0 ? 0 : (double) lower / letters;
    }

    /**
     * Share (0 to 1) of the non-ASCII characters that are plausible: lowercase letters next to a letter, a capital
     * opening a word or inside an all-caps word, a lone é/à/ó/ô/É/À, ordinal indicators after a digit and typographic
     * punctuation. Implausible: capitals after a lowercase letter, lowercase letters before a capital, isolated letters,
     * other symbols and U+FFFD.
     */
    public static double plausibility(String text) {
        int total = 0;
        double good = 0;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char ch = text.charAt(i);
            if (ch < 0x80)
                continue;
            total++;
            char prev = i > 0 ? text.charAt(i - 1) : ' ';
            char next = i + 1 < n ? text.charAt(i + 1) : ' ';
            good += plausible(ch, prev, next);
        }
        return total == 0 ? 0 : good / total;
    }

    /** Letters that appear in Portuguese, Spanish, French, German and Dutch text far less than the common accents (ã é ç ó ...). */
    private static final String UNCOMMON_LETTERS = "\u00EC\u00EF\u00F2\u00F9\u00FB\u00EE\u00FF\u00F0\u00FE\u00F8\u00E6\u0153";

    private static double plausible(char ch, char prev, char next) {
        if (!Character.isLetter(ch))
            return TYPOGRAPHIC.indexOf(ch) >= 0 ? 1 : 0;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
        if (block != Character.UnicodeBlock.LATIN_1_SUPPLEMENT && block != Character.UnicodeBlock.LATIN_EXTENDED_A
                && block != Character.UnicodeBlock.LATIN_EXTENDED_B)
            return 0;
        boolean pl = Character.isLetter(prev), nl = Character.isLetter(next);
        if (Character.isLowerCase(ch)) {
            boolean fits;
            if (pl)
                fits = !(nl && Character.isUpperCase(next) && Character.isLowerCase(prev));
            else if (nl)
                fits = Character.isLowerCase(next);
            else
                fits = "\u00E9\u00E0\u00F3\u00F4".indexOf(ch) >= 0;
            return !fits ? 0 : UNCOMMON_LETTERS.indexOf(ch) >= 0 ? 0.5 : 1;
        }
        if (Character.isUpperCase(ch)) {
            if (pl && Character.isLowerCase(prev))
                return 0;
            if (pl)
                return !nl || Character.isUpperCase(next) ? 1 : 0;
            if (nl)
                return 1;
            return "\u00C9\u00C0".indexOf(ch) >= 0 ? 1 : 0;
        }
        return Character.isDigit(prev) ? 1 : 0;
    }

    /**
     * Drops the bytes windows-1252 leaves undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D) from data that is going to be read
     * as windows-1252 or ISO-8859-1. They are junk in such a page (a browser shows nothing for them), and left in they
     * make the whole page fall back to ISO-8859-1, where the curly quotes and dashes of 0x80-0x9F become control
     * characters, or come out as U+FFFD.
     *
     * @return the bytes without them, or null if there is nothing to drop or the page is not read as a Latin charset
     */
    public static byte[] dropUndefinedLatinBytes(byte[] data, String declaredHttp) {
        if (!hasCp1252UndefinedByte(data) || nulRatio(data) > MAX_NUL_RATIO_FOR_TEXT_REPAIR)
            return null;
        Charset c = resolve(data, declaredHttp).charset;
        if (!c.equals(WINDOWS_1252) && !c.equals(StandardCharsets.ISO_8859_1))
            return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (byte b : data)
            if (!isCp1252Undefined(b & 0xFF))
                out.write(b);
        return out.toByteArray();
    }

    private static boolean isLatinDeclaration(Charset c) {
        return c != null && (c.equals(WINDOWS_1252) || c.equals(StandardCharsets.ISO_8859_1) || c.name().equals("ISO-8859-15"));
    }

    /**
     * Text in a multi-byte CJK charset served with a Latin-1 declaration (typically a server default). Real Latin text
     * has isolated accented letters; CJK text has long runs of high bytes. Both the run length and a statistical
     * detector that names a multi-byte charset able to decode every byte must agree.
     */
    private static Charset cjkMisdeclaredAsLatin(byte[] sample, byte[] data) {
        int high = 0, runs = 0, c1 = 0;
        boolean inRun = false;
        for (byte x : sample) {
            if (x < 0) {
                high++;
                if ((x & 0xff) < 0xA0)
                    c1++;
                if (!inRun) {
                    runs++;
                    inRun = true;
                }
            } else {
                inRun = false;
            }
        }
        if (high < MIN_CJK_HIGH_BYTES || high < sample.length * MIN_CJK_HIGH_FRACTION)
            return null;
        // C1 bytes (0x80-0x9F) as most of the high bytes are Shift_JIS lead bytes, which Latin text does not have
        double minAvgRun = c1 * 2 >= high ? MIN_CJK_AVG_RUN_SHIFT_JIS : MIN_CJK_AVG_RUN;
        if (high < runs * minAvgRun)
            return null;
        try {
            CharsetDetector icu = new CharsetDetector();
            icu.enableInputFilter(true);
            icu.setText(sample);
            CharsetMatch[] matches = icu.detectAll();
            if (matches != null) {
                for (CharsetMatch m : matches) {
                    if (m.getConfidence() < MIN_ICU_CONFIDENCE)
                        break;
                    Charset c = multiByteCjk(normalize(m.getName()), data);
                    if (c != null)
                        return c;
                }
            }
        } catch (RuntimeException e) {
            // ICU failing on odd input must not abort the document
        }
        try {
            UniversalDetector uni = new UniversalDetector(null);
            uni.handleData(sample, 0, sample.length);
            uni.dataEnd();
            return multiByteCjk(normalize(uni.getDetectedCharset()), data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Charset multiByteCjk(Charset c, byte[] data) {
        if (c == null || c.name().startsWith("UTF") || c.newEncoder().maxBytesPerChar() < 2)
            return null;
        return decodesCleanly(c, data) ? c : null;
    }

    /**
     * Returns the charset if it is believable for these bytes, else null. Java's windows-1252 rejects the five
     * bytes the WHATWG table leaves undefined (0x81, 0x8D, 0x8F, 0x90, 0x9D); ISO-8859-1 maps them to C1 controls
     * instead of failing, which is the closest lossless reading.
     */
    private static Charset accept(Charset c, boolean looksWide, byte[] data) {
        if (!usable(c, looksWide))
            return null;
        if (decodesCleanly(c, data))
            return c;
        if (c.equals(WINDOWS_1252))
            return StandardCharsets.ISO_8859_1;
        return null;
    }

    /**
     * Declared charset that fails on a few bytes only: a page that mixes encodings (a legacy title in a UTF-8 page,
     * UTF-8 punctuation in a GBK page). Browsers decode with the declaration anyway, replacing the bad bytes.
     */
    private static Charset acceptLenient(Charset c, boolean looksWide, byte[] data) {
        if (!usable(c, looksWide))
            return null;
        int nonAscii = 0;
        for (byte x : data)
            if (x < 0)
                nonAscii++;
        if (nonAscii == 0)
            return null;
        String text = new String(data, c);
        int bad = 0;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) == '\uFFFD')
                bad++;
        return bad <= nonAscii * LENIENT_MAX_BAD_RATIO ? c : null;
    }

    private static Charset bomCharset(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF)
            return StandardCharsets.UTF_8;
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE)
            return StandardCharsets.UTF_16LE;
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF)
            return StandardCharsets.UTF_16BE;
        return null;
    }

    private static boolean isPlainAscii(byte[] b) {
        for (byte x : b) {
            int c = x & 0xff;
            // NUL suggests UTF-16 without BOM, ESC suggests ISO-2022
            if (c >= 0x80 || c == 0x00 || c == 0x1B)
                return false;
        }
        return true;
    }

    /**
     * Share of bytes that are control characters, the sign of binary data. NULs are not counted (text with stray NULs,
     * and UTF-16, are full of them; binary data is told apart by its other control bytes), nor are the ESC bytes of
     * ISO-2022 escape sequences, which 7-bit Japanese pages have every few characters.
     */
    private static double controlRatio(byte[] b) {
        if (b.length == 0)
            return 0;
        int control = 0;
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == 0x1B && isIso2022Escape(b, i))
                continue;
            if (c != 0 && c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f')
                control++;
        }
        return control / (double) b.length;
    }

    /** ESC followed by a G0/G1 designation: ESC $ B, ESC $ @, ESC $ A, ESC $ ( D, ESC $ ) C, ESC ( B, ESC ( J, ESC ( I. */
    private static boolean isIso2022Escape(byte[] b, int i) {
        if (i + 2 >= b.length)
            return false;
        int x = b[i + 1], y = b[i + 2];
        if (x == '$')
            return y == 'B' || y == '@' || y == 'A' || y == '(' || y == ')';
        return x == '(' && (y == 'B' || y == 'J' || y == 'I');
    }

    private static boolean hasIso2022JpEscape(byte[] b) {
        for (int i = 0; i + 2 < b.length; i++)
            if (b[i] == 0x1B && b[i + 1] == '$' && (b[i + 2] == 'B' || b[i + 2] == '@' || b[i + 2] == '('))
                return true;
        return false;
    }

    /** True if the bytes contain U+FFFD already, either encoded or as a numeric character reference (&amp;#65533; / &amp;#xFFFD;). */
    public static boolean containsReplacementChar(byte[] b) {
        for (int i = 0; i + 2 < b.length; i++) {
            int c = b[i] & 0xff;
            if (c == 0xEF && (b[i + 1] & 0xff) == 0xBF && (b[i + 2] & 0xff) == 0xBD)
                return true;
            if (c == '&' && b[i + 1] == '#' && isReplacementReference(b, i + 2))
                return true;
        }
        return false;
    }

    private static boolean isReplacementReference(byte[] b, int p) {
        if (p < b.length && (b[p] == 'x' || b[p] == 'X'))
            return matchesIgnoreCase(b, p + 1, "fffd") && !(p + 5 < b.length && Character.digit(b[p + 5], 16) >= 0);
        return matchesIgnoreCase(b, p, "65533") && !(p + 5 < b.length && b[p + 5] >= '0' && b[p + 5] <= '9');
    }

    private static boolean matchesIgnoreCase(byte[] b, int p, String s) {
        if (p + s.length() > b.length)
            return false;
        for (int i = 0; i < s.length(); i++)
            if (Character.toLowerCase((char) (b[p + i] & 0xff)) != s.charAt(i))
                return false;
        return true;
    }

    private static double nulRatio(byte[] b) {
        if (b.length == 0)
            return 0;
        int n = Math.min(b.length, STATISTICAL_SAMPLE);
        int nul = 0;
        for (int i = 0; i < n; i++)
            if (b[i] == 0)
                nul++;
        return nul / (double) n;
    }

    /**
     * Counts well-formed multi-byte UTF-8 sequences and malformed bytes. An incomplete sequence cut off by the
     * end of the data (truncated record or sample) is neither.
     *
     * @return {multibyteSequences, invalidBytes}
     */
    static int[] scanUtf8(byte[] b) {
        int multi = 0;
        int invalid = 0;
        int i = 0;
        int len = b.length;
        while (i < len) {
            int c = b[i] & 0xff;
            if (c < 0x80) {
                i++;
                continue;
            }
            int need;
            if (c >= 0xC2 && c <= 0xDF)
                need = 1;
            else if (c >= 0xE0 && c <= 0xEF)
                need = 2;
            else if (c >= 0xF0 && c <= 0xF4)
                need = 3;
            else {
                invalid++;
                i++;
                continue;
            }
            boolean ok = true;
            boolean truncated = false;
            for (int j = 1; j <= need; j++) {
                if (i + j >= len) {
                    truncated = true;
                    break;
                }
                int cc = b[i + j] & 0xff;
                boolean valid = (cc & 0xC0) == 0x80;
                if (valid && j == 1) {
                    if (c == 0xE0 && cc < 0xA0) valid = false;
                    else if (c == 0xED && cc > 0x9F) valid = false;
                    else if (c == 0xF0 && cc < 0x90) valid = false;
                    else if (c == 0xF4 && cc > 0x8F) valid = false;
                }
                if (!valid) {
                    ok = false;
                    break;
                }
            }
            if (truncated && ok)
                break;
            if (ok) {
                multi++;
                i += need + 1;
            } else {
                invalid++;
                i++;
            }
        }
        return new int[] { multi, invalid };
    }

    /**
     * A UTF-8 BOM in front of a body that has high bytes but not a single valid UTF-8 sequence (a Latin-1 file saved by
     * an editor that adds the BOM): returns the data without the BOM so the other rules decide. Otherwise null.
     */
    public static byte[] dropFalseUtf8Bom(byte[] data) {
        if (bomCharset(data) != StandardCharsets.UTF_8)
            return null;
        byte[] rest = Arrays.copyOfRange(data, 3, data.length);
        int[] scan = scanUtf8(rest);
        return scan[0] == 0 && scan[1] > 0 ? rest : null;
    }

    /**
     * For a page that resolves to UTF-8 but contains stray single bytes (Latin-1/windows-1252 text pasted into a
     * UTF-8 page, e.g. "Portugu\u00eas" with a lone 0xEA), returns a copy where each stray byte is replaced by the UTF-8
     * encoding of its windows-1252 character, so the text survives instead of becoming U+FFFD. A lone first half of a
     * cut-off character is dropped.
     *
     * @return the repaired bytes, or null if the page is not mostly-UTF-8 or has no stray bytes
     */
    public static byte[] repairStrayBytes(byte[] data, String declaredHttp) {
        int[] scan = scanUtf8(data);
        if (scan[0] == 0 || scan[1] == 0)
            return null;
        Resolution r = resolve(data, declaredHttp);
        if (r.reason == Reason.BOM && r.charset == StandardCharsets.UTF_8)
            r = resolve(Arrays.copyOfRange(data, 3, data.length), declaredHttp); // a BOM does not make the body clean
        if (r.reason != Reason.UTF8_MOSTLY)
            return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + scan[1] * 2);
        int i = 0;
        while (i < data.length) {
            int c = data[i] & 0xff;
            if (c < 0x80) {
                out.write(c);
                i++;
                continue;
            }
            int seq = utf8SequenceLength(data, i);
            if (seq > 0) {
                out.write(data, i, seq);
                i += seq;
            } else if (seq < 0) {
                out.write(data, i, data.length - i); // cut off by the end of the data
                break;
            } else if (cutOffLength(data, i) > 0) {
                i += cutOffLength(data, i); // dangling first part of a cut-off character: drop it
            } else {
                byte[] repl = new String(new byte[] { (byte) c }, c == 0x81 || c == 0x8d || c == 0x8f || c == 0x90 || c == 0x9d
                        ? StandardCharsets.ISO_8859_1 : WINDOWS_1252).getBytes(StandardCharsets.UTF_8);
                out.write(repl, 0, repl.length);
                i++;
            }
        }
        return out.toByteArray();
    }

    /**
     * For a page that resolves to a Latin single-byte charset but contains UTF-8 sequences (a Latin-1 template with
     * UTF-8 user content, e.g. "Gon\u00c3\u00a7alves" next to "Coment\u00e1rio"), returns a copy converted to UTF-8 where
     * the UTF-8 sequences are kept and every other byte is decoded with the chosen charset. Only sequences that Latin
     * text never produces by chance are recognised: Latin-1 Supplement letters and symbols (C2/C3 xx) and the common
     * punctuation and symbols E2 80 xx, E2 82 AC (euro), E2 84 A2 (trademark).
     *
     * @return the converted bytes, or null if the page does not resolve to Latin-1/windows-1252/ISO-8859-15 or has no such sequence
     */
    public static byte[] repairEmbeddedUtf8(byte[] data, String declaredHttp) {
        boolean found = false;
        for (int i = 0; i < data.length && !found; i++)
            if ((data[i] & 0xff) >= 0xC2 && embeddedUtf8Length(data, i) > 0)
                found = true;
        if (!found)
            return null;
        Resolution r = resolve(data, declaredHttp);
        switch (r.reason) {
            case DECLARED_HTTP: case DECLARED_META: case DECLARED_LENIENT: case STATISTICAL: case FALLBACK:
                break;
            default:
                return null;
        }
        String name = r.charset.name();
        if (!name.equals("windows-1252") && !name.equals("ISO-8859-1") && !name.equals("ISO-8859-15"))
            return null;
        byte[][] table = new byte[256][];
        for (int b = 0x80; b < 256; b++)
            table[b] = new String(new byte[] { (byte) b }, r.charset).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + data.length / 8);
        int i = 0;
        while (i < data.length) {
            int c = data[i] & 0xff;
            if (c < 0x80) {
                out.write(c);
                i++;
                continue;
            }
            int seq = embeddedUtf8Length(data, i);
            if (seq > 0) {
                out.write(data, i, seq);
                i += seq;
            } else {
                out.write(table[c], 0, table[c].length);
                i++;
            }
        }
        return out.toByteArray();
    }

    /**
     * C1 controls (U+0080-U+009F) written as UTF-8 (C2 80 - C2 9F) in a UTF-8 page are windows-1252 characters
     * (smart quotes, dashes, the euro sign) that went through a Latin-1 to UTF-8 conversion. A browser shows them as
     * the windows-1252 character; here they would become U+FFFD (XML cannot carry them). Each one is replaced by that
     * character, and the five undefined ones by a space.
     *
     * @return the repaired bytes, or null if the data is not UTF-8 or has no such sequence
     */
    public static byte[] repairC1InUtf8(byte[] data, String declaredHttp) {
        boolean found = false;
        for (int i = 0; i + 1 < data.length && !found; i++)
            if ((data[i] & 0xff) == 0xC2 && (data[i + 1] & 0xff) >= 0x80 && (data[i + 1] & 0xff) <= 0x9F)
                found = true;
        if (!found)
            return null;
        Resolution r = resolve(data, declaredHttp);
        if (!StandardCharsets.UTF_8.equals(r.charset))
            return null;
        switch (r.reason) {
            case BOM: case UTF8_VALID: case UTF8_MOSTLY:
                break;
            default:
                return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 64);
        int i = 0;
        while (i < data.length) {
            int c = data[i] & 0xff;
            if (c == 0xC2 && i + 1 < data.length && (data[i + 1] & 0xff) >= 0x80 && (data[i + 1] & 0xff) <= 0x9F) {
                int b = data[i + 1] & 0xff;
                byte[] repl = b == 0x81 || b == 0x8d || b == 0x8f || b == 0x90 || b == 0x9d
                        ? new byte[] { ' ' }
                        : new String(new byte[] { (byte) b }, WINDOWS_1252).getBytes(StandardCharsets.UTF_8);
                out.write(repl, 0, repl.length);
                i += 2;
            } else {
                out.write(c);
                i++;
            }
        }
        return out.toByteArray();
    }

    /**
     * Replaces control bytes (C0 controls except tab, line feed and carriage return; form feeds in RFC page breaks,
     * stray NULs, ...) by spaces. XML cannot represent them, so Tika's SafeContentHandler would turn each one into
     * U+FFFD. Left alone: data that looks like UTF-16/32 (BOM or many NULs), ISO-2022 (ESC) and binary data (more than
     * 2% control bytes, which {@link #resolve} reports as BINARY). Every other supported charset keeps C0 bytes out of
     * multi-byte sequences.
     *
     * @return the cleaned bytes, or null if there was nothing to clean or the data must not be touched
     */
    public static byte[] stripControlBytes(byte[] data) {
        int control = 0;
        for (int i = 0; i < data.length; i++) {
            int c = data[i] & 0xff;
            if (c == 0x1B && isIso2022Escape(data, i))
                return null;
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F)
                control++;
        }
        if (control == 0)
            return null;
        Charset bom = bomCharset(data);
        if (bom != null && bom != StandardCharsets.UTF_8)
            return null;
        if (nulRatio(data) > 0.2)
            return null;
        byte[] sample = data.length > STATISTICAL_SAMPLE ? Arrays.copyOf(data, STATISTICAL_SAMPLE) : data;
        if (controlRatio(sample) > BINARY_CONTROL_RATIO)
            return null;
        byte[] out = data.clone();
        for (int i = 0; i < out.length; i++) {
            int c = out[i] & 0xff;
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0x7F)
                out[i] = ' ';
        }
        return out;
    }

    private static final Pattern NUMERIC_REFERENCE = Pattern.compile("&#([xX][0-9a-fA-F]{1,6}|[0-9]{1,7});?");

    /**
     * Numeric character references to control characters (&amp;#127;, &amp;#129;, &amp;#1; ...) become control characters
     * in the parsed text, which Tika's SafeContentHandler turns into U+FFFD. References 130-159 are what browsers show
     * as the windows-1252 character (&amp;#150; is an en dash), so they are rewritten to that character's own reference;
     * the other controls and the undefined slots (127, 129, 141, 143, 144, 157) become a space. The data is only touched
     * as ASCII, so this is meant for HTML in any ASCII-compatible charset (no UTF-16/32, no ISO-2022).
     *
     * @return the repaired bytes, or null if there is nothing to repair
     */
    public static byte[] repairControlReferences(byte[] data) {
        boolean candidate = false;
        for (int i = 0; i + 2 < data.length && !candidate; i++)
            candidate = data[i] == '&' && data[i + 1] == '#';
        if (!candidate)
            return null;
        Charset bom = bomCharset(data);
        if (bom != null && bom != StandardCharsets.UTF_8)
            return null;
        if (nulRatio(data) > 0.2)
            return null;
        String latin = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = NUMERIC_REFERENCE.matcher(latin);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String digits = m.group(1);
            int value;
            try {
                value = digits.charAt(0) == 'x' || digits.charAt(0) == 'X'
                        ? Integer.parseInt(digits.substring(1), 16) : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            String replacement;
            if (value >= 0x80 && value <= 0x9F) {
                char mapped = new String(new byte[] { (byte) value }, WINDOWS_1252).charAt(0);
                replacement = mapped == '\uFFFD' ? " " : "&#" + (int) mapped + ";";
            } else if ((value < 0x20 && value != '\t' && value != '\n' && value != '\r') || value == 0x7F) {
                replacement = " ";
            } else {
                continue;
            }
            if (out == null)
                out = new StringBuilder(latin.length());
            out.append(latin, last, m.start()).append(replacement);
            last = m.end();
        }
        if (out == null)
            return null;
        out.append(latin, last, latin.length());
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static final Pattern XML_PROLOG = Pattern.compile("^<\\?xml\\s[^>]*\\?>");
    private static final Pattern XML_ENCODING = Pattern.compile("encoding\\s*=\\s*([\"'])([^\"']*)\\1");

    /** An XML document converted to UTF-8, with the charset decision that was taken. */
    public static final class XmlResult {
        public final byte[] bytes;
        public final Resolution resolution;

        XmlResult(byte[] bytes, Resolution resolution) {
            this.bytes = bytes;
            this.resolution = resolution;
        }
    }

    /**
     * Tika's XML parser decodes by itself (the encoding of the XML declaration, UTF-8 by default) and never asks the
     * {@link EncodingDetector}, so feeds with a wrong or missing declaration (a Latin-1 RSS declared as UTF-8, UTF-8
     * declared as ISO-8859-1) came out as U+FFFD or mojibake. This decides the charset with the same rules as for HTML
     * (the HTTP charset, else the XML declaration, counts as the declaration) and returns the document as UTF-8 with the
     * declared encoding rewritten to UTF-8. The bytes are returned untouched when Tika would already read them correctly
     * or when the data is binary.
     */
    public static XmlResult xmlToUtf8(byte[] data, String declaredHttp) {
        String prolog = xmlProlog(data);
        String xmlEncoding = null;
        if (prolog != null) {
            Matcher m = XML_ENCODING.matcher(prolog);
            if (m.find())
                xmlEncoding = m.group(2);
        }
        Resolution r = resolve(data, declaredHttp != null ? declaredHttp : xmlEncoding);
        if (r.reason == Reason.BINARY)
            return new XmlResult(data, r);
        boolean utf8 = StandardCharsets.UTF_8.equals(r.charset);
        boolean declaresOther = xmlEncoding != null && !StandardCharsets.UTF_8.equals(normalize(xmlEncoding));
        if (utf8 && r.reason != Reason.BOM && !declaresOther)
            return new XmlResult(data, r);
        byte[] utf8Bytes;
        if (utf8) {
            utf8Bytes = data;
        } else {
            String text = new String(data, r.charset);
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF')
                text = text.substring(1);
            utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        }
        return new XmlResult(rewriteXmlEncoding(utf8Bytes), r);
    }

    /** The XML declaration (ASCII-compatible encodings only, after an optional UTF-8 BOM), or null. */
    private static String xmlProlog(byte[] data) {
        int start = bomCharset(data) == StandardCharsets.UTF_8 ? 3 : 0;
        int end = Math.min(data.length, start + 512);
        if (end - start < 6)
            return null;
        Matcher m = XML_PROLOG.matcher(new String(data, start, end - start, StandardCharsets.ISO_8859_1));
        return m.find() ? m.group() : null;
    }

    private static byte[] rewriteXmlEncoding(byte[] utf8) {
        String prolog = xmlProlog(utf8);
        if (prolog == null)
            return utf8;
        Matcher m = XML_ENCODING.matcher(prolog);
        if (!m.find())
            return utf8;
        String fixed = prolog.substring(0, m.start(2)) + "UTF-8" + prolog.substring(m.end(2));
        int start = bomCharset(utf8) == StandardCharsets.UTF_8 ? 3 : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(utf8.length + 8);
        out.write(utf8, 0, start);
        byte[] head = fixed.getBytes(StandardCharsets.ISO_8859_1);
        out.write(head, 0, head.length);
        out.write(utf8, start + prolog.length(), utf8.length - start - prolog.length());
        return out.toByteArray();
    }

    private static int embeddedUtf8Length(byte[] b, int i) {
        int c = b[i] & 0xff;
        if (i + 1 >= b.length)
            return 0;
        int c1 = b[i + 1] & 0xff;
        if ((c == 0xC2 || c == 0xC3) && c1 >= 0x80 && c1 <= 0xBF)
            return 2;
        if (c == 0xE2 && i + 2 < b.length) {
            int c2 = b[i + 2] & 0xff;
            if (c1 == 0x80 && c2 >= 0x80 && c2 <= 0xBF)
                return 3;
            if ((c1 == 0x82 && c2 == 0xAC) || (c1 == 0x84 && c2 == 0xA2))
                return 3;
        }
        return 0;
    }

    /**
     * A lone 0xC2/0xC3 (A-circumflex, A-tilde) right before punctuation, whitespace or the end is the first half of a
     * multi-byte character that was cut off (e.g. a title truncated by bytes), not an intended letter.
     */
    private static int cutOffLength(byte[] b, int i) {
        if (looksCutOff(b, i))
            return 1;
        int c = b[i] & 0xff;
        if (c < 0xE0 || c > 0xF4)
            return 0;
        int need = c < 0xF0 ? 2 : 3;
        int k = 0;
        while (k < need && i + 1 + k < b.length && (b[i + 1 + k] & 0xC0) == 0x80)
            k++;
        if (k == 0 || k >= need)
            return 0;
        if (i + 1 + k >= b.length)
            return 1 + k;
        int next = b[i + 1 + k] & 0xff;
        return next < 0x80 && !Character.isLetterOrDigit(next) ? 1 + k : 0;
    }

    private static boolean looksCutOff(byte[] b, int i) {
        int c = b[i] & 0xff;
        if (c != 0xC2 && c != 0xC3)
            return false;
        if (i + 1 >= b.length)
            return true;
        int next = b[i + 1] & 0xff;
        return next < 0x80 && !Character.isLetterOrDigit(next);
    }

    /** Length of the well-formed UTF-8 sequence at i, 0 if malformed, -1 if it is cut off by the end of the data. */
    private static int utf8SequenceLength(byte[] b, int i) {
        int c = b[i] & 0xff;
        int need;
        if (c >= 0xC2 && c <= 0xDF)
            need = 1;
        else if (c >= 0xE0 && c <= 0xEF)
            need = 2;
        else if (c >= 0xF0 && c <= 0xF4)
            need = 3;
        else
            return 0;
        for (int j = 1; j <= need; j++) {
            if (i + j >= b.length)
                return -1;
            int cc = b[i + j] & 0xff;
            boolean valid = (cc & 0xC0) == 0x80;
            if (valid && j == 1) {
                if (c == 0xE0 && cc < 0xA0) valid = false;
                else if (c == 0xED && cc > 0x9F) valid = false;
                else if (c == 0xF0 && cc < 0x90) valid = false;
                else if (c == 0xF4 && cc > 0x8F) valid = false;
            }
            if (!valid)
                return 0;
        }
        return need + 1;
    }

    private static final Pattern META_CHARSET_LABEL = Pattern.compile("<meta[^>]{0,400}?charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_:.\\-]+)", Pattern.CASE_INSENSITIVE);

    /** The charset label as the HTML meta tag writes it (lowercase), even if Java has no such charset; empty if none. */
    public static String metaCharsetLabel(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 8192), StandardCharsets.ISO_8859_1);
        Matcher m = META_CHARSET_LABEL.matcher(head);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static Charset metaCharset(byte[] data) {
        try {
            return new HtmlEncodingDetector().detect(new ByteArrayInputStream(data), new Metadata());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** UTF-16/32 are only believable with a BOM or visible NULs; otherwise a declaration is ignored (WHATWG does the same). */
    private static boolean usable(Charset c, boolean looksWide) {
        if (c == null)
            return false;
        String name = c.name();
        if (name.startsWith("UTF-16") || name.startsWith("UTF-32"))
            return looksWide;
        return true;
    }

    /** True when the charset decodes every byte without malformed or unmappable input. */
    public static boolean decodesCleanly(Charset charset, byte[] data) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(data);
        CharBuffer out = CharBuffer.allocate(8192);
        while (true) {
            CoderResult r = decoder.decode(in, out, false);
            if (r.isError())
                return false;
            if (r.isUnderflow())
                return true; // a sequence cut off by the end of the data is tolerated
            out.clear();
        }
    }

    /**
     * Maps a charset label to a Java charset, following the WHATWG treatment of legacy labels
     * (Latin-1 and ASCII really are windows-1252, GB2312/GBK are GB18030, ...). Null if unknown.
     */
    public static Charset normalize(String label) {
        if (label == null)
            return null;
        String l = label.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty())
            return null;
        switch (l) {
            case "iso-8859-1": case "iso8859-1": case "iso_8859-1": case "latin1": case "latin-1": case "l1":
            case "cp819": case "ibm819": case "us-ascii": case "ascii": case "iso-ir-100": case "cp1252":
            case "x-cp1252": case "windows-1252":
                return WINDOWS_1252;
            case "macintosh": case "macroman": case "x-mac-roman": case "mac":
                return Charset.forName("x-MacRoman");
            case "utf8": case "utf-8": case "unicode-1-1-utf-8":
                return StandardCharsets.UTF_8;
            case "gb2312": case "gbk": case "x-gbk": case "gb_2312-80": case "gb18030":
                return Charset.forName("GB18030");
            case "iso-8859-9": case "latin5":
                return Charset.forName("windows-1254");
            case "iso-8859-11": case "tis-620":
                return Charset.forName("windows-874");
            case "shift_jis": case "shift-jis": case "sjis": case "x-sjis": case "ms_kanji": case "windows-31j":
                return Charset.forName("windows-31j");
            case "big5": case "big5-hkscs": case "x-big5":
                return Charset.forName("Big5-HKSCS");
            case "euc-kr": case "ks_c_5601-1987": case "windows-949":
                return Charset.forName("x-windows-949");
            default:
                try {
                    return Charset.forName(l);
                } catch (IllegalArgumentException e) {
                    return null;
                }
        }
    }
}
