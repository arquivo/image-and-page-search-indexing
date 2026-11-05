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
    public void gzipAfterLeadingWhitespaceWithoutContentEncodingIsDecoded() throws Exception {
        byte[] page = html(PT, null, StandardCharsets.UTF_8);
        byte[] raw = concat("  ".getBytes(StandardCharsets.US_ASCII), gzip(page));
        byte[] decoded = org.apache.commons.io.IOUtils.toByteArray(
                pt.arquivo.imagesearch.indexing.utils.HttpBodyDecoder.decode(new ByteArrayInputStream(raw), null, null));
        assertTrue(java.util.Arrays.equals(page, decoded));
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
}
