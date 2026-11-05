package pt.arquivo.imagesearch.indexing.utils;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import org.apache.commons.httpclient.ChunkedInputStream;
import org.brotli.dec.BrotliInputStream;

/**
 * Removes HTTP Content-Encoding from payloads and reads the charset declared in Content-Type.
 */
public final class HttpBodyDecoder {

    private static final Pattern CHARSET_PARAM = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"',]+)", Pattern.CASE_INSENSITIVE);

    private static final int CHUNK_SNIFF_BYTES = 32;
    private static final int GZIP_SNIFF_BYTES = 16;

    private HttpBodyDecoder() {
    }

    /** True if the bytes begin with a chunk-size line: hex digits, optional extension, CRLF. */
    static boolean startsWithChunkSize(byte[] b, int len) {
        int i = 0;
        while (i < len && Character.digit(b[i], 16) >= 0)
            i++;
        if (i == 0)
            return false;
        if (i < len && b[i] == ';') {
            while (i < len && b[i] != '\r' && b[i] != '\n')
                i++;
        }
        return i + 1 < len && ((b[i] == '\r' && b[i + 1] == '\n') || b[i] == '\n');
    }

    /**
     * Charset parameter of a Content-Type header value, or null when absent.
     */
    public static String charsetFromContentType(String contentType) {
        if (contentType == null)
            return null;
        Matcher m = CHARSET_PARAM.matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Undoes Content-Encoding (gzip, deflate, br, or a comma separated list of them).
     * Truncated compressed payloads yield the bytes decoded so far instead of an exception.
     *
     * @param raw              payload as stored in the (W)ARC
     * @param transferEncoding Transfer-Encoding header value, may be null
     * @param contentEncoding  Content-Encoding header value, may be null
     */
    public static InputStream decode(InputStream raw, String transferEncoding, String contentEncoding) throws IOException {
        return unwrapUndeclaredGzip(decodeDeclared(raw, transferEncoding, contentEncoding));
    }

    /**
     * Gzip data that the headers do not announce, possibly after stray whitespace (a server that prints a newline
     * before its compressed output). Browsers show such pages as garbage too, but the content is recoverable and
     * the bytes are never legitimate text.
     */
    private static InputStream unwrapUndeclaredGzip(InputStream in) throws IOException {
        InputStream stream = new BufferedInputStream(in);
        stream.mark(GZIP_SNIFF_BYTES + 8);
        int lead = 0;
        int c = stream.read();
        while (lead < GZIP_SNIFF_BYTES && (c == ' ' || c == '\t' || c == '\r' || c == '\n')) {
            lead++;
            c = stream.read();
        }
        boolean gzip = c == 0x1f && stream.read() == 0x8b && stream.read() == 0x08;
        stream.reset();
        if (!gzip)
            return stream;
        for (int i = 0; i < lead; i++)
            stream.read();
        return new TruncationTolerantInputStream(new GZIPInputStream(stream));
    }

    private static InputStream decodeDeclared(InputStream raw, String transferEncoding, String contentEncoding) throws IOException {
        InputStream stream = new BufferedInputStream(raw);
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            // Some crawlers store the chunked framing; others already removed it, so look before decoding
            stream.mark(CHUNK_SNIFF_BYTES);
            byte[] head = new byte[CHUNK_SNIFF_BYTES];
            int n = 0;
            int r;
            while (n < head.length && (r = stream.read(head, n, head.length - n)) > 0)
                n += r;
            stream.reset();
            if (startsWithChunkSize(head, n))
                stream = new TruncationTolerantInputStream(new ChunkedInputStream(stream));
        }
        if (contentEncoding == null)
            return stream;
        String[] encodings = contentEncoding.toLowerCase().split(",");
        // Encodings are listed in the order they were applied, so undo them in reverse
        for (int i = encodings.length - 1; i >= 0; i--) {
            String enc = encodings[i].trim();
            if (enc.equals("gzip") || enc.equals("x-gzip")) {
                stream = new BufferedInputStream(stream);
                stream.mark(2);
                int b0 = stream.read();
                int b1 = stream.read();
                stream.reset();
                // Some servers declare gzip but send plain bytes
                if (b0 == 0x1f && b1 == 0x8b)
                    stream = new TruncationTolerantInputStream(new GZIPInputStream(stream));
            } else if (enc.equals("deflate")) {
                stream = new BufferedInputStream(stream);
                stream.mark(2);
                int b0 = stream.read();
                int b1 = stream.read();
                stream.reset();
                if (b0 < 0 || b1 < 0)
                    continue;
                // "deflate" is zlib-wrapped by spec, but some servers send raw deflate
                boolean zlibWrapped = (b0 & 0x0f) == 8 && ((b0 << 8) | b1) % 31 == 0;
                stream = new TruncationTolerantInputStream(new InflaterInputStream(stream, new Inflater(!zlibWrapped)));
            } else if (enc.equals("br")) {
                stream = new TruncationTolerantInputStream(new BrotliInputStream(stream));
            }
        }
        return stream;
    }

    /**
     * Treats a decompression error after some data was produced as end of stream (crawls often truncate payloads).
     */
    private static class TruncationTolerantInputStream extends FilterInputStream {
        private boolean readAny = false;
        private boolean ended = false;

        TruncationTolerantInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            if (ended)
                return -1;
            try {
                int b = in.read();
                if (b < 0) ended = true; else readAny = true;
                return b;
            } catch (IOException e) {
                return fail(e);
            }
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (ended)
                return -1;
            try {
                int n = in.read(buf, off, len);
                if (n < 0) ended = true; else if (n > 0) readAny = true;
                return n;
            } catch (IOException e) {
                return fail(e);
            }
        }

        private int fail(IOException e) throws IOException {
            if (!readAny)
                throw e;
            ended = true;
            return -1;
        }

        @Override
        public int available() throws IOException {
            return ended ? 0 : super.available();
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
