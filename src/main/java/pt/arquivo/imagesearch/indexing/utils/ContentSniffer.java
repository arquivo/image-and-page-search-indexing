package pt.arquivo.imagesearch.indexing.utils;

/**
 * Recognises payloads by their leading magic bytes, for records whose reported type says text (a GIF counter served as
 * text/html, a CAPTCHA served as text/plain, a Word file served as text/doc). Only formats whose signature cannot start
 * a text document are listed, so a match is never a false positive.
 */
public final class ContentSniffer {

    public enum Kind {
        /** Not recognised: treat as text. */
        NONE,
        /** Image, font, audio or video: nothing to index. */
        BINARY,
        /** PDF, Office, zip, gzip...: a format Tika can unpack, so it must not be read as text. */
        CONTAINER
    }

    private ContentSniffer() {
    }

    public static Kind sniff(byte[] d) {
        if (d == null || d.length < 4)
            return Kind.NONE;
        if (startsWith(d, "GIF87a") || startsWith(d, "GIF89a") || startsWith(d, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)
                || startsWith(d, 0xFF, 0xD8, 0xFF) || startsWith(d, 'I', 'I', '*', 0x00) || startsWith(d, 'M', 'M', 0x00, '*')
                || startsWith(d, 0x00, 0x00, 0x01, 0x00) || startsWith(d, 'w', 'O', 'F', 'F') || startsWith(d, 'w', 'O', 'F', '2')
                || startsWith(d, 'O', 'T', 'T', 'O') || startsWith(d, 0x00, 0x01, 0x00, 0x00) || startsWith(d, 'O', 'g', 'g', 'S')
                || startsWith(d, 'f', 'L', 'a', 'C') || startsWith(d, 'I', 'D', '3') || startsWith(d, 0x1A, 0x45, 0xDF, 0xA3)
                || startsWith(d, 0x7F, 'E', 'L', 'F') || isRiffMedia(d) || isIsoMedia(d) || isBmp(d))
            return Kind.BINARY;
        if (hasDamagedImageHeader(d))
            return Kind.BINARY;
        if (startsWith(d, "%PDF-") || startsWith(d, 'P', 'K', 0x03, 0x04) || startsWith(d, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
                || startsWith(d, 0x1F, 0x8B) || isBzip2(d) || startsWith(d, '7', 'z', 0xBC, 0xAF, 0x27, 0x1C)
                || startsWith(d, 'R', 'a', 'r', '!', 0x1A, 0x07) || startsWith(d, 0xFD, '7', 'z', 'X', 'Z', 0x00))
            return Kind.CONTAINER;
        return Kind.NONE;
    }

    private static final java.util.regex.Pattern JSONP_START = java.util.regex.Pattern.compile("^[A-Za-z_$][\\w$.]*\\(\\s*[\\[{]");

    /**
     * True if the whole payload is one JSON value (an API response, a manifest, a tracker call), or one wrapped in a call
     * (JSONP). That is data for programs, not text for people. The ends are checked first, so ordinary text never reaches
     * the parser; a text that merely starts with "[" and ends with "]" ("[Refr\u00e3o] ... [Fim]") fails the parse.
     */
    public static boolean isJsonData(byte[] data) {
        if (data == null || data.length < 2)
            return false;
        int start = 0, end = data.length;
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF)
            start = 3;
        while (start < end && isSpace(data[start]))
            start++;
        while (end > start && isSpace(data[end - 1]))
            end--;
        if (end - start < 2)
            return false;
        int first = data[start] & 0xFF;
        int last = data[end - 1] & 0xFF;
        if ((first == '{' && last == '}') || (first == '[' && last == ']'))
            return parses(data, start, end);
        if (first >= 0x80 || !(Character.isLetter(first) || first == '_' || first == '$'))
            return false;
        // JSONP: name({...}) or name([...]) with an optional semicolon
        if (last == ';' && end - start > 3 && (data[end - 2] & 0xFF) == ')')
            end--;
        if ((data[end - 1] & 0xFF) != ')')
            return false;
        String head = new String(data, start, Math.min(end - start, 120), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = JSONP_START.matcher(head);
        if (!m.find())
            return false;
        int open = start + m.end() - 1;
        return parses(data, open, end - 1);
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static boolean parses(byte[] data, int from, int to) {
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream(data, from, to - from), java.nio.charset.StandardCharsets.UTF_8))) {
            reader.skipValue();
            return reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Images whose first bytes were mangled on their way into the archive (a PNG's 0x89 or a JPEG's FF D8 FF E0 turned into the
     * UTF-8 bytes of U+FFFD), so the usual signature is gone but the rest of the header is still there. A NUL after "JFIF"
     * or a PNG signature after a few bytes cannot start a text.
     */
    private static boolean hasDamagedImageHeader(byte[] d) {
        int limit = Math.min(d.length, 40);
        for (int i = 0; i < 16 && i + 8 <= limit; i++)
            if (d[i] == 'P' && d[i + 1] == 'N' && d[i + 2] == 'G' && d[i + 3] == 0x0D && d[i + 4] == 0x0A && d[i + 5] == 0x1A && d[i + 6] == 0x0A)
                return true;
        for (int i = 0; i < 16 && i + 5 <= limit; i++)
            if (((d[i] == 'J' && d[i + 1] == 'F' && d[i + 2] == 'I' && d[i + 3] == 'F') || (d[i] == 'E' && d[i + 1] == 'x' && d[i + 2] == 'i' && d[i + 3] == 'f'))
                    && d[i + 4] == 0)
                return true;
        return false;
    }

    private static boolean isBzip2(byte[] d) {
        return d.length > 9 && d[0] == 'B' && d[1] == 'Z' && d[2] == 'h' && d[3] >= '1' && d[3] <= '9' && (d[4] & 0xff) == 0x31
                && (d[5] & 0xff) == 0x41 && (d[6] & 0xff) == 0x59;
    }

    private static boolean isRiffMedia(byte[] d) {
        if (d.length < 12 || !startsWith(d, "RIFF"))
            return false;
        String kind = new String(d, 8, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        return kind.equals("WEBP") || kind.equals("WAVE") || kind.equals("AVI ");
    }

    /** MP4/MOV/HEIC: a box size, then "ftyp". */
    private static boolean isIsoMedia(byte[] d) {
        return d.length > 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p';
    }

    /** "BM" alone could open a text, so the reserved fields and the DIB header size are checked as well. */
    private static boolean isBmp(byte[] d) {
        if (d.length < 26 || d[0] != 'B' || d[1] != 'M' || d[6] != 0 || d[7] != 0 || d[8] != 0 || d[9] != 0)
            return false;
        int header = (d[14] & 0xff) | (d[15] & 0xff) << 8;
        return header == 12 || header == 40 || header == 52 || header == 56 || header == 64 || header == 108 || header == 124;
    }

    private static boolean startsWith(byte[] d, String s) {
        if (d.length < s.length())
            return false;
        for (int i = 0; i < s.length(); i++)
            if (d[i] != (byte) s.charAt(i))
                return false;
        return true;
    }

    private static boolean startsWith(byte[] d, int... magic) {
        if (d.length < magic.length)
            return false;
        for (int i = 0; i < magic.length; i++)
            if ((d[i] & 0xff) != magic[i])
                return false;
        return true;
    }
}
