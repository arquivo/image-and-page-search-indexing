package pt.arquivo.imagesearch.indexing.processors;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.counters.GenericCounter;

import pt.arquivo.imagesearch.indexing.DocumentDupDigestMergerJob;
import pt.arquivo.imagesearch.indexing.data.TextDocumentData;

import java.util.HashMap;
import java.util.List;

/**
 * Merges TextDocumentData records for deduplication
 */
public class DocumentInformationMerger {

    /**
     * Maximum supported number of objects to merge
     */
    public static final int MAX_OBJECTS_TO_MERGE = 1000;


    /**
     * Metadata entry that will contain the merged record
     */
    private TextDocumentData entry;

    /**
     * Hadoop context
     */
    private DocumentDupDigestMergerJob.Reduce.Context context = null;

    /**
     * Local counter cache
     */
    private HashMap<Enum<?>, Counter> localCounters;

    /**
     * Constructor for Hadoop
     *
     * @param context Hadoop context
     */
    public DocumentInformationMerger(DocumentDupDigestMergerJob.Reduce.Context context) {
        this.context = context;
        reset();
    }

    /**
     * Non-Hadoop constructor
     */
    public DocumentInformationMerger() {
        this.localCounters = new HashMap<>();
        reset();
    }

    /**
     * Get counters from Hadoop or from local cache
     *
     * @param counterName name of counter
     * @return counter object
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
     * Reset the Merger to work with a new TextDocumentData object
     */
    public void reset() {
        entry = null;
    }

    /**
     * Merge all objects into the current TextDocumentData object
     *
     * @param values TextDocumentData values to merge
     * @return merged object
     */
    public int mergeAll(Iterable<TextDocumentData> values) {
        int counter = 0;
        for (TextDocumentData metadata : values) {
            entry = TextDocumentData.merge(entry, metadata);
            counter++;
        }
        return counter;
    }

    /**
     * Merge all objects into the current TextDocumentData object
     * (to be used in Haddop)
     *
     * @param values TextDocumentData values to merge
     * @return merged object
     */
    public int mergeAllHadoop(Iterable<Writable> values) {
        int counter = 0;
        for (Writable value : values) {
            TextDocumentData metadata =  new TextDocumentData((TextDocumentData) value);
            entry = TextDocumentData.merge(entry, metadata);
            counter++;
            if (counter >= MAX_OBJECTS_TO_MERGE)
                break;
        }
        return counter;
    }

    /**
     * Merge all objects into the current TextDocumentData object
     *
     * @param values TextDocumentData values to merge
     * @return merged object
     */
    public int mergeAll(List<Object> values) {
        int counter = 0;
        for (Object value : values) {
            TextDocumentData metadata = (TextDocumentData) value;
            entry = TextDocumentData.merge(entry, metadata);
            counter++;
            if (counter >= MAX_OBJECTS_TO_MERGE)
                break;
        }
        return counter;
    }

    /**
     * Create best object from the candidate objects
     *
     * @return the best matched object
     */
    public TextDocumentData getBestMatch() {
        return entry;
    }
}
