package pt.arquivo.imagesearch.indexing.utils;

import java.nio.charset.Charset;
import java.util.Locale;

/**
 * One line of the optional charset dataset used to tune the pywb replayer: what the page reports about its encoding
 * against what the indexer detected. Tab separated, one line per captured URL.
 */
public final class CharsetDatasetRow {

    public static final String HEADER = "url\ttimestamp\tsurt\tcollection\twarc\toffset\thttp_charset\tmeta_charset\tdetected_charset\treason\tconfidence\tmixed\tagrees";

    private CharsetDatasetRow() {
    }

    /** True when the reported label (HTTP header, else meta tag) names the same charset as the detected one, aliases included. */
    public static boolean agrees(String httpCharset, String metaCharset, String detectedJavaName) {
        String reported = reported(httpCharset, metaCharset);
        if (reported.isEmpty() || detectedJavaName == null)
            return false;
        Charset a = TextEncodingDetector.normalize(reported);
        Charset b = TextEncodingDetector.normalize(detectedJavaName);
        return a != null && a.equals(b);
    }

    public static String reported(String httpCharset, String metaCharset) {
        if (httpCharset != null && !httpCharset.trim().isEmpty())
            return httpCharset.trim();
        return metaCharset == null ? "" : metaCharset.trim();
    }

    /** How much the decision can be trusted as ground truth: high, medium or low. */
    public static String confidence(String reason) {
        switch (reason) {
            case "BOM": case "UTF8_VALID": case "DECLARED_HTTP": case "DECLARED_META":
                return "high";
            case "UTF8_MOSTLY": case "DECLARED_LENIENT":
                return "medium";
            default:
                return "low";
        }
    }

    /** Standard (WHATWG/IANA) label for a Java charset name. */
    public static String label(String javaName) {
        if (javaName == null)
            return "";
        switch (javaName) {
            case "windows-31j": return "shift_jis";
            case "x-windows-949": return "euc-kr";
            case "x-MacRoman": return "macintosh";
            case "Big5-HKSCS": return "big5";
            case "x-windows-874": return "windows-874";
            default: return javaName.toLowerCase(Locale.ROOT);
        }
    }

    public static String format(String url, String timestamp, String surt, String collection, String warc, long offset,
            String httpCharset, String metaCharset, String detectedJavaName, String reason, boolean mixed, boolean agrees) {
        return clean(url) + "\t" + clean(timestamp) + "\t" + clean(surt) + "\t" + clean(collection) + "\t" + clean(warc) + "\t" + offset
                + "\t" + clean(httpCharset == null ? "" : httpCharset.trim().toLowerCase(Locale.ROOT))
                + "\t" + clean(metaCharset == null ? "" : metaCharset.trim().toLowerCase(Locale.ROOT))
                + "\t" + label(detectedJavaName) + "\t" + reason + "\t" + confidence(reason) + "\t" + (mixed ? 1 : 0) + "\t" + (agrees ? 1 : 0);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
