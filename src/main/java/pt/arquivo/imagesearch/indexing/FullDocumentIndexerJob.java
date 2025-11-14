package pt.arquivo.imagesearch.indexing;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;

import pt.arquivo.imagesearch.indexing.DocumentIndexerWithDupsJob.OUTPUT_MODE;


public class FullDocumentIndexerJob {

    /**
     * Runs the full indexing process for the desired input
     *
     * @param args args[0]: HDFS file with (W)ARC file list, args[1]: collection name, args[2]: (W)ARC files per map, args[3]: number of reduces, args[4]: are (W)ARCs in HDFS (true, false), args[5]: are (W)ARCs in HDFS (true, false), args[5]: output mode for the JSON format (FULL, COMPACT), args[6]: temporary directory where warc files are downloaded to
     * @throws Exception crash if there is an error getting required files from HDFS
     */
    public static void main(String[] args) throws Exception {
        //long currentTime = System.currentTimeMillis();

        assert args.length >= 1 : "Missing hdfs file with all arcs path argument";
        String hdfsArcsPath = args[0];

        assert args.length >= 2 : "Missing collection name argument";
        String collection = args[1];

        assert args.length >= 3 : "Missing number of warcs per map";
        String linesPerMap = args[2];

        assert args.length >= 4 : "Missing number of reduces";
        String reducesCount = args[3];

        assert args.length >= 5 : "Missing outputDirJob1";
        String outputDirJob1 = args[4];
        //outputDirJob1 = "/document-search-indexing/output/" + collection + "/" + currentTime;

        assert args.length >= 6 : "Missing outputDirJob2";
        String outputDirJob2 = args[5];
        //outputDirJob2 = "/document-search-indexing/output/" + collection + "/" + currentTime + "_nodups";

        assert args.length >= 7 : "Missing warcTempDir";
        String warcTempDir = args[6];

        OUTPUT_MODE outputMode = OUTPUT_MODE.PAGES;

        if (args.length >= 8) {
            outputMode = OUTPUT_MODE.valueOf(args[7]);
        }
        

        if (outputMode == OUTPUT_MODE.INLINKS)
            outputDirJob1 = outputDirJob2;
        
        
        String[] argsJob1 = new String[]{hdfsArcsPath, collection, linesPerMap, reducesCount, outputDirJob1, warcTempDir, outputMode.toString()};

        int exitCode = ToolRunner.run(new DocumentIndexerWithDupsJob(), argsJob1);

        Configuration conf = new Configuration();
        FileSystem hdfs = FileSystem.get(conf);

        // delete intermediate results from job1, as only the output of the final job is needed

        if (exitCode != 0){
            // delete intermediate results, as the second job failed and they will not be used further
            hdfs.delete(new Path(outputDirJob1), true);
            System.exit(exitCode);
        }

        if (outputMode == OUTPUT_MODE.INLINKS) {
            // if the output mode is INLINKS, there is no need to run the second job
            System.exit(exitCode);
        }

        // the output dir for the first Hadoop job is the input dir for the second job
        String[] argsJob2 = new String[]{collection, reducesCount, outputDirJob1, outputDirJob2};
        exitCode = ToolRunner.run(new DocumentDupDigestMergerJob(), argsJob2);
        
        // delete intermediate results
        hdfs.delete(new Path(outputDirJob1), true);

        System.exit(exitCode);
    }
}