package pt.arquivo.imagesearch.indexing.data;

import org.apache.hadoop.io.Writable;

import pt.arquivo.imagesearch.indexing.data.comparators.ImageDataComparator;
import pt.arquivo.imagesearch.indexing.data.comparators.PageImageDataComparator;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * This class represents the combination of page and image metadata for a single record.
 * It is made of a combination of various ImageData and PageImageData that match the same image
 */
public class FullImageMetadata implements Writable, Serializable {


    /**
     * Maximum amount of metadata fields to accumulate for each metadata record
     */
    public static final int MAXIMUM_META = 500;

    /**
     * ImageData (image metadata) objects for this image. It is organized as a TreeMap with the same object as the key and value to simplify comparisons.
     * This guarantees that the objects are ordered and can be added and removed during the deduplication process.
     */
    private TreeMap<ImageData, ImageData> imageDatas;

    /**
     * PageImageData (page image metadata) objects for this image. It is organized as a TreeMap with the same object as the key and value to simplify comparisons.
     * As with the ImageDatas, this guarantees that the objects are ordered and can be added and removed during the deduplication process.
     */
    private TreeMap<PageImageData, PageImageData> pageImageDatas;


    /**
     * SURT of the oldest record found. It will be used as the canonical SURT for this object
     */
    private String oldestSurt;


    /**
     * When was the oldest SURT captured
     */
    private LocalDateTime oldestSurtDate;


    /**
     * How many different URLs are on this metadata object
     */
    private int uniqueDigestsOnURL;

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
     * Basic builder for an empty object
     */
    public FullImageMetadata() {
        this.imageDatas = new TreeMap<>(new ImageDataComparator());
        this.pageImageDatas = new TreeMap<>(new PageImageDataComparator());
        this.imageMetadataChanges = 0;
        this.pageMetadataChanges = 1;
        this.matchingImages = 0;
        this.matchingPages = 0;
        this.uniqueDigestsOnURL = 0;
    }

    /**
     * Creates a clone af a FullImageMetadata
     *
     * @param metadata the object to be cloned
     */
    @SuppressWarnings("unchecked")
    public FullImageMetadata(FullImageMetadata metadata) {
        imageDatas = (TreeMap<ImageData, ImageData>) metadata.getImageDatas().clone();
        pageImageDatas = (TreeMap<PageImageData, PageImageData>) metadata.getPageImageDatas().clone();

        imageMetadataChanges = metadata.getImageMetadataChanges();
        pageMetadataChanges = metadata.getPageMetadataChanges();

        matchingImages = metadata.getMatchingImages();
        matchingPages = metadata.getMatchingPages();

        uniqueDigestsOnURL = metadata.getUniqueDigestsOnURL();

        oldestSurt = metadata.getOldestSurt();
        oldestSurtDate = metadata.getOldestSurtDate();
    }

    /**
     * Creates a bew metadata object using the ImageData object
     * Used when splitting objects by digest.
     * It will group an ImageData object with the temporally closest PageMetadataObjects
     *
     * @param metadata  the base object when we get the page metadata from
     * @param imageData the imageData to use for these page metadata objects
     */
    public FullImageMetadata(FullImageMetadata metadata, ImageData imageData) {

        imageDatas = new TreeMap<>(new ImageDataComparator());
        imageDatas.put(imageData, imageData);

        pageImageDatas = new TreeMap<>(new PageImageDataComparator());

        // get all objects that are a part of the new incomming object
        for (PageImageData pageImageData : metadata.pageImageDatas.values()) {
            if (imageData.getTimestamp().contains(pageImageData.getImgTimestamp())) {
                pageImageDatas.put(pageImageData, pageImageData);
            }
        }

        uniqueDigestsOnURL = metadata.getUniqueDigestsOnURL();

        matchingImages = metadata.getMatchingImages();
        matchingPages = metadata.getMatchingPages();

        imageMetadataChanges = metadata.getImageMetadataChanges();
        pageMetadataChanges = metadata.getPageMetadataChanges();

        oldestSurt = metadata.getOldestSurt();
        oldestSurtDate = metadata.getOldestSurtDate();
    }

    /**
     * Merges page and image metadata from the incoming object into the current object
     *
     * @param metadata object from where to get metadata
     */
    public void merge(FullImageMetadata metadata) {
        int matchingImagesOriginal = this.matchingImages;
        int matchingPagesOriginal = this.matchingPages;

        for (ImageData data : metadata.getImageDatasValues()) {
            if (this.imageDatas.size() < MAXIMUM_META) {
                this.addImageData(data);
            } else { // do not keep adding image data indefinitely, as malformed pages can have thousands of useless image refs
                break;
            }
        }

        for (PageImageData data : metadata.getPageImageDatasValues()) {
            if (this.pageImageDatas.size() < MAXIMUM_META) {
                this.addPageImageData(data);
            } else { // do not keep adding image data indefinitely, as malformed pages can have thousands of useless image refs
                break;
            }
        }
        //This line avoids double counting the newly added pages and images
        //and enables counting images that were not parsec due to the MAXIMUM_META limit
        this.matchingImages = matchingImagesOriginal + metadata.getMatchingImages();
        this.matchingPages = matchingPagesOriginal + metadata.getMatchingPages();

        // store the oldest surt where the image was referenced at.
        // Do this here again for the cases where the MAXIMUM_META limit was reached
        if (metadata.getOldestSurtDate() == null)
            return;

        if (oldestSurtDate == null) {
            oldestSurt = metadata.getOldestSurt();
            oldestSurtDate = metadata.getOldestSurtDate();
        } else {
            int comparator = metadata.getOldestSurtDate().compareTo(oldestSurtDate);
            if (comparator < 0 || (comparator == 0 && oldestSurt.length() < metadata.getOldestSurt().length()) || (comparator == 0 && oldestSurt.length() == metadata.getOldestSurt().length() && metadata.getOldestSurt().compareTo(oldestSurt) < 0)) {
                oldestSurt = metadata.getOldestSurt();
                oldestSurtDate = metadata.getOldestSurtDate();
            }
        }
    }


    /**
     * Adds image data to the current object
     *
     * @param imageData image metadat to add to the current object
     * @return true if the object is new, false if it already exists
     */
    public boolean addImageData(ImageData imageData) {
        this.matchingImages++;

        if (oldestSurtDate == null) {
            oldestSurt = imageData.getSurt();
            oldestSurtDate = imageData.getTimestamp().get(0);
        } else {
            int comparator = imageData.getTimestamp().get(0).compareTo(oldestSurtDate);
            if (comparator < 0 || (comparator == 0 && oldestSurt.length() < imageData.getSurt().length()) || (comparator == 0 && oldestSurt.length() == imageData.getSurt().length() && imageData.getSurt().compareTo(oldestSurt) < 0)) {
                oldestSurt = imageData.getSurt();
                oldestSurtDate = imageData.getTimestamp().get(0);
            }
        }

        ImageData id = imageDatas.get(imageData);
        if (id != null) {
            id.addTimestamp(imageData);
            imageDatas.put(imageData, id);
            return false;
        } else {
            this.uniqueDigestsOnURL += 1;
            imageDatas.put(imageData, imageData);
            return true;
        }
    }

    /**
     * Adds page image data to the current object
     *
     * @param newPageImageData image metadat to add to the current object
     * @return true if the object is new, false if it already exists
     */
    public boolean addPageImageData(PageImageData newPageImageData) {
        this.matchingPages++;

        PageImageData pageImageData = pageImageDatas.get(newPageImageData);
        if (pageImageData != null) {
            if (!pageImageData.getPageMetadata().equals(newPageImageData.getPageMetadata()))
                this.pageMetadataChanges++;

            pageImageData.updatePageTimestamp(newPageImageData);
            // add entry in case image changed
            pageImageDatas.put(newPageImageData, pageImageData);
            return false;
        } else {
            this.imageMetadataChanges++;
            pageImageDatas.put(newPageImageData, newPageImageData);
            return true;
        }
    }

    /**
     * Returns all image metadata values as a collection
     *
     * @return image metadata values
     */
    public Collection<ImageData> getImageDatasValues() {
        return imageDatas.values();
    }


    /**
     * Returns all image metadata values as a tree-map
     *
     * @return image metadata values
     */
    public TreeMap<ImageData, ImageData> getImageDatas() {
        return imageDatas;
    }

    /**
     * Returns all page image metadata values as a collection
     *
     * @return page image metadata values
     */
    public Collection<PageImageData> getPageImageDatasValues() {
        return pageImageDatas.values();
    }


    /**
     * Returns all page image metadata values as a tree-map
     *
     * @return page image metadata values
     */
    public TreeMap<PageImageData, PageImageData> getPageImageDatas() {
        return pageImageDatas;
    }

    public boolean hasImageMetadata() {
        for (PageImageData pid : pageImageDatas.values())
            if (!pid.getImageMetadata().isEmpty() || !pid.getPageMetadata().isEmpty())
                return true;
        return false;
    }


    /**
     * Match each image to the closest page in time
     */
    public void assignImagesToPages() {
        if (imageDatas.isEmpty())
            return;

        TreeMap<LocalDateTime, ImageData> map = new TreeMap<>();
        for (ImageData data : this.getImageDatasValues()) {
            data.assignMetadataToImage(this);
            for (LocalDateTime timestamp : data.getTimestamp())
                map.put(timestamp, data);
        }

        for (PageImageData data : this.getPageImageDatasValues()) {
            LocalDateTime timestamp = data.getPageTimestamp();

            LocalDateTime before = map.floorKey(timestamp);
            LocalDateTime after = map.ceilingKey(timestamp);
            LocalDateTime correct;
            if (before == null) correct = after;
            else if (after == null) correct = before;
            else if ((after.compareTo(timestamp)) < (timestamp.compareTo(before))) correct = after;
            else correct = before;
            ImageData id = map.get(correct);
            data.assignImageToPage(id, correct);
            data.assignMetadataToPage(this);
        }
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

    public void setUniqueDigestsOnURL(int uniqueDigestsOnURL) {
        this.uniqueDigestsOnURL = uniqueDigestsOnURL;
    }


    @Override
    public void write(DataOutput dataOutput) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(this);
        byte[] data = baos.toByteArray();
        int size = data.length;
        dataOutput.writeInt(size);
        dataOutput.write(data);
    }

    @Override
    public void readFields(DataInput dataInput) throws IOException {
        int size = dataInput.readInt();
        byte[] data = new byte[size];
        dataInput.readFully(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        try {
            FullImageMetadata other = (FullImageMetadata) ois.readObject();

            this.imageDatas = other.getImageDatas();
            this.pageImageDatas = other.getPageImageDatas();

            this.matchingImages = other.getMatchingImages();
            this.matchingPages = other.getMatchingPages();
            this.imageMetadataChanges = other.getImageMetadataChanges();
            this.pageMetadataChanges = other.getPageMetadataChanges();

            this.uniqueDigestsOnURL = other.getUniqueDigestsOnURL();

            this.oldestSurt = other.getOldestSurt();
            this.oldestSurtDate = other.getOldestSurtDate();

        } catch (ClassNotFoundException e) {
            System.err.println("Error reading FullImageMetadata from DataInput " +  e);
        }

    }

    public String getOldestSurt() {
        return oldestSurt;
    }

    public LocalDateTime getOldestSurtDate() {
        return oldestSurtDate;
    }
}
