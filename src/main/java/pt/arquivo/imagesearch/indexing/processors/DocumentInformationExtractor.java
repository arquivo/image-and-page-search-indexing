package pt.arquivo.imagesearch.indexing.processors;

import org.archive.format.warc.WARCConstants;
import org.archive.io.ArchiveRecord;
import org.archive.io.warc.WARCRecord;
import org.checkerframework.checker.units.qual.t;
import org.jsoup.helper.StringUtil;

import pt.arquivo.imagesearch.indexing.DocumentIndexerWithDupsJob;
import pt.arquivo.imagesearch.indexing.DocumentIndexerWithDupsJob.DOCUMENT_COUNTERS;
import pt.arquivo.imagesearch.indexing.DocumentIndexerWithDupsJob.STATUS_CODES;
import pt.arquivo.imagesearch.indexing.data.TextDocumentData;

import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.counters.GenericCounter;
import org.apache.log4j.Logger;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.HtmlParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.LinkContentHandler;
import org.apache.tika.sax.TeeContentHandler;
import org.archive.io.arc.ARCRecord;
import org.xml.sax.SAXException;

import pt.arquivo.imagesearch.indexing.utils.HttpBodyDecoder;
import pt.arquivo.imagesearch.indexing.utils.ImageSearchIndexingUtil;
import pt.arquivo.imagesearch.indexing.utils.CharsetDatasetRow;
import pt.arquivo.imagesearch.indexing.utils.ContentSniffer;
import pt.arquivo.imagesearch.indexing.utils.MojibakeRepair;
import pt.arquivo.imagesearch.indexing.utils.TextEncodingDetector;
import pt.arquivo.imagesearch.indexing.utils.WARCRecordResponseEncapsulated;
import pt.arquivo.imagesearch.indexing.utils.MimeTypeCounters.PAGE_INDEXER_COUNTERS_DETECTED;
import pt.arquivo.imagesearch.indexing.utils.MimeTypeCounters.PAGE_INDEXER_COUNTERS_REPORTED;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.annotation.adapters.HexBinaryAdapter;

/**
 * Auxiliary class to extract metadata from pages and other documents for text
 * search
 */
public class DocumentInformationExtractor implements InformationExtractor {

    /**
     * File extensions to be included when extracting text
     */
    private static final Set<String> FILES_TO_PARSE_MIMETYPES = new HashSet<>(Arrays.asList("application/msword",
            "application/pdf", "application/postscript", "application/rss+xml", "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint", "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.text-template", "application/vnd.oasis.opendocument.text-master",
            "application/vnd.oasis.opendocument.text-web", "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.presentation-template",
            "application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.spreadsheet-template",
            "application/vnd.sun.xml.calc", "application/vnd.sun.xml.calc.template", "application/vnd.sun.xml.impress",
            "application/vnd.sun.xml.impress.template", "application/vnd.sun.xml.writer",
            "application/vnd.sun.xml.writer.template", "application/xhtml+xml", "application/x-bzip2",
            "application/x-gzip", "application/gzip", "application/x-kword", "application/x-kspread", "application/x-shockwave-flash",
            "application/zip", "application/rtf", "text/html", "text/plain", "text/richtext", "text/rtf", "text/sgml",
            "text/tab-separated-values", "text/xml"));

    private static final Set<String> METADATA_KEYS = new HashSet<>(
            Arrays.asList("dc:creator", "dc:subject", "dc:description"));

    // private static final int MAX_OUTLINKS = 1000;
    private static final int CONTENT_CHAR_LIMIT = 10000000; 
    private static final int ANCHOR_CHAR_LIMIT = 100; 
    private static final int TITLE_FROM_BODY_MAX_LENGTH = 50;
    

    private Logger logger = Logger.getLogger(DocumentInformationExtractor.class);

    /**
     * Collection name
     */
    protected String collection;

    /**
     * Hadoop context
     */
    private DocumentIndexerWithDupsJob.Map.Context context;

    /**
     * Stores the counters. This enables using this code both inside and outside
     * Hadoop
     */
    private HashMap<Enum<?>, Counter> localCounters;

    private HashMap<String, Counter> tmpCounters;

    private HashMap<String, Counter> linkTypes;

    /**
     * Largest text payload buffered in memory; the rest is dropped
     */
    private static final int MAX_TEXT_BYTES = 64 * 1024 * 1024;

    /**
     * When set, records for each document which charset was declared and which was chosen (keyed by content digest)
     */
    private HashMap<String, String[]> charsetDecisions = null;
    private java.io.Writer charsetDataset = null;
    private int charsetDatasetAgreePercent = 1;

    /**
     * Documents already parsed during this session
     */
    protected HashMap<String, TextDocumentData> entries;

    /**
     * Documents already parsed during this session (301, 302, 303, 307, 308)
     */
    protected HashMap<String, TextDocumentData> entriesRedirect;

    private TikaConfig config;

    /**
     * Constructor used for Hadoop
     *
     * @param collection collection name
     * @param context    Hadoop context
     */
    public DocumentInformationExtractor(String collection, DocumentIndexerWithDupsJob.Map.Context context) {
        init(collection);
        this.context = context;
    }

    /**
     * Constructor used for local parser
     *
     * @param collection collection name
     */
    public DocumentInformationExtractor(String collection) {
        init(collection);
        this.localCounters = new HashMap<>();
    }

    /**
     * Init with common code from both constructors
     *
     * @param collection Collection name
     */
    private void init(String collection) {
        this.collection = collection;

        ClassLoader classLoader = getClass().getClassLoader();
        try {
            config = new TikaConfig(classLoader.getResourceAsStream("tika-config.xml"));
        } catch (TikaException | IOException | SAXException e) {
            logger.error("Error loading Tika config", e);
        }

        this.entries = new HashMap<>();
        this.entriesRedirect = new HashMap<>();
        this.linkTypes = new HashMap<>();
        this.tmpCounters = new HashMap<>();
    }

    /**
     * Gets the desired counters from either Hadoop or local Counter cache
     *
     * @param counterName name of the counter
     * @return desired counter
     */
    public Counter getCounter(Enum<?> counterName) {
        if (context != null) {
            return context.getCounter(counterName);
        } else {
            if (localCounters.get(counterName) == null)
                localCounters.put(counterName, new GenericCounter(counterName.name(), counterName.name()));
            return localCounters.get(counterName);
        }
    }

    /**
     * Generic entry point to parse wither WARCs or ARCs
     *
     * @param arcName name of the (W)ARCs
     * @param arcURL  (W)ARCs url
     */
    public void parseRecord(String arcName, String arcURL) {
        if (arcURL.endsWith("warc.gz") || arcURL.endsWith("warc")) {
            parseWarcEntryRecord(arcName, arcURL);
        } else {
            parseArcEntry(arcName, arcURL);
        }
    }

    /**
     * Parse a WARC record
     *
     * @param warcName WARC name
     * @param warcURL  WARC url
     */
    public void parseWarcEntryRecord(String warcName, String warcURL) {
        ImageSearchIndexingUtil.readWarcRecords(warcURL, this, (record) -> {
            parseWarcRecord(record, warcName);
        });

    }

    /**
     * Parse a WARC record inner method
     *
     * @param record   WARC record object
     * @param warcName WARC name
     */
    public void parseWarcRecord(WARCRecordResponseEncapsulated record, String warcName) {
        getCounter(DOCUMENT_COUNTERS.RECORDS_READ).increment(1);
        String mimeType = record.getContentMimetype();
        if (this.tmpCounters.get(mimeType) == null)
            this.tmpCounters.put(mimeType, new GenericCounter(mimeType, mimeType));
        this.tmpCounters.get(mimeType).increment(1);


        String url = record.getWARCRecord().getHeader().getUrl();
        if (!allowURL(url)) {
            logger.warn("Skipping record with URL: " + url);
            return;
        }
        if (isCodeFile(url, mimeType)) {
            getCounter(DOCUMENT_COUNTERS.RECORDS_SKIPPED_CODE).increment(1);
            return;
        }
        parseTextRecord(record, warcName);

    }

    /**
     * Parse a ARC record
     *
     * @param arcName ARC name
     * @param arcURL  ARC url
     */
    public void parseArcEntry(String arcName, String arcURL) {
        ImageSearchIndexingUtil.readArcRecords(arcURL, this, record -> {
            parseArcRecord(record, arcName);
        });
    }

    /**
     * Parse a ARC record inner method
     *
     * @param record  ARC record object
     * @param arcName ARC url
     */
    public void parseArcRecord(ARCRecord record, String arcName) {
        getCounter(DOCUMENT_COUNTERS.RECORDS_READ).increment(1);
        String mimeType = getMimeType(record);
        if (this.tmpCounters.get(mimeType) == null)
            this.tmpCounters.put(mimeType, new GenericCounter(mimeType, mimeType));
        this.tmpCounters.get(mimeType).increment(1);
        try {
            record.skipHttpHeader();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            logger.error("Error parsing record: " + record.getHeader().getUrl(), e);
        }
        String url = record.getHeader().getUrl();

        if (!allowURL(url)) {
            logger.warn("Skipping record with URL: " + url);
            return;
        }
        if (isCodeFile(url, mimeType)) {
            getCounter(DOCUMENT_COUNTERS.RECORDS_SKIPPED_CODE).increment(1);
            return;
        }

        String timestamp = record.getMetaData().getDate();
        long offset = record.getMetaData().getOffset();
        int statusCode = record.getStatusCode();
        incrementStatusCodeCounter(statusCode);

        if (statusCode >= 300 && statusCode < 400) {
            String redirectUrl = null;
            try {
                redirectUrl = record.getHeader().getHeaderValue("location").toString();
                parseRedirectRecord(record, mimeType, arcName, url, timestamp, offset, statusCode, redirectUrl);
            } catch (Exception e) {
                logger.debug("Error parsing redirect URL", e);
            }

        } else {
            try {
                String contentEncoding = null;
                String transferEncoding = null;
                String contentType = null;
                if (record.getHttpHeaders() != null) {
                    for (org.apache.commons.httpclient.Header h : record.getHttpHeaders()) {
                        if (h.getName().equalsIgnoreCase("content-encoding"))
                            contentEncoding = h.getValue();
                        else if (h.getName().equalsIgnoreCase("transfer-encoding"))
                            transferEncoding = h.getValue();
                        else if (h.getName().equalsIgnoreCase("content-type"))
                            contentType = h.getValue();
                    }
                }
                parseTextRecord(HttpBodyDecoder.decode(record, transferEncoding, contentEncoding), mimeType, arcName, url, timestamp, offset,
                        statusCode, HttpBodyDecoder.charsetFromContentType(contentType));
            } catch (IOException e) {
                logger.error("Error parsing text record: " + record.getHeader().getUrl(), e);
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_DECODING).increment(1);
            }
        }

    }

    /**
     * Record parser for HDFS WARCs
     * @
     * param rec record to be processed
     */
    public void parseRecord(ArchiveRecord rec) {
        if (rec instanceof ARCRecord) {
            ARCRecord arcRecord = (ARCRecord) rec;
            String arcName = ((ARCRecord) rec).getMetaData().getArc();
            parseArcRecord(arcRecord, arcName);
        } else {
            WARCRecordResponseEncapsulated warcRecord = ImageSearchIndexingUtil.parseWarcRecord((WARCRecord) rec, this);
            String warcName = ((String) rec.getHeader().getHeaderValue(WARCConstants.READER_IDENTIFIER_FIELD_KEY));
            if (warcRecord != null)
                parseWarcRecord(warcRecord, warcName);
        }
    }

    private String removeJunkCharacters(String str) {
        // remove all whitespace characters
        Pattern pattern = Pattern.compile("[\\s\\u00A0\\u1680\\u180E\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]+");
        Matcher matcher = pattern.matcher(str.trim().replaceAll("[\\n\\t]", " "));
        return matcher.replaceAll(" ").trim();
    }

    private void incrementStatusCodeCounter(int statusCode) {
        if (statusCode < 0) {
            getCounter(STATUS_CODES.statusUnknown).increment(1);
        } else
        if (statusCode >= 100 && statusCode < 200) {
            getCounter(STATUS_CODES.status1xx).increment(1);
        } else if (statusCode >= 200 && statusCode < 300) {
            getCounter(STATUS_CODES.status2xx).increment(1);
        } else if (statusCode >= 300 && statusCode < 400) {
            getCounter(STATUS_CODES.status3xx).increment(1);
        } else if (statusCode >= 400 && statusCode < 500) {
            getCounter(STATUS_CODES.status4xx).increment(1);
        } else if (statusCode >= 500 && statusCode < 600) {
            getCounter(STATUS_CODES.status5xx).increment(1);
        } else {
            getCounter(STATUS_CODES.statusOther).increment(1);
        }
    }


    /**
     * Parse a text record
     *
     * @param record  record to be parsed
     * @param arcName name of the (W)ARC
     */

    public void parseTextRecord(WARCRecordResponseEncapsulated record, String arcName) {
        String mimeType = record.getContentMimetype();
        String url = record.getWARCRecord().getHeader().getUrl();
        String timestamp = record.getTs();
        long offset = record.getWARCRecord().getHeader().getOffset();
        int statusCode = record.getStatusCode();
        incrementStatusCodeCounter(statusCode);
        if (statusCode >= 300 && statusCode < 400) {
            String redirectUrl = record.getRedirectURL();
            parseRedirectRecord(record.getWARCRecord(), mimeType, arcName, url, timestamp, offset, statusCode, redirectUrl);
        } else {
            try {
                InputStream decoded = record.getDecodedInputStream();
                parseTextRecord(decoded, mimeType, arcName, url, timestamp, offset, statusCode,
                        record.getContentTypeCharset());
            } catch (IOException e) {
                logger.error("Error parsing text record: " + url, e);
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_DECODING).increment(1);
            }
        }
    }

    public TextDocumentData parseTextRecord(InputStream record, String mimeType, String arcName, String url,
            String timestamp, long offset, int statusCode) throws IOException {
        return parseTextRecord(record, mimeType, arcName, url, timestamp, offset, statusCode, null);
    }

    /** Types read by Tika's XML parser, which does its own decoding. */
    private static boolean isXml(String mimeType) {
        return mimeType != null && mimeType.contains("xml") && !mimeType.contains("html");
    }

    private static boolean isTextLike(String mimeType) {
        return mimeType != null && (mimeType.startsWith("text/") || mimeType.contains("xml") || mimeType.contains("html"));
    }

    /**
     * Parse a text record
     *
     * @param record          payload with Content-Encoding already removed
     * @param declaredCharset charset declared in the HTTP Content-Type header, or null
     */
    public TextDocumentData parseTextRecord(InputStream record, String mimeType, String arcName, String url,
            String timestamp, long offset, int statusCode, String declaredCharset) throws IOException {
        getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_READ).increment(1);
        getCounter(mimeToCounterReported(mimeType)).increment(1);

        TextDocumentData textDocumentData = new TextDocumentData();

        // ignore all non-OK status codes
        if (statusCode >= 400) {
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_STATUS_CODE).increment(1);
            return textDocumentData;
        }

        MessageDigest md5Text;
        MessageDigest md5Stream;

        try {
            md5Text = MessageDigest.getInstance("MD5");
            md5Stream = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_PREPARSING_FAILED).increment(1);
            return null;
        }

        String fileExtension = getFileExtension(url);
        textDocumentData.setFileExtension(fileExtension);
        try {
            textDocumentData.addURL(url, timestamp, collection);
            textDocumentData.setTimestamp(timestamp);
            textDocumentData.setWarc(arcName);
            textDocumentData.setWarcOffset(offset);

            textDocumentData.addCollection(collection);
            textDocumentData.setMimeTypeReported(mimeType);
            textDocumentData.setStatusCode(statusCode);
        } catch (Exception e) {
            logger.error("Error parsing record before Tika: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_PREPARSING_FAILED).increment(1);
            return null;
        }

        InputStream stream;
        boolean sourceHasReplacementChar = false;
        boolean sniffedContainer = false;
        String metaCharsetLabel = "";
        String repairReason = null;
        String xmlReason = null;
        boolean decodedAsText = isTextLike(mimeType);
        try {
            if (decodedAsText) {
                // Text is buffered so the encoding detector can look at all of it
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int n;
                while (buffer.size() < MAX_TEXT_BYTES && (n = record.read(chunk)) > 0)
                    buffer.write(chunk, 0, n);
                byte[] bytes = buffer.toByteArray();
                if (bytes.length == 0) {
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_EMPTY).increment(1);
                    return textDocumentData;
                }
                md5Stream.update(bytes);
                sourceHasReplacementChar = TextEncodingDetector.containsReplacementChar(bytes);
                if (charsetDataset != null && mimeType.contains("html"))
                    metaCharsetLabel = TextEncodingDetector.metaCharsetLabel(bytes);
                ContentSniffer.Kind sniffed = ContentSniffer.sniff(bytes);
                if (sniffed == ContentSniffer.Kind.BINARY) {
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_SNIFFED_BINARY).increment(1);
                    return textDocumentData;
                }
                sniffedContainer = sniffed == ContentSniffer.Kind.CONTAINER;
                if (sniffedContainer)
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_SNIFFED_CONTAINER).increment(1);
                byte[] noBom = sniffedContainer ? null : TextEncodingDetector.dropFalseUtf8Bom(bytes);
                if (noBom != null) {
                    bytes = noBom;
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FALSE_BOM_DROPPED).increment(1);
                }
                byte[] repaired = sniffedContainer ? null : TextEncodingDetector.repairStrayBytes(bytes, declaredCharset);
                if (repaired != null) {
                    repairReason = "UTF8_REPAIRED";
                } else if (!sniffedContainer) {
                    repaired = TextEncodingDetector.repairEmbeddedUtf8(bytes, declaredCharset);
                    if (repaired != null)
                        repairReason = "LATIN_REPAIRED";
                }
                if (repaired != null)
                    bytes = repaired;
                byte[] c1 = sniffedContainer ? null : TextEncodingDetector.repairC1InUtf8(bytes, declaredCharset);
                if (c1 != null) {
                    bytes = c1;
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_C1_CONTROLS_REMAPPED).increment(1);
                }
                byte[] noUndefined = sniffedContainer ? null : TextEncodingDetector.dropUndefinedLatinBytes(bytes, declaredCharset);
                if (noUndefined != null) {
                    bytes = noUndefined;
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_UNDEFINED_LATIN_BYTES_DROPPED).increment(1);
                }
                byte[] stripped = sniffedContainer ? null : TextEncodingDetector.stripControlBytes(bytes);
                if (stripped != null) {
                    bytes = stripped;
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_CONTROL_CHARS_STRIPPED).increment(1);
                }
                if (mimeType.contains("html") && !sniffedContainer) {
                    byte[] refs = TextEncodingDetector.repairControlReferences(bytes);
                    if (refs != null) {
                        bytes = refs;
                        getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_CONTROL_REFERENCES_REPAIRED).increment(1);
                    }
                }
                if (isXml(mimeType) && !sniffedContainer) {
                    TextEncodingDetector.XmlResult xml = TextEncodingDetector.xmlToUtf8(bytes, declaredCharset);
                    bytes = xml.bytes;
                    xmlReason = xml.resolution.reason.name();
                }
                stream = new ByteArrayInputStream(bytes);
            } else {
                BufferedInputStream buffered = new BufferedInputStream(record);
                buffered.mark(1);
                if (buffered.read() < 0) {
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_EMPTY).increment(1);
                    return textDocumentData;
                }
                buffered.reset();
                stream = new DigestInputStream(buffered, md5Stream);
            }
        } catch (IOException e) {
            logger.error("Error reading record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_DECODING).increment(1);
            return textDocumentData;
        }

         Parser parser = null;
         if (mimeType.equals("text/html") && !sniffedContainer) {
             parser = new HtmlParser();
         } else {
             parser = new AutoDetectParser(config);
         }

        Metadata metadata = new Metadata();
        if (declaredCharset != null)
            metadata.set(TextEncodingDetector.DECLARED_CHARSET, declaredCharset);
        // Tika's magic detection calls text in legacy 8-bit charsets application/octet-stream; the hint only applies
        // when it finds nothing more specific
        if (isTextLike(mimeType) && !mimeType.equals("text/html") && !sniffedContainer)
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        BodyContentHandler bodyHandler = new BodyContentHandler(CONTENT_CHAR_LIMIT);

        // Add anchor text to the content
        LinkContentHandler linkHandler = new LinkContentHandler();
        TeeContentHandler handler = new TeeContentHandler(bodyHandler, linkHandler);
        ParseContext context = new ParseContext();
        context.set(EncodingDetector.class, new TextEncodingDetector());

        try {
            parser.parse(stream, handler, metadata, context);

            if (repairReason != null)
                metadata.set(TextEncodingDetector.RESOLUTION_REASON, repairReason);
            else if (xmlReason != null)
                metadata.set(TextEncodingDetector.RESOLUTION_REASON, xmlReason);
            String charsetReason = metadata.get(TextEncodingDetector.RESOLUTION_REASON);
            if (charsetReason != null)
                getCounter(DOCUMENT_COUNTERS.valueOf("CHARSET_" + charsetReason)).increment(1);
            if (charsetReason != null && (!charsetReason.startsWith("UTF8") || repairReason != null) && !charsetReason.equals("ASCII")
                    && !charsetReason.equals("BOM") && !charsetReason.equals("DECLARED_HTTP"))
                logger.info("Charset " + charsetReason + " -> " + metadata.get(TextEncodingDetector.RESOLVED_CHARSET)
                        + " (declared " + declaredCharset + ") " + timestamp + " " + url + " " + arcName + "@" + offset);
            if (TextEncodingDetector.Reason.BINARY.name().equals(charsetReason))
                return textDocumentData;
            String detectedMimeType = metadata.get("Content-Type").split(";")[0].trim();

            textDocumentData.setMimeTypeDetected(detectedMimeType);

            getCounter(mimeToCounterDetected(detectedMimeType)).increment(1);

            if (!FILES_TO_PARSE_MIMETYPES.contains(detectedMimeType)) {
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_IGNORED_MIME_DETECTED).increment(1);
                return textDocumentData;
            }

            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_PARSED_MIME).increment(1);

        } catch (IOException | SAXException | TikaException e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED).increment(1);
        } catch (NoSuchMethodError e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_NO_SUCH_METHOD).increment(1);
        } catch (Exception e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED).increment(1);
            throw e;
        }

        try {

            String body = bodyHandler.toString();
            // PDFBox and POI emit the glyph code of characters without a Unicode mapping as a control character, and
            // Tika turns it into U+FFFD. The letter is lost either way; a space keeps words from being glued together
            String contentType = metadata.get("Content-Type");
            if (body.indexOf('\uFFFD') >= 0 && contentType != null && !isTextLike(contentType.split(";")[0].trim())) {
                body = body.replaceAll("\uFFFD+", " ");
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_UNMAPPED_GLYPHS_REPLACED).increment(1);
            }
            body = removeJunkCharacters(body);

            if (body.isEmpty()) {
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_EMPTY_BODY).increment(1);
                return textDocumentData;
            }

            LanguageDetector detector = LanguageDetector.getDefaultLanguageDetector().loadModels();

            detector.addText(body);
            // Whether the page is Portuguese is decided by its body alone: titles and anchor texts often name foreign works
            LanguageResult bodyLanguage = detector.detect();

            String title = metadata.get("dc:title");

            if (title == null || title.isEmpty())
                title = metadata.get("title");
                


            if (title == null || title.isEmpty())
                title = body.substring(0, Math.min(body.length(), TITLE_FROM_BODY_MAX_LENGTH));

            detector.addText(title);

            LanguageResult languageResult = detector.detect();

            if (languageResult != null) {
                textDocumentData.setLanguage(languageResult.getLanguage());
                textDocumentData.setLanguageConfidence(languageResult.getConfidence().toString());
            }

            // Pages in Portuguese that carry double-encoded text ("CidadÃ£os") are repaired; other languages are left as they are
            final boolean portuguese = bodyLanguage != null && "pt".equals(bodyLanguage.getLanguage());
            boolean mojibakeRepaired = false;
            if (portuguese) {
                String repairedBody = MojibakeRepair.repair(body);
                String repairedTitle = MojibakeRepair.repair(title);
                mojibakeRepaired = repairedBody != body || repairedTitle != title;
                if (mojibakeRepaired)
                    getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_MOJIBAKE_REPAIRED).increment(1);
                body = repairedBody;
                title = repairedTitle;
            }
            textDocumentData.addTitle(title);

            List<String> metadataStrings = new LinkedList<>();

            for (String name : metadata.names()) {
                String value = metadata.get(name);
                if (METADATA_KEYS.contains(name) && metadataStrings.indexOf(value) == -1 && value != null
                        && !value.isEmpty() && !value.equalsIgnoreCase("unknown")) {
                    metadataStrings.add(value);
                }
            }

            String metadataString = removeJunkCharacters(String.join("\n", metadataStrings).trim());
            if (portuguese)
                metadataString = MojibakeRepair.repair(metadataString);

            textDocumentData.addMetadata(metadataString);

            linkHandler.getLinks().forEach(link -> {
                String linkURL = link.getUri();
                String anchorText = removeJunkCharacters(link.getText());
                if (portuguese)
                    anchorText = MojibakeRepair.repair(anchorText);
                if (anchorText.length() > ANCHOR_CHAR_LIMIT) {
                    anchorText = anchorText.substring(0, ANCHOR_CHAR_LIMIT);
                }
                if (link.getType() == "a" && !linkURL.trim().isEmpty() && !linkURL.startsWith("#")
                        && !linkURL.startsWith("mailto:") && !linkURL.startsWith("javascript:")) {
                    String linkAbsURL = StringUtil.resolve(url, linkURL);
                    textDocumentData.addOutlink(linkAbsURL, anchorText);
                }
            });


            md5Text.update((body+"\t").getBytes(StandardCharsets.UTF_8));
            md5Text.update((metadataString+"\t").getBytes(StandardCharsets.UTF_8));
            md5Text.update((title+"\t").getBytes(StandardCharsets.UTF_8));

            HexBinaryAdapter hexBinaryAdapter = new HexBinaryAdapter();
            String digest = hexBinaryAdapter.marshal(md5Text.digest());

            String warcDigest = hexBinaryAdapter.marshal(md5Stream.digest());
            textDocumentData.setDigestContent(digest);
            textDocumentData.setDigestContainer(warcDigest);

            textDocumentData.addContent(body);

            if (sourceHasReplacementChar)
                getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_SOURCE_HAS_REPLACEMENT_CHAR).increment(1);
            if (charsetDataset != null && decodedAsText && !sniffedContainer && !mojibakeRepaired
                    && (mimeType.contains("html") || mimeType.equals("text/plain")))
                writeCharsetDatasetRow(textDocumentData, url, timestamp, arcName, offset, declaredCharset, metaCharsetLabel,
                        metadata.get(TextEncodingDetector.RESOLVED_CHARSET),
                        repairReason != null ? repairReason : metadata.get(TextEncodingDetector.RESOLUTION_REASON), repairReason != null);
            if (charsetDecisions != null)
                charsetDecisions.put(digest, new String[] { declaredCharset, metadata.get(TextEncodingDetector.RESOLVED_CHARSET),
                        metadata.get(TextEncodingDetector.RESOLUTION_REASON), String.valueOf(sourceHasReplacementChar) });

            insertDocumentIndex(textDocumentData);
            getCounter(DOCUMENT_COUNTERS.RECORDS_SUCCESS).increment(1);
            return textDocumentData;

        } catch (Exception e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_PARSING_FAILED).increment(1);
        }
        return null;
    }



    public TextDocumentData parseRedirectRecord(InputStream record, String mimeType, String arcName, String url,
            String timestamp, long offset, int statusCode, String redirectUrl) {
        getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_READ).increment(1);
        getCounter(mimeToCounterReported(mimeType)).increment(1);

        TextDocumentData textDocumentData = new TextDocumentData();

        if (redirectUrl == null || redirectUrl.isEmpty()) {
            getCounter(DOCUMENT_COUNTERS.RECORDS_TIKA_FAILED_REDIRECT).increment(1);
            return textDocumentData;
        }

        try {
            textDocumentData.addURL(url, timestamp, collection);
            textDocumentData.addURL(redirectUrl, timestamp, collection);
            textDocumentData.setTimestamp(timestamp);
            textDocumentData.setWarc(arcName);
            textDocumentData.setWarcOffset(offset);

            textDocumentData.addCollection(collection);
            textDocumentData.setMimeTypeReported(mimeType);
            textDocumentData.setStatusCode(statusCode);
            textDocumentData.setRedirect(true);
        } catch (Exception e) {
            logger.error("Error parsing record before Tika: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_PREPARSING_FAILED).increment(1);
            return null;
        }

        try {

            insertRedirectDocumentIndex(textDocumentData);
            getCounter(DOCUMENT_COUNTERS.RECORDS_SUCCESS).increment(1);
            return textDocumentData;

        } catch (Exception e) {
            logger.error("Error parsing record: " + url, e);
            getCounter(DOCUMENT_COUNTERS.RECORDS_PARSING_FAILED).increment(1);
        }
        return null;
    }

    // Get all counters
    public HashMap<Enum<?>, Counter> getCounters() {
        return localCounters;
    }

    public HashMap<String, Counter> getTmpCounters() {
        return tmpCounters;
    }

    public Enum<PAGE_INDEXER_COUNTERS_DETECTED> mimeToCounterDetected(String mimeType) {
        if (mimeType == null || mimeType.isEmpty())
            return PAGE_INDEXER_COUNTERS_DETECTED.unknown;
        String enumName = mimeType.replace("/", "_").replace("+", "_").replace("-", "_").replace(".", "_")
                .replace(";", "_").replace("=", "_").replace(" ", "_");
        try {
            return PAGE_INDEXER_COUNTERS_DETECTED.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return PAGE_INDEXER_COUNTERS_DETECTED.other;
        }
    }

    public Enum<PAGE_INDEXER_COUNTERS_REPORTED> mimeToCounterReported(String mimeType) {
        if (mimeType == null || mimeType.isEmpty())
            return PAGE_INDEXER_COUNTERS_REPORTED.unknown;
        String enumName = mimeType.replace("/", "_").replace("+", "_").replace("-", "_").replace(".", "_")
                .replace(";", "_").replace("=", "_").replace(" ", "_");
        try {
            return PAGE_INDEXER_COUNTERS_REPORTED.valueOf(enumName);
        } catch (IllegalArgumentException e) {
            return PAGE_INDEXER_COUNTERS_REPORTED.other;
        }
    }

    public String getMimeType(ArchiveRecord rec) {
        String mimeType = null;
        if (rec instanceof ARCRecord) {
            mimeType = ((ARCRecord) rec).getMetaData().getMimetype();
        } else {
            mimeType = ((WARCRecord) rec).getHeader().getMimetype();

        }
        if (mimeType != null) {
            mimeType = mimeType.split(";")[0].trim();
        }
        return mimeType;

    }

    /**
     * Starts recording the declared and chosen charset of every parsed document (for local analysis)
     */
    public void trackCharsetDecisions() {
        charsetDecisions = new HashMap<>();
    }

    /**
     * Content digest -> {declared charset, chosen charset, reason, "true" if the source bytes already contained U+FFFD}; null unless tracking was enabled
     */
    /**
     * Optional dataset of pages whose reported charset differs from the detected one (plus a sample of agreeing pages),
     * one line per captured URL, to tune other replayers. Pages whose text was repaired afterwards are left out, since the
     * detection may have been wrong to begin with.
     *
     * @param agreePercent share (0-100) of the agreeing pages to include
     */
    public void setCharsetDataset(java.io.Writer writer, int agreePercent) {
        this.charsetDataset = writer;
        this.charsetDatasetAgreePercent = agreePercent;
    }

    private void writeCharsetDatasetRow(TextDocumentData doc, String url, String timestamp, String warc, long offset,
            String httpCharset, String metaCharset, String detected, String reason, boolean mixed) {
        if (detected == null || reason == null || reason.equals("ASCII") || reason.equals("BINARY"))
            return;
        boolean agrees = CharsetDatasetRow.agrees(httpCharset, metaCharset, detected);
        if (agrees && Math.floorMod((url + timestamp).hashCode(), 100) >= charsetDatasetAgreePercent)
            return;
        String surt = doc.getSurts() == null || doc.getSurts().isEmpty() ? "" : doc.getSurts().get(0);
        try {
            charsetDataset.write(CharsetDatasetRow.format(url, timestamp, surt, collection, warc, offset, httpCharset, metaCharset,
                    detected, reason, mixed, agrees) + "\n");
        } catch (IOException e) {
            logger.error("Error writing charset dataset row: " + url, e);
        }
    }

    public HashMap<String, String[]> getCharsetDecisions() {
        return charsetDecisions;
    }

    public HashMap<String, TextDocumentData> getEntries() {
        return entries;
    }

    public HashMap<String, TextDocumentData> getEntriesRedirect() {
        return entriesRedirect;
    }

    // linkTypes
    public HashMap<String, Counter> getLinkTypes() {
        return linkTypes;
    }

    public void insertDocumentIndex(TextDocumentData pageImageData) {
        if (entries.containsKey(pageImageData.getDigestContent())) {
            TextDocumentData oldPageImageData = entries.get(pageImageData.getDigestContent());
            TextDocumentData mergedImageData = TextDocumentData.merge(oldPageImageData, pageImageData);
            entries.put(pageImageData.getDigestContent(), mergedImageData);
        } else {
            entries.put(pageImageData.getDigestContent(), pageImageData);
        }
    }

    public void insertRedirectDocumentIndex(TextDocumentData pageImageData) {
        for (String surt : pageImageData.getSurts()) {
            if (entriesRedirect.containsKey(surt)) {
                TextDocumentData oldPageImageData = entriesRedirect.get(surt);
                TextDocumentData mergedImageData = TextDocumentData.merge(oldPageImageData, pageImageData);
                entriesRedirect.put(surt, mergedImageData);
            } else {
                entriesRedirect.put(surt, pageImageData);
            }
        }
    }

    /** Extensions of source code and style sheets, which are not worth indexing as text. */
    private static final Set<String> CODE_EXTENSIONS = new HashSet<>(Arrays.asList("js", "mjs", "cjs", "jsx", "ts", "tsx", "map",
            "css", "scss", "sass", "less", "java", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "py", "rb", "go", "rs", "sh",
            "bat", "lua", "swift", "kt", "scala"));

    /** Types reported for source code and style sheets. */
    private static final Set<String> CODE_MIMETYPES = new HashSet<>(Arrays.asList("text/javascript", "application/javascript",
            "application/x-javascript", "application/ecmascript", "text/ecmascript", "text/jscript", "text/css", "text/x-c",
            "text/x-csrc", "text/x-chdr", "text/x-c++src", "text/x-c++hdr", "text/x-java", "text/x-java-source", "text/x-python",
            "text/x-script.python", "application/x-python-code", "text/x-perl", "text/x-ruby", "text/x-shellscript",
            "application/x-sh", "application/x-csh", "text/x-php", "application/x-httpd-php-source"));

    /**
     * True for source code and style sheets: by the reported type or, for records not reported as HTML (CGI scripts and
     * repository viewers end in .pl/.py/.c and are HTML pages), by the extension of the URL path (query and fragment ignored).
     */
    public static boolean isCodeFile(String url, String mimeType) {
        String type = mimeType == null ? "" : mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (CODE_MIMETYPES.contains(type))
            return true;
        if (url == null || type.contains("html"))
            return false;
        int end = url.length();
        int cut = url.indexOf('#');
        if (cut >= 0)
            end = cut;
        cut = url.indexOf('?');
        if (cut >= 0 && cut < end)
            end = cut;
        String path = url.substring(0, end);
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private boolean allowURL(String url) {
        return !(url == null || url.isEmpty() || url.equals("null")
            || url.startsWith("dns:") || url.endsWith(".css") || url.endsWith(".js")
            || url.endsWith(".gif"));
    }

    private String getFileExtension(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        // get last part of the URL after the last slash
        String lastPart = url.substring(url.lastIndexOf('/') + 1);

        for (char charToRemove : new char[] { '!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', ':', '"', '?', '#' }) {
            if (lastPart.contains(String.valueOf(charToRemove))) {
                lastPart = lastPart.substring(0, lastPart.indexOf(charToRemove));
            }
        }
        
        // if there is a dot, return the part after the last dot
        if (lastPart.contains(".")) {
            return lastPart.substring(lastPart.lastIndexOf('.') + 1).toLowerCase();
        }
        return "";
    }
}
