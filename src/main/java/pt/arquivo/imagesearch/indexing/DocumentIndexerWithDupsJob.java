package pt.arquivo.imagesearch.indexing;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import pt.arquivo.imagesearch.indexing.data.*;
import pt.arquivo.imagesearch.indexing.data.serializers.InlinkSerializer;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pt.arquivo.imagesearch.indexing.processors.DocumentInformationExtractor;
import pt.arquivo.imagesearch.indexing.utils.AlternativeFileUtils;
import pt.arquivo.imagesearch.indexing.utils.ImageSearchIndexingUtil;
import pt.arquivo.imagesearch.indexing.utils.WARCInformationParser;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Hadoop process responsible for the 1nd stage of the pipeline.
 * Takes (W)ARCs, extracts image and page metadata and generates intermediate results ready for deduplication.
 * The output is a set of intermediate files ready for the 2nd stage of Hadoop processing
 */
public class DocumentIndexerWithDupsJob extends Configured implements Tool {


    // Maximum difference between two capture dates to consider them the same for inlink matching
    public static final Duration MAX_CAPTURE_DATE_DIFFERENCE = Duration.ofDays(90);

    /**
     * Counters for the first Hadoop process that are related to images
     * <p>
     * WARCS: number of WARCS parsed
     * WARCS_DOWNLOAD_ERROR: number of WARCS that resulted in a download error
     * <p>
     * WARCS_FAILED: number of WARCs that failed processing
     * <p>
     * WARCS_FAILED_STREAM: number of WARCs that failed during stream decoding
     * <p>
     * RECORDS_READ: number of records processed from all types (pages, images and all other)
     * RECORDS_FAILED: number of records that failed processing
     * RECORD_NEXT_FAILED: number of records that failed when progressing to the next record
     * <p>
     * IMAGES_IN_WARC_TOTAL: number of images that are present in the WARC
     * IMAGES_IN_WARC_FAILED: number of images that failed processing (decoding, getting resolution, ...)
     * <p>
     * IMAGES_IN_WARC_TOO_SMALL: images that were not processed due to being too small (below 50x50 px)
     * IMAGES_IN_WARC_TOO_SMALL_BASE64: images that were not processed due to being too small in base64
     * IMAGES_IN_WARC_TOO_LARGE: images that were not processed due to being too large (area above 15000*15000)
     * IMAGES_IN_WARC_MIME_INVALID: images where the mimetype could not be detected
     * IMAGES_IN_WARC_MIME_WRONG: images where the detected mimetipe differs from the reported mimetype
     * <p>
     * IMAGES_IN_WARC_PARSED: number of images that were effectively parsed
     * IMAGES_IN_WARC_PARSED_DUP: number of images that were parsed (with duplicates from the same WARC removed)
     */
    public enum DOCUMENT_COUNTERS {
        WARCS,
        WARCS_DOWNLOAD_ERROR,

        WARCS_FAILED,

        WARCS_FAILED_STREAM,
        TIKA_RECORDS_FAILED_DIGEST,

        RECORDS_READ,
        RECORDS_PREPARSING_FAILED,
        RECORDS_TIKA_EMPTY,
        RECORDS_TIKA_READ,
        RECORDS_TIKA_FAILED,
        RECORDS_TIKA_FAILED_REDIRECT,
        RECORDS_TIKA_FAILED_STATUS_CODE,
        RECORDS_TIKA_FAILED_NO_SUCH_METHOD,
        RECORDS_TIKA_PARSED_MIME,
        RECORDS_TIKA_IGNORED_MIME_DETECTED,
        RECORDS_TIKA_EMPTY_BODY,
        
        RECORDS_PARSING_FAILED,
        RECORDS_SUCCESS,
        
        REDUCE_TOTAL_RECORDS,
        REDUCE_TOTAL_PAGES,
        REDUCE_TOTAL_INLINKS,
        REDUCE_MISSING_PAGES,
        REDUCE_NO_INLINKS,
        REDUCE_UNIQUE_PAGES,
        REDUCE_UNIQUE_INLINKS,
        REDUCE_IGNORED_INLINKS
        
    }

    public enum STATUS_CODES {
        status1xx,
        status2xx,
        status3xx,
        status4xx,
        status5xx,
        statusOther,
        statusUnknown,
    }

    
    
    public enum OUTPUT_MODE {
        PAGES,
        INLINKS
    }

    public static class Map extends Mapper<LongWritable, Text, Text, Writable> {

        private final Logger logger = Logger.getLogger(Map.class);
        public String collection;
        DocumentInformationExtractor indexer;
        private String warcFileTempBaseDir;
        

        @Override
        public void setup(Context context) {
            String logLevel = System.getenv("INDEXING_LOG_LEVEL");
            if (logLevel != null) {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
            } else {
                org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
            }
            //logger.setLevel(Level.DEBUG);
            Configuration config = context.getConfiguration();
            collection = config.get("collection");
            logger.debug(collection + "_Docs/docs/");
            this.collection = config.get("collection");
            this.warcFileTempBaseDir = config.get("warcFileTempBaseDir");
            // Make dir if not exists
            File dir = new File(warcFileTempBaseDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            indexer = new DocumentInformationExtractor(collection, context);
        }

        /**
         * First stage hadoop processing
         * <p>
         * Downloads the (W)ARC locally and processes it into all required metadata.
         * Entries are written into their corresponding SURT entries
         *
         * @param key     Hadoop key (not required at this stage)
         * @param value   (W)ARC url
         * @param context Hadoop context
         * @throws IOException unrecoverable errors processing (W)ARCs are thrown so that Hadoop retries it
         */
        public void map(LongWritable key, Text value, Context context) throws IOException {
            String arcURL = value.toString();
            if (!arcURL.isEmpty()) {
                logger.info("(W)ARCNAME: " + arcURL);
                context.getCounter(DOCUMENT_COUNTERS.WARCS).increment(1);

                URL url = null;
                try {
                    url = new URL(arcURL);
                } catch (MalformedURLException e) {
                    context.getCounter(DOCUMENT_COUNTERS.WARCS_FAILED).increment(1);
                    throw e;
                }
                String[] surl = url.getPath().split("/");
                String arcName = surl[surl.length - 1];
                String filename = warcFileTempBaseDir + "/" + System.currentTimeMillis() + "_" + arcName;

                try {
                    long fileSize = ImageSearchIndexingUtil.getFileSize(url);
                    //ImageSearchIndexingUtil.saveFile(url, filename);
                    File dest = new File(filename);

                    // download and parse WARC locally to avoid problems when streaming from remote server
                    AlternativeFileUtils.copyURLToFile(url, dest, 1000 * 120, 1000 * 60);
                    dest = new File(filename);
                    if (fileSize != dest.length()) {
                        long localFileSize = dest.length();
                        FileUtils.deleteQuietly(dest);
                        throw new IOException("Incomplete file: Local file and remote file have different sizes. Remote URL: " + url + " Remote file size: " + fileSize + " Local file name: " + filename + " Local file size: " + localFileSize);
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(), e);
                    context.getCounter(DOCUMENT_COUNTERS.WARCS_DOWNLOAD_ERROR).increment(1);
                    File dest = new File(filename);
                    FileUtils.deleteQuietly(dest);
                    throw e;
                }
                File dest = new File(filename);
                logger.info("(W)ARC downloaded: " + dest.getAbsolutePath());

                indexer.parseRecord(arcName, dest.getPath());
                FileUtils.deleteQuietly(dest);

            }
        }


        /**
         * So, results are only written at Hadoop cleanup stage (after all maps are finished) so that fewer duplicates are sent to the next stage
         *
         * @param context Hadoop context which will contain all the processed metadata
         * @throws IOException          error writing to Hadoop context
         * @throws InterruptedException error calling parent super.cleanup
         */
        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            logger.info("Cleanup");
            super.cleanup(context);
            for (java.util.Map.Entry<String, TextDocumentData> entry : indexer.getEntries().entrySet()) {
                
                TextDocumentData value = entry.getValue();
                try {
                    for (String surt : value.getSurts()) {
                        context.write(new Text(surt), new TextDocumentDataOutlinkPair(value, null));
                    }
                    for (Outlink outlink : value.getOutlinks().keySet()) {
                        context.write(new Text(outlink.getSurt()), new TextDocumentDataOutlinkPair(null, outlink));
                    }
                } catch (IOException | InterruptedException e) {
                    logger.error("Error writing output", e);
                }
            }
            for (java.util.Map.Entry<String, TextDocumentData> entry : indexer.getEntriesRedirect().entrySet()) {
                
                TextDocumentData value = entry.getValue();
                try {
                    for (String surt : value.getSurts()) {
                        context.write(new Text(surt), new TextDocumentDataOutlinkPair(value, null));
                    }
                    for (Outlink outlink : value.getOutlinks().keySet()) {
                        context.write(new Text(outlink.getSurt()), new TextDocumentDataOutlinkPair(null, outlink));
                    }
                } catch (IOException | InterruptedException e) {
                    logger.error("Error writing output", e);
                }
            }
        }
    }

    public static class Reduce extends Reducer<Text, Writable, Text, TextDocumentData> {

        private final Logger logger = Logger.getLogger(Reduce.class);
        public String collection;

        @Override
        public void setup(Reduce.Context context) {
            Configuration config = context.getConfiguration();
            collection = config.get("collection");

            this.collection = config.get("collection");
        }

        /**
         * 
         *
         * @param key     SURT for that specific entry
         * @param values  Page and inlinks for that SURT
         * @param context Hadoop context
         */
        public void reduce(Text key, Iterable<Writable> values, Context context) throws IOException, InterruptedException {
            try {
                HashMap<String, TextDocumentData> docDatas = new HashMap<>();
                HashSet<Outlink> inlinks = new HashSet<>();
                
                for (Writable value: values) {
                    context.getCounter(DOCUMENT_COUNTERS.REDUCE_TOTAL_RECORDS).increment(1);
                    TextDocumentDataOutlinkPair newDocdata = (TextDocumentDataOutlinkPair) value;
                    if (newDocdata.getTextDocumentData() != null) {
                        TextDocumentData newDoc = new TextDocumentData(newDocdata.getTextDocumentData());
                        context.getCounter(DOCUMENT_COUNTERS.REDUCE_TOTAL_PAGES).increment(1);

                        if (docDatas.get(newDoc.getDigestContent()) == null) {
                            docDatas.put(newDoc.getDigestContent(), newDoc);
                        } else {
                            TextDocumentData docData = docDatas.get(newDoc.getDigestContent());
                            docData = TextDocumentData.merge(docData, newDoc);
                            
                        }
                    }
                    if (newDocdata.getOutlink() != null) {
                        inlinks.add((Outlink) newDocdata.getOutlink());
                        context.getCounter(DOCUMENT_COUNTERS.REDUCE_TOTAL_INLINKS).increment(1);
                    }

                }
                if (docDatas.size() == 0) {
                    context.getCounter(DOCUMENT_COUNTERS.REDUCE_MISSING_PAGES).increment(1);
                    return;
                }

                context.getCounter(DOCUMENT_COUNTERS.REDUCE_UNIQUE_PAGES).increment(1);

                HashSet<Outlink> inlinksMatched = new HashSet<>();
                if (inlinks.size() > 0) {
                    for (Outlink inlink : inlinks) {
                        for (TextDocumentData docData : docDatas.values()) {
                            // add inlink to docData if inlink start date is before 30 days of docData start date,
                            // between the start and end date of the document or 30 days after the end date of the document
                             if (docData.getTimestamp().compareTo(inlink.getCaptureDateStart().minus(MAX_CAPTURE_DATE_DIFFERENCE)) >= 0 &&
                                 docData.getTimestamp().compareTo(inlink.getCaptureDateEnd().plus(MAX_CAPTURE_DATE_DIFFERENCE)) <= 0) {
                                docData.addInlink(inlink);
                                inlinksMatched.add(inlink);
                            }
                        }
                    }
                }

                context.getCounter(DOCUMENT_COUNTERS.REDUCE_IGNORED_INLINKS).increment(inlinks.size() - inlinksMatched.size());
                context.getCounter(DOCUMENT_COUNTERS.REDUCE_UNIQUE_INLINKS).increment(inlinks.size());

                try {
                    for (TextDocumentData docData : docDatas.values()) {
                        if (docData.isRedirect())
                            continue;
                        String digestKey = docData.getDigestContent();
                        Text digestKeyText = new Text(digestKey);
                        context.write(digestKeyText, docData);
                    }
                } catch (IOException | InterruptedException e) {
                    logger.error("Error writing output", e);
                }
            } catch (Exception e) {
                logger.error("Error reducing", e);
            }
        }
    }

    public static class ReduceInlinks extends Reducer<Text, Writable, NullWritable, Text> {

        private final Logger logger = Logger.getLogger(Reduce.class);
        public String collection;

        @Override
        public void setup(ReduceInlinks.Context context) {
            Configuration config = context.getConfiguration();
            collection = config.get("collection");

            this.collection = config.get("collection");
        }


        public void reduce(Text key, Iterable<Writable> values, Context context) throws IOException, InterruptedException {
            try {
                HashMap<Inlink,Inlink> inlinksInternal = new HashMap<>();
                HashMap<Inlink,Inlink> inlinksExternal = new HashMap<>();
                boolean wasCaptured = false;
                LocalDateTime captureDateStart = null;

                for (Writable value: values) {
                    context.getCounter(DOCUMENT_COUNTERS.REDUCE_TOTAL_RECORDS).increment(1);
                    TextDocumentDataOutlinkPair newDocdata = (TextDocumentDataOutlinkPair) value;
                    if (newDocdata.getOutlink() != null) {
                        Outlink outlink = (Outlink) newDocdata.getOutlink();
                        Inlink inlink = new Inlink(outlink);
                        boolean isInternal = WARCInformationParser.isInternal(key.toString(), outlink.getSource());
                        if (isInternal){

                            if (inlinksInternal.containsKey(inlink)) {
                                Inlink existingInlink = inlinksInternal.get(inlink);
                                if (existingInlink.getCaptureDateStart().compareTo(inlink.getCaptureDateStart()) > 0) {
                                    inlink.incrementCount(existingInlink.getCount());
                                    inlinksInternal.put(inlink, inlink);
                                } else {
                                    existingInlink.incrementCount(inlink.getCount());
                                }
                            } else if (inlinksInternal.size() < TextDocumentData.MAX_INLINKS_INTERNAL) {
                                inlinksInternal.put(inlink, inlink);   
                            }
                        } else {
                            if (inlinksExternal.containsKey(inlink)) {
                                Inlink existingInlink = inlinksExternal.get(inlink);
                                if (existingInlink.getCaptureDateStart().compareTo(inlink.getCaptureDateStart()) > 0) {
                                    inlink.incrementCount(existingInlink.getCount());
                                    inlinksExternal.put(inlink, inlink);
                                } else {
                                    existingInlink.incrementCount(inlink.getCount());
                                }
                            } else if (inlinksExternal.size() < TextDocumentData.MAX_INLINKS_EXTERNAL) {
                                inlinksExternal.put(inlink, inlink);
                            }
                        }
                        
                    } else if (newDocdata.getTextDocumentData() != null) {
                        wasCaptured = true;
                        captureDateStart = ((TextDocumentData) newDocdata.getTextDocumentData()).getTimestamp();
                    }
                }

                if (!wasCaptured) {
                    context.getCounter(DOCUMENT_COUNTERS.REDUCE_MISSING_PAGES).increment(1);
                    return;
                }

                if (inlinksInternal.size()+inlinksExternal.size() == 0) {
                    context.getCounter(DOCUMENT_COUNTERS.REDUCE_NO_INLINKS).increment(1);
                    return;
                }

                context.getCounter(DOCUMENT_COUNTERS.REDUCE_UNIQUE_INLINKS).increment(1);

                exportInlinksToJson(context, key.toString(), wasCaptured, inlinksInternal.values(), inlinksExternal.values(), captureDateStart);
            } catch (Exception e) {
                logger.error("Error reducing", e);
            }
        }

        private void exportInlinksToJson(Reducer<Text,Writable,NullWritable,Text>.Context context, String target, boolean wasCaptured, Collection<Inlink> inlinksInternal, Collection<Inlink> inlinksExternal, LocalDateTime captureDateStart) {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(InlinkSerializer.SetInlink.class, new InlinkSerializer())
                    .disableHtmlEscaping()
                    .serializeNulls()
                    .create();
            
            try {
                context.write(NullWritable.get(), new Text(gson.toJson(new InlinkSerializer.SetInlink(target, wasCaptured, inlinksInternal, inlinksExternal, captureDateStart))));
            } catch (IOException | InterruptedException e) {
                logger.error(e.getMessage());
            }
        }
    }



    /**
     * Class entry point, process all (W)ARCs and output intermediary non-deduplicated results ready for the next Hadoop stage
     *
     * @param args: args[0]: HDFS file with (W)ARC file list, args[1]: collection name, args[2]: (W)ARC files per map, args[3]: number of reduces, args[4]: are (W)ARCs in HDFS (true, false), (optional) args[5]: output HDFS dir
     * @return 0 if successful, 1 otherwise
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

        assert args.length >= 1 : "Missing hdfs file with all arcs path argument";
        String hdfsArcsPath = args[0];

        assert args.length >= 2 : "Missing collection name argument";
        String collection = args[1];
        

        assert args.length >= 3 : "Missing number of warcs per map";
        int linesPerMap = Integer.parseInt(args[2]);

        assert args.length >= 4 : "Missing number of reduces";
        int reducesCount = Integer.parseInt(args[3]);

        assert args.length >= 5 : "Missing output dir";
        String outputDir = args[4];

        assert args.length >= 6 : "Missing warcFileTempBaseDir";
        String warcFileTempBaseDir = args[5];

        OUTPUT_MODE outputMode = OUTPUT_MODE.PAGES;
        if (args.length >= 7) {
            outputMode = OUTPUT_MODE.valueOf(args[6]);
        }

        Configuration conf = new Configuration();
        conf.set("collection", collection);
        conf.set("warcFileTempBaseDir", warcFileTempBaseDir);

        Job job = Job.getInstance(conf);

        job.setJarByClass(DocumentIndexerWithDupsJob.class);

        job.setMapperClass(DocumentIndexerWithDupsJob.Map.class);
        job.setInputFormatClass(NLineInputFormat.class);
        NLineInputFormat.addInputPath(job, new Path(hdfsArcsPath));
        job.getConfiguration().setInt("mapreduce.input.lineinputformat.linespermap", linesPerMap);
        job.getConfiguration().setBoolean("inlinksOnly", outputMode == OUTPUT_MODE.INLINKS);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(TextDocumentDataOutlinkPair.class);

        String jobName;
        if (outputMode == OUTPUT_MODE.PAGES) {
            job.setReducerClass(DocumentIndexerWithDupsJob.Reduce.class);
            job.setOutputKeyClass(Text.class);
            job.setOutputValueClass(TextDocumentData.class);
            job.setOutputFormatClass(SequenceFileOutputFormat.class);
            jobName = collection + "_DocumentIndexerWithDups";
        } else { // if (outputMode == OUTPUT_MODE.INLINKS) {
            job.setReducerClass(DocumentIndexerWithDupsJob.ReduceInlinks.class);
            job.setOutputKeyClass(NullWritable.class);
            job.setOutputValueClass(Text.class);
            job.setOutputFormatClass(TextOutputFormat.class);
            jobName = collection + "_InlinkExtractor";
            //job.getConfiguration().setFloat("mapreduce.job.reduce.slowstart.completedmaps", 1);
        }
        job.setJobName(jobName);

        //job.getConfiguration().setInt("mapreduce.job.running.map.limit", 80);

        // sometimes, jobs can fail for recoverable reasons (errors getting WARC from document server
        // by setting the retry amount to 6, we ensure that only Maps from unusable WARCs fail processing
        job.getConfiguration().setInt("mapreduce.map.maxattempts", 6);
        job.getConfiguration().setInt("mapreduce.reduce.shuffle.parallelcopies", 10);

        // increased timeout ensure that even the most complex and largest (W)ARCS are processed
        job.getConfiguration().setInt("mapreduce.task.timeout", 5400000);

        job.setNumReduceTasks(reducesCount);

        FileOutputFormat.setOutputPath(job, new Path(outputDir));

        FileSystem hdfs = FileSystem.get(conf);
        if (hdfs.exists(new Path(outputDir)))
            hdfs.delete(new Path(outputDir), true);

        return job.waitForCompletion(true) ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new DocumentIndexerWithDupsJob(), args);
        System.exit(exitCode);
    }
}