package pt.arquivo.imagesearch.indexing.utils;

import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import pt.arquivo.imagesearch.indexing.data.ImageData;
import org.apache.log4j.Logger;
import org.archive.url.SURT;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * WARC parser aux class
 */
public class WARCInformationParser {
    /**
     * Datetime archive format string
     */
    public static final String PATTERN = "yyyyMMddHHmmss";

    public static Logger logger = Logger.getLogger(WARCInformationParser.class);

    public static ContentInfoUtil util = new ContentInfoUtil();

    public static enum SURTMatchType {
        SAME_SURT, SAME_FQDN, SAME_SLDN, DIFFERENT_SLDN, INVALID
    }

    /**
     * Parse Archive date time into LocalDateTime object
     *
     * @param timestamp timestamp to parse
     * @return LocalDateTime object
     */
    public static LocalDateTime parseLocalDateTime(String timestamp) {
        if (timestamp.length() == WARCInformationParser.PATTERN.length() - 2)
            timestamp += "00";
        else if (timestamp.length() == WARCInformationParser.PATTERN.length() + 2)
            timestamp = timestamp.substring(0, WARCInformationParser.PATTERN.length());
        return LocalDateTime.parse(timestamp, DateTimeFormatter.ofPattern(WARCInformationParser.PATTERN));
    }

    public static String getLocalDateTimeToTimestamp(LocalDateTime localDT) {
        return localDT.format(DateTimeFormatter.ofPattern(WARCInformationParser.PATTERN));
    }

    /**
     * Converts url to SURT
     *
     * @param url url to convert
     * @return SURT
     */
    public static String toSURT(String url) {
        if (url.startsWith("data:") || url.startsWith("hash:"))
            return url;

        if (url.startsWith("//"))
            url = url.substring("//".length());
        else if (url.startsWith("https://"))
            url = url.substring("https://".length());
        else if (url.startsWith("http://"))
            url = url.substring("http://".length());

        if (url.startsWith("www."))
            url = url.substring("www.".length());
        else if (url.startsWith("ww2."))
            url = url.substring("ww2.".length());
        else if (url.startsWith("ww3."))
            url = url.substring("ww3.".length());

        if (url.trim().isEmpty())
            return url;
        return SURT.toSURT(url);
    }

    /**
     * Compares two SURTs
     *
     * @param surt1 first SURT
     * @param surt2 second SURT
     * @return SURTMatchType
     */
    public static SURTMatchType compareSURTs(String surt1, String surt2) {

        if (surt1 == null || surt2 == null || surt1.isEmpty() || surt2.isEmpty())
            return SURTMatchType.INVALID;

        if (surt1.equals(surt2))
            return SURTMatchType.SAME_SURT;

        String[] surt1Parts = surt1.split("\\)");
        String[] surt2Parts = surt2.split("\\)");
        // remove port
        if (surt1Parts[0].contains(":"))
            surt1Parts[0] = surt1Parts[0].substring(0, surt1Parts[0].indexOf(":"));
        if (surt2Parts[0].contains(":"))
            surt2Parts[0] = surt2Parts[0].substring(0, surt2Parts[0].indexOf(":"));

        if (surt1Parts[0].equals(surt2Parts[0]))
            return SURTMatchType.SAME_FQDN;

        String[] domain1Parts = surt1Parts[0].split(",");
        String[] domain2Parts = surt2Parts[0].split(",");

        if (domain1Parts.length >= 2 && domain2Parts.length >= 2 &&
            domain2Parts[0].equals(domain1Parts[0]) &&
            domain2Parts[1].equals(domain1Parts[1]))
            return SURTMatchType.SAME_SLDN;

        return SURTMatchType.DIFFERENT_SLDN;
        
    }

    /**
     * Is an internal inlink
     *
     * @param surt1 first SURT
     * @param surt2 second SURT
     * @return true if internal inlink
     */
    public static boolean isInternal(String surt1, String surt2) {
        return compareSURTs(surt1, surt2).ordinal() <= SURTMatchType.SAME_SLDN.ordinal();
    }


    /**
     * Returns image dimensions in pixels
     *
     * @param img image to parse
     * @return image dimensions
     */
    public static Map.Entry<ImageReader, Dimension> getImageDimensions(ImageData img) {
        ImageReader reader = null;

        Iterator<ImageReader> iter = ImageIO.getImageReadersByMIMEType(img.getMimeDetected());

        if (!iter.hasNext()) {
            iter = ImageIO.getImageReadersByMIMEType(img.getMimeReported());
            if (iter.hasNext())
                img.setMimeDetected(img.getMimeReported());
        }

        if (!iter.hasNext()) {
            logger.debug("No reader found for given format: " + img.getMimeReported() + " " + img.getMimeDetected() + " " + img.getURLWithTimestamp());
            return null;
        }

        while (iter.hasNext()) {
            reader = iter.next();
            try {
                ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(img.getBytes()));
                reader.setInput(stream, true, true);
                int width = reader.getWidth(reader.getMinIndex());
                int height = reader.getHeight(reader.getMinIndex());
                // avoid creating a new stream in reader
                return new AbstractMap.SimpleEntry<>(reader, new Dimension(width, height));
            } catch (Exception e) {
                logger.error(e.getMessage() + " reader: " + reader.toString() + " " + img.getURLWithTimestamp());
            }
            reader.dispose();
        }

        return null;
    }

    /**
     * Returns mime type for image
     *
     * @param contentBytes iamge content bytes
     * @return detected mime type
     */
    public static String getMimeType(byte[] contentBytes) {

        ContentInfo info = util.findMatch(contentBytes);
        if (info == null)
            return null;

        String detectedMimeType = info.getMimeType();

        //Image IO is dumb and does not recognize 'image/x-ms-bmp' as 'bmp'
        if (detectedMimeType.equals("image/x-ms-bmp"))
            detectedMimeType = "image/bmp";


        return detectedMimeType;
    }

}
