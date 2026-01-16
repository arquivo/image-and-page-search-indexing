package pt.arquivo.imagesearch.indexing;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Tool;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pt.arquivo.imagesearch.indexing.data.ImageData;
import pt.arquivo.imagesearch.indexing.data.MultiPageImageData;
import pt.arquivo.imagesearch.indexing.data.serializers.ImageDataSerializer;
import pt.arquivo.imagesearch.indexing.data.serializers.MultiPageImageDataSerializer;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;


public class HadoopFullPipelineTest {
    private Configuration conf;

    @Before
    public void setup() throws IOException {
        conf = new Configuration();
        conf.set("fs.default.name", "file:///");
        conf.set("mapred.job.tracker", "local");
    }

    private void runMapReduceJob(Tool job, Configuration conf, String[] args) throws Exception {
        job.setConf(conf);
        int output = job.run(args);
        assert(output == 0);
    }

    @Test
    public void testImageSearchIndexingWorkflow() throws Exception {

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

        File tempFile = File.createTempFile("teste", "teste");
        tempFile.deleteOnExit();

        PrintWriter pw = new PrintWriter(tempFile);

        BufferedReader br = new BufferedReader(new FileReader(path));
        for (String line; (line = br.readLine()) != null; ) {
            if (!line.trim().isEmpty()) {
                URL warcURL = classLoader.getResource(line.trim());
                assertNotNull(warcURL);
                String warcPath = "file://" + warcURL.getPath();
                pw.println(warcPath);
            }
        }
        br.close();
        pw.close();

        FileSystem fs = FileSystem.getLocal(conf);

        fs.mkdirs(new Path("target/output"));
        fs.mkdirs(new Path("target/outputI"));

        String[] args = new String[]{tempFile.getPath(), collection, "1", "1", "false", "target/outputI", "/tmp"};

        runMapReduceJob(new ImageIndexerWithDupsJob(), conf, args);

        args = new String[]{collection, "1", "COMPACT", "target/outputI", "target/output"};

        runMapReduceJob(new DupDigestMergerJob(), conf, args);

        URL warcURL = classLoader.getResource("outputs/HadoopFullPipelineTest.jsonl");
        assertNotNull(warcURL);
        final File expected = new File(warcURL.getPath());

        Path outputHDFS = new Path("target/output/part-r-00000");
        InputStream in = null;
        BufferedReader br2 = null;
        String text = "";
        try {
            in = fs.open(outputHDFS);
            br2 = new BufferedReader(new InputStreamReader(in));
            text = br2.lines().collect(Collectors.joining("\n"));
        } finally {
            IOUtils.closeStream(in);
            IOUtils.closeStream(br2);
        }
        String expectedText = FileUtils.readFileToString(expected, Charset.defaultCharset());
        
        String[] expectedLines = FileUtils.readFileToString(expected, Charset.defaultCharset()).split("\n");
        String[] actualLines = text.split("\n");

        Gson gson = new GsonBuilder()
            .registerTypeAdapter(MultiPageImageData.class, new MultiPageImageDataSerializer())
            .registerTypeAdapter(ImageData.class, new ImageDataSerializer())
            .create();
            
        for (int i = 0; i < expectedLines.length; i++) {
            // parse as Java dictionary to avoid differences in field ordering
            Map<?, ?> expectedMap = gson.fromJson(expectedLines[i], Map.class);
            Map<?, ?> actualMap = gson.fromJson(actualLines[i], Map.class);
            // delete imgSrcBase64 key since it is not deterministic
            expectedMap.remove("imgSrcBase64");
            actualMap.remove("imgSrcBase64");
            assertEquals(expectedMap, actualMap);
        }
    }
}