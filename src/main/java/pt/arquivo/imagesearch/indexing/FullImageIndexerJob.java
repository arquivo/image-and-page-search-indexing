package pt.arquivo.imagesearch.indexing;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;


public class FullImageIndexerJob {

    /**
     * Runs the full indexing process for the desired input
     *
     * @param args args[0]: HDFS file with (W)ARC file list, args[1]: collection name, args[2]: (W)ARC files per map, args[3]: number of reduces, args[4]: are (W)ARCs in HDFS (true, false), args[5]: are (W)ARCs in HDFS (true, false), args[5]: output mode for the JSON format (FULL, COMPACT), args[6]: temporary directory where warc files are downloaded to
     * @throws Exception crash if there is an error getting required files from HDFS
     */
    public static void main(String[] args) throws Exception {
        // get system variable INDEXING_LOG_LEVEL and set log level accordingly
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
        String linesPerMap = args[2];

        assert args.length >= 4 : "Missing number of reduces";
        String reducesCount = args[3];

        assert args.length >= 5 : "Missing modeIsHDFS";
        String modeIsHDFS = args[4];

        assert args.length >= 6 : "Missing outputDirJob1";
        String outputDirJob1Arg = args[5];

        assert args.length >= 7 : "Missing warcFileTempBaseDir";
        String warcFileTempBaseDir = args[6];

        long currentTime = System.currentTimeMillis();
        // the output dir for the first Hadoop job is the input dir for the second job
        String outputDirJob1 = "/image-search-indexing/output/" + collection + "/" + currentTime + "_dups";
        String outputDirJob2 = "/image-search-indexing/output/" + collection + "/" + currentTime + "_nodups";

        String[] argsJob1 = new String[]{hdfsArcsPath, collection, linesPerMap, reducesCount, modeIsHDFS, outputDirJob1, warcFileTempBaseDir};

        int exitCode = ToolRunner.run(new ImageIndexerWithDupsJob(), argsJob1);

        Configuration conf = new Configuration();
        FileSystem hdfs = FileSystem.get(conf);

        if (exitCode != 0){
            // delete intermediate results, as the second job failed and they will not be used further
            hdfs.delete(new Path(outputDirJob1), true);
            System.exit(exitCode);
        }

        String[] argsJob2 = new String[]{collection, reducesCount, outputDirJob1Arg, outputDirJob1, outputDirJob2};
        exitCode = ToolRunner.run(new DupDigestMergerJob(), argsJob2);

        // delete intermediate results from job1, as only the output of the final job is needed
        hdfs.delete(new Path(outputDirJob1), true);


        System.exit(exitCode);


    }
}