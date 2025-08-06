package pt.arquivo.imagesearch.indexing.data.serializers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import pt.arquivo.imagesearch.indexing.data.TextDocumentData;

import java.lang.reflect.Type;

/**
 * Configures JsonSerializable to export the object in the desired JSON format
 * Used in the COMPACT export format
 */
public class TextDocumentDataSerializer implements JsonSerializer<TextDocumentData> {

    /**
     * Converts the object into a JSON ready for writing
     *
     * @param src object to export
     * @param typeOfSrc (unused)
     * @param context Hadoop context
     * @return JsonElement ready for export
     */
    @Override
    public JsonElement serialize(TextDocumentData src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject obj = new JsonObject();
        //obj.addProperty("type", "document");

        obj.addProperty("id", src.getId());
        //obj.addProperty("digestContainer", src.getDigestContainer());

        //obj.addProperty("id", src.getDigestContainer());

        
        obj.add("collections", context.serialize(src.getCollection()));

        obj.add("collectionOldest", context.serialize(src.getCollection().get(0)));
        if (src.getContent() != null && !src.getContent().isEmpty())
            obj.add("content", context.serialize(src.getContent()));
        
        if (src.getMetadata() != null && !src.getMetadata().isEmpty())
            obj.add("metadata", context.serialize(src.getMetadata()));
        obj.addProperty("type", src.getMimeTypeDetected());
        //obj.addProperty("typeReported", src.getMimeTypeReported());
        //obj.addProperty("tstamp", src.getTimestampString());
        obj.addProperty("dateOldest", src.getTimestampFormatted());
        obj.addProperty("dateLatest", src.getTimestampLatestFormatted());
        obj.addProperty("timeRange", src.getTimerange());
        //obj.add("host", context.serialize(src.getHost()));

        obj.add("urlTimestamp", context.serialize(src.getUrlTimestamp()));

        //obj.add("urls", context.serialize(src.getURL()));
        obj.add("surts", context.serialize(src.getSurts()));
        if (src.getTitle() != null && !src.getTitle().isEmpty())
            obj.add("title", context.serialize(src.getTitle()));
        obj.add("urlTokens", context.serialize(src.getURLTokens()));
 
        //obj.addProperty("warc", src.getWarc());
        //obj.addProperty("warcOffset", src.getWarcOffset());
        obj.addProperty("surtOldest", src.getSurts().get(0));
        //obj.addProperty("url", src.getURL().get(0));
        //obj.addProperty("protocol", src.getProtocol());
        //obj.addProperty("urlHash", src.getURLHash());
        //obj.add("outlinks", context.serialize(src.getOutlinks()));
        
        obj.add("inlinksInternal", context.serialize(src.getInlinkSurtsInternal().size()));
        obj.add("inlinksExternal", context.serialize(src.getInlinkSurtsExternal().size()));
        
        obj.add("inlinkAnchorsInternal", context.serialize(src.getConcatedInlinkAnchorsInternal()));
        obj.add("inlinkAnchorsExternal", context.serialize(src.getConcatedInlinkAnchorsExternal()));
        obj.add("captureCount", context.serialize(src.getCaptureCount()));

        obj.addProperty("language", src.getLanguage());
        obj.addProperty("languageConfidence", src.getLanguageConfidence());
        obj.addProperty("fileExtension", src.getFileExtension());

        //obj.addProperty("safe", 0);
        //obj.addProperty("reputation", 0.5);
        //obj.addProperty("blocked", 0);

        return obj;
    }
}
