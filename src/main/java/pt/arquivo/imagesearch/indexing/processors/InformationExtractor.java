package pt.arquivo.imagesearch.indexing.processors;

import org.apache.hadoop.mapreduce.Counter;

public interface InformationExtractor {
    
    public Counter getCounter(Enum<?> counterName);
}
