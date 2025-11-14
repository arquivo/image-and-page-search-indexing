package pt.arquivo.imagesearch.indexing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import pt.arquivo.imagesearch.indexing.data.FullImageMetadata;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.KeyValueTextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.log4j.Logger;
import pt.arquivo.imagesearch.indexing.data.ImageData;
import pt.arquivo.imagesearch.indexing.data.MultiPageImageData;
import pt.arquivo.imagesearch.indexing.data.PageImageData;
import pt.arquivo.imagesearch.indexing.data.serializers.ImageDataSerializer;
import pt.arquivo.imagesearch.indexing.data.serializers.MultiPageImageDataSerializer;
import pt.arquivo.imagesearch.indexing.processors.ImageInformationMerger;

import java.io.IOException;

/**
 * Hadoop process responsible for the 2nd stage of the pipeline.
 * Takes intermediate, duplicate results from the first stage (grouped by digest), and performs digest-based deduplication.
 * The output is a set of JSONL files ready for the NSFW classification process
 */
public class DupDigestMergerJob extends Configured implements Tool {


    /**
     * OUTPUT_MODE corresponds to the way the data will be represented in the output JSON. In this scenario, the same image is indexed multiple times, each one with a set of page metadata matching a different page where it showed up.
     * The FULL mode corresponds to having a JSON document per page where the image appears.
     * The COMPACT mode (currently used in prod) creates a single JSON document per image, with the oldest page metadata plus all unique image metadata (imgTitle, imgAlt, imgCaption) from all pages.
     * More information about the process behind this decision is present in the technical report
     */
    public enum OUTPUT_MODE {
        FULL,
        COMPACT
    }


    /**
     * Hadoop config key for the output mode config
     */
    public static final String OUTPUT_MODE_NAME = "output_mode";


    /**
     * Counters for the map process:
     *
     * RECORDS_IN: Count of images (with unique digests) that where processed
     * RECORDS_OUT: Count of images that where outputted
     * RECORDS_WITH_METADATA: Number of images that have image specific metadata
     * RECORDS_WITHOUT_METADATA: Number of images that don't have image specific metadata
     * URL_IMAGES_PAGESALL: Number of pages referencing images (without deduplication)
     * URL_IMAGESALL_PAGES: Number of images referenced by pages (without deduplication)
     * URL_IMAGES_PAGES: Number of images processed (with deduplication)
     */
    public enum COUNTERS {
        RECORDS_MAP_IN,
        RECORDS_IN,
        RECORDS_OUT,
        RECORDS_WITH_METADATA,
        RECORDS_WITHOUT_METADATA
    }

    /**
     * Counters for the reduce process:
     * <p>
     * URL_IMAGES_PAGESALL: Number of pages referencing images (without deduplication)
     * URL_IMAGESALL_PAGES: Number of images referenced by pages (without deduplication)
     * URL_IMAGES_PAGES: Number of images processed (with deduplication)
     */
    public enum REDUCE_COUNTERS {
        URL_IMAGES_PAGESALL,
        URL_IMAGESALL_PAGES,
        URL_IMAGES_PAGES
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
        private ImageInformationMerger merger;
        OUTPUT_MODE outputMode;

        @Override
        public void setup(Reduce.Context context) {
            String logLevel = System.getenv("INDEXING_LOG_LEVEL");
            if (logLevel != null) {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
            } else {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
            }
            merger = new ImageInformationMerger(context);
            Configuration config = context.getConfiguration();
            String legacyOutput = config.get(OUTPUT_MODE_NAME);
            outputMode = OUTPUT_MODE.valueOf(legacyOutput);
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
            FullImageMetadata result = merger.getBestMatch();

            merger.getCounter(DupDigestMergerJob.COUNTERS.RECORDS_OUT).increment(1);
            merger.getCounter(DupDigestMergerJob.REDUCE_COUNTERS.URL_IMAGES_PAGESALL).increment(result.getPageImageDatasValues().size());
            merger.getCounter(DupDigestMergerJob.REDUCE_COUNTERS.URL_IMAGESALL_PAGES).increment(result.getImageDatasValues().size());
            merger.getCounter(DupDigestMergerJob.REDUCE_COUNTERS.URL_IMAGES_PAGES).increment(1);

            logger.info(String.format("Found %d records", counter));

            if (result.hasImageMetadata())
                merger.getCounter(DupDigestMergerJob.COUNTERS.RECORDS_WITH_METADATA).increment(1);
            else
                merger.getCounter(DupDigestMergerJob.COUNTERS.RECORDS_WITHOUT_METADATA).increment(1);

            logger.debug("Reducing: " + key);

            exportToJson(context, result);

        }

        /**
         * Transforms a FullImageMetadata object into the desired JSONL format
         *
         * @param context Hadoop context
         * @param result FullImageMetadata to be outputted
         */
        private void exportToJson(Reducer<Text, Writable, NullWritable, Text>.Context context, FullImageMetadata result) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(MultiPageImageData.class, new MultiPageImageDataSerializer())
                    .registerTypeAdapter(ImageData.class, new ImageDataSerializer())
                    .create();

            try {
                // If output mode is FULL, "deduplication" only removes FullImageMetadata if they are exactly the same. There may be multiple image and page records for each unique (by digest) image
                if (outputMode == OUTPUT_MODE.FULL) {
                    for (ImageData data : result.getImageDatasValues())
                        context.write(NullWritable.get(), new Text(gson.toJson(data)));
                    for (PageImageData data : result.getPageImageDatasValues())
                        context.write(NullWritable.get(), new Text(gson.toJson(data)));
                // If output mode is COMPACT, "deduplication" will make that there will only be a single image data line and a single image metadata line
                } else if (outputMode == OUTPUT_MODE.COMPACT) {
                    if (!result.getPageImageDatas().isEmpty() && !result.getImageDatas().isEmpty()) {
                        ImageData id = result.getImageDatas().firstKey();
                        MultiPageImageData pid = new MultiPageImageData(result);
                        // ImageData and MultiPageImageData are written separately to simplify the next processing stage
                        // The ImageData JSON contains a base64 version of the iamge and will be used for NSFW classification
                        context.write(NullWritable.get(), new Text(gson.toJson(id)));
                        // The MultiPageImageData contains image and page metadata and is the document that is sent for SolrCloud
                        context.write(NullWritable.get(), new Text(gson.toJson(pid)));
                    }

                }
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
        String jobName = collection + "_DupDigestMerger";

        assert args.length >= 2 : "Missing number of reduces";
        int reducesCount = Integer.parseInt(args[1]);

        assert args.length >= 3 : "Missing Output mode (FULL, COMPACT)";
        String outputModeString = args[2];

        String inputDir;
        String outputDirDigest;

        Configuration conf = new Configuration();
        conf.set("collection", collection);
        conf.set(OUTPUT_MODE_NAME, outputModeString);
        FileSystem hdfs = FileSystem.get(conf);

        if (args.length >= 4) {
            inputDir = args[3];
            outputDirDigest = args[4];
        } else {
            inputDir = "/image-search-indexing/output/" + collection + "/";

            FileStatus[] fileStatus = hdfs.listStatus(new Path(inputDir));
            long latestValueLong = 0;
            for (FileStatus fileStat : fileStatus) {
                if (fileStat.isDirectory()) {
                    if (fileStat.getPath().getName().endsWith("_dups")) {
                        try {
                            String name = fileStat.getPath().getName().replace("_dups", "");
                            long currentValueLong = Long.parseLong(name);
                            if (currentValueLong > latestValueLong)
                                latestValueLong = currentValueLong;
                        } catch (Exception ignore) {

                        }
                    }
                }
            }
            // Default output dir for HDFS processes
            inputDir += latestValueLong + "_dups/";
            outputDirDigest = "/image-search-indexing/output/" + collection + "/" + latestValueLong + "_nodups/";
        }


        Job jobDigest = Job.getInstance(conf);
        jobDigest.setJarByClass(DupDigestMergerJob.class);
        jobDigest.setInputFormatClass(SequenceFileInputFormat.class);

        jobDigest.setMapperClass(DupDigestMergerJob.Map.class);
        jobDigest.setMapOutputKeyClass(Text.class);
        jobDigest.setMapOutputValueClass(FullImageMetadata.class);

        jobDigest.setReducerClass(DupDigestMergerJob.Reduce.class);
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
        int exitCode = ToolRunner.run(new DupDigestMergerJob(), args);
        System.exit(exitCode);
    }
}