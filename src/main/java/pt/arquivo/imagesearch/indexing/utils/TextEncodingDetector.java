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
        if (controlRatio(sample, !looksWide) > BINARY_CONTROL_RATIO)
            return new Resolution(WINDOWS_1252, Reason.BINARY);

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

    private static double controlRatio(byte[] b, boolean countNul) {
        if (b.length == 0)
            return 0;
        int control = 0;
        for (byte x : b) {
            int c = x & 0xff;
            if (c == 0 && !countNul)
                continue;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f')
                control++;
        }
        return control / (double) b.length;
    }

    /** True if the bytes contain U+FFFD already, either encoded or as a numeric character reference. */
    public static boolean containsReplacementChar(byte[] b) {
        for (int i = 0; i + 2 < b.length; i++)
            if ((b[i] & 0xff) == 0xEF && (b[i + 1] & 0xff) == 0xBF && (b[i + 2] & 0xff) == 0xBD)
                return true;
        String latin = new String(b, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return latin.contains("&#65533;") || latin.contains("&#xfffd;");
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
            } else if (looksCutOff(data, i)) {
                i++; // dangling first half of a cut-off character: drop it
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
        for (byte x : data) {
            int c = x & 0xff;
            if (c == 0x1B)
                return null;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r')
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
        if (controlRatio(sample, true) > BINARY_CONTROL_RATIO)
            return null;
        byte[] out = data.clone();
        for (int i = 0; i < out.length; i++) {
            int c = out[i] & 0xff;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r')
                out[i] = ' ';
        }
        return out;
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
