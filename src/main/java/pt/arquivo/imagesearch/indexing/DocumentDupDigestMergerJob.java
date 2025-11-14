package pt.arquivo.imagesearch.indexing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.log4j.Logger;

import pt.arquivo.imagesearch.indexing.data.TextDocumentData;
import pt.arquivo.imagesearch.indexing.data.serializers.TextDocumentDataSerializer;
import pt.arquivo.imagesearch.indexing.processors.DocumentInformationMerger;

import java.io.IOException;

/**
 * Hadoop process responsible for the 2nd stage of the pipeline.
 * Takes intermediate, duplicate results from the first stage (grouped by digest), and performs digest-based deduplication.
 * The output is a set of JSONL files ready for the NSFW classification process
 */
public class DocumentDupDigestMergerJob extends Configured implements Tool {

    /**
     * Counters for the map process:
     *
     * RECORDS_IN: Count of pages (with unique digests) that where processed
     * RECORDS_OUT: Count of pages that where outputted
     * RECORDS_WITH_INLINKS: Number of pages that have inlinks
     * RECORDS_WITH_INLINKS: Number of pages that don't have inlinks
     * INLINKS_ALL: Total number of inlinks
     */
    public enum COUNTERS {
        RECORDS_MAP_IN,
        RECORDS_IN,
        RECORDS_OUT,
        RECORDS_WITH_INLINKS,
        RECORDS_WITHOUT_INLINKS,
        RECORDS_WITH_INLINKS_INTERNAL,
        RECORDS_WITHOUT_INLINKS_INTERNAL,
        RECORDS_WITH_INLINKS_EXTERNAL,
        RECORDS_WITHOUT_INLINKS_EXTERNAL,
        RECORDS_IS_REDIRECT,
        INLINKS_ALL_INTERNAL,
        INLINKS_UNIQUE_SURT_INTERNAL,
        INLINKS_ALL_EXTERNAL,
        INLINKS_UNIQUE_SURT_EXTERNAL,
    }

    /**
     * Counters for the reduce process:
     * <p>
     * URL_IMAGES_PAGESALL: Number of pages referencing images (without deduplication)
     * URL_IMAGESALL_PAGES: Number of images referenced by pages (without deduplication)
     * URL_IMAGES_PAGES: Number of images processed (with deduplication)
     */
    public enum REDUCE_COUNTERS {
        RECORDS_IN,
        RECORDS_OUT,
    }

    public static class Map extends Mapper<Text, Writable, Text, Writable> {

        private final Logger logger = Logger.getLogger(Map.class);

        @Override
        public void setup(Map.Context context) {
            String logLevel = System.getenv("INDEXING_LOG_LEVEL");
            if (logLevel != null) {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
            } else {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
            }
        }
        /**
         * Map process groups FullImageMetadata records by digest by writing them to the "key" Hadoop entry
         *
         * @param key     image digest for this FullImageMetadata object
         * @param value   single FullImageMetadata object to be deduplicated
         * @param context Hadoop context
         */
        public void map(Text key, Writable value, Context context) {
            try {
                context.getCounter(COUNTERS.RECORDS_MAP_IN).increment(1);
                context.write(key, value);
            } catch (IOException | InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
    }

    public static class Reduce extends Reducer<Text, Writable, NullWritable, Text> {

        private final Logger logger = Logger.getLogger(Reduce.class);
        public String collection;
        private DocumentInformationMerger merger;

        @Override
        public void setup(Reduce.Context context) {
            String logLevel = System.getenv("INDEXING_LOG_LEVEL");
            if (logLevel != null) {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
            } else {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
            }
            merger = new DocumentInformationMerger(context);
        }


        /**
         * For each digest, merges all FullImageMetadata into a single record for output
         *
         * @param key     image digest for this set of FullImageMetadata object
         * @param values  set of FullImageMetadata object to be deduplicated
         * @param context Hadoop context
         */
        public void reduce(Text key, Iterable<Writable> values, Context context) {
            logger.debug("Reducing: " + key);

            merger.reset();
            int counter = merger.mergeAllHadoop(values);
            TextDocumentData result = merger.getBestMatch();

            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_IN).increment(counter);
            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_OUT).increment(1);


            if (result.isRedirect()) {
                logger.error("No result found for digest: " + key);
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_IS_REDIRECT).increment(1);
                return;
            }

            if ((result.getInlinksInternal().size() + result.getInlinksExternal().size()) > 0)
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITH_INLINKS).increment(1);
            else
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITHOUT_INLINKS).increment(1);


            if (result.getInlinksInternal().size() > 0)
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITH_INLINKS_INTERNAL).increment(1);
            else
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITHOUT_INLINKS_INTERNAL).increment(1);

            if (result.getInlinksExternal().size() > 0)
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITH_INLINKS_EXTERNAL).increment(1);
            else
                merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.RECORDS_WITHOUT_INLINKS_EXTERNAL).increment(1);

            logger.debug(String.format("Found %d records", counter));

            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.INLINKS_ALL_EXTERNAL).increment(result.getInlinksExternal().size());
            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.INLINKS_UNIQUE_SURT_EXTERNAL).increment(result.getInlinkSurtsExternal().size());

            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.INLINKS_ALL_INTERNAL).increment(result.getInlinksInternal().size());
            merger.getCounter(DocumentDupDigestMergerJob.COUNTERS.INLINKS_UNIQUE_SURT_INTERNAL).increment(result.getInlinkSurtsInternal().size());

            logger.debug("Reducing: " + key);

            exportToJson(context, result);

        }

        /**
         * Transforms a FullImageMetadata object into the desired JSONL format
         *
         * @param context Hadoop context
         * @param result FullImageMetadata to be outputted
         */
        private void exportToJson(Reducer<Text, Writable, NullWritable, Text>.Context context, TextDocumentData result) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(TextDocumentData.class, new TextDocumentDataSerializer())
                    .create();
            
            try {
                context.write(NullWritable.get(), new Text(gson.toJson(result)));
            } catch (IOException | InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
    }

    /**
     * Runs the DupDigestMergerJob process
     *
     * @param args: args[0]: collection name, args[1]: files per map, args[2]: number of reduces, args[4]: output mode (FULL, COMPACT), (optional) args[4]: input HDFS dir,  args[5]: output HDFS dir
     * @return Whether Hadoop process finihsed successfully (0) or not (1)
     * @throws Exception crash if there is an error getting required files from HDFS
     */
    @Override
    public int run(String[] args) throws Exception {
        String logLevel = System.getenv("INDEXING_LOG_LEVEL");
        if (logLevel != null) {
            org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
        } else {
            org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
        }
    
        assert args.length >= 1 : "Missing collection name argument";
        String collection = args[0];
        String jobName = collection + "_DocumentDupDigestMerger";

        assert args.length >= 2 : "Missing number of reduces";
        int reducesCount = Integer.parseInt(args[1]);

        String inputDir;
        String outputDirDigest;

        Configuration conf = new Configuration();
        conf.set("collection", collection);
        FileSystem hdfs = FileSystem.get(conf);

        if (args.length < 3)
            throw new IllegalArgumentException("Missing input and output directories");

        inputDir = args[2];
        outputDirDigest = args[3];


        Job jobDigest = Job.getInstance(conf);
        jobDigest.setJarByClass(DocumentDupDigestMergerJob.class);
        jobDigest.setInputFormatClass(SequenceFileInputFormat.class);

        jobDigest.setMapperClass(DocumentDupDigestMergerJob.Map.class);
        jobDigest.setMapOutputKeyClass(Text.class);
        jobDigest.setMapOutputValueClass(TextDocumentData.class);

        jobDigest.setReducerClass(DocumentDupDigestMergerJob.Reduce.class);
        jobDigest.setOutputKeyClass(NullWritable.class);
        jobDigest.setOutputValueClass(Text.class);
        jobDigest.setOutputFormatClass(TextOutputFormat.class);

        jobDigest.setJobName(jobName);

        jobDigest.setNumReduceTasks(reducesCount);

        jobDigest.getConfiguration().setFloat("mapreduce.job.reduce.slowstart.completedmaps", 1.0f);


        KeyValueTextInputFormat.setInputDirRecursive(jobDigest, true);
        KeyValueTextInputFormat.addInputPath(jobDigest, new Path(inputDir));

        TextOutputFormat.setOutputPath(jobDigest, new Path(outputDirDigest));
        if (hdfs.exists(new Path(outputDirDigest)))
            hdfs.delete(new Path(outputDirDigest), true);

        return jobDigest.waitForCompletion(true) ? 0 : 1;
    }


    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new DocumentDupDigestMergerJob(), args);
        System.exit(exitCode);
    }
}