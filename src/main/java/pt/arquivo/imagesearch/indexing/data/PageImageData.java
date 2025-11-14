package pt.arquivo.imagesearch.indexing.data;


import org.apache.commons.io.FilenameUtils;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import pt.arquivo.imagesearch.indexing.utils.WARCInformationParser;

import pt.arquivo.imagesearch.indexing.utils.ImageSearchIndexingUtil;

public class PageImageData implements Comparable<LocalDateTime>, Serializable {


    /**
     * Collection where to which this page matches
     */
    private String collection;

    /**
     * (W)ARC name
     */
    private String warc;

    /**
     * (W)ARC offset in bytes
     */
    private long warcOffset;


    /**
     * Always page for this object
     */
    private String type;


    /**
     * Title of the image (if exists)
     */
    private String imgTitle;

    /**
     * Image alt text (if exists)
     */
    private String imgAlt;

    /**
     * Image filename
     */
    private String imgFilename;

    /**
     * Image caption extracted from the HTML
     */
    private String imgCaption;

    /**
     * Image capture timestamp
     */
    private LocalDateTime imgTimestamp;

    /**
     * Oldest matching page title
     */
    private String pageTitle;

    /**
     * Oldest matching page url split into word tokens
     */
    private String pageURLTokens;

    /**
     * Deprecated img id form yyyyMMddHHmmss/{imageURLHash}
     */
    private String imgId;

    /**
     * Image url
     */
    private String imgURL;

    /**
     * Oldest matching image url split into word tokens
     */
    private String imgURLTokens;

    /**
     * Image SURT
     */
    private String imgSurt;

    /**
     * Image height in pixels
     */
    private int imgHeight;

    /**
     * Image width in pixels
     */
    private int imgWidth;

    /**
     * Image mime type
     */
    private String imgMimeType;

    /**
     * (W)ARC where the image was found
     */
    private String imgWarc;

    /**
     * Offset in the imgWARC in byes
     */
    private long imgWarcOffset;

    /**
     * Oldest page capture timestamp
     */
    private LocalDateTime pageTimestamp;

    /**
     * Newest page capture timestamp
     */
    private LocalDateTime pageTimestampNewest;

    /**
     * Oldest page capture timestamp in the yyyyMMddHHmmss format
     */
    private String pageTimestampString;

    /**
     * Oldest matching page URL
     */
    private String pageURL;

    /**
     * Host of the matching page
     */
    private String pageHost;

    /**
     * Page protocol (http vs. https)
     */
    private String pageProtocol;

    /**
     * Number of images that were matched to this object
     */
    private int matchingImages;

    /**
     * Number of pages that were matched to this object
     */
    private int matchingPages;

    /**
     * Number of times image metadata has changed in this object
     */
    private int imageMetadataChanges;

    /**
     * Number of times page metadata has changed in this object
     */
    private int pageMetadataChanges;

    /**
     * Number of images in the orginal page
     */
    private int imagesInPage;

    /**
     * Number of digests that were found under this URL
     */
    private int uniqueDigestsOnURL;


    /**
     * Whether the image is an inline base64 images
     */
    private boolean inline;

    /**
     * Whether the image was found in a <a>, <img> or CSS tag
     */
    private Set<String> tagFoundIn;

    /**
     * Image SHA-256 digest
     */
    private String imageDigest;

    /**
     * SURT of the oldest record found. It will be used as the canonical SURT for this object
     */
    private String oldestSurt;

    /**
     * When was the oldest SURT captured
     */
    private LocalDateTime oldestSurtDate;

    /**
     * @param type always pare for this object
     * @param imgTitle Title of the image (if exists)
     * @param imgAlt Image alt text (if exists)
     * @param imgURLTokens Image URL split into tokens
     * @param imgCaption Image caption extracted from HTML
     * @param pageTitle HTML tage title
     * @param pageURLTokens Page URL split into tokens
     * @param imgURL Image URL
     * @param imageSurt Image SURT
     * @param imagesInPage Number of images in page
     * @param pageTimestampString Page capture timestamp in the yyyyMMddHHmmss format
     * @param pageURL Page URL
     * @param pageHost Page host
     * @param pageProtocol Page protocol
     * @param tagType Whether the image was found in a <a>, <img> or CSS tag
     * @param warc WARC filename
     * @param warcOffset WARC offset in bytes
     * @param collection Collection name
     */
    public PageImageData(String type, String imgTitle, String imgAlt, String imgURLTokens, String imgCaption, String pageTitle, String pageURLTokens, String imgURL, String imageSurt, int imagesInPage, String pageTimestampString, String pageURL, String pageHost, String pageProtocol, String tagType, String warc, long warcOffset, String collection) {

        this.type = type;

        this.imgAlt = imgAlt;

        this.tagFoundIn = new HashSet<>();

        tagFoundIn.add(tagType);

        inline = imgURL.startsWith("hash:");

        this.imgURLTokens = "";

        if (!inline) {
            this.imgURLTokens = imgURLTokens;
            URL url = null;
            try {
                url = new URL(imgURL);
                String filename = FilenameUtils.getBaseName(url.getPath());
                if (!filename.isEmpty())
                    this.imgFilename = ImageSearchIndexingUtil.cleanPunctuation(filename);
            } catch (MalformedURLException ignored) {

            }
        }

        this.imgCaption = imgCaption;

        this.pageTitle = pageTitle;

        this.imgTitle = imgTitle;

        this.pageURLTokens = pageURLTokens;

        this.imgURL = imgURL;
        this.imgSurt = imageSurt;

        this.imagesInPage = imagesInPage;

        this.pageURL = pageURL;

        this.pageHost = pageHost;

        this.pageProtocol = pageProtocol;

        this.pageTimestampString = pageTimestampString;
        this.pageTimestamp = WARCInformationParser.parseLocalDateTime(pageTimestampString);
        this.pageTimestampNewest = WARCInformationParser.parseLocalDateTime(pageTimestampString);

        this.warc = warc;
        this.warcOffset = warcOffset;

        this.collection = collection;
    }

    @Override
    public int compareTo(LocalDateTime timestamp) {
        return this.pageTimestamp.compareTo(timestamp);
    }

    @Override
    public String toString() {
        return String.format("\"%s\": \"%s\", %s", pageTitle, pageURL, pageTimestamp.toString());
    }

    public String getType() {
        return type;
    }

    public String getImgTitle() {
        return imgTitle;
    }

    public String getImgURLTokens() {
        return imgURLTokens;
    }

    public String getPageURLTokens() {
        return pageURLTokens;
    }

    public String getImgURL() {
        return imgURL;
    }

    public String getImgSurt() {
        return imgSurt;
    }

    public String getPageTimestampString() {
        return pageTimestampString;
    }

    public String getPageURL() {
        return pageURL;
    }

    public String getImgFilename() {
        return imgFilename;
    }

    public String getPageHost() {
        return pageHost;
    }

    public String getImgCaption() {
        return imgCaption;
    }

    public String getPageProtocol() {
        return pageProtocol;
    }

    public LocalDateTime getPageTimestamp() {
        return pageTimestamp;
    }

    public String getTimestampsAsStrings() {
        return pageTimestamp.toString();
    }

    public String getPageMetadata() {
        return (String.join(pageTitle.trim(), pageURLTokens).trim()).trim();
    }

    public String getImageMetadata() {
        return (String.join(" ", imgTitle.trim(), imgAlt.trim(), imgCaption.trim()).trim()).trim();
    }

    public int getPageMetadataSize() {
        return this.getPageMetadata().length();
    }

    public int getImageMetadataSize() {
        return this.getImageMetadata().length();
    }

    public int getImagesInPage() {
        return imagesInPage;
    }

    public void setImagesInPage(int imagesInPage) {
        this.imagesInPage = imagesInPage;
    }

    public boolean getInline() {
        return inline;
    }

    public Set<String> getTagFoundIn() {
        return tagFoundIn;
    }

    public String getImgAlt() {
        return imgAlt;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public boolean equals(PageImageData o) {
        return (o.getImageMetadata().equals(this.getImageMetadata()));
    }

    /**
     * Merge this record with another record for the same SURT or digest
     *
     * if two pages with the same metadata are parsed, store the oldest one
     * if two pages with the same timestamp and metadata are parsed, store the one with the shortest URL
     *
     * @param pageImageData the record to merge
     */
    public void updatePageTimestamp(PageImageData pageImageData) {
        int comparison = pageImageData.getPageTimestamp().compareTo(this.pageTimestamp);
        if (comparison < 0 || (comparison == 0 && pageImageData.getPageURL().length() < pageURL.length())) {
            pageTimestamp = pageImageData.getPageTimestamp();
            pageTimestampString = WARCInformationParser.getLocalDateTimeToTimestamp(pageTimestamp);
            pageURL = pageImageData.getPageURL();
            pageURLTokens = pageImageData.getPageURLTokens();
            pageTitle = pageImageData.getPageTitle();
            pageHost = pageImageData.getPageHost();
            pageProtocol = pageImageData.getPageProtocol();
            imagesInPage = pageImageData.getImagesInPage();
            warc = pageImageData.getWarc();
            warcOffset = pageImageData.getWarcOffset();
        } else if (pageImageData.getPageTimestamp().compareTo(this.pageTimestampNewest) > 0){
            pageTimestampNewest = pageImageData.getPageTimestamp();
        }
    }

    public long getTimespan() {
        return Duration.between(pageTimestamp, pageTimestampNewest).getSeconds();
    }

    public String getId() {
        return pageTimestampString + "/" + ImageSearchIndexingUtil.md5ofString(pageURL) + "/" + ImageSearchIndexingUtil.md5ofString(getImageMetadata()) + "/" + imageDigest;
    }

    public String getPageURLHash() {
        return ImageSearchIndexingUtil.md5ofString(pageURL);
    }

    public String getImgTimestampString() {
        return imgTimestamp.toString();
    }

    public LocalDateTime getImgTimestamp() {
        return imgTimestamp;
    }

    public void setImgTimestamp(LocalDateTime imgTimestamp) {
        this.imgTimestamp = imgTimestamp;
    }

    public void setImageDigest(String imageDigest) {
        this.imageDigest = imageDigest;
    }

    public String getImageDigest() {
        return imageDigest;
    }

    public int getImgHeight() {
        return imgHeight;
    }

    public int getImgWidth() {
        return imgWidth;
    }

    public void setImgWidth(int imgWidth) {
        this.imgWidth = imgWidth;
    }

    public void setImgHeight(int imgHeight) {
        this.imgHeight = imgHeight;
    }

    /**
     * Add a matching image metadata to this object
     * @param id the matching metadata
     * @param correct the best match timestamp
     */
    public void assignImageToPage(ImageData id, LocalDateTime correct) {
        this.setImgTimestamp(correct);
        this.setImageDigest(id.getContentHash());
        this.setImgHeight(id.getHeight());
        this.setImgWidth(id.getWidth());
        this.setImgMimeType(id.getMimeDetected());
        this.setImgId(id.getId());
        this.setImgWarc(id.getWarc());
        this.setImgWarcOffset(id.getWarcOffset());
    }

    /**
     * Add a matching page to this object
     * @param fullImageMetadata the matching metadata
     */
    public void assignMetadataToPage(FullImageMetadata fullImageMetadata) {
        this.matchingImages = fullImageMetadata.getMatchingImages();
        this.matchingPages = fullImageMetadata.getMatchingPages();
        this.imageMetadataChanges = fullImageMetadata.getImageMetadataChanges();
        this.pageMetadataChanges = fullImageMetadata.getPageMetadataChanges();
        this.uniqueDigestsOnURL = fullImageMetadata.getUniqueDigestsOnURL();

        this.oldestSurt = fullImageMetadata.getOldestSurt();
        this.oldestSurtDate = fullImageMetadata.getOldestSurtDate();
    }

    public String getImgMimeType() {
        return imgMimeType;
    }

    public void setImgMimeType(String imgMimeType) {
        this.imgMimeType = imgMimeType;
    }

    public String getImgId() {
        return imgId;
    }

    public void setImgId(String imgId) {
        this.imgId = imgId;
    }

    public String getWarc() {
        return warc;
    }

    public void setWarc(String warc) {
        this.warc = warc;
    }

    public long getWarcOffset() {
        return warcOffset;
    }

    public String getImgWarc() {
        return imgWarc;
    }

    public void setImgWarc(String imgWarc) {
        this.imgWarc = imgWarc;
    }

    public long getImgWarcOffset() {
        return imgWarcOffset;
    }

    public void setImgWarcOffset(long imgWarcOffset) {
        this.imgWarcOffset = imgWarcOffset;
    }

    public String getImgURLHash() {
        return ImageSearchIndexingUtil.md5ofString(imgURL);
    }

    public String getCollection() {
        return collection;
    }

    public void setCollection(String collection) {
        this.collection = collection;
    }

    public int getMatchingImages() {
        return matchingImages;
    }

    public int getMatchingPages() {
        return matchingPages;
    }

    public int getImageMetadataChanges() {
        return imageMetadataChanges;
    }

    public int getPageMetadataChanges() {
        return pageMetadataChanges;
    }

    public int getUniqueDigestsOnURL() {
        return uniqueDigestsOnURL;
    }

    public String getOldestSurt() {
        return oldestSurt;
    }

    public LocalDateTime getOldestSurtDate() {
        return oldestSurtDate;
    }
}

