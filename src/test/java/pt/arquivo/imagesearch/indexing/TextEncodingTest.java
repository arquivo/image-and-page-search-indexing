package pt.arquivo.imagesearch.indexing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.io.IOUtils;
import org.junit.Test;

import pt.arquivo.imagesearch.indexing.data.TextDocumentData;
import pt.arquivo.imagesearch.indexing.processors.DocumentInformationExtractor;
import pt.arquivo.imagesearch.indexing.utils.ContentSniffer;
import pt.arquivo.imagesearch.indexing.utils.MojibakeRepair;
import pt.arquivo.imagesearch.indexing.utils.HttpBodyDecoder;
import pt.arquivo.imagesearch.indexing.utils.TextEncodingDetector;
import pt.arquivo.imagesearch.indexing.utils.TextEncodingDetector.Reason;
import pt.arquivo.imagesearch.indexing.utils.TextEncodingDetector.Resolution;
import pt.arquivo.imagesearch.indexing.utils.TextQualityChecker;

/**
 * Text decoding tests with synthetic bytes. Also run against the packaged jar by scripts/test-fatjar.sh, where
 * Tika's META-INF/services merging matters.
 */
public class TextEncodingTest {

    private static final String PT = "Informação sobre a administração pública e o coração da cidade de São João. ";

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++)
            sb.append(s);
        return sb.toString();
    }

    private static byte[] html(String text, String charsetInMeta, Charset encodeAs) {
        String meta = charsetInMeta == null ? "" : "<meta charset=\"" + charsetInMeta + "\">";
        return ("<html><head>" + meta + "<title>t</title></head><body><p>" + text + "</p></body></html>").getBytes(encodeAs);
    }

    private static byte[] gzip(byte[] b) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(b);
        }
        return out.toByteArray();
    }

    private static byte[] chunked(byte[] b, int chunk) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < b.length; i += chunk) {
            int n = Math.min(chunk, b.length - i);
            out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(b, i, n);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static String parse(byte[] body, String mime, String declaredCharset) throws Exception {
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
        TextDocumentData result = extractor.parseTextRecord(new ByteArrayInputStream(body), mime, "x.warc.gz",
                "http://example.com/a", "20240101000000", 0, 200, declaredCharset);
        Collection<TextDocumentData> docs = extractor.getEntries().values();
        assertEquals("document indexed", 1, docs.size());
        return String.join(" ", docs.iterator().next().getContent());
    }

    // ---- resolver ----

    @Test
    public void validUtf8BeatsDeclaredLatin1() {
        Resolution r = TextEncodingDetector.resolve(html(PT, null, StandardCharsets.UTF_8), "iso-8859-1");
        assertEquals(StandardCharsets.UTF_8, r.charset);
        assertEquals(Reason.UTF8_VALID, r.reason);
    }

    @Test
    public void latin1BytesDeclaredUtf8AreNotDecodedAsUtf8() {
        Resolution r = TextEncodingDetector.resolve(html(repeat(PT, 5), null, Charset.forName("ISO-8859-1")), "utf-8");
        assertFalse(r.charset.equals(StandardCharsets.UTF_8));
        String decoded = new String(html(repeat(PT, 5), null, Charset.forName("ISO-8859-1")), r.charset);
        assertTrue(decoded, decoded.contains("Informação"));
    }

    @Test
    public void declaredCharsetIsUsedWhenItDecodesCleanly() {
        byte[] b = html(repeat("Привет мир, это тест кодировки. ", 10), null, Charset.forName("windows-1251"));
        Resolution r = TextEncodingDetector.resolve(b, "windows-1251");
        assertEquals(Charset.forName("windows-1251"), r.charset);
        assertEquals(Reason.DECLARED_HTTP, r.reason);
    }

    @Test
    public void metaCharsetIsUsedWhenHeaderIsMissing() {
        byte[] b = html(repeat("Привет мир, это тест кодировки. ", 10), "windows-1251", Charset.forName("windows-1251"));
        Resolution r = TextEncodingDetector.resolve(b, null);
        assertEquals(Charset.forName("windows-1251"), r.charset);
        assertEquals(Reason.DECLARED_META, r.reason);
    }

    @Test
    public void wrongDeclarationIsRejectedWhenBytesDoNotDecode() {
        // GBK text claiming to be UTF-8 and Shift_JIS-incompatible: must not stay UTF-8
        byte[] b = html(repeat("中文网页编码测试，这是一个很长的句子。", 20), null, Charset.forName("GBK"));
        Resolution r = TextEncodingDetector.resolve(b, "utf-8");
        assertFalse(r.charset.equals(StandardCharsets.UTF_8));
        assertTrue(new String(b, r.charset).contains("中文网页编码测试"));
    }

    @Test
    public void bomWins() {
        byte[] body = "﻿olá mundo".getBytes(StandardCharsets.UTF_16LE);
        assertEquals(Reason.BOM, TextEncodingDetector.resolve(body, "utf-8").reason);
    }

    @Test
    public void truncatedMultiByteSequenceAtEndStillUtf8() {
        byte[] full = html(repeat(PT, 3), null, StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 1); // may end inside a sequence
        byte[] mid = "ação ç".getBytes(StandardCharsets.UTF_8);
        mid = java.util.Arrays.copyOf(mid, mid.length - 1);
        assertEquals(StandardCharsets.UTF_8, TextEncodingDetector.resolve(cut, null).charset);
        assertEquals(StandardCharsets.UTF_8, TextEncodingDetector.resolve(mid, null).charset);
    }

    @Test
    public void mostlyUtf8WithStrayLatin1ByteStaysUtf8() {
        byte[] a = repeat(PT, 3).getBytes(StandardCharsets.UTF_8);
        byte[] b = new byte[a.length + 2];
        System.arraycopy(a, 0, b, 0, a.length);
        b[a.length] = (byte) 0xE7;
        b[a.length + 1] = ' ';
        Resolution r = TextEncodingDetector.resolve(b, "utf-8");
        assertEquals(StandardCharsets.UTF_8, r.charset);
        assertEquals(Reason.UTF8_MOSTLY, r.reason);
    }

    @Test
    public void binaryDataIsFlagged() {
        byte[] b = new byte[4000];
        new java.util.Random(7).nextBytes(b);
        for (int i = 0; i < b.length; i += 3)
            b[i] = (byte) (b[i] & 0x07);
        assertEquals(Reason.BINARY, TextEncodingDetector.resolve(b, "utf-8").reason);
    }

    // ---- HTTP body decoding ----

    @Test
    public void decodesChunkedGzip() throws Exception {
        byte[] plain = html(PT, null, StandardCharsets.UTF_8);
        InputStream in = HttpBodyDecoder.decode(new ByteArrayInputStream(chunked(gzip(plain), 100)), "chunked", "gzip");
        assertEquals(new String(plain, StandardCharsets.UTF_8), new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8));
    }

    @Test
    public void chunkedHeaderWithAlreadyDechunkedBody() throws Exception {
        byte[] plain = html(PT, null, StandardCharsets.UTF_8);
        InputStream in = HttpBodyDecoder.decode(new ByteArrayInputStream(plain), "chunked", null);
        assertEquals(new String(plain, StandardCharsets.UTF_8), new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8));
    }

    @Test
    public void decodesZlibAndRawDeflate() throws Exception {
        byte[] plain = html(PT, null, StandardCharsets.UTF_8);
        for (boolean nowrap : new boolean[] { false, true }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(6, nowrap))) {
                d.write(plain);
            }
            InputStream in = HttpBodyDecoder.decode(new ByteArrayInputStream(out.toByteArray()), null, "deflate");
            assertEquals(new String(plain, StandardCharsets.UTF_8), new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void gzipHeaderWithPlainBodyIsPassedThrough() throws Exception {
        byte[] plain = html(PT, null, StandardCharsets.UTF_8);
        InputStream in = HttpBodyDecoder.decode(new ByteArrayInputStream(plain), null, "gzip");
        assertEquals(new String(plain, StandardCharsets.UTF_8), new String(IOUtils.toByteArray(in), StandardCharsets.UTF_8));
    }

    @Test
    public void truncatedGzipKeepsWhatWasDecoded() throws Exception {
        byte[] plain = repeat(PT, 200).getBytes(StandardCharsets.UTF_8);
        byte[] gz = gzip(plain);
        byte[] cut = java.util.Arrays.copyOf(gz, gz.length - 40);
        byte[] got = IOUtils.toByteArray(HttpBodyDecoder.decode(new ByteArrayInputStream(cut), null, "gzip"));
        assertTrue(got.length > 0 && got.length < plain.length);
    }

    @Test
    public void parsesCharsetFromContentType() {
        assertEquals("utf-8", HttpBodyDecoder.charsetFromContentType("text/html; charset=utf-8"));
        assertEquals("ISO-8859-1", HttpBodyDecoder.charsetFromContentType("text/html;charset=\"ISO-8859-1\";x=y"));
        assertEquals(null, HttpBodyDecoder.charsetFromContentType("text/html"));
    }

    // ---- full parse through Tika ----

    @Test
    public void utf8PageDeclaredLatin1() throws Exception {
        String text = parse(html(repeat(PT, 3), null, StandardCharsets.UTF_8), "text/html", "iso-8859-1");
        assertTrue(text, text.contains("Informação sobre a administração"));
        assertFalse(text, text.contains("Ã"));
    }

    @Test
    public void latin1PageDeclaredUtf8() throws Exception {
        String text = parse(html(repeat(PT, 5), null, Charset.forName("ISO-8859-1")), "text/html", "utf-8");
        assertTrue(text, text.contains("Informação sobre a administração"));
        assertFalse(text, text.contains("�"));
    }

    @Test
    public void latin1PageWithoutAnyDeclaration() throws Exception {
        String text = parse(html(repeat(PT, 5), null, Charset.forName("ISO-8859-1")), "text/html", null);
        assertTrue(text, text.contains("coração da cidade"));
    }

    @Test
    public void cyrillicPageInWindows1251WithoutDeclaration() throws Exception {
        String text = parse(html(repeat("Привет мир, это тест кодировки страницы. ", 10), null, Charset.forName("windows-1251")), "text/html", null);
        assertTrue(text, text.contains("Привет мир"));
    }

    @Test
    public void gbkPageWithoutDeclaration() throws Exception {
        String text = parse(html(repeat("中文网页编码测试，这是一个很长的句子。", 20), null, Charset.forName("GBK")), "text/html", null);
        assertTrue(text, text.contains("中文网页编码测试"));
    }

    @Test
    public void plainTextThroughAutoDetectParser() throws Exception {
        String text = parse(repeat(PT, 5).getBytes(Charset.forName("ISO-8859-1")), "text/plain", "utf-8");
        assertTrue(text, text.contains("administração pública"));
    }

    @Test
    public void sameContentWithDifferentEncodingsHasSameDigest() throws Exception {
        byte[] plain = html(repeat(PT, 3), null, StandardCharsets.UTF_8);
        DocumentInformationExtractor a = new DocumentInformationExtractor("test");
        a.parseTextRecord(new ByteArrayInputStream(plain), "text/html", "x", "http://e.com/1", "20240101000000", 0, 200, "utf-8");
        DocumentInformationExtractor b = new DocumentInformationExtractor("test");
        b.parseTextRecord(new ByteArrayInputStream(html(repeat(PT, 3), null, Charset.forName("ISO-8859-1"))), "text/html", "x",
                "http://e.com/2", "20240101000000", 0, 200, null);
        assertEquals(a.getEntries().keySet(), b.getEntries().keySet());
        assertNotNull(a.getEntries().keySet().iterator().next());
    }

    // ---- quality checker ----

    @Test
    public void qualityCheckerFindsMojibake() {
        assertTrue(hasIssue("A informaÃ§Ã£o e a administraÃ§Ã£o pÃºblica", TextQualityChecker.Type.MOJIBAKE_UTF8_AS_LATIN));
        assertTrue(hasIssue("itâ€™s a â€œquoteâ€\u009D", TextQualityChecker.Type.MOJIBAKE_UTF8_AS_LATIN));
        assertTrue(hasIssue("Ð¿Ñ€Ð¸Ð²ÐµÑ‚ Ð¼Ð¸Ñ€", TextQualityChecker.Type.MOJIBAKE_CYRILLIC));
        assertTrue(hasIssue("ä¸­æ–‡ç½‘é¡µ", TextQualityChecker.Type.MOJIBAKE_CJK));
        assertTrue(hasIssue("S�o Paulo", TextQualityChecker.Type.REPLACEMENT_CHAR));
    }

    @Test
    public void qualityCheckerAcceptsCleanText() {
        assertTrue(TextQualityChecker.check(repeat(PT, 5) + "Привет мир 中文网页 ÀÉÎÕÜ", null).isEmpty());
    }

    private static boolean hasIssue(String text, TextQualityChecker.Type type) {
        for (TextQualityChecker.Issue i : TextQualityChecker.check(text, null))
            if (i.type == type)
                return true;
        return false;
    }

    // ---- mixed-encoding pages and recovered bodies ----

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts)
            out.write(p);
        return out.toByteArray();
    }

    @Test
    public void declaredUtf8PageWithStrayLatin1BytesStaysUtf8() throws Exception {
        byte[] stray = { (byte) 0x9b };
        byte[] body = concat("<html><body><a>Frontpage</a> ".getBytes(StandardCharsets.UTF_8), stray,
                " Coil Nailers <p>Fran\u00e7ais Fran\u00e7ais Fran\u00e7ais 34\u00b0 Framing</p> ".getBytes(StandardCharsets.UTF_8), stray,
                " Nails ".getBytes(StandardCharsets.UTF_8), stray, " Rings ".getBytes(StandardCharsets.UTF_8), stray,
                "</body></html>".getBytes(StandardCharsets.UTF_8));
        Resolution r = TextEncodingDetector.resolve(body, "utf8");
        assertEquals(StandardCharsets.UTF_8, r.charset);
        String text = parse(body, "text/html", "utf8");
        assertTrue(text, text.contains("Fran\u00e7ais") && text.contains("34\u00b0"));
        assertFalse(text, text.contains("\u00c3"));
        assertTrue(text, text.contains("Frontpage \u203a Coil Nailers"));
        assertFalse(text, text.contains("\ufffd"));
    }

    @Test
    public void strayLatin1BytesInUtf8PortuguesePageAreRepaired() throws Exception {
        byte[] body = concat("<html><head><meta charset=\"utf-8\"></head><body><p>Licenciou-se em Letras Portugu".getBytes(StandardCharsets.UTF_8),
                new byte[] { (byte) 0xea }, "s pela Universidade Federal. Educa\u00e7\u00e3o e Cl\u00e1ssicos, Mestre".getBytes(StandardCharsets.UTF_8),
                new byte[] { (byte) 0xa0 }, "em Estudos Cl".getBytes(StandardCharsets.UTF_8), new byte[] { (byte) 0xe1 },
                "ssicos. Gest\u00e3o, informa\u00e7\u00e3o, a\u00e7\u00e3o.</p></body></html>".getBytes(StandardCharsets.UTF_8));
        String text = parse(body, "text/html", "utf-8");
        assertTrue(text, text.contains("Portugu\u00eas pela"));
        assertTrue(text, text.contains("Cl\u00e1ssicos."));
        assertFalse(text, text.contains("\ufffd"));
    }

    @Test
    public void utf8UserContentInLatin1PageIsRepaired() throws Exception {
        Charset l1 = Charset.forName("ISO-8859-1");
        byte[] body = concat("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=ISO-8859-1\"></head><body><p>Coment\u00e1rio de Jos\u00e9, informa\u00e7\u00e3o e educa\u00e7\u00e3o p\u00fablica. Autor: Augusto Gon".getBytes(l1),
                "\u00e7alves \u201cB\u00e1rbara\u201d \u20ac5 \u00ba".getBytes(StandardCharsets.UTF_8),
                " Mais texto da p\u00e1gina para a administra\u00e7\u00e3o e gest\u00e3o.</p></body></html>".getBytes(l1));
        String text = parse(body, "text/html", "iso-8859-1");
        assertTrue(text, text.contains("Coment\u00e1rio de Jos\u00e9"));
        assertTrue(text, text.contains("Gon\u00e7alves \u201cB\u00e1rbara\u201d \u20ac5 \u00ba"));
        assertTrue(text, text.contains("administra\u00e7\u00e3o e gest\u00e3o"));
        assertFalse(text, text.contains("\u00c3"));
    }

    @Test
    public void cleanLatin1PageWithoutUtf8SequencesIsNotTouched() throws Exception {
        byte[] body = html("Gest\u00e3o, informa\u00e7\u00e3o \u00c3 \u00c2 e \u00c0 \u00c9 \u00c7 \u00bd", null, Charset.forName("windows-1252"));
        assertEquals(null, TextEncodingDetector.repairEmbeddedUtf8(body, "windows-1252"));
        byte[] pt = html(repeat(PT, 3), null, Charset.forName("ISO-8859-1"));
        assertEquals(null, TextEncodingDetector.repairEmbeddedUtf8(pt, "iso-8859-1"));
    }

    @Test
    public void cutOffCharacterIsDroppedNotTurnedIntoLatin1Letter() throws Exception {
        byte[] body = concat("<html><body><p>Educa\u00e7\u00e3o, gest\u00e3o e informa\u00e7\u00e3o. Desta\u00e7amos o t\u00edtulo, cumplea".getBytes(StandardCharsets.UTF_8),
                new byte[] { (byte) 0xc3 }, "... Portugu".getBytes(StandardCharsets.UTF_8), new byte[] { (byte) 0xea },
                "s e Ci".getBytes(StandardCharsets.UTF_8), new byte[] { (byte) 0xea },
                "ncia.</p></body></html>".getBytes(StandardCharsets.UTF_8));
        String text = parse(body, "text/html", "utf-8");
        assertTrue(text, text.contains("cumplea..."));
        assertTrue(text, text.contains("Portugu\u00eas e Ci\u00eancia"));
    }

    private static byte[] rss(String prolog, String text, Charset encodeAs) {
        return (prolog + "<rss version=\"2.0\"><channel><title>Feed</title><item><title>" + text + "</title><link>http://example.com/1</link><description>"
                + text + "</description></item></channel></rss>").getBytes(encodeAs);
    }

    private static final String XML_PT = "Cr\u00e9dito e educa\u00e7\u00e3o em Portugu\u00eas, Troms\u00f8 e a\u00e7\u00f5es";

    @Test
    public void latin1FeedDeclaredAsUtf8IsDecodedByOurRules() throws Exception {
        byte[] body = rss("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", XML_PT, StandardCharsets.ISO_8859_1);
        String text = parse(body, "application/rss+xml", null);
        assertTrue(text, text.contains("Cr\u00e9dito e educa\u00e7\u00e3o em Portugu\u00eas"));
        assertFalse(text, text.contains("\ufffd"));
    }

    @Test
    public void utf8FeedDeclaredAsLatin1IsNotMojibake() throws Exception {
        byte[] body = rss("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>", XML_PT, StandardCharsets.UTF_8);
        String text = parse(body, "text/xml", null);
        assertTrue(text, text.contains("Cr\u00e9dito e educa\u00e7\u00e3o em Portugu\u00eas"));
    }

    @Test
    public void latin1FeedWithoutDeclarationUsesHttpCharset() throws Exception {
        byte[] body = rss("", XML_PT, StandardCharsets.ISO_8859_1);
        String text = parse(body, "application/rss+xml", "iso-8859-1");
        assertTrue(text, text.contains("Portugu\u00eas"));
    }

    @Test
    public void utf16FeedWithBomIsTranscoded() throws Exception {
        byte[] body = rss("<?xml version=\"1.0\" encoding=\"UTF-16\"?>", XML_PT, Charset.forName("UTF-16"));
        String text = parse(body, "application/xml", null);
        assertTrue(text, text.contains("Portugu\u00eas"));
    }

    @Test
    public void xmlDeclarationIsRewrittenToUtf8() throws Exception {
        byte[] body = rss("<?xml version='1.0' encoding = 'windows-1252' standalone=\"yes\"?>", XML_PT, Charset.forName("windows-1252"));
        TextEncodingDetector.XmlResult r = TextEncodingDetector.xmlToUtf8(body, null);
        String out = new String(r.bytes, StandardCharsets.UTF_8);
        assertTrue(out, out.startsWith("<?xml version='1.0' encoding = 'UTF-8' standalone=\"yes\"?>"));
        assertTrue(out, out.contains(XML_PT));
    }

    @Test
    public void utf8FeedIsLeftUntouched() throws Exception {
        byte[] body = rss("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", XML_PT, StandardCharsets.UTF_8);
        assertTrue(TextEncodingDetector.xmlToUtf8(body, null).bytes == body);
    }

    @Test
    public void c1ControlsInUtf8BecomeWindows1252Characters() throws Exception {
        byte[] body = concat("<html><body><p>Educa\u00e7\u00e3o e gest\u00e3o \u0096 100 melhores empresas, ".getBytes(StandardCharsets.UTF_8),
                "\u0093aspas\u0094 e pre\u00e7o 5\u0080.</p></body></html>".getBytes(StandardCharsets.UTF_8));
        String text = parse(body, "text/html", "utf-8");
        assertTrue(text, text.contains("gest\u00e3o \u2013 100 melhores"));
        assertTrue(text, text.contains("\u201caspas\u201d e pre\u00e7o 5\u20ac."));
        assertFalse(text, text.contains("\ufffd"));
    }

    @Test
    public void c1RepairLeavesLatin1AndPlainUtf8Alone() throws Exception {
        byte[] utf8 = "<html><body><p>Informa\u00e7\u00e3o \u00c2 e educa\u00e7\u00e3o</p></body></html>".getBytes(StandardCharsets.UTF_8);
        assertNull(TextEncodingDetector.repairC1InUtf8(utf8, "utf-8"));
        byte[] latin1 = { '<', 'p', '>', (byte) 0xc2, (byte) 0x96, ' ', 'x', (byte) 0xe7, (byte) 0xe3, 'o', '<', '/', 'p', '>' };
        assertNull(TextEncodingDetector.repairC1InUtf8(latin1, "iso-8859-1"));
    }

    @Test
    public void repairLeavesPureLatin1AndCleanUtf8Alone() throws Exception {
        byte[] latin1 = "<html><body><p>Informa\u00e7\u00e3o e educa\u00e7\u00e3o em Portugu\u00eas</p></body></html>".getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(null, TextEncodingDetector.repairStrayBytes(latin1, null));
        byte[] utf8 = "<html><body><p>Informa\u00e7\u00e3o e educa\u00e7\u00e3o</p></body></html>".getBytes(StandardCharsets.UTF_8);
        assertEquals(null, TextEncodingDetector.repairStrayBytes(utf8, "utf-8"));
    }

    @Test
    public void gbkPageWithUtf8PunctuationKeepsDeclaredCharset() throws Exception {
        String chinese = repeat("\u516d\u5408\u6295\u6ce8\u5b98\u7f51\u516c\u793e", 40);
        byte[] body = concat("<html><head><meta charset=\"gbk\"></head><body><p>".getBytes(StandardCharsets.US_ASCII),
                chinese.getBytes(Charset.forName("GB18030")), " service\u201d model. ".getBytes(StandardCharsets.UTF_8),
                "</p></body></html>".getBytes(StandardCharsets.US_ASCII));
        Resolution r = TextEncodingDetector.resolve(body, "gbk");
        assertEquals(Charset.forName("GB18030"), r.charset);
        assertEquals(Reason.DECLARED_LENIENT, r.reason);
        assertTrue(parse(body, "text/html", "gbk").contains(chinese));
    }

    @Test
    public void binaryDataIsRecognisedEvenWhenFullOfNulsAndDeclaredUtf8() {
        byte[] pattern = { 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xFE };
        byte[] font = new byte[3000];
        for (int i = 0; i < font.length; i++)
            font[i] = pattern[i % pattern.length];
        assertEquals(Reason.BINARY, TextEncodingDetector.resolve(font, "utf-8").reason);
    }

    @Test
    public void utf16WithoutBomIsNotBinary() {
        byte[] b = repeat("Hello world, this is plain text. ", 20).getBytes(StandardCharsets.UTF_16LE);
        assertTrue(TextEncodingDetector.resolve(b, null).reason != Reason.BINARY);
    }

    @Test
    public void undeclaredGzipIsLeftAlone() throws Exception {
        byte[] page = html(PT, null, StandardCharsets.UTF_8);
        byte[] raw = concat("  ".getBytes(StandardCharsets.US_ASCII), gzip(page));
        byte[] decoded = org.apache.commons.io.IOUtils.toByteArray(
                pt.arquivo.imagesearch.indexing.utils.HttpBodyDecoder.decode(new ByteArrayInputStream(raw), null, null));
        assertTrue(java.util.Arrays.equals(raw, decoded));
    }

    @Test
    public void plainBodyWithLeadingWhitespaceIsLeftUntouched() throws Exception {
        byte[] raw = " \r\n  <html>hello</html>".getBytes(StandardCharsets.US_ASCII);
        byte[] decoded = org.apache.commons.io.IOUtils.toByteArray(
                pt.arquivo.imagesearch.indexing.utils.HttpBodyDecoder.decode(new ByteArrayInputStream(raw), null, null));
        assertTrue(java.util.Arrays.equals(raw, decoded));
    }

    private static void writeWarcResponse(ByteArrayOutputStream out, String version, String warcContentType, String url,
            byte[] httpMessage) throws Exception {
        String head = "WARC/" + version + "\r\nWARC-Type: response\r\nWARC-Target-URI: " + url
                + "\r\nWARC-Date: 2016-03-14T11:53:20Z\r\nWARC-Record-ID: <urn:uuid:" + java.util.UUID.randomUUID()
                + ">\r\nContent-Type: " + warcContentType + "\r\nContent-Length: " + httpMessage.length + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(httpMessage);
        out.write("\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    public void warc11ResponseRecordsWithPayloadMimeAreIndexed() throws Exception {
        ByteArrayOutputStream warc = new ByteArrayOutputStream();
        String[][] pages = {{"1.0", "application/http; msgtype=response", "http://a.example/old"},
                {"1.1", "text/html", "http://a.example/new"}};
        for (String[] page : pages) {
            byte[] body = html(PT + page[2], null, StandardCharsets.UTF_8);
            ByteArrayOutputStream http = new ByteArrayOutputStream();
            http.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                    + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            http.write(body);
            writeWarcResponse(warc, page[0], page[1], page[2], http.toByteArray());
        }

        java.io.File f = java.io.File.createTempFile("warc11", ".warc");
        try {
            org.apache.commons.io.FileUtils.writeByteArrayToFile(f, warc.toByteArray());
            DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
            extractor.parseRecord("warc11.warc", f.getPath());
            assertEquals(2, extractor.getEntries().size());
        } finally {
            f.delete();
        }
    }

    // ---- control characters ----

    @Test
    public void formFeedsInPlainTextDoNotBecomeReplacementChars() throws Exception {
        String rfc = repeat("Informational [Page 1]\f\nRFC 1956 Management of the Domain June 1996\n", 30);
        String text = parse(rfc.getBytes(StandardCharsets.US_ASCII), "text/plain", null);
        assertTrue(text, text.contains("[Page 1] RFC 1956"));
        assertFalse(text, text.contains("\uFFFD"));
    }

    @Test
    public void controlBytesInHtmlAreReplacedBySpaces() throws Exception {
        String text = parse(html("Informa\u00e7\u00e3o\u0001sobre\u0000a\u001fcidade " + repeat(PT, 3), null, StandardCharsets.UTF_8),
                "text/html", "utf-8");
        assertTrue(text, text.contains("Informação sobre a cidade"));
        assertFalse(text, text.contains("\uFFFD"));
    }

    @Test
    public void stripControlBytesLeavesCleanBinaryAndWideDataAlone() throws Exception {
        assertNull(TextEncodingDetector.stripControlBytes("clean\ttext\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertNull("ISO-2022 uses ESC", TextEncodingDetector.stripControlBytes("a\u001b$B$3$s\u001b(B".getBytes(StandardCharsets.US_ASCII)));
        assertNull("UTF-16 is full of NULs", TextEncodingDetector.stripControlBytes("hello world\u0001".getBytes(StandardCharsets.UTF_16LE)));
        byte[] binary = new byte[1000];
        for (int i = 0; i < binary.length; i++)
            binary[i] = (byte) (i % 3 == 0 ? 0x01 : 'a');
        assertNull("binary data stays for the binary detection", TextEncodingDetector.stripControlBytes(binary));
        byte[] cleaned = TextEncodingDetector.stripControlBytes((repeat("word ", 100) + "\f page").getBytes(StandardCharsets.US_ASCII));
        assertEquals(repeat("word ", 100) + "  page", new String(cleaned, StandardCharsets.US_ASCII));
    }

    @Test
    public void numericReferencesToControlsDoNotBecomeReplacementCharacters() throws Exception {
        String body = "<html><body><p>Quicktime&#129; v2.0 e&#127;fim&#1; &#x85; de &#150; texto&#153;</p>" + repeat(PT, 3) + "</body></html>";
        String text = parse(body.getBytes(StandardCharsets.US_ASCII), "text/html", "iso-8859-1");
        assertFalse(text, text.contains("\uFFFD"));
        assertTrue(text, text.contains("Quicktime v2.0"));
        assertTrue(text, text.contains("\u2013"));
        assertTrue(text, text.contains("\u2122"));
    }

    @Test
    public void numericReferencesAreLeftAloneInPlainText() throws Exception {
        assertNull(TextEncodingDetector.repairControlReferences("&#169; &#8212; &#65; &amp;".getBytes(StandardCharsets.US_ASCII)));
        assertNull(TextEncodingDetector.repairControlReferences("no references".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void strayEscInTextDoesNotStopControlStripping() throws Exception {
        byte[] data = (repeat("word ", 100) + "mark\u0005 and \u001b stray").getBytes(StandardCharsets.US_ASCII);
        byte[] cleaned = TextEncodingDetector.stripControlBytes(data);
        assertNotNull(cleaned);
        assertFalse(new String(cleaned, StandardCharsets.US_ASCII).contains("\u0005"));
        String text = parse(data, "text/plain", null);
        assertFalse(text, text.contains("\uFFFD"));
    }

    @Test
    public void stripControlBytesReplacesDel() throws Exception {
        byte[] cleaned = TextEncodingDetector.stripControlBytes("<label>\u007f</label><p>Acompanhe</p>".getBytes(StandardCharsets.UTF_8));
        assertEquals("<label> </label><p>Acompanhe</p>", new String(cleaned, StandardCharsets.UTF_8));
    }

    @Test
    public void utf8BomOnLatin1BodyIsDropped() throws Exception {
        byte[] latin = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
                "<html><body>N\u00e3o foi poss\u00edvel carregar</body></html>".getBytes(StandardCharsets.ISO_8859_1));
        byte[] dropped = TextEncodingDetector.dropFalseUtf8Bom(latin);
        assertNotNull(dropped);
        assertEquals('<', dropped[0]);
        byte[] real = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, "N\u00e3o".getBytes(StandardCharsets.UTF_8));
        assertNull("a real UTF-8 file keeps its BOM", TextEncodingDetector.dropFalseUtf8Bom(real));
        assertNull(TextEncodingDetector.dropFalseUtf8Bom("plain".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void utf8BomWithStrayBytesIsRepaired() throws Exception {
        byte[] body = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF },
                "<html><body>Ca\u00e7\u00e3o \u00e9 \u00fatil, n\u00e3o \u00e9 mau, ent\u00e3o ".getBytes(StandardCharsets.UTF_8),
                new byte[] { (byte) 0xF3 }, " fim</body></html>".getBytes(StandardCharsets.UTF_8));
        byte[] repaired = TextEncodingDetector.repairStrayBytes(body, null);
        assertNotNull(repaired);
        String text = new String(repaired, StandardCharsets.UTF_8);
        assertTrue(text.contains("\u00f3 fim"));
        assertFalse(text.contains("\uFFFD"));
    }

    @Test
    public void truncatedThreeByteCharactersAreDropped() throws Exception {
        byte[] body = concat("<html><body>Ca\u00e7\u00e3o \u00e9 \u00fatil, n\u00e3o \u00e9 mau, ent\u00e3o O Grande Naufr\u00e1gio".getBytes(StandardCharsets.UTF_8),
                new byte[] { (byte) 0xE2, (byte) 0x80 }, " (...) Abrir post, \u00e9 \u00fatil</body></html>".getBytes(StandardCharsets.UTF_8));
        byte[] repaired = TextEncodingDetector.repairStrayBytes(body, null);
        assertNotNull(repaired);
        String text = new String(repaired, StandardCharsets.UTF_8);
        assertTrue(text.contains("Naufr\u00e1gio (...)"));
        assertFalse(text.contains("\u00e2"));
    }

    // ---- CJK pages served with a Latin-1 declaration ----

    private static final String JA = "歴史街道ホームページへようこそ。自治体アンケートの実施について、主旨とお願いをご覧ください。";

    @Test
    public void shiftJisDeclaredLatin1IsDecodedAsShiftJis() throws Exception {
        byte[] b = html(repeat(JA, 5), null, Charset.forName("Shift_JIS"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertTrue(r.charset.name(), r.charset.name().equals("Shift_JIS") || r.charset.name().equals("windows-31j"));
        assertTrue(parse(b, "text/html", "iso-8859-1"), parse(b, "text/html", "iso-8859-1").contains("歴史街道"));
    }

    @Test
    public void eucJpDeclaredLatin1IsDecodedAsEucJp() throws Exception {
        byte[] b = html(repeat(JA, 5), null, Charset.forName("EUC-JP"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals("EUC-JP", r.charset.name());
        assertTrue(parse(b, "text/html", "iso-8859-1").contains("自治体アンケート"));
    }

    @Test
    public void portugueseLatin1PageKeepsItsDeclaration() throws Exception {
        byte[] b = html(repeat(PT, 20), null, Charset.forName("ISO-8859-1"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals(Reason.DECLARED_HTTP, r.reason);
        assertTrue(parse(b, "text/html", "iso-8859-1").contains("coração"));
    }

    @Test
    public void mostlyAsciiPortuguesePageWithFewAccentsIsNotCjk() throws Exception {
        String text = repeat("<p>Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor</p>\n", 200)
                + repeat("<li>informa\u00e7\u00e3o</li>", 8);
        byte[] b = html(text, null, Charset.forName("ISO-8859-1"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-15");
        assertEquals(Reason.DECLARED_HTTP, r.reason);
        assertTrue(parse(b, "text/html", "iso-8859-15").contains("informação"));
    }

    private static final String CP850_PT = "Aten\u00e7\u00e3o: a seq\u00fc\u00eancia de a\u00e7\u00f5es segue a ordem. \u00c9 um ficheiro de texto para a Universidade. ";
    private static final String MACROMAN_FR = "La F\u00e9d\u00e9ration fran\u00e7aise des r\u00f4les, o\u00f9 le public d\u00e9cide, \u00e0 la mani\u00e8re d'une \u00e9cole. ";

    @Test
    public void dosCodePageTextIsNotReadAsWindows1252() throws Exception {
        byte[] b = repeat(CP850_PT, 20).getBytes("IBM850");
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals("IBM850", r.charset.name());
        assertTrue(parse(b, "text/plain", "iso-8859-1").contains("seq\u00fc\u00eancia"));
        assertTrue(parse(b, "text/plain", "iso-8859-1").contains(". \u00c9 um ficheiro"));
    }

    @Test
    public void macRomanTextIsNotReadAsWindows1252() throws Exception {
        byte[] b = repeat(MACROMAN_FR, 20).getBytes("x-MacRoman");
        Resolution r = TextEncodingDetector.resolve(b, null);
        assertEquals("x-MacRoman", r.charset.name());
        assertTrue(parse(b, "text/plain", null).contains("o\u00f9 le public"));
    }

    @Test
    public void windows1252TextWithAStrayUndefinedByteKeepsWindows1252() throws Exception {
        byte[] text = repeat(PT, 30).getBytes("windows-1252");
        text[text.length / 2] = (byte) 0x90;
        Resolution r = TextEncodingDetector.resolve(text, "iso-8859-1");
        assertFalse(r.charset.name().equals("IBM850") || r.charset.name().equals("x-MacRoman"));
    }

    // ---- Portuguese regressions seen in the real 54-WARC sample (old code pages must never cost Portuguese) ----

    /** windows-1252 prose (curly quotes) with one UTF-8 quote pasted in, whose 0x9D byte is undefined in windows-1252 (sapo.pt story pages). */
    @Test
    public void windows1252PageWithPastedUtf8QuoteKeepsCurlyQuotes() throws Exception {
        byte[] cp = "<P>\u201cSomething has to be wrong,\u201d Evan said. \u201cThe only time I\u2019ve ever seen it.\u201d\r\n</P>\r\n".getBytes("windows-1252");
        byte[] utf8Quote = "<P>\u201cWow, you\u2019re here, Annie,\u201d offered Tom.</P>".getBytes(StandardCharsets.UTF_8);
        byte[] b = concat("<HTML><BODY>".getBytes(), repeat("", 0).getBytes(), cp, cp, cp, utf8Quote, "</BODY></HTML>".getBytes());
        String text = parse(b, "text/html", null);
        assertTrue(text, text.contains("\u201cSomething has to be wrong,\u201d"));
        assertTrue(text, text.contains("you\u2019re here"));
        assertFalse(text, text.contains("\u00ec") || text.contains("\u00ee") || text.contains("\u00e2\u20ac"));
        assertFalse(text, text.contains("\uFFFD"));
    }

    /** Portuguese windows-1252 page with a junk 0x81 after the capital A (chavesferreira.pt "Á\x81rea de Clientes"). */
    @Test
    public void portuguesePageWithStrayUndefinedByteStaysWindows1252() throws Exception {
        byte[] b = concat("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1252\"></head><body>".getBytes(),
                "<a>\u00c1".getBytes("windows-1252"), new byte[] {(byte) 0x81}, "rea de Clientes</a> ".getBytes(),
                "<p>Farm\u00e1cia Quem Somos Hist\u00f3ria Equipa Produtos Marcas S\u00e3o Jo\u00e3o. Informa\u00e7\u00e3o sobre administra\u00e7\u00e3o p\u00fablica.</p></body></html>".getBytes("windows-1252"));
        String text = parse(b, "text/html", null);
        assertTrue(text, text.contains("\u00c1rea de Clientes"));
        assertTrue(text, text.contains("Farm\u00e1cia"));
        assertTrue(text, text.contains("Hist\u00f3ria"));
        assertTrue(text, text.contains("Informa\u00e7\u00e3o"));
        assertFalse(text, text.contains("\uFFFD"));
        assertFalse(text, text.contains("\u2534") || text.contains("\u00df"));
    }

    @Test
    public void latin1DeclaredPortuguesePageWithUndefinedBytesKeepsItsCurlyQuotes() throws Exception {
        byte[] body = repeat("<p>\u201cInforma\u00e7\u00e3o\u201d \u2013 a\u00e7\u00e3o da administra\u00e7\u00e3o p\u00fablica, e n\u00e3o s\u00f3.</p>", 6).getBytes("windows-1252");
        byte[] b = concat("<html><body>".getBytes(), body, new byte[] {(byte) 0x8D, ' ', (byte) 0x90, ' ', (byte) 0x9D}, "</body></html>".getBytes());
        String text = parse(b, "text/html", "iso-8859-1");
        assertTrue(text, text.contains("\u201cInforma\u00e7\u00e3o\u201d \u2013"));
        assertFalse(text, text.contains("\uFFFD"));
    }

    @Test
    public void undefinedLatinBytesAreDroppedOnlyFromLatinPages() throws Exception {
        byte[] latin = concat(repeat("<p>Administra\u00e7\u00e3o p\u00fablica e inform\u00e7\u00e3o.</p>", 5).getBytes("windows-1252"), new byte[] {(byte) 0x81});
        byte[] dropped = TextEncodingDetector.dropUndefinedLatinBytes(latin, null);
        assertNotNull(dropped);
        assertEquals(latin.length - 1, dropped.length);
        byte[] sjis = html(repeat(JA, 3), null, Charset.forName("windows-31j"));
        assertNull(TextEncodingDetector.dropUndefinedLatinBytes(sjis, "iso-8859-1"));
    }

    /** An OLE Word document served as text/doc (146battalion.ca): editing its bytes made Tika's OfficeParser fail. */
    @Test
    public void binaryDocumentWithManyNulsIsLeftAlone() throws Exception {
        byte[] ole = new byte[4096];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, ole, 0, magic.length);
        for (int i = 8; i < ole.length; i += 7) {
            ole[i] = (byte) 0x81;
            ole[i + 1] = (byte) 0xE9;
        }
        assertNull(TextEncodingDetector.dropUndefinedLatinBytes(ole, "iso-8859-1"));
    }

    /** Mac Roman Portuguese page that says charset=macintosh (espenalva.pt). */
    @Test
    public void macRomanPortuguesePageIsDecodedAsMacRoman() throws Exception {
        String text = repeat("<p>11 de Outubro (ter\u00e7a-feira) Rece\u00e7\u00e3o de boas vindas, associa\u00e7\u00e3o de pais e log\u00f3tipo da escola.</p>", 6);
        byte[] b = html(text, "macintosh", Charset.forName("x-MacRoman"));
        String out = parse(b, "text/html", null);
        assertTrue(out, out.contains("ter\u00e7a-feira"));
        assertTrue(out, out.contains("Rece\u00e7\u00e3o"));
    }

    /** Portuguese DOS code page 860 (amadeus.inesc.pt): 0x90 is É, 0x91 is À, 0x8F is Â, none of which CP850 gets right. */
    @Test
    public void portugueseDosCodePage860IsRecognised() throws Exception {
        String text = repeat("<p>Foi no passado. \u00c9 um ficheiro extenso. \u00c0s 18h na sala. \u00c9 que o \u00c2ngelo disse: informa\u00e7\u00e3o p\u00fablica.</p>\n", 4);
        byte[] b = html(text, null, Charset.forName("IBM860"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals("IBM860", r.charset.name());
        String out = parse(b, "text/html", "iso-8859-1");
        assertTrue(out, out.contains("\u00c9 um ficheiro"));
        assertTrue(out, out.contains("\u00c0s 18h"));
        assertTrue(out, out.contains("\u00c2ngelo"));
    }

    /** Mac Roman French page with guillemets, non-breaking spaces and typographic apostrophes (paris.org). */
    @Test
    public void macRomanFrenchPageWithTypographicPunctuationIsRecognised() throws Exception {
        String text = repeat("<p>Musiciens de l\u2019Orchestre de l\u2019Op\u00e9ra \u00ab\u00a0Salle Pleyel\u00a0\u00bb o\u00f9 le public d\u00e9cide, \u00e0 la mani\u00e8re d\u2019une \u00e9cole.</p>\n", 4);
        byte[] b = html(text, null, Charset.forName("x-MacRoman"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals("x-MacRoman", r.charset.name());
    }

    @Test
    public void shiftJisPageDeclaredLatin1IsNotReadAsOldCodePage() throws Exception {
        byte[] b = html("<p>Home page </p>" + repeat(JA, 2), null, Charset.forName("windows-31j"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertFalse(r.charset.name(), r.charset.name().equals("IBM850") || r.charset.name().equals("x-MacRoman"));
    }

    @Test
    public void plausibilityPrefersRealProseOverNoise() {
        assertTrue(TextEncodingDetector.plausibility("a\u00e7\u00e3o, informa\u00e7\u00f5es, \u00c9 um ficheiro") > 0.9);
        assertTrue(TextEncodingDetector.plausibility("\u00ecSomething has to be wrong,\u00ee Evan said") < 0.5);
        assertTrue(TextEncodingDetector.plausibility("\u2534\u00fc\u00df\u00f7\u00a7\u00b6\u2591") < 0.5);
    }

    /** All-caps looking noise made of a single 0x8F byte between capitals (a forum page, ugbase.eu) is not a code page. */
    @Test
    public void repeatedUndefinedByteBetweenCapitalsIsNotACodePage() throws Exception {
        byte[] b = concat("<html><body><p>Releases (Moderator: ".getBytes(), new byte[] {(byte) 0x8F}, "T".getBytes(), new byte[] {(byte) 0x8F},
                "H".getBytes(), new byte[] {(byte) 0x8F}, "3".getBytes(), new byte[] {(byte) 0x8F}, "R".getBytes(), new byte[] {(byte) 0x8F},
                "M".getBytes(), new byte[] {(byte) 0x8F}, "4".getBytes(), new byte[] {(byte) 0x8F}, "L".getBytes(), new byte[] {(byte) 0x8F},
                ") auto_getmats.cs</p></body></html>".getBytes());
        Resolution r = TextEncodingDetector.resolve(b, null);
        assertFalse(r.charset.name(), r.charset.name().equals("IBM850") || r.charset.name().equals("x-MacRoman"));
    }

    // ---- content sniffing, code files, source U+FFFD, Portuguese mojibake repair ----

    private static int indexedDocuments(byte[] body, String mime) throws Exception {
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
        extractor.parseTextRecord(new ByteArrayInputStream(body), mime, "x.warc.gz", "http://example.com/a", "20240101000000", 0, 200, null);
        return extractor.getEntries().size();
    }

    private static byte[] zipOf(String name, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            z.putNextEntry(new java.util.zip.ZipEntry(name));
            z.write(content);
            z.closeEntry();
        }
        return out.toByteArray();
    }

    /** A 264-byte hit-counter GIF served as text/html (mcs.net): 1.9% control bytes, under the BINARY ratio. */
    @Test
    public void gifServedAsTextIsNotIndexed() throws Exception {
        byte[] gif = new byte[264];
        byte[] header = "GIF89a".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, gif, 0, header.length);
        for (int i = header.length; i < gif.length; i += 2)
            gif[i] = (byte) (0x20 + i % 90);
        gif[100] = 0x05;
        gif[101] = 0x03;
        gif[102] = 0x01;
        gif[103] = 0x02;
        gif[104] = 0x04;
        assertEquals(0, indexedDocuments(gif, "text/html"));
        assertEquals(0, indexedDocuments(gif, "text/plain"));
    }

    @Test
    public void textStartingLikeAnImageMagicIsStillText() throws Exception {
        assertEquals(ContentSniffer.Kind.NONE, ContentSniffer.sniff("BMW is a car maker. Informa\u00e7\u00e3o".getBytes("UTF-8")));
        assertEquals(ContentSniffer.Kind.NONE, ContentSniffer.sniff("GIF files are images".getBytes("UTF-8")));
        assertEquals(ContentSniffer.Kind.BINARY, ContentSniffer.sniff("GIF87a\u0001".getBytes("ISO-8859-1")));
        assertEquals(ContentSniffer.Kind.CONTAINER, ContentSniffer.sniff("%PDF-1.4 x".getBytes("ISO-8859-1")));
    }

    @Test
    public void zipServedAsTextHtmlIsUnpackedNotReadAsText() throws Exception {
        byte[] zip = zipOf("LEIAME.TXT", ("Informa\u00e7\u00e3o sobre a administra\u00e7\u00e3o p\u00fablica e o cora\u00e7\u00e3o da cidade. ").getBytes("UTF-8"));
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
        extractor.parseTextRecord(new ByteArrayInputStream(zip), "text/html", "x.warc.gz", "http://example.com/a.zip", "20240101000000", 0, 200, null);
        String text = String.join(" ", extractor.getEntries().values().iterator().next().getContent());
        assertTrue(text, text.contains("administra\u00e7\u00e3o p\u00fablica"));
    }

    @Test
    public void gzipServedAsTextHtmlIsUnpackedNotReadAsText() throws Exception {
        byte[] gz = gzip(html(repeat(PT, 3), null, StandardCharsets.UTF_8));
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
        extractor.parseTextRecord(new ByteArrayInputStream(gz), "text/html", "x.warc.gz", "http://example.com/a.html.gz", "20240101000000", 0, 200, null);
        assertEquals(1, extractor.getEntries().size());
        String text = String.join(" ", extractor.getEntries().values().iterator().next().getContent());
        assertTrue(text, text.contains("Informa\u00e7\u00e3o sobre a administra\u00e7\u00e3o"));
    }

    @Test
    public void codeFilesAreRecognisedByExtensionIgnoringQueryStrings() {
        assertTrue(DocumentInformationExtractor.isCodeFile("https://www.space.com/resources/js/jquery.min.js?097e36c", "application/javascript"));
        assertTrue(DocumentInformationExtractor.isCodeFile("https://weltbet11.com/media/common_desktop.js?1711022858", null));
        assertTrue(DocumentInformationExtractor.isCodeFile("http://example.com/src/Main.java", "text/plain"));
        assertTrue(DocumentInformationExtractor.isCodeFile("http://example.com/src/util.PY#L10", "text/plain"));
        assertTrue(DocumentInformationExtractor.isCodeFile("http://example.com/lib/stdio.h", null));
        assertTrue(DocumentInformationExtractor.isCodeFile("http://example.com/a", "text/x-python; charset=utf-8"));
        assertTrue(DocumentInformationExtractor.isCodeFile("http://example.com/style.css?v=2", "text/css"));
    }

    @Test
    public void pagesAreNotMistakenForCode() {
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/index.php?file=a.js", "text/html"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/pt/noticia.html", "text/html"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/js/", "text/html"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/readme.txt", "text/plain"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/a.json", "application/json"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://www.example.pt/", "text/html"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/cgi-bin/forum.pl?action=post", "text/html"));
        assertFalse(DocumentInformationExtractor.isCodeFile("https://github.com/user/repo/blob/master/plot_fits.py", "text/html; charset=utf-8"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/cgi-bin/search.pl?q=a", "text/plain"));
    }

    @Test
    public void sourceReplacementCharacterIsDetectedInBytesAndReferences() throws Exception {
        assertTrue(TextEncodingDetector.containsReplacementChar("coment\u00ef\u00bf\u00bdrios".getBytes("ISO-8859-1")));
        assertTrue(TextEncodingDetector.containsReplacementChar("<p>coment\uFFFDrios</p>".getBytes("UTF-8")));
        assertTrue(TextEncodingDetector.containsReplacementChar("Par&#65533;quia".getBytes("ISO-8859-1")));
        assertTrue(TextEncodingDetector.containsReplacementChar("Par&#xFFFD;quia".getBytes("ISO-8859-1")));
        assertTrue(TextEncodingDetector.containsReplacementChar("Par&#xfffd quia".getBytes("ISO-8859-1")));
        assertFalse(TextEncodingDetector.containsReplacementChar("Par&#655330;quia &#xfffd1; &#233;".getBytes("ISO-8859-1")));
        assertFalse(TextEncodingDetector.containsReplacementChar("Paróquia".getBytes("UTF-8")));
    }

    @Test
    public void doubleEncodedPortugueseIsRepaired() {
        assertEquals("Cidad\u00e3os Auto-Mobilizados", MojibakeRepair.repair("Cidad\u00c3\u00a3os Auto-Mobilizados"));
        assertEquals("Ol\u00e1 \u00faltimos", MojibakeRepair.repair("Ol\u00c3\u00a1 \u00c3\u00baltimos"));
        assertEquals("fra\u00e7\u00e3o", MojibakeRepair.repair("fra\u00c3\u00a7\u00c3\u00a3o"));
        assertEquals("\u00ab Em Madrid \u00bb", MojibakeRepair.repair("\u00c2\u00ab Em Madrid \u00c2\u00bb"));
        assertEquals("\u201cINFO\u201d", MojibakeRepair.repair("\u00e2\u20ac\u0153INFO\u00e2\u20ac\u009d"));
        assertEquals("\u201cINFO\u201d que", MojibakeRepair.repair("\u00e2\u20ac\u0153INFO\u00e2\u20ac que"));
        assertEquals("it\u2019s", MojibakeRepair.repair("it\u00e2\u20ac\u2122s"));
        assertEquals("fra\u00e7\u00e3o", MojibakeRepair.repair("fra\u00c3\u0083\u00c2\u00a7\u00c3\u0083\u00c2\u00a3o"));
    }

    @Test
    public void correctPortugueseIsLeftUntouched() {
        String ok = "Cidad\u00e3os, A\u00c7\u00c3O, \u00c9 um ficheiro \u00e0s 18h; 1\u00aa edi\u00e7\u00e3o \u2013 \u201ccita\u00e7\u00e3o\u201d \u00c2ngelo.";
        assertTrue(MojibakeRepair.repair(ok) == ok);
        String lone = "A\u00c3 e \u00c2 sozinhos, \u00c3O";
        assertEquals(lone, MojibakeRepair.repair(lone));
    }

    private static String portuguesePage(String paragraph) {
        return "<html><head><title>Not\u00edcias</title></head><body><p>" + repeat(paragraph, 6) + "</p></body></html>";
    }

    @Test
    public void portuguesePageWithDoubleEncodedCommentIsRepaired() throws Exception {
        String sentence = "A administra\u00e7\u00e3o p\u00fablica portuguesa publicou hoje o relat\u00f3rio sobre a educa\u00e7\u00e3o e a sa\u00fade das crian\u00e7as. ";
        byte[] b = (portuguesePage(sentence) + "<p>Coment\u00c3\u00a1rio de um leitor: os cidad\u00c3\u00a3os n\u00c3\u00a3o concordam.</p>").getBytes("UTF-8");
        String text = parse(b, "text/html", "utf-8");
        assertTrue(text, text.contains("os cidad\u00e3os n\u00e3o concordam"));
        assertTrue(text, text.contains("Coment\u00e1rio de um leitor"));
        assertFalse(text, text.contains("\u00c3"));
    }

    @Test
    public void englishPageWithDoubleEncodedTextIsLeftAlone() throws Exception {
        String sentence = "The committee published its annual report on public administration, education and health for the children of the region today. ";
        byte[] b = (portuguesePage(sentence) + "<p>A reader wrote: the caf\u00c3\u00a9 near the station was nice.</p>").getBytes("UTF-8");
        String text = parse(b, "text/html", "utf-8");
        assertTrue(text, text.contains("caf\u00c3\u00a9"));
    }

    // ---- charset dataset for pywb ----

    private static java.util.List<String[]> datasetRows(byte[] body, String mime, String httpCharset, int agreePercent) throws Exception {
        java.io.StringWriter out = new java.io.StringWriter();
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("bad-chars-warc");
        extractor.setCharsetDataset(out, agreePercent);
        extractor.parseTextRecord(new ByteArrayInputStream(body), mime, "merged-0001.warc.gz", "http://www.example.pt/a?b=1", "20240101000000", 42, 200, httpCharset);
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String line : out.toString().split("\n"))
            if (!line.isEmpty())
                rows.add(line.split("\t", -1));
        return rows;
    }

    private static final int HTTP = 6, META = 7, DETECTED = 8, REASON = 9, CONFIDENCE = 10, MIXED = 11, AGREES = 12;

    @Test
    public void datasetRowHasTheAgreedColumns() throws Exception {
        java.util.List<String[]> rows = datasetRows(html(repeat(PT, 5), null, StandardCharsets.UTF_8), "text/html", "iso-8859-1", 0);
        assertEquals(1, rows.size());
        String[] r = rows.get(0);
        assertEquals(13, r.length);
        assertEquals("http://www.example.pt/a?b=1", r[0]);
        assertEquals("20240101000000", r[1]);
        assertEquals("(pt,example,)/a?b=1", r[2]);
        assertEquals("bad-chars-warc", r[3]);
        assertEquals("merged-0001.warc.gz", r[4]);
        assertEquals("42", r[5]);
        assertEquals("iso-8859-1", r[HTTP]);
        assertEquals("", r[META]);
        assertEquals("utf-8", r[DETECTED]);
        assertEquals("UTF8_VALID", r[REASON]);
        assertEquals("high", r[CONFIDENCE]);
        assertEquals("0", r[MIXED]);
        assertEquals("0", r[AGREES]);
        assertTrue(CharsetDatasetRowHeaderMatches(r.length));
    }

    private static boolean CharsetDatasetRowHeaderMatches(int columns) {
        return pt.arquivo.imagesearch.indexing.utils.CharsetDatasetRow.HEADER.split("\t").length == columns;
    }

    @Test
    public void pageWithoutAnyCharsetLeavesReportedFieldsEmpty() throws Exception {
        java.util.List<String[]> rows = datasetRows(html(repeat(PT, 5), null, StandardCharsets.UTF_8), "text/html", null, 0);
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0)[HTTP]);
        assertEquals("", rows.get(0)[META]);
        assertEquals("0", rows.get(0)[AGREES]);
    }

    @Test
    public void metaCharsetIsReportedAsWrittenAndAliasesAgree() throws Exception {
        java.util.List<String[]> rows = datasetRows(html(repeat(PT, 5), "macintosh", Charset.forName("x-MacRoman")), "text/html", null, 100);
        assertEquals(1, rows.size());
        assertEquals("macintosh", rows.get(0)[META]);
        assertEquals("macintosh", rows.get(0)[DETECTED]);
        assertEquals("1", rows.get(0)[AGREES]);
        java.util.List<String[]> latin = datasetRows(html(repeat(PT, 5), null, Charset.forName("windows-1252")), "text/html", "iso-8859-1", 100);
        assertEquals("1", latin.get(0)[AGREES]);
    }

    @Test
    public void agreeingPagesAreSampledAndAsciiPagesAreLeftOut() throws Exception {
        byte[] page = html(repeat(PT, 5), null, StandardCharsets.UTF_8);
        assertEquals(0, datasetRows(page, "text/html", "utf-8", 0).size());
        assertEquals(1, datasetRows(page, "text/html", "utf-8", 100).size());
        assertEquals(0, datasetRows(html("Plain ASCII page about nothing in particular.", null, StandardCharsets.US_ASCII), "text/html", null, 100).size());
    }

    @Test
    public void repairedMojibakePagesAreNeverInTheDataset() throws Exception {
        String sentence = "A administra\u00e7\u00e3o p\u00fablica portuguesa publicou hoje o relat\u00f3rio sobre a educa\u00e7\u00e3o e a sa\u00fade das crian\u00e7as. ";
        byte[] b = (portuguesePage(sentence) + "<p>os cidad\u00c3\u00a3os n\u00c3\u00a3o concordam.</p>").getBytes("UTF-8");
        assertEquals(0, datasetRows(b, "text/html", "iso-8859-1", 100).size());
    }

    @Test
    public void pagesMixingEncodingsAreFlaggedMixed() throws Exception {
        byte[] cp = "<p>\u201cInforma\u00e7\u00e3o\u201d da administra\u00e7\u00e3o p\u00fablica e n\u00e3o s\u00f3.</p>".getBytes("windows-1252");
        byte[] utf8 = "<p>Educa\u00e7\u00e3o \u201csa\u00fade\u201d</p>".getBytes(StandardCharsets.UTF_8);
        byte[] b = concat("<html><body>".getBytes(), cp, cp, cp, utf8, "</body></html>".getBytes());
        java.util.List<String[]> rows = datasetRows(b, "text/html", "iso-8859-1", 0);
        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0)[MIXED]);
        assertEquals("low", rows.get(0)[CONFIDENCE]);
    }

    // ---- Portuguese repair decided by the body alone; markup is not code ----

    @Test
    public void englishTitleOfAPortuguesePageIsRepairedToo() throws Exception {
        String sentence = "A administra\u00e7\u00e3o p\u00fablica portuguesa publicou hoje o relat\u00f3rio sobre a educa\u00e7\u00e3o e a sa\u00fade das crian\u00e7as. ";
        byte[] b = ("<html><head><title>Caf\u00c3\u00a9 Heavy Metal</title></head><body><p>" + repeat(sentence, 6) + "</p></body></html>").getBytes("UTF-8");
        DocumentInformationExtractor extractor = new DocumentInformationExtractor("test");
        extractor.parseTextRecord(new ByteArrayInputStream(b), "text/html", "x.warc.gz", "http://example.com/a", "20240101000000", 0, 200, "utf-8");
        assertEquals("Caf\u00e9 Heavy Metal", extractor.getEntries().values().iterator().next().getTitle().get(0));
    }

    @Test
    public void markupLanguagesAreNotCode() {
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/README.md", "text/markdown"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/doc.rst", "text/plain"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/paper.tex", "text/plain"));
        assertFalse(DocumentInformationExtractor.isCodeFile("http://example.com/page.xhtml", "application/xhtml+xml"));
    }

    @Test
    public void iso2022JpPageIsNotBinaryEvenWhenDeclaredLatin1() throws Exception {
        byte[] b = html(repeat(JA, 3), null, Charset.forName("ISO-2022-JP"));
        Resolution r = TextEncodingDetector.resolve(b, "iso-8859-1");
        assertEquals("ISO-2022-JP", r.charset.name());
        assertTrue(parse(b, "text/html", "iso-8859-1").contains("歴史街道"));
    }

    @Test
    public void textWithStrayNulBytesIsNotBinary() throws Exception {
        String text = repeat(PT, 30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            sb.append(text.charAt(i));
            if (i % 25 == 0)
                sb.append('\u0000');
        }
        byte[] b = html(sb.toString(), null, Charset.forName("ISO-8859-1"));
        assertTrue(TextEncodingDetector.resolve(b, "iso-8859-1").reason != Reason.BINARY);
        assertTrue(parse(b, "text/html", "iso-8859-1").contains("coração"));
    }

    @Test
    public void randomBinaryStillCountsAsBinary() {
        byte[] b = new byte[4000];
        new java.util.Random(7).nextBytes(b);
        assertEquals(Reason.BINARY, TextEncodingDetector.resolve(b, "iso-8859-1").reason);
    }
}
