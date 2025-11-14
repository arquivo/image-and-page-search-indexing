package pt.arquivo.imagesearch.indexing;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.Test;

import pt.arquivo.imagesearch.indexing.data.TextDocumentData;

public class TextDocumentDataTest {

    private TextDocumentData generateTextDocumentData() {

        TextDocumentData textDocumentData = new TextDocumentData();
                
        String body = "This is a dummy body content.";
        String title = "Dummy Title";
        String timestamp = "20190101000000";

        textDocumentData.addMetadata("Author: John Doe\nKeywords: example, dummy");

        textDocumentData.addTitle(title);

        textDocumentData.setTimestamp(timestamp);

        textDocumentData.setDigestContent("dummy digest content");
        textDocumentData.setDigestContainer("dummy digest container");
        textDocumentData.addContent(body);

        textDocumentData.addURL("http://example.com/link1", timestamp, "dummy collection");
        textDocumentData.addURL("http://example.com/link2", timestamp, "dummy collection 2");
        textDocumentData.addURL("http://example.com/link3", timestamp, "dummy collection 3");
        
        textDocumentData.addCollection("collection1");

        return textDocumentData;

    }


    
    @Test
    public void testTextDocumentDataMerge() {

        TextDocumentData textDocumentData = generateTextDocumentData();
        TextDocumentData textDocumentData2 = generateTextDocumentData();

        textDocumentData2.addMetadata("Author: Jane Doe\nKeywords: example, dummy");

        textDocumentData2.addTitle("Dummy Title 2");

        String timestamp = "20190106000000";


        textDocumentData2.setTimestamp(timestamp);

        textDocumentData2.addContent("This is a dummy body content 2.");

        textDocumentData2.addURL("http://example.com/link4", timestamp, "dummy collection 4");
        textDocumentData2.addURL("http://example.com/link5", timestamp, "dummy collection 5");
        textDocumentData2.addURL("http://example.com/link6", timestamp, "dummy collection 6");
        
        textDocumentData2.addCollection("collection2");

        textDocumentData = TextDocumentData.merge(textDocumentData, textDocumentData2);

        assert(textDocumentData.getMetadata().get(0).contains("Author: John Doe"));
        assert(textDocumentData.getMetadata().get(1).contains("Author: Jane Doe"));



        assert(textDocumentData.getTitle().get(0).contains("Dummy Title"));
        assert(textDocumentData.getTitle().get(1).contains("Dummy Title 2"));


        assert(textDocumentData.getDigestContent().contains("dummy digest content"));

        assert(textDocumentData.getDigestContainer().contains("dummy digest container"));

        assert(textDocumentData.getContent().get(0).contains("This is a dummy body content."));
        assert(textDocumentData.getContent().get(1).contains("This is a dummy body content 2."));

        assert(textDocumentData.getURL().get(0).contains("http://example.com/link1"));
        assert(textDocumentData.getURL().get(1).contains("http://example.com/link2"));

        assert(textDocumentData.getUrlTimestamp().get(0).equals("dummy collection/20190101000000/(com,example,)/link1"));


        assert(textDocumentData.getCollection().get(0).contains("collection1"));
        assert(textDocumentData.getCollection().get(1).contains("collection2"));


        // check the timestamp and time range
        assert(textDocumentData.getTimestamp().equals(LocalDateTime.parse("2019-01-01T00:00:00")));
        assert(textDocumentData.getTimestampLatest().equals(LocalDateTime.parse("2019-01-06T00:00:00")));

        assert(textDocumentData.getTimerange() == Duration.between(LocalDateTime.parse("2019-01-01T00:00:00"), LocalDateTime.parse("2019-01-06T00:00:00")).toMillis() / 1000);





        
    }

}
