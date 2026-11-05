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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import pt.arquivo.imagesearch.indexing.data.TextDocumentData;
import pt.arquivo.imagesearch.indexing.data.serializers.TextDocumentDataSerializer;
import pt.arquivo.imagesearch.indexing.utils.TextQualityChecker;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.TreeMap;


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

    private static final double TOLERATED_REPLACEMENT_RATIO = 0.005;

    /**
     * Writes documents in the same JSONL format as DocumentDupDigestMergerJob, runs the text quality checks on each
     * one and reports counts by issue type and by declared/chosen charset. Issues (with snippets) go to
     * outPath + ".issues.tsv".
     */
    private static void writeJsonl(DocumentInformationExtractor indexer, String outPath) throws IOException {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(TextDocumentData.class, new TextDocumentDataSerializer())
                .create();
        java.util.Map<String, String[]> decisions = indexer.getCharsetDecisions();
        TreeMap<String, Integer> issueCounts = new TreeMap<>();
        TreeMap<String, Integer> charsetCounts = new TreeMap<>();
        TreeMap<String, Integer> issuesByCharset = new TreeMap<>();
        int withReplacementChar = 0;
        int withAnyIssue = 0;
        int cleanDocuments = 0;
        // [documents, clean, clean tolerating a few U+FFFD] for HTML/plain text (decoding quality) and for PDF/Office/other
        // (extraction quality: unmapped glyphs, which no charset rule can recover)
        int[] textLike = new int[3];
        int[] otherTypes = new int[3];
        Collection<TextDocumentData> docs = indexer.getEntries().values();

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outPath), StandardCharsets.UTF_8);
             Writer issuesOut = new OutputStreamWriter(new FileOutputStream(outPath + ".issues.tsv"), StandardCharsets.UTF_8)) {
            issuesOut.write("issue\tdeclared\tchosen\treason\twarc\toffset\turl\tdetail\n");
            for (TextDocumentData doc : docs) {
                String json = gson.toJson(doc);
                if (json.indexOf('\uFFFD') >= 0)
                    withReplacementChar++;
                w.write(json);
                w.write('\n');

                String[] decision = decisions == null ? null : decisions.get(doc.getDigestContent());
                String declared = decision == null || decision[0] == null ? "(none)" : decision[0].toLowerCase();
                String chosen = decision == null || decision[1] == null ? "(unknown)" : decision[1];
                String reason = decision == null || decision[2] == null ? "(unknown)" : decision[2];
                charsetCounts.merge(declared + " -> " + chosen + " [" + reason + "]", 1, Integer::sum);

                StringBuilder text = new StringBuilder();
                for (java.util.List<String> field : java.util.Arrays.asList(doc.getTitle(), doc.getContent(), doc.getMetadata()))
                    if (field != null)
                        for (String f : field)
                            text.append(f).append('\n');
                Charset charset = null;
                try {
                    charset = decision == null || decision[1] == null ? null : Charset.forName(decision[1]);
                } catch (IllegalArgumentException ignored) {
                }
                java.util.List<TextQualityChecker.Issue> issues = TextQualityChecker.check(text.toString(), charset);
                if (!issues.isEmpty())
                    withAnyIssue++;
                boolean decodedBadly = false;
                for (TextQualityChecker.Issue issue : issues)
                    if (issue.type == TextQualityChecker.Type.REPLACEMENT_CHAR
                            || issue.type.name().startsWith("MOJIBAKE_"))
                        decodedBadly = true;
                boolean mojibake = false;
                for (TextQualityChecker.Issue issue : issues)
                    if (issue.type.name().startsWith("MOJIBAKE_"))
                        mojibake = true;
                int replacementChars = 0;
                for (int i = 0; i < text.length(); i++)
                    if (text.charAt(i) == '\uFFFD')
                        replacementChars++;
                boolean tolerant = !mojibake && replacementChars <= text.length() * TOLERATED_REPLACEMENT_RATIO;
                String detected = doc.getMimeTypeDetected();
                int[] group = detected != null && (detected.startsWith("text/") || detected.contains("xml") || detected.contains("html"))
                        ? textLike : otherTypes;
                group[0]++;
                if (!decodedBadly) {
                    cleanDocuments++;
                    group[1]++;
                }
                if (tolerant)
                    group[2]++;
                String url = doc.getURL() == null || doc.getURL().isEmpty() ? "" : doc.getURL().get(0);
                boolean sourceBroken = decision != null && decision.length > 3 && "true".equals(decision[3]);
                for (TextQualityChecker.Issue issue : issues) {
                    String type = issue.type == TextQualityChecker.Type.REPLACEMENT_CHAR && sourceBroken
                            ? "REPLACEMENT_CHAR_IN_SOURCE" : issue.type.name();
                    issueCounts.merge(type, 1, Integer::sum);
                    issuesByCharset.merge(type + " | " + chosen, 1, Integer::sum);
                    issuesOut.write(type + "\t" + declared + "\t" + chosen + "\t" + reason + "\t" + doc.getWarc() + "\t"
                            + doc.getWarcOffset() + "\t" + url + "\t" + issue.snippet + "\n");
                }
            }
        }
        System.out.println("JSONL documents written: " + docs.size() + " to " + outPath);
        System.out.println("JSONL documents containing U+FFFD: " + withReplacementChar);
        System.out.println("Documents with any text quality issue: " + withAnyIssue + " (details in " + outPath + ".issues.tsv)");
        System.out.println("Text documents (unique content, images and binary files excluded) and how many have neither U+FFFD nor mojibake:");
        System.out.println("\tTEXT_DOCUMENTS;" + docs.size());
        System.out.println("\tTEXT_DOCUMENTS_CLEAN;" + cleanDocuments);
        System.out.println("\tTEXT_DOCUMENTS_CLEAN_TOLERANT;" + (textLike[2] + otherTypes[2]));
        System.out.println("By type (clean = no U+FFFD, no mojibake; tolerant = no mojibake and U+FFFD in at most "
                + TOLERATED_REPLACEMENT_RATIO * 100 + "% of the characters):");
        String[] groupNames = { "TEXT_DOCUMENTS_HTML_PLAIN", "TEXT_DOCUMENTS_OTHER" };
        int[][] groups = { textLike, otherTypes };
        for (int g = 0; g < groups.length; g++) {
            System.out.println("\t" + groupNames[g] + ";" + groups[g][0]);
            System.out.println("\t" + groupNames[g] + "_CLEAN;" + groups[g][1]);
            System.out.println("\t" + groupNames[g] + "_CLEAN_TOLERANT;" + groups[g][2]);
        }
        System.out.println("Text quality issues by type:");
        issueCounts.forEach((k, v) -> System.out.println("\t" + k + ";" + v));
        System.out.println("Text quality issues by type and chosen charset:");
        issuesByCharset.forEach((k, v) -> System.out.println("\t" + k + ";" + v));
        System.out.println("Declared charset -> chosen charset [reason]:");
        charsetCounts.forEach((k, v) -> System.out.println("\t" + k + ";" + v));
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
        map.indexer.trackCharsetDecisions();

        try (BufferedReader br = new BufferedReader(new FileReader(hdfsArcsPath))) {
            for (String line; (line = br.readLine()) != null; ) {
                if (!line.trim().isEmpty())
                    map.map(line);
            }
        } catch (IOException e) {
            logger.error("Error reading file: " + hdfsArcsPath);
        }

        System.out.println(map.indexer.getEntries().size());

        if (args.length >= 3) {
            try {
                writeJsonl(map.indexer, args[2]);
            } catch (IOException e) {
                logger.error("Error writing JSONL: " + args[2], e);
            }
        }

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