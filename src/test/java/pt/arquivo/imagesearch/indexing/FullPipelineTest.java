package pt.arquivo.imagesearch.indexing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.io.Text;
import org.junit.Test;
import pt.arquivo.imagesearch.indexing.data.FullImageMetadata;
import pt.arquivo.imagesearch.indexing.data.ImageData;
import pt.arquivo.imagesearch.indexing.data.MultiPageImageData;
import pt.arquivo.imagesearch.indexing.data.serializers.ImageDataSerializer;
import pt.arquivo.imagesearch.indexing.data.serializers.MultiPageImageDataSerializer;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FullPipelineTest {

    @Test
    public void pipelineTestWARC() throws IOException {
        String logLevel = System.getenv("INDEXING_LOG_LEVEL");
        if (logLevel != null) {
            org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.toLevel(logLevel));
        } else {
            org.apache.log4j.Logger.getRootLogger().setLevel(org.apache.log4j.Level.ERROR);
        }
        
        ClassLoader classLoader = getClass().getClassLoader();

        URL hdfsArcsPath = classLoader.getResource("FullOfflinePipelineTestWARCS.txt");

        assertNotNull(hdfsArcsPath);

        String path = hdfsArcsPath.getPath();
        String collection = "Teste";

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(MultiPageImageData.class, new MultiPageImageDataSerializer())
                .registerTypeAdapter(ImageData.class, new ImageDataSerializer())
                .create();

        LocalFullImageIndexer.Map map = new LocalFullImageIndexer.Map(collection);

        BufferedReader br = new BufferedReader(new FileReader(path));

        for (String line; (line = br.readLine()) != null; ) {
            if (!line.trim().isEmpty()) {
                URL warcURL = classLoader.getResource(line.trim());
                assertNotNull(warcURL);
                String warcPath = "file://" + warcURL.getPath();
                map.map(warcPath);
            }
        }

        br.close();

        HashMap<String, List<Object>> mapResults = map.cleanup();
        HashMap<String, List<FullImageMetadata>> reduceResults = new HashMap<>();

        LocalFullImageIndexer.Reduce reduce = new LocalFullImageIndexer.Reduce();
        LocalFullImageIndexer.ReduceDigest reduceDigest = new LocalFullImageIndexer.ReduceDigest();

        for (java.util.Map.Entry<String, List<Object>> entry : mapResults.entrySet()) {
            FullImageMetadata result = reduce.reduce(entry.getKey(), entry.getValue());
            if (result != null) {
                Set<String> digests = new HashSet<>();
                for (ImageData imageData : result.getImageDatasValues()) {
                    String digest = imageData.getContentHash();
                    if (!digests.contains(imageData.getContentHash())) {
                        reduce.getCounter(DupDigestMergerJob.COUNTERS.RECORDS_MAP_IN).increment(1);
                        FullImageMetadata resultDigest = new FullImageMetadata(result, imageData);
                        reduceResults.putIfAbsent(digest, new LinkedList<>());
                        reduceResults.get(digest).add(resultDigest);
                        digests.add(digest);
                    }
                }
            }
        }


        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, List<FullImageMetadata>> entry : reduceResults.entrySet()) {

            FullImageMetadata result = reduceDigest.reduce(new Text(entry.getKey()), entry.getValue());
            if (result != null && !result.getPageImageDatas().isEmpty() && !result.getImageDatas().isEmpty()) {
                if (!result.getPageImageDatas().isEmpty() && !result.getImageDatas().isEmpty()) {
                    ImageData id = result.getImageDatas().firstKey();
                    MultiPageImageData pid = new MultiPageImageData(result);
                    out.append(gson.toJson(id));
                    out.append("\n");
                    out.append(gson.toJson(pid));
                    out.append("\n");
                }
            }
        }


        URL warcURL = classLoader.getResource("outputs/FullPipelineTest.jsonl");
        assertNotNull(warcURL);
        final File expected = new File(warcURL.getPath());
        assertEquals(FileUtils.readFileToString(expected, Charset.defaultCharset()), out.toString());
    }
}

