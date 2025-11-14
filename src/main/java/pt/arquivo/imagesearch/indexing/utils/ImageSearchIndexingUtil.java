package pt.arquivo.imagesearch.indexing.utils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import pt.arquivo.imagesearch.indexing.ImageIndexerWithDupsJob;
import pt.arquivo.imagesearch.indexing.processors.InformationExtractor;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.Logger;
import org.archive.format.warc.WARCConstants;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveRecord;
import org.archive.io.arc.ARCReader;
import org.archive.io.arc.ARCReaderFactory;
import org.archive.io.arc.ARCRecord;
import org.archive.io.warc.WARCReaderFactory;
import org.archive.io.warc.WARCRecord;

/**
 * Utility methods for image search
 */
public class ImageSearchIndexingUtil {

    public static final int MAXIMUM_RECORD_SIZE_MB = 32;
    private static Logger logger = Logger.getLogger(ImageSearchIndexingUtil.class);


    /**
     * Pattern to find pages that are in UTF_8 but encoded in ISO_8859_1
     */
    static Pattern UTF8_MISMATCH = Pattern.compile("Ã©|Ã¡|Ã£|Ã§|Ã°|Ãµ|Ã´|Ã³|Â®|Ã‡|ÃŠ|Ã•|Ã¨|Ãª|Ã«|Ã±|Ã¹|Ãº|Ã»|Â£");


    /**
     * URL split pattern to generate URL tokens
     */
    private static final String SPLIT_PATTERN = "[\\p{Punct}\b]+";


    /**
     * Generate MD5 hash
     *
     * @param content string to be hashes
     * @return MD5 hash
     */
    public static String md5ofString(String content) {
        return DigestUtils.md5Hex(content);
    }


    /**
     * Parses a ARC record and extracts image and page information
     *
     * @param arcURL arc URL
     * @param context Hadoop context
     * @param consumer object to send back the processed object
     */
    public static void readArcRecords(String arcURL, InformationExtractor context, Consumer<ARCRecord> consumer) {
        logger.debug("Reading ARC records for: " + arcURL);
        ARCReader reader;
        try {
            reader = ARCReaderFactory.get(arcURL);
        } catch (Exception e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.WARCS_FAILED).increment(1);
            return;
        }

        int records = 0;
        int errors = 0;

        reader.setStrict(true);
        Iterator<ArchiveRecord> ii = reader.iterator();

        try {
            while (ii.hasNext()) {
                ARCRecord record;
                try {
                    record = (ARCRecord) ii.next();
                } catch (RuntimeException e) {
                    errors++;
                    // skip this record
                    context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORD_NEXT_FAILED).increment(1);
                    logger.error("Exception reading next (W)ARC record", e);
                    throw e;
                }
                try {
                    consumer.accept(record);
                    context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_READ).increment(1);
                } catch (RuntimeException e) {
                    context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_FAILED).increment(1);
                    logger.error("Exception reading (W)ARC record", e);
                    errors++;
                }

                ++records;
                if (record.hasErrors()) {
                    errors += record.getErrors().size();
                }
            }
            logger.debug("records: " + records);
            logger.debug("errors: " + errors);
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    logger.debug("error closing ArchiveReader" + e.getMessage());
                }

            }
        } catch (RuntimeException e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.WARCS_FAILED_STREAM).increment(1);
            logger.error("Exception reading ARC bytes, WARCNAME: " + arcURL + " " + e.getMessage());
            if (!e.getMessage().startsWith("Retried") && !e.getMessage().startsWith("java.util.zip.ZipException: Corrupt GZIP trailer") && !e.getMessage().startsWith("(Record start") && !e.getMessage().startsWith("java.io.IOException: Record STARTING at")){
                FileUtils.deleteQuietly(new File(arcURL));
                throw e;
            }

        }

    }


    /**
     * Get ARC record content bytes to be used to convert to HTML or image
     *
     * @param record ARC record obbject
     * @return byte array
     * @throws IOException failed parsing the data
     */
    public static byte[] getRecordContentBytes(ARCRecord record) throws IOException {
        record.skipHttpHeader();/*Skipping http headers to only get the content bytes*/
        byte[] buffer = new byte[1024 * 16];
        int len = record.read(buffer, 0, buffer.length);
        ByteArrayOutputStream contentBuffer =
                new ByteArrayOutputStream(1024 * MAXIMUM_RECORD_SIZE_MB * 1000); /*Max record size: 32Mb*/
        contentBuffer.reset();
        while (len != -1) {
            contentBuffer.write(buffer, 0, len);
            len = record.read(buffer, 0, buffer.length);
        }
        record.close();
        return contentBuffer.toByteArray();
    }

    public static void readWarcRecords(String warcURL, InformationExtractor context, Consumer<WARCRecordResponseEncapsulated> consumer) {
        logger.debug("Reading WARC records for: " + warcURL);
        ArchiveReader reader = null;
        try {
            reader = WARCReaderFactory.get(warcURL);
        } catch (Exception e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.WARCS_FAILED).increment(1);
            logger.error("Exception starting reading WARC", e);
            return;
        }
        int records = 0;
        int errors = 0;
        reader.setStrict(true);
        Iterator<ArchiveRecord> ii = reader.iterator();

        try {
            while (ii.hasNext()) {
                WARCRecord warcRecord;
                try {
                    warcRecord = (WARCRecord) ii.next();
                    WARCRecordResponseEncapsulated record = parseWarcRecord(warcRecord, context);
                    if (record != null)
                        consumer.accept(record);
                } catch (RuntimeException re) {
                    context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORD_NEXT_FAILED).increment(1);
                    errors++;
                    logger.error("Exception reading next WARC record", re);
                    throw re;
                }
            }
        } catch (RuntimeException e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.WARCS_FAILED_STREAM).increment(1);
            FileUtils.deleteQuietly(new File(warcURL));
            logger.error("Exception reading WARC bytes, WARCNAME: " + warcURL + " " + e.getMessage());
            throw e;
        }

        logger.info("WARCS RECORDS READ: " + records + " ERRORS: " + errors);
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.debug("error closing ArchiveReader" + e.getMessage());
            }
        }

    }

    /**
     * Parses a WARC record and extracts image and page information
     *
     * @param warcRecord warc record
     * @param context Hadoop context
     * @return object to send back the processed object
     */
    public static WARCRecordResponseEncapsulated parseWarcRecord(WARCRecord warcRecord, InformationExtractor context){
        String warcRecordType = (String) warcRecord.getHeader().getHeaderValue(WARCConstants.HEADER_KEY_TYPE);
        String warcRecordMimetype = warcRecord.getHeader().getMimetype();
        String warcName = ((String) warcRecord.getHeader().getHeaderValue(WARCConstants.READER_IDENTIFIER_FIELD_KEY));

        try {
            if (warcRecordType.equalsIgnoreCase(WARCConstants.WARCRecordType.resource.toString())) {
                Map<String, Object> headers = new HashMap<>();
                headers.put(WARCConstants.CONTENT_LENGTH.toLowerCase(), String.valueOf(warcRecord.getHeader().getContentLength()));
                headers.put(WARCConstants.CONTENT_TYPE.toLowerCase(), warcRecordMimetype);
                headers.put(WARCConstants.MIMETYPE_FIELD_KEY.toLowerCase(), warcRecordMimetype);

                context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_READ).increment(1);
                return new WARCRecordResponseEncapsulated(warcRecord, headers, warcName);

            } else {
                context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_READ).increment(1);
                return new WARCRecordResponseEncapsulated(warcRecord, warcName);
            }

        } catch (InvalidWARCResponseIOException e) {
            /* This is not a WARCResponse; skip */
        } catch (IOException e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_FAILED).increment(1);
            logger.error("IO Exception reading WARCrecord WARCNAME: " + warcName + " " + e.getMessage());
        } catch (Exception e) {
            context.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.RECORDS_FAILED).increment(1);
            logger.error("Exception reading WARCrecord WARCNAME: " + warcName + " " + e.getMessage());
        }
        return null;
    }

    /**
     * Guess encoding from page bytes
     *
     * @param bytes page as a byte array
     * @return page encoding code
     */
    public static String guessEncoding(byte[] bytes) {
        String DEFAULT_ENCODING = "UTF-8";
        org.mozilla.universalchardet.UniversalDetector detector =
                new org.mozilla.universalchardet.UniversalDetector(null);
        detector.handleData(bytes, 0, bytes.length);
        detector.dataEnd();
        String encoding = detector.getDetectedCharset();
        detector.reset();
        if (encoding == null) {
            encoding = DEFAULT_ENCODING;
        }
        return encoding;
    }

    /**
     * Decode page from generated encoding. Tries to reencode UTF-8/ISO_8859_1 mismatches
     *
     * @param arcRecordBytes page HTML bytes
     * @param context context used to increment counters
     * @return page HTML as String
     * @throws IOException if page is malformed
     */
    public static String decode(byte[] arcRecordBytes, InformationExtractor context) throws IOException {
        String recordEncoding = ImageSearchIndexingUtil.guessEncoding(arcRecordBytes);
        InputStream is = new ByteArrayInputStream(arcRecordBytes);
        String html = IOUtils.toString(is, recordEncoding);
        //if the chars in UTF8_MISMATCH were detected, this means that the page is in UTF_8 but encoded in ISO_8859_1
        //if we re-encode the string, the accented chars will be correctly represented
        if (ImageSearchIndexingUtil.UTF8_MISMATCH.matcher(html).find()){
            context.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.PAGE_UTF8_MISMATCH).increment(1);
            byte[] b = html.getBytes(StandardCharsets.ISO_8859_1);
            String newHtml = new String(b, StandardCharsets.UTF_8);
            //if the chars are detected again, the page is beyond repair and the initial encoding is used
            if (!ImageSearchIndexingUtil.UTF8_MISMATCH.matcher(newHtml).find()){
                context.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.PAGE_UTF8_MISMATCH_DOUBLE).increment(1);
                html = newHtml;
            }
        }

        return html;
    }


    /**
     * Remove symbols from URL to transform into tokens
     *
     * @param toParse URL
     * @return URL ready to split into tokens
     */
    public static String parseURL(String toParse) {
        if (toParse.startsWith("hash:"))
            return "";
        return cleanPunctuation(toParse).trim();
    }

    /**
     * Remove punctuation fron string
     *
     * @param toParse string to parse
     * @return string without punctuation
     */
    public static String cleanPunctuation(String toParse) {
        return String.join(" ", toParse.split(SPLIT_PATTERN)).replaceAll("\\s+", " ");
    }

    /**
     * Transform DateTime objects into String dates in the form yyyyMMddHHmmss
     *
     * @param timestamps Array of timestamp objects
     * @return Array of yyyyMMddHHmmss strings
     */
    public static List<String> getTimestampStandardFormat(List<LocalDateTime> timestamps) {
        List<String> output = new LinkedList<>();
        for (LocalDateTime l : timestamps)
            output.add(l.toString());
        return output;
    }

    /**
     * Gets the filesize for a remote URL
     * This method is used to ensure that WARCs are downloaded fully
     *
     * @param url URL from which to get the size
     * @return File size under URL
     */
    public static long getFileSize(URL url) {
        URLConnection conn = null;
        try {
            conn = url.openConnection();
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).setRequestMethod("HEAD");
            }
            return conn.getContentLengthLong();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (conn instanceof HttpURLConnection) {
                ((HttpURLConnection) conn).disconnect();
            }
        }
    }

    public static boolean isInteralOutlink(String url, String outlink){
        // page hosts match
        try {
            URL urlVal = new URL(url);
            URL outlinkVal = new URL(outlink);
            return urlVal.getHost().equals(outlinkVal.getHost());
        } catch (MalformedURLException e) {
            return false;
        }     
    }

}
