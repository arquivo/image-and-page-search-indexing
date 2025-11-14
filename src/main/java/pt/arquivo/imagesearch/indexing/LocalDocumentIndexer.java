package pt.arquivo.imagesearch.indexing;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.mapreduce.*;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import pt.arquivo.imagesearch.indexing.utils.MimeTypeCounters.PAGE_INDEXER_COUNTERS_DETECTED;
import pt.arquivo.imagesearch.indexing.utils.MimeTypeCounters.PAGE_INDEXER_COUNTERS_REPORTED;
import pt.arquivo.imagesearch.indexing.DocumentIndexerWithDupsJob.DOCUMENT_COUNTERS;
import pt.arquivo.imagesearch.indexing.processors.DocumentInformationExtractor;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;


/**
 * Runs the full indexing process, similarly to FullImageIndexerJob, but without Hadoop dependencies, running fully locally
 *
 */
public class LocalDocumentIndexer {

    /**
     * Similar to the Map stage in the ImageIndexerWithDupsJob
     */
    public static class Map {

        private Logger logger = Logger.getLogger(Map.class);
        public String collection;
        public DocumentInformationExtractor indexer;

        public Map(String collection) {
            this.collection = collection;
            logger.debug(collection + "_Images/img/");
            indexer = new DocumentInformationExtractor(collection);
        }

        public void map(String arcURL) {
            logger.info("(W)ARCNAME: " + arcURL);
            indexer.getCounter(DocumentIndexerWithDupsJob.DOCUMENT_COUNTERS.WARCS).increment(1);

            URL url = null;
            try {
                url = new URL(arcURL);
            } catch (MalformedURLException ignored) {
                logger.error("Error parsing URL: " + arcURL);
                return;
            }

            String[] surl = url.getPath().split("/");
            String arcName = surl[surl.length - 1];
            String filename = System.currentTimeMillis() + "_" + arcName;
            File dest = new File("/tmp/" + filename);

            try {
                FileUtils.copyURLToFile(url, dest);
                indexer.parseRecord(arcName, dest.getPath());
            } catch (IOException e) {
                logger.error("Error downloading file: " + arcURL);
            }
            FileUtils.deleteQuietly(dest);
        }

        
    }

    public static void main(String[] args) {
        Logger logger = Logger.getLogger(Map.class);
        logger.setLevel(Level.DEBUG);
        BasicConfigurator.configure();


        assert args.length >= 1 : "Missing file with all arcs path argument";
        String hdfsArcsPath = args[0];

        assert args.length >= 2 : "Missing collection name argument";
        String collection = args[1];

        LocalDocumentIndexer.Map map = new Map(collection);

        try (BufferedReader br = new BufferedReader(new FileReader(hdfsArcsPath))) {
            for (String line; (line = br.readLine()) != null; ) {
                if (!line.trim().isEmpty())
                    map.map(line);
            }
        } catch (IOException e) {
            logger.error("Error reading file: " + hdfsArcsPath);
        }

        System.out.println(map.indexer.getEntries().size());

        System.out.println("FullPageIndexer$PAGE_INDEXER_COUNTERS");


        for (DOCUMENT_COUNTERS counter : DOCUMENT_COUNTERS.values()) {
            Counter c = map.indexer.getCounter(counter);
            System.out.println("\t" + c.getName() + ";" + c.getValue());
        }

        for (PAGE_INDEXER_COUNTERS_REPORTED counter : PAGE_INDEXER_COUNTERS_REPORTED.values()) {
            Counter c = map.indexer.getCounter(counter);
            System.out.println("\t" + c.getName() + ";" + c.getValue());
        }

        for (PAGE_INDEXER_COUNTERS_DETECTED counter : PAGE_INDEXER_COUNTERS_DETECTED.values()) {
            Counter c = map.indexer.getCounter(counter);
            System.out.println("\t" + c.getName() + ";" + c.getValue());
        }


        System.out.println("FullPageIndexer$MIME_TYPES");


        for (Counter c :  map.indexer.getTmpCounters().values()) {
            System.out.println("\t" + c.getName() + ";" + c.getValue());
        }


        System.out.println("FullPageIndexer$LINK_YPES");


        for (Counter c :  map.indexer.getLinkTypes().values()) {
            System.out.println("\t" + c.getName() + ";" + c.getValue());
        }

    }
}