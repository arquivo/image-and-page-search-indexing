package pt.arquivo.imagesearch.indexing.processors;

import com.sun.jersey.core.util.Base64;
import org.archive.format.warc.WARCConstants;
import org.archive.io.ArchiveRecord;
import org.archive.io.warc.WARCRecord;
import pt.arquivo.imagesearch.indexing.ImageIndexerWithDupsJob;
import pt.arquivo.imagesearch.indexing.utils.ImageParse;
import pt.arquivo.imagesearch.indexing.data.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.counters.GenericCounter;
import org.apache.log4j.Logger;
import org.archive.io.arc.ARCRecord;
import org.jsoup.Jsoup;
import org.jsoup.helper.StringUtil;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import pt.arquivo.imagesearch.indexing.utils.ImageSearchIndexingUtil;
import pt.arquivo.imagesearch.indexing.utils.WARCInformationParser;
import pt.arquivo.imagesearch.indexing.utils.WARCRecordResponseEncapsulated;

import javax.imageio.ImageIO;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auxiliary class to extract metadat from pages and images
 */
@SuppressWarnings("rawtypes")
public class ImageInformationExtractor implements InformationExtractor {

    /**
     * Image file extensions to be included when parsing from <a> tags
     */
    private static final Set<String> IMAGE_FILE_EXTENSIONS = new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "tif", "tiff", "gif", "svg", "webp", "bmp", "ico"));


    /**
     * Image atrributes to be included when parsing from <img> tags
     */
    private static final Set<String> IMAGE_TAG_ATTRIBUTES_WITH_FILES = new HashSet<>(Arrays.asList("src", "lowsrc"));

    /**
     * Regex to find image urls in CSS
     */
    public static final Pattern CSS_URLS = Pattern.compile("url\\(['\"]*(.*?)['\"]*\\)");

    /**
     * Maximum caption size for the parent method
     */
    public static final int MAX_PARENT_CAPTION_SIZE = 250;

    /**
     * Maximum field sizes for image metadatas
     */
    public static final int MAX_IMAGE_FIELD_SIZE = 10000;

    public static final String DATA_IMAGE_URL_PREFIX = "data:image";

    /**
     * Maximum number of images to parse per HTML file
     */
    public static final int MAX_IMAGE_IN_HTML = 10000;

    /**
     * Maximum time to spend extracting captions per page.
     * Used to avoid getting stuck in malformed structures
     */
    public static final int EXTRACT_CAPTION_TIMEOUT_SECS = 60 * 2;


    private Logger logger = Logger.getLogger(ImageInformationExtractor.class);

    /**
     * Images already parsed during this session
     */
    protected HashMap<String, FullImageMetadata> entries;

    /**
     * Collection name
     */
    protected String collection;

    /**
     * Hadoop context
     */
    private Mapper.Context context;

    /**
     * Stores the counters. This enables using this code both inside and outside Hadoop
     */
    private HashMap<Enum<?>, Counter> localCounters;

    /**
     * Used to store parent captions when transversing up the DOM tree for caption extraction
     */
    private HashMap<String, String> captionCache;


    /**
     * Constructor used for Hadoop
     *
     * @param collection collection name
     * @param context Hadoop context
     */
    public ImageInformationExtractor(String collection, Mapper.Context context) {
        init(collection);
        this.context = context;
    }

    /**
     * Constructor used for local parser
     *
     * @param collection collection name
     */
    public ImageInformationExtractor(String collection) {
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
        entries = new HashMap<>();
        captionCache = new HashMap<>();
        ImageIO.setUseCache(false);
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
     * @param arcURL (W)ARCs url
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
     * @param warcURL WARC url
     */
    public void parseWarcEntryRecord(String warcName, String warcURL) {
        ImageSearchIndexingUtil.readWarcRecords(warcURL, this, (record) -> {
            parseWarcRecord(record, warcName);
        });

    }

    /**
     * Parse a WARC record inner method
     *
     * @param record WARC record object
     * @param warcName WARC name
     */
    public void parseWarcRecord(WARCRecordResponseEncapsulated record, String warcName) {
        String mimetype = record.getContentMimetype();
        if (mimetype != null) {
            if (mimetype.contains("image")) {
                createImageDB(record, context, warcName, record.getWARCRecord().getHeader().getOffset());
            }
            if (mimetype.contains("html")) { /*only processing images*/
                logger.debug("Searching images in html record");
                parseImagesFromHtmlRecord(context, record.getContentBytes(), record.getWARCRecord().getHeader().getUrl(), record.getTs(), warcName, record.getWARCRecord().getHeader().getOffset());
            }
        }
    }

    /**
     * Parse a ARC record
     *
     * @param arcName ARC name
     * @param arcURL ARC url
     */
    public void parseArcEntry(String arcName, String arcURL) {
        ImageSearchIndexingUtil.readArcRecords(arcURL, this, record -> {
            parseArcRecord(record, arcName);
        });
    }

    /**
     * Parse a ARC record inner method
     *
     * @param record ARC record object
     * @param arcName ARC url
     */
    public void parseArcRecord(ARCRecord record, String arcName) {
        if (record.getMetaData().getMimetype().contains("image")) {
            createImageDB(record, context, arcName, record.getMetaData().getOffset());
        } else if (record.getMetaData().getMimetype().contains("html")) {
            byte[] recordContentBytes;
            try {
                recordContentBytes = ImageSearchIndexingUtil.getRecordContentBytes(record);
            } catch (IOException e) {
                logger.error(String.format("Error getting record content bytes for (w)arc: %s on offset %d with error message %s", arcName, record.getBodyOffset(), e.getMessage()));
                return;
            }
            logger.debug("Searching images in html record");
            parseImagesFromHtmlRecord(context, recordContentBytes, record.getHeader().getUrl(), record.getMetaData().getDate(), arcName, record.getMetaData().getOffset());
        }
    }

    /**
     * Create ImageData record for inline images
     *
     * @param url inline imgage URL
     * @param timestamp image timestamp
     * @param warcName name of the WARC whete the page was found
     * @param warcOffset offset of the HTML file where the image was found in bytes
     * @return ImageData record for inline image
     */
    public ImageData saveImageMetadataInline(String url, String timestamp, String warcName, long warcOffset) {
        try {
            String[] surl = url.split(",");


            String[] metadata = surl[0].split(";");
            String reportedMimeType = metadata[0].split(":")[1];

            String data = url.substring(surl[0].length() + 1);
            String imageURLHashKey = ImageSearchIndexingUtil.md5ofString(url);

            byte[] contentBytes = data.getBytes();
            for (String meta : metadata) {
                if (meta.contains("base64")) {
                    contentBytes = Base64.decode(data);
                    break;
                }
            }

            return saveImageMetadata("hash:" + imageURLHashKey, imageURLHashKey, timestamp, reportedMimeType, contentBytes, warcName, warcOffset);
        } catch (Exception e) {
            logger.error(String.format("Malformed inline image"));
            return null;
        }
    }

    /**
     * Create ImageData record for regular images
     *
     * @param url iamge url
     * @param imageURLHashKey iamge url hash key
     * @param timestamp image capture timestamp
     * @param reportedMimeType image mimetype reported by server
     * @param contentBytes image bytes
     * @param warcName name of the WARC
     * @param warcOffset offset of the record in the WARC
     * @return ImageData record for that image
     */
    public ImageData saveImageMetadata(String url, String imageURLHashKey, String timestamp, String reportedMimeType, byte[] contentBytes, String warcName, long warcOffset) {

        String imgSurt = WARCInformationParser.toSURT(url);


        String detectedMimeType = "";

        try {

            detectedMimeType = WARCInformationParser.getMimeType(contentBytes);

            if (detectedMimeType == null) {
                this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_MIME_INVALID).increment(1);
                detectedMimeType = "";
            } else if (!detectedMimeType.isEmpty() && !detectedMimeType.equals(reportedMimeType)) {
                logger.debug(String.format("MimeType for http://arquivo.pt/wayback/%s/%s", timestamp, url));
                logger.debug(String.format("reported: \"%s\" ; detected: \"%s\"", reportedMimeType, detectedMimeType));
                this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_MIME_WRONG).increment(1);
            }
        } catch (Exception e) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_MIME_INVALID).increment(1);
        }

        ImageData imageData = new ImageData(imageURLHashKey, timestamp, url, imgSurt, reportedMimeType, detectedMimeType, this.collection, contentBytes, warcName, warcOffset);

        try {
            imageData = ImageParse.getPropImage(imageData);
        } catch (Exception | StackOverflowError e) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_FAILED).increment(1);
            return null;
        }


        if (imageData == null) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_FAILED).increment(1);
        } else if (url.startsWith("hash:") && (imageData.getWidth() < ImageParse.MIN_WIDTH || imageData.getHeight() < ImageParse.MIN_HEIGHT)) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_TOO_SMALL_BASE64).increment(1);
        } else if (imageData.getWidth() < ImageParse.MIN_WIDTH || imageData.getHeight() < ImageParse.MIN_HEIGHT) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_TOO_SMALL).increment(1);
        } else if (imageData.getWidth() * imageData.getHeight() > ImageParse.MAX_HEIGHT * ImageParse.MAX_HEIGHT) {
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_TOO_LARGE).increment(1);
        } else {

            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_PARSED_DUP).increment(1);
            FullImageMetadata fullImageMetadata = entries.get(imageData.getSurt());
            if (fullImageMetadata == null) {
                fullImageMetadata = new FullImageMetadata();
                entries.put(imageData.getSurt(), fullImageMetadata);
            }
            boolean isNew = entries.get(imageData.getSurt()).addImageData(imageData);
            if (isNew)
                this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_PARSED).increment(1);

            return imageData;
        }
        return null;
    }

    public void createImageDB(WARCRecordResponseEncapsulated record, Mapper.Context context, String warcName, long warcOffset) {
        String url = "";
        String timestamp = "";
        try {
            url = record.getWARCRecord().getHeader().getUrl();
            timestamp = record.getTs();
            String mime = record.getContentMimetype();

            String imageURLHashKey = ImageSearchIndexingUtil.md5ofString(url);
            byte[] contentBytes = null;

            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_TOTAL).increment(1);

            try {
                contentBytes = record.getContentBytes();
            } catch (RuntimeException e) {
                logger.error(String.format("Error getting record content bytes for image url: %s/%s with error message %s", timestamp, url, e.getMessage()));
                this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_FAILED).increment(1);
                return;
            }

            saveImageMetadata(url, imageURLHashKey, timestamp, mime, contentBytes, warcName, warcOffset);

        } catch (Exception e) {
            logger.error(String.format("Error parsing image url: %s/%s with error message %s", timestamp, url, e.getMessage()));
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_FAILED).increment(1);
        }
    }

    public ImageData createImageDB(ARCRecord record, Mapper.Context context, String warcName, long warcOffset) {
        String url = record.getHeader().getUrl();
        String timestamp = record.getMetaData().getDate();
        String mime = record.getMetaData().getMimetype();
        String imageURLHashKey = ImageSearchIndexingUtil.md5ofString(url);

        byte[] contentBytes;

        this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_TOTAL).increment(1);

        try {
            contentBytes = ImageSearchIndexingUtil.getRecordContentBytes(record);
        } catch (IOException e) {
            logger.error(String.format("Error getting record content bytes for image url: %s/%s on offset %d with error message %s", timestamp, url, record.getBodyOffset(), e.getMessage()));
            this.getCounter(ImageIndexerWithDupsJob.IMAGE_COUNTERS.IMAGES_IN_WARC_FAILED).increment(1);
            return null;
        }

        return saveImageMetadata(url, imageURLHashKey, timestamp, mime, contentBytes, warcName, warcOffset);
    }

    public void parseImagesFromHtmlRecord(Mapper.Context context, byte[] arcRecordBytes, String pageURL, String
            pageTstamp, String warcName, long warcOffset) {
        try {

            captionCache = new HashMap<>();


            logger.debug("Parsing Images from HTML in (W)ARCrecord");
            logger.debug("Read Content Bytes from (W)ARCrecord" + arcRecordBytes.length);
            logger.debug("URL: " + pageURL);
            logger.debug("Page TS: " + pageTstamp);



            String html = ImageSearchIndexingUtil.decode(arcRecordBytes, this);

            if (pageTstamp == null || pageTstamp.equals("")) {
                logger.debug("Null pageTstamp");
                pageTstamp = "";
            }

            logger.debug("pageTstamp:" + pageTstamp);

            parseHTMLPage(pageURL, pageTstamp, warcName, warcOffset, html);

        } catch (Exception e) {
            logger.debug("Something failed JSOUP parsing " + e.getMessage());
        }


    }

    public void parseHTMLPage(String pageURL, String pageTstamp, String warcName, long warcOffset, String html) throws MalformedURLException, UnsupportedEncodingException {
        boolean malformedPageForCaptions = false;
        long startTime = System.nanoTime();

        Document doc = Jsoup.parse(html, pageURL);

        String pageTitle = doc.title(); /*returns empty string if no title in html document*/

        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.PAGES).increment(1);
        //Find all images in img tags


        Elements imgs = doc.getElementsByTag("img");
        int pageImages = imgs.size();

        //logger.debug("Page contains: " + pageImages + " images");

        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_TOTAL).increment(pageImages);

        if (!pageURL.startsWith("http"))
            pageURL = "http://" + pageURL;

        URL uri = new URL(pageURL);
        String pageHost = uri.getHost();
        String pageProtocol = uri.getProtocol();
        String pageURLTokens = getURLSrcTokens(pageURL);

        Set<String> imgSrcParsed = new HashSet<>();

        int parsedImagesPerPage = 0;
        try {
            for (Element el : imgs) {

                Set<String> imgSrcAtrToParse = getImgURLToParse(pageURL, el);

                for (String imgRelSrc : imgSrcAtrToParse) {

                    String imgSrc = StringUtil.resolve(pageURL, imgRelSrc);

                    imgSrcParsed.add(imgRelSrc);

                    logger.debug("Getting information for: " + imgSrc);
                    if (imgRelSrc.startsWith(DATA_IMAGE_URL_PREFIX)) {
                        logger.debug("Inline image");
                        ImageData acceptedRecord = saveImageMetadataInline(imgRelSrc, pageTstamp, warcName, warcOffset);
                        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_BASE64).increment(1);
                        if (acceptedRecord == null)
                            continue;
                        imgSrc = acceptedRecord.getUrl();
                    } else if (imgSrc.length() > MAX_IMAGE_FIELD_SIZE || pageURL.length() > MAX_IMAGE_FIELD_SIZE) {
                        logger.debug("URL of image too big ");
                        logger.debug(pageURL.substring(0, 500) + "...");
                        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                        continue;
                    } else if (imgRelSrc.isEmpty()) {
                        logger.debug("Null imgSrc");
                        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                        continue;
                    }

                    this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING).increment(1);

                    String imgSrcTokens = getURLSrcTokens(imgSrc);
                    String imgTitle = getHTMLAttribute(el, "title");
                    String imgAlt = getHTMLAttribute(el, "alt");

                    String imgCaption = "";

                    // some pages exhaust Java Heap Space due to very complex DOM structures
                    // This generates Java Heap Space OOM exceptions; if that happens,
                    // stop processing captions for the reminder of the page
                    // Check the {@link #getCaption(Element current)} method to see how this is handled.
                    if (!malformedPageForCaptions) {
                        imgCaption = extractCaptionFromParent(el);
                        if (imgCaption == null) {
                            malformedPageForCaptions = true;
                            imgCaption = "";
                            logger.debug("Page generated Java Heap space error: " + pageURL);
                            logger.debug("Skipping caption extraction for the remainder of the page");
                        }

                        long duration = (System.nanoTime() - startTime) / 1000000000;
                        if (duration > EXTRACT_CAPTION_TIMEOUT_SECS){
                            malformedPageForCaptions = true;
                            logger.debug("Page takes very long extrating caption: " + pageURL);
                            logger.debug("Skipping caption extraction for the remainder of the page");
                        }

                    }

                    insertImageIndexes(imgSrc, imgSrcTokens, imgTitle, imgAlt, imgCaption, pageImages, pageTstamp, pageURL, pageHost, pageProtocol, pageTitle, pageURLTokens, "img", warcName, warcOffset);

                    logger.debug("Written to file - successfully indexed image record");
                }

                parsedImagesPerPage++;

                if (parsedImagesPerPage >= MAX_IMAGE_IN_HTML) {
                    this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_EXCEDED).increment(1);
                    this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_NOT_PARSED).increment(imgs.size() - parsedImagesPerPage);
                    break;
                }
            }
        } catch (Exception e) {
            logger.error(String.format("Error parsing HTML img record: %s", e.getMessage()));
            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_FAILED).increment(pageImages);
        }


        Elements links = doc.getElementsByTag("a");

        logger.debug("Page contains: " + links.size() + " links");

        try {

            for (Element el : links) {

                String imgSrc = el.attr("abs:href");
                String imgRelSrc = el.attr("href").trim();

                imgSrcParsed.add(imgRelSrc);
                if (!isLinkToImage(imgSrc))
                    continue;

                logger.debug("Getting information for: " + imgSrc);

                if (imgSrc.length() > MAX_IMAGE_FIELD_SIZE || pageURL.length() > MAX_IMAGE_FIELD_SIZE) {
                    logger.debug("URL of image too big ");
                    logger.debug(pageURL.substring(0, 500) + "...");
                    this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                    continue;
                } else if (imgRelSrc.isEmpty()) {
                    logger.debug("Null href");
                    this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                    continue;
                }

                this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING).increment(1);
                this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING_LINK).increment(1);

                String imgSrcTokens = getURLSrcTokens(imgSrc);


                String imgCaption = el.text();
                if (imgCaption.length() >= MAX_IMAGE_FIELD_SIZE) {
                    imgCaption = imgCaption.substring(0, MAX_IMAGE_FIELD_SIZE);
                }


                insertImageIndexes(imgSrc, imgSrcTokens, "", "", imgCaption, pageImages, pageTstamp, pageURL, pageHost, pageProtocol, pageTitle, pageURLTokens, "a", warcName, warcOffset);

                logger.debug("Written to file - successfully indexed image record");

            }

        } catch (Exception e) {
            logger.error(String.format("Error parsing HTML img record: %s", e.getMessage()));
            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_FAILED).increment(pageImages);
        }

        List<String> cssUrls = new LinkedList<>();
        Matcher m = CSS_URLS.matcher(html);
        while (m.find()) {
            String imgRelSrc = m.group(1);
            if (!imgRelSrc.isEmpty() && !imgSrcParsed.contains(imgRelSrc)) {
                if (imgRelSrc.startsWith(DATA_IMAGE_URL_PREFIX)) {
                    cssUrls.add(imgRelSrc);
                } else {
                    try {
                        String imgSrc = StringUtil.resolve(pageURL, imgRelSrc);
                        if (isLinkToImage(imgSrc)) {
                            cssUrls.add(imgRelSrc);
                        }

                    } catch (Exception ignored) {

                    }
                }
            }
        }

        for (String imgRelSrc : cssUrls) {

            String imgSrc = StringUtil.resolve(pageURL, imgRelSrc);

            logger.debug("Getting information for: " + imgSrc);

            if (imgSrc.length() > MAX_IMAGE_FIELD_SIZE || pageURL.length() > MAX_IMAGE_FIELD_SIZE) {
                logger.debug("URL of image too big ");
                logger.debug(pageURL.substring(0, 500) + "...");
                this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                continue;
            } else if (imgRelSrc == null || imgRelSrc.equals("")) {
                logger.debug("Null href");
                this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_INVALID).increment(1);
                continue;
            }

            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING).increment(1);
            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING_CSS).increment(1);

            String imgSrcTokens = getURLSrcTokens(imgSrc);

            insertImageIndexes(imgSrc, imgSrcTokens, "", "", "", pageImages, pageTstamp, pageURL, pageHost, pageProtocol, pageTitle, pageURLTokens, "css", warcName, warcOffset);

            logger.debug("Written to file - successfully indexed image record");

        }


        if (imgs.size() > 0)
            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.PAGES_WITH_IMAGES).increment(1);
    }

    /**
     * Checks if url extension matches an image
     * @param imgSrc URL to be tested
     * @return true if ti matches
     */
    public boolean isLinkToImage(String imgSrc) {

        String extension = "";
        try {
            URL url = new URL(imgSrc);
            extension = FilenameUtils.getExtension(url.getPath()).toLowerCase();
        } catch (Exception ignored) {
            return false;
        }

        return IMAGE_FILE_EXTENSIONS.contains(extension);
    }

    /**
     * Extracts caption from HTML parent
     *
     * @param node node where to start from finding caption
     * @return image caption
     */
    public String extractCaptionFromParent(Element node) {
        String imgCaption = "";

        int maxChildLevel = getMaxChildLevel(node);

        Element previous = node;
        Element current = node;

        int i = 0;
        // Go up the DOM tree until something is found or root is reached
        while (current != null && (imgCaption = getCaption(current)) != null && imgCaption.isEmpty()) {
            previous = current;
            current = current.parent();
            i++;
        }

        // recover from OOM error in the {@link #getCaption(Element current)} method.
        if (previous == null || imgCaption == null)
            return null;

        if (i >= maxChildLevel) {
            imgCaption = getImgCaptionSibling(previous);
        }

        // recover from OOM error in the {@link #getCaption(Element current)} method.
        if (imgCaption == null)
            return null;

        // No need for additional checks, as imgCaption will be empty if other conditions fail
        imgCaption = trimCaption(imgCaption);
        return imgCaption;
    }


    /**
     * Get caption text for current element
     *
     * @param current node to parse
     * @return children/caption text for the current element
     */
    private String getCaption(Element current) {
        String id = getNodeId(current);
        String caption;
        if ((caption = captionCache.getOrDefault(id, null)) == null) {
            try {
                if (current.tagName().equalsIgnoreCase("html") || current.tagName().equalsIgnoreCase("#root"))
                    caption = "";
                else
                    caption = current.text().trim();
                captionCache.put(id, caption);
            } catch (OutOfMemoryError e) {
                captionCache.clear();
            }

        }
        return caption;
    }

    /**
     * Get current node id, according to its depth
     *
     * @param current current node
     * @return node id
     */
    private String getNodeId(Element current) {
        StringBuilder id = new StringBuilder();
        while (current != null) {
            id.insert(0, current.elementSiblingIndex() + "_");
            current = current.parent();
        }
        return id.toString();
    }

    /**
     * Extract caption from node siblings
     *
     * @param current current node from which to get the nodes
     * @return caption from siblings
     */
    private String getImgCaptionSibling(Element current) {
        String imgCaption;
        Element sibling = current.previousElementSibling();
        String imgCaptionPrev = "";
        String imgCaptionNext = "";
        while (sibling != null && (imgCaptionPrev = getCaption(sibling)) != null && imgCaptionPrev.isEmpty()) {
            sibling = sibling.previousElementSibling();
        }

        sibling = current.nextElementSibling();
        while (sibling != null && (imgCaptionNext = getCaption(sibling)) != null && imgCaptionNext.isEmpty()) {
            sibling = sibling.nextElementSibling();
        }

        // recover from OOM error in the {@link #getCaption(Element current)} method.
        if (imgCaptionPrev == null || imgCaptionNext == null)
            return null;

        imgCaption = (imgCaptionPrev + "\n" + imgCaptionNext).trim();
        return imgCaption;
    }

    /**
     * Trims captions so that words are not split
     *
     * @param imgCaption  caption to be trimmed
     * @return trimmed caption
     */
    private String trimCaption(String imgCaption) {
        if (imgCaption.length() > MAX_PARENT_CAPTION_SIZE) {
            // Crop until closest empty space near the chosen text border (MAX_PARENT_CAPTION_SIZE chars in the end of the caption)
            int lastSpace = imgCaption.substring(0, MAX_PARENT_CAPTION_SIZE / 2).lastIndexOf(" ");
            if (lastSpace == -1)
                lastSpace = MAX_PARENT_CAPTION_SIZE / 2;

            String newImgCaption = imgCaption.substring(0, lastSpace).trim();

            lastSpace = (imgCaption.length() - MAX_PARENT_CAPTION_SIZE / 2) + imgCaption.substring(imgCaption.length() - MAX_PARENT_CAPTION_SIZE / 2).indexOf(" ");

            newImgCaption += "\n" + imgCaption.substring(lastSpace).trim();
            imgCaption = newImgCaption.trim();
        }
        return imgCaption;
    }

    /**
     * Get max node depth to choose between sibling and parent method
     *
     * @param current image node to start from
     * @return trimmed caption
     */
    private int getMaxChildLevel(Element current) {
        int maxChildLevel = 0;
        int maxChildLevelCount = 0;
        int i = 0;
        while (current != null) {
            if (maxChildLevelCount <= current.childNodeSize()) {
                maxChildLevelCount = current.childNodeSize();
                maxChildLevel = i;
            }

            current = current.parent();
            i++;
        }
        return maxChildLevel;
    }

    /**
     * Get HTML attribute from node
     * @param el node from which to extract atrribute
     * @param atr attribute to extract
     * @return attribute value
     */
    public String getHTMLAttribute(Element el, String atr) {
        String atrVal = el.attr(atr);
        if (atrVal.length() >= MAX_IMAGE_FIELD_SIZE) {
            atrVal = atrVal.substring(0, MAX_IMAGE_FIELD_SIZE);
        }
        return atrVal;
    }

    /**
     * Find all potential image URLs for this node
     *
     * @param pageURL base page URL
     * @param el current element
     * @return set of URLs to parse
     */
    public Set<String> getImgURLToParse(String pageURL, Element el) {
        Set<String> imgSrcAtrToParse = new HashSet<>();

        //Find all images in normal img src attributes
        for (String tag : IMAGE_TAG_ATTRIBUTES_WITH_FILES) {
            String imgRelSrc = el.attr(tag).trim();
            if (!imgRelSrc.isEmpty())
                imgSrcAtrToParse.add(imgRelSrc);
        }

        int oldSize = imgSrcAtrToParse.size();

        //Find text that matches URLs in all other img src attributes
        for (Attribute attribute : el.attributes()) {
            try {
                String imgRelSrc = attribute.getValue();
                if (imgRelSrc != null && !imgRelSrc.isEmpty()) {
                    if (imgRelSrc.startsWith(DATA_IMAGE_URL_PREFIX)) {
                        imgSrcAtrToParse.add(imgRelSrc);
                    } else {
                        String imgSrc = StringUtil.resolve(pageURL, imgRelSrc);
                        if (isLinkToImage(imgSrc)) {
                            imgSrcAtrToParse.add(imgRelSrc);
                        }
                    }
                }
            } catch (Exception ignored) {
                //Crashes if attribute is not an URL, safe to ignore
            }
        }

        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_MATCHING_ALT_ATRIB).increment(imgSrcAtrToParse.size() - oldSize);

        return imgSrcAtrToParse;
    }

    /**
     * SPlits URL into tokens
     *
     * @param imgSrc URL to split
     * @return URL tokens
     */
    public static String getURLSrcTokens(String imgSrc)  {
        try {
            imgSrc = URLDecoder.decode(imgSrc, "UTF-8"); /*Escape imgSrc URL e.g %C3*/
        } catch (Exception ignored) {

        }
        if (imgSrc.startsWith("http://") || imgSrc.startsWith("https://")) {
            imgSrc = imgSrc.substring(imgSrc.indexOf("://"));
        }
        if (imgSrc.startsWith("www.")) {
            imgSrc = imgSrc.substring(imgSrc.indexOf("."));
        }
        return ImageSearchIndexingUtil.parseURL(imgSrc);
    }

    /**
     * Create an PageImageData entry from parsed data
     *
     * @param imgSrc img URL
     * @param imgSrcTokens img URL tokens
     * @param imgTitle image title (is exists)
     * @param imgAlt image alt text (is exists)
     * @param imgCaption image caption
     * @param pageImages number of images in the page
     * @param pageTstamp page capture timestamp
     * @param pageURL page url
     * @param pageHost page host
     * @param pageProtocol page protocol
     * @param pageTitle page title
     * @param pageURLTokens page url tokens
     * @param foundInTag found in which tags
     * @param warc WARC name
     * @param warcOffset Offset in WARC
     */
    public void insertImageIndexes(String imgSrc, String imgSrcTokens, String imgTitle, String imgAlt,
                                   String imgCaption, int pageImages, String pageTstamp, String pageURL, String pageHost, String pageProtocol, String
                                           pageTitle, String pageURLTokens, String foundInTag, String warc, long warcOffset) {
        String imgSurtSrc = WARCInformationParser.toSURT(imgSrc);

        PageImageData pageImageData = new PageImageData("page", imgTitle, imgAlt, imgSrcTokens, imgCaption, pageTitle, pageURLTokens, imgSrc, imgSurtSrc, pageImages, pageTstamp, pageURL, pageHost, pageProtocol, foundInTag, warc, warcOffset, collection);

        this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_SENT_DUP).increment(1);

        FullImageMetadata fullImageMetadata = entries.get(pageImageData.getImgSurt());
        if (fullImageMetadata == null) {
            fullImageMetadata = new FullImageMetadata();
            entries.put(pageImageData.getImgSurt(), fullImageMetadata);
        }

        boolean isNew = entries.get(pageImageData.getImgSurt()).addPageImageData(pageImageData);
        if (isNew) {
            this.getCounter(ImageIndexerWithDupsJob.PAGE_COUNTERS.IMAGES_IN_HTML_SENT).increment(1);
        }
    }

    /**
     * Get parsed metadatas
     *
     * @return parsed metadatas
     */
    public HashMap<String, FullImageMetadata> getEntries() {
        return entries;
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
}
