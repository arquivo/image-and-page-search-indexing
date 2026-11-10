package pt.arquivo.imagesearch.indexing.utils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Undoes text that was UTF-8, read as windows-1252/Latin-1 and encoded as UTF-8 again ("CidadÃ£os" for
 * "Cidadãos", "OlÃ¡" for "Olá", "â€œ...â€" for curly quotes). This happens
 * inside the page itself (guestbooks, comments, blog sidebars), so the page is decoded correctly and still contains the
 * damage. Each damaged sequence is reversed on its own and only when its bytes form exactly one valid UTF-8 character,
 * so correct text is left alone.
 */
public final class MojibakeRepair {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final int MAX_PASSES = 4;
    private static final char A_TILDE = 'Ã';
    private static final char A_CIRCUMFLEX = 'Â';
    private static final char LOWER_A_CIRCUMFLEX = 'â';
    private static final char EURO = '€';
    private static final String RIGHT_DOUBLE_QUOTE = "”";

    /** windows-1252 characters of 0x80-0x9F other than Latin-1 ones, with their byte. */
    private static final Map<Character, Integer> SPECIAL = new HashMap<>();

    static {
        for (int b = 0x80; b <= 0x9F; b++) {
            char c = new String(new byte[] {(byte) b}, WINDOWS_1252).charAt(0);
            if (c != '�')
                SPECIAL.put(c, b);
        }
    }

    private MojibakeRepair() {
    }

    /** Returns the text with every reversible sequence repaired, or the same instance if there was none. */
    public static String repair(String text) {
        if (text == null || text.length() < 2)
            return text;
        String current = text;
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String next = repairOnce(current);
            if (next == current)
                break;
            current = next;
        }
        return current;
    }

    private static String repairOnce(String s) {
        StringBuilder out = null;
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int length = 0;
            String replacement = null;
            if (c == A_TILDE || c == A_CIRCUMFLEX) {
                replacement = decode(s, i, 2);
                length = 2;
            } else if (c == LOWER_A_CIRCUMFLEX) {
                replacement = decode(s, i, 3);
                length = 3;
                // E2 80 9D (right double quote) often loses its last byte: 0x9D is undefined in windows-1252
                if (replacement == null && i + 1 < n && s.charAt(i + 1) == EURO && !isContinuation(s, i + 2)) {
                    replacement = RIGHT_DOUBLE_QUOTE;
                    length = 2;
                }
            }
            if (replacement != null) {
                if (out == null) {
                    out = new StringBuilder(n);
                    out.append(s, 0, i);
                }
                out.append(replacement);
                i += length;
            } else {
                if (out != null)
                    out.append(c);
                i++;
            }
        }
        return out == null ? s : out.toString();
    }

    private static boolean isContinuation(String s, int i) {
        if (i >= s.length())
            return false;
        int b = byteOf(s.charAt(i));
        return b >= 0x80 && b <= 0xBF;
    }

    /** The character the bytes of s[i, i+length) form in UTF-8, or null if they do not form exactly one. */
    private static String decode(String s, int i, int length) {
        if (i + length > s.length())
            return null;
        byte[] bytes = new byte[length];
        for (int k = 0; k < length; k++) {
            int b = byteOf(s.charAt(i + k));
            if (b < 0 || (k > 0 && (b < 0x80 || b > 0xBF)))
                return null;
            bytes[k] = (byte) b;
        }
        if (length == 3 && (bytes[0] & 0xFF) != 0xE2)
            return null;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return decoded.length() == 1 ? decoded.toString() : null;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** The windows-1252 byte of a character (the undefined slots read as their C1 control), or -1. */
    private static int byteOf(char c) {
        if (c < 0x100)
            return c;
        Integer b = SPECIAL.get(c);
        return b == null ? -1 : b;
    }
}
