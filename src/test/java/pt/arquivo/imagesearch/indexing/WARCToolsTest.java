package pt.arquivo.imagesearch.indexing;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

import pt.arquivo.imagesearch.indexing.utils.WARCInformationParser;

public class WARCToolsTest {
    
    @Test
    public void testSURTExtract() {
        String url = "http://www.fccn.pt";
        String surt = WARCInformationParser.toSURT(url);
        assertEquals("(pt,fccn,", surt);

        url = "http://www.fccn.pt:80";
        surt = WARCInformationParser.toSURT(url);
        assertEquals("(pt,fccn,", surt);

        url = "http://www.fccn.pt?param1=value1&param2=value2";
        surt = WARCInformationParser.toSURT(url);
        assertEquals("(pt,fccn,)/?param1=value1&param2=value2", surt);

        url = "http://www.fccn.pt/path/to/page";
        surt = WARCInformationParser.toSURT(url);
        assertEquals("(pt,fccn,)/path/to/page", surt);
    }

    @Test
    public void testSURTCompare() {
        String url1 = "http://www.fccn.pt";
        String url2 = "http://www.fccn.pt";
        WARCInformationParser.SURTMatchType matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_SURT, matchType);

        url1 = "http://www.fccn.pt";
        url2 = "http://sobre.fccn.pt";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_SLDN, matchType);


        url1 = "http://www.fccn.pt";
        url2 = "http://www.fccn.pt?param1=value1&param2=value2";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);

        url1 = "http://www.fccn.pt/path/to/page";
        url2 = "http://www.fccn.pt/path/to/page?param1=value1&param2=value2";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);


        url1 = "http://www.fccn.pt";
        url2 = "http://www.fccn.pt/path/to/page";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);

        url1 = "http://www.fccn.pt";
        url2 = "http://www.fccn.pt:8080";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);

        url1 = "http://www.fccn.pt";
        url2 = "http://www.fccn.pt:8080/path/to/page";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);

        url1 = "http://www.fccn.pt";
        url2 = "http://www.fccn.pt:8080/path/to/page";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.SAME_FQDN, matchType);

        url1 = "http://www.fccn.pt";
        url2 = "http://www.fct.pt";
        matchType = WARCInformationParser.compareSURTs(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(WARCInformationParser.SURTMatchType.DIFFERENT_SLDN, matchType);
    }


    @Test
    public void testSURTIsInteral() {
        String url1 = "http://www.fccn.pt";
        String url2 = "http://www.fccn.pt";
        boolean isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://sobre.fccn.pt";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fccn.pt?param1=value1&param2=value2";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);
        
        url2 = "http://www.fccn.pt/path/to/page?param1=value1&param2=value2";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fccn.pt/path/to/page";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fccn.pt:8080";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fccn.pt:8080/path/to/page";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fccn.pt:8080/path/to/page";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(true, isInternal);

        url2 = "http://www.fct.pt";
        isInternal = WARCInformationParser.isInternal(WARCInformationParser.toSURT(url1), WARCInformationParser.toSURT(url2));
        assertEquals(false, isInternal);
    }

}
