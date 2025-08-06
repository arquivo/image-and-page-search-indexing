package pt.arquivo.imagesearch.indexing.data;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.io.Writable;

import pt.arquivo.imagesearch.indexing.processors.ImageInformationExtractor;
import pt.arquivo.imagesearch.indexing.utils.WARCInformationParser;

public class TextDocumentData implements Comparable<LocalDateTime>, Writable, Serializable {

    public static final int MAX_INLINKS_INTERNAL = 1000;
    public static final int MAX_INLINKS_EXTERNAL = 1000;
    public static final int MAX_OUTLINKS = 1000;
    public static final int MAX_UNIQUE_URLS = 1000;


    /**
     * Collection where to which this  matches
     */
    private ArrayList<String> collection;

    /**
     * (W)ARC name
     */
    private String warc;

    /**
     * (W)ARC offset in bytes
     */
    private long warcOffset;

    /**
     * Mime type detected
     */
    private String mimeTypeDetected;

    /**
     * Mime type reported
     */
    private String mimeTypeReported;

    /**
     * title
     */
    private ArrayList<String> title;

    /**
     * metadata
     */
    private ArrayList<String> metadata;

    /**
     *  url split into word tokens
     */
    private ArrayList<String> urlTokens;

    /**
     *  capture timestamp
     */
    private LocalDateTime timestamp;

    /**
     * Oldest capture timestamp in the yyyyMMddHHmmss format
     */
    private String timestampString;

    /**
     *  latest capture timestamp
     */
    private LocalDateTime timestampLatest;

    /**
     * Document URL
     */
    private ArrayList<String> urls;

    /**
     * Document SURT
     */
    private ArrayList<String> surts;

    /**
     * Host of the matching 
     */
    private ArrayList<String> host;

    /**
     * URL timestamp
     */
    private ArrayList<String> urlTimestamp;

    /**
     *  content
     */
    private ArrayList<String> content;

    /** 
     * Outlinks
     */
    private Map<Outlink,Outlink> outlinks;

    /** 
     * Inlinks
     */
    private Map<Outlink,Outlink> inlinksInternal;

    /** 
     * Inlinks
     */
    private Map<Outlink,Outlink> inlinksExternal;

    /**
     * Digest of the document
     */
    private String digestContainer;

    /**
     * Digest of the content
     */
    private String digestContent;

    private int code;

    private int captureCount;

    private boolean isRedirect;

    private String language;
    private String languageConfidence;


    public TextDocumentData(TextDocumentData other){
        this.collection = new ArrayList<>(other.collection);
        this.warc = other.warc;
        this.warcOffset = other.warcOffset;
        this.mimeTypeDetected = other.mimeTypeDetected;
        this.mimeTypeReported = other.mimeTypeReported;
        this.title = new ArrayList<>(other.title);
        this.urlTokens = new ArrayList<>(other.urlTokens);
        this.timestamp = other.timestamp;
        this.timestampString = other.timestampString;
        this.timestampLatest = other.timestampLatest;
        this.urls = new ArrayList<>(other.urls);
        this.surts = new ArrayList<>(other.surts);
        this.host = new ArrayList<>(other.host);
        this.content = new ArrayList<>(other.content);
        this.outlinks = new HashMap<>(other.outlinks);
        this.inlinksInternal = new HashMap<>(other.inlinksInternal);
        this.inlinksExternal = new HashMap<>(other.inlinksExternal);
        this.digestContainer = other.digestContainer;
        this.digestContent = other.digestContent;
        this.metadata = new ArrayList<>(other.metadata);
        this.urlTimestamp = new ArrayList<>(other.urlTimestamp);
        this.code = other.code;
        this.captureCount = other.captureCount;
        this.isRedirect = other.isRedirect;
        this.language = other.language;
        this.languageConfidence = other.languageConfidence;
        
    }

    public TextDocumentData() {
        this.outlinks = new HashMap<>();
        this.inlinksInternal = new HashMap<>();
        this.inlinksExternal = new HashMap<>();
        this.title = new ArrayList<>();
        this.collection = new ArrayList<>();
        this.content = new ArrayList<>();
        this.urlTokens = new ArrayList<>();
        this.surts = new ArrayList<>();
        this.host = new ArrayList<>();
        this.urls = new ArrayList<>();
        this.metadata = new ArrayList<>();
        this.urlTimestamp = new ArrayList<>();
        this.captureCount = 1;  
        this.language = "";
        this.languageConfidence = "";
    }

    @Override
    public int compareTo(LocalDateTime timestamp) {
        return this.timestamp.compareTo(timestamp);
    }

    @Override
    public String toString() {
        return String.format("\"%s\": \"%s\", %s", title, urls, timestamp.toString());
    }

    public String getId() {
        return digestContent;
        //return timestampString + "/" + ImageSearchIndexingUtil.md5ofString(url);
    }

    // generate getters and setters
    public ArrayList<String> getCollection() {
        return collection;
    }

    public String getWarc() {
        return warc;
    }

    public long getWarcOffset() {
        return warcOffset;
    }

    public String getMimeTypeDetected() {
        return mimeTypeDetected;
    }

    public String getMimeTypeReported() {
        return mimeTypeReported;
    }

    public ArrayList<String> getTitle() {
        return title;
    }

    public ArrayList<String> getURLTokens() {
        return urlTokens;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public LocalDateTime getTimestampLatest() {
        return timestampLatest;
    }

    public String getTimestampFormatted() {
        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ",
                timestamp.getYear(),
                timestamp.getMonthValue(),
                timestamp.getDayOfMonth(),
                timestamp.getHour(),
                timestamp.getMinute(),
                timestamp.getSecond());
    }

    public String getTimestampLatestFormatted() {
        return String.format("%04d-%02d-%02dT%02d:%02d:%02dZ",
                timestampLatest.getYear(),
                timestampLatest.getMonthValue(),
                timestampLatest.getDayOfMonth(),
                timestampLatest.getHour(),
                timestampLatest.getMinute(),
                timestampLatest.getSecond());
    }

    public long getTimerange() {
        return Duration.between(timestamp, timestampLatest).toMillis() / 1000;
    }
    
    public String getTimestampString() {
        return timestampString;
    }

    public ArrayList<String> getURL() {
        return urls;
    }

    public ArrayList<String> getSurts() {
        return surts;
    }

    public ArrayList<String> getHost() {
        return host;
    }

    public ArrayList<String> getContent() {
        return content;
    }

    public Map<Outlink,Outlink> getOutlinks() {
        return outlinks;
    }

    // These will match the inlinks to the surt of the document
    // Internal inlinks are those that match the subdomain of the document
    // s.g. links from arquivo.pt/wayback and arquivo.pt/text_search are considered the same internal
    // links to arquivo.pt from premio.arquivo.pt is considered external
    public Map<Outlink,Outlink> getInlinksInternal() {       
        return inlinksInternal;
    }

    public Map<Outlink,Outlink> getInlinksExternal() {
        return inlinksExternal;
    }

    private List<String> getInlinkAnchors(Map<Outlink,Outlink> inlinks) {
        ArrayList<String> inlinkAnchors = new ArrayList<>();
        for (Outlink inlink : inlinks.keySet()) {
            if (inlink.getAnchor() != null && !inlink.getAnchor().trim().isEmpty() && !inlinkAnchors.contains(inlink.getAnchor()))
                inlinkAnchors.add(inlink.getAnchor());
        }
        return inlinkAnchors;
    }

    private List<String> getInlinkSurts(Map<Outlink,Outlink> inlinks) {
        Set<String> inlinkSurt = new HashSet<>();
        for (Outlink inlink : inlinks.keySet()) {
            if (inlink.getSurt() != null && !inlink.getSurt().trim().isEmpty() && !inlinkSurt.contains(inlink.getSurt()))
                inlinkSurt.add(inlink.getSurt());
        }
        return new ArrayList<>(inlinkSurt);
    }

    public List<String> getInlinkAnchorsInternal() {
        return getInlinkAnchors(inlinksInternal);
    }

    public List<String> getInlinkAnchorsExternal() {
        return getInlinkAnchors(inlinksExternal);
    }

    public String getConcatedInlinkAnchorsExternal() {
        return String.join(" ", getInlinkAnchors(inlinksExternal));
    }

    public String getConcatedInlinkAnchorsInternal() {
        return String.join(" ", getInlinkAnchors(inlinksInternal));
    }

    public List<String> getInlinkSurtsInternal() {
        return getInlinkSurts(inlinksInternal);
    }

    public List<String> getInlinkSurtsExternal() {
        return getInlinkSurts(inlinksExternal);
    }

    public void addCollection(String collection) {
        if (collection.isEmpty() || this.collection.contains(collection))
            return;
        this.collection.add(collection);
    }

    public void setWarc(String warc) {
        this.warc = warc;
    }

    public void setWarcOffset(long warcOffset) {
        this.warcOffset = warcOffset;
    }

    public void setMimeTypeDetected(String mimeTypeDetected) {
        this.mimeTypeDetected = mimeTypeDetected;
    }

    public void setMimeTypeReported(String mimeTypeReported) {
        this.mimeTypeReported = mimeTypeReported;
    }

    public void addHost(String host) {
        host = host.replaceAll("www.", "");
        if (host.isEmpty() || this.host.contains(host))
            return;
        this.host.add(host);
    }

    public void addSurt(String surt) {
        if (surt.isEmpty() || this.surts.contains(surt))
            return;
        this.surts.add(surt);
    }

    public void addUrlTokens(String urlTokens) {
        if (urlTokens.isEmpty() || this.urlTokens.contains(urlTokens))
            return;
        this.urlTokens.add(urlTokens);
    }

    public void addTitle(String title) {
        if (title.isEmpty() || this.title.contains(title))
            return;
        this.title.add(title);
    }

    public void addContent(String content) {
        if (content.isEmpty() || this.content.contains(content))
            return;
        this.content.add(content);
    }

    public void addURLTokens(String urlTokens) {
        if (urlTokens.isEmpty() || this.urlTokens.contains(urlTokens))
            return;
        this.urlTokens.add(urlTokens);
    }

    private LocalDateTime getTimestamp(String timestamp) {
        // timestamp format is YYYYMMDDHHmmss
        if (timestamp.length() < 14) {
            timestamp = timestamp.concat("00000000000000").substring(0, 14);
        }
        return LocalDateTime.of(
                Integer.parseInt(timestamp.substring(0, 4)),
                Integer.parseInt(timestamp.substring(4, 6)),
                Integer.parseInt(timestamp.substring(6, 8)),
                Integer.parseInt(timestamp.substring(8, 10)),
                Integer.parseInt(timestamp.substring(10, 12)),
                Integer.parseInt(timestamp.substring(12, 14))
        );
    }
    public void setTimestamp(String timestampString) {
        this.timestampString = timestampString;
        // timestampString format is YYYYMMDDHHmmss
        if (timestampString.length() < 14) {
            timestampString = timestampString.concat("00000000000000").substring(0, 14);
        }
        
        this.timestamp = getTimestamp(timestampString);
        this.timestampLatest = this.timestamp;
    }
    
    public void setDigestContainer(String digestContainer) {
        this.digestContainer = digestContainer;
    }

    public void setDigestContent(String digestContent) {
        this.digestContent = digestContent;
    }

    public String getDigestContainer() {
        return digestContainer;
    }

    public String getDigestContent() {
        return digestContent;
    }

    public void addMetadata(String metadata) {
        if (metadata.isEmpty() || this.metadata.contains(metadata))
            return;
        this.metadata.add(metadata);
    }

    public ArrayList<String> getMetadata() {
        return metadata;
    }

    public void addURL(String url, String timestamp, String collection) {
        if (this.urls.contains(url) || this.urls.size() > MAX_UNIQUE_URLS)
            return;
        this.addURLTimestamp(url, timestamp, collection);
        this.addURL(url);
    }

    
    public void addURL(String url) {
        if (this.urls.contains(url) || this.urls.size() > MAX_UNIQUE_URLS)
            return;
        this.urls.add(url);
        if (!url.startsWith("http"))
            url = "http://" + url;
        URL uri;
        try {
            uri = new URL(url);
            this.addHost(uri.getHost());
        } catch (MalformedURLException e) {
            // e.printStackTrace();
            url = url.replaceAll("http://", "").replaceAll("https://", "");
            this.addHost(url.split("/")[0]);
        }
        this.addUrlTokens(ImageInformationExtractor.getURLSrcTokens(url));
        this.addSurt(WARCInformationParser.toSURT(url));
    }

    public void addOutlink(String outlink, String anchor) {
        String outlinkSurt = WARCInformationParser.toSURT(outlink);
        if (outlinkSurt.trim().isEmpty())
            return;
        Outlink outlinkObj = new Outlink(outlinkSurt, outlink, anchor, this.timestamp, this.surts.get(0));
        this.outlinks.put(outlinkObj, outlinkObj);
    }

    public void addOutlink(Outlink outlink) {
        Outlink existing = outlinks.get(outlink);
        if (existing != null) {
            if (outlink.getCaptureDateStart().isBefore(existing.getCaptureDateStart())) {
                existing.setCaptureDateStart(outlink.getCaptureDateStart());
            }
            if (outlink.getCaptureDateEnd().isAfter(existing.getCaptureDateEnd())) {
                existing.setCaptureDateEnd(outlink.getCaptureDateEnd());
            }
            existing.incrementCount();
            return;
        }
        if (this.outlinks.size() > MAX_OUTLINKS)
            return;
        this.outlinks.put(outlink, outlink);
    }

    public void addInlink(Outlink inlink) {
        Outlink existing = inlinksInternal.get(inlink) != null ? inlinksInternal.get(inlink) : inlinksExternal.get(inlink);
        if (existing != null) {
            if (inlink.getCaptureDateStart().isBefore(existing.getCaptureDateStart())) {
                existing.setCaptureDateStart(inlink.getCaptureDateStart());
            }
            if (inlink.getCaptureDateEnd().isAfter(existing.getCaptureDateEnd())) {
                existing.setCaptureDateEnd(inlink.getCaptureDateEnd());
            }
            existing.incrementCount();
            return;
        }
        boolean isInternal = false;
        // broader domain matching
        for (String surt : surts){
            if (WARCInformationParser.isInternal(surt, inlink.getSource())) {
                isInternal = true;
                break;
            }
        }
        if (isInternal) {
            if (this.inlinksInternal.size() > MAX_INLINKS_INTERNAL)
                return;
            this.inlinksInternal.put(inlink, inlink);
        } else {
            if (this.inlinksExternal.size() > MAX_INLINKS_EXTERNAL)
                return;
            this.inlinksExternal.put(inlink, inlink);
        }
    }

    public int getStatusCode() {
        return code;
    }

    public void setStatusCode(int code) {
        this.code = code;
    }

    public int getCaptureCount() {
        return captureCount;
    }

    public boolean isRedirect() {
        return isRedirect;
    }

    public void setRedirect(boolean redirect) {
        isRedirect = redirect;
    }

    public void addTimestampLatest(LocalDateTime otherTimestamp) {
        if (this.timestampLatest == null || otherTimestamp == null) {
            return;
        }
        if (otherTimestamp.isAfter(this.timestampLatest)) {
            this.timestampLatest = otherTimestamp;
        }
    }

    public ArrayList<String> getUrlTimestamp() {
        return urlTimestamp;
    }

    public void addURLTimestamp(String urlTimestamp) {
        if (urlTimestamp.isEmpty() || this.urlTimestamp.contains(urlTimestamp) || this.urlTimestamp.size() > MAX_UNIQUE_URLS)
            return;
        this.urlTimestamp.add(urlTimestamp);
    }

    public void addURLTimestamp(String url, String timestamp, String collection) {
        String surt = WARCInformationParser.toSURT(url);
        String urlTimestamp = collection + "/" + timestamp + "/" + surt;
        this.addURLTimestamp(urlTimestamp);
    }

    public void setLanguage(String language) {
        if (language == null || language.isEmpty()) {
            this.language = "";
        } else {
            this.language = language;
        }
    }

    public void setLanguageConfidence(String confidence) {
        if (confidence == null || confidence.isEmpty()) {
            this.languageConfidence = "";
        } else {
            this.languageConfidence = confidence;
        }
    }

    public String getLanguage() {
        return language;
    }

    public String getLanguageConfidence() {
        return languageConfidence;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(this);
        byte[] data = baos.toByteArray();
        int size = data.length;
        out.writeInt(size);
        out.write(data);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        int size = in.readInt();
        byte[] data = new byte[size];
        in.readFully(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        try {
            TextDocumentData other = (TextDocumentData) ois.readObject();

            this.collection = other.collection;
            this.warc = other.warc;
            this.warcOffset = other.warcOffset;
            this.mimeTypeDetected = other.mimeTypeDetected;
            this.mimeTypeReported = other.mimeTypeReported;
            this.title = other.title;
            this.urlTokens = other.urlTokens;
            this.timestamp = other.timestamp;
            this.timestampString = other.timestampString;
            this.timestampLatest = other.timestampLatest;
            this.urls = other.urls;
            this.surts = other.surts;
            this.host = other.host;
            this.content = other.content;
            this.outlinks = other.outlinks;
            this.inlinksInternal = other.inlinksInternal;
            this.inlinksExternal = other.inlinksExternal;
            this.digestContainer = other.digestContainer;
            this.digestContent = other.digestContent;
            this.metadata = other.metadata;
            this.code = other.code;
            this.captureCount = other.captureCount;
            this.isRedirect = other.isRedirect;
            this.urlTimestamp = other.urlTimestamp;
            this.language = other.language;
            this.languageConfidence = other.languageConfidence;

        } catch (ClassNotFoundException e) {
            System.err.println("Error reading TextDocumentData from Writable");
        }
    }

    public static TextDocumentData merge(TextDocumentData a, TextDocumentData b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }

        if (a == b) {
            return a;
        }


        TextDocumentData result = a.getTimestamp().isAfter(b.getTimestamp()) ? b : a;

        // prefer records that are not redirects, regardless of timestamp
        if (a.isRedirect() && !b.isRedirect()) {
            result = b;
        } else if (!a.isRedirect() && b.isRedirect()) {
            result = a;
        }

        TextDocumentData other = a == result ? b : a;

        other.getCollection().forEach(result::addCollection);
        other.getTitle().forEach(result::addTitle);
        other.getURL().forEach(result::addURL);
        other.getContent().forEach(result::addContent);
        other.getOutlinks().keySet().forEach(result::addOutlink);
        other.getInlinksInternal().keySet().forEach(result::addInlink);
        other.getInlinksExternal().keySet().forEach(result::addInlink);
        other.getMetadata().forEach(result::addMetadata);
        other.getUrlTimestamp().forEach(result::addURLTimestamp);

        result.captureCount += other.getCaptureCount(); 
        result.addTimestampLatest(other.getTimestampLatest());

        return result;


    }
}

