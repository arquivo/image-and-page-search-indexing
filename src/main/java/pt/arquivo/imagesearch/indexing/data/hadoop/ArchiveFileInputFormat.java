package pt.arquivo.imagesearch.indexing.data.hadoop;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import java.io.IOException;


/**
 * This class is used to read (W)ARC files when they come from HDFS
 * Used in the HDFSImageIndexerWithDupsJob clause
 */
public class ArchiveFileInputFormat extends FileInputFormat<LongWritable, WritableArchiveRecord> {

    /**
     * Transforms the (W)ARC into a reader that is supported by Hadoop
     *
     * @param split Hadoop struct that contains the (W)ARC object
     * @param context Hadoop context
     * @return a reader ready to be transformed into an Archive object
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public RecordReader<LongWritable, WritableArchiveRecord> createRecordReader(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
        ArchiveFileRecordReader reader = new ArchiveFileRecordReader();
        reader.initialize(split, context);
        return reader;
    }

    /**
     * Returns whether the record can be split or not
     *
     * @param context Hadoop context
     * @param filename (W)ARC location
     * @return alwaays false, a (W)ARC file must be processed all at the same time
     */
    @Override
    protected boolean isSplitable(JobContext context, Path filename) {
        return false;
    }
}