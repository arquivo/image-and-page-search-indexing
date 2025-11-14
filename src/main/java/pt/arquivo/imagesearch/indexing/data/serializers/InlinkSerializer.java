package pt.arquivo.imagesearch.indexing.data.serializers;

import java.lang.reflect.Type;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.Collection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import pt.arquivo.imagesearch.indexing.data.Inlink;

public class InlinkSerializer implements JsonSerializer<InlinkSerializer.SetInlink> {

    // Serializer for Outlink objects doesn't like generic types (Set<Outlink>), so we need to wrap it in a class
    public static class SetInlink {
        public boolean wasCaptured;
        public Collection<Inlink> inlinksInternal;
        public Collection<Inlink> inlinksExternal;
        public String surt;
        public LocalDateTime captureDateStart;

        public SetInlink(String surt, boolean wasCaptured, Collection<Inlink> inlinksInternal, Collection<Inlink> inlinksExternal, LocalDateTime captureDateStart) {
            this.wasCaptured = wasCaptured;
            this.inlinksInternal = inlinksInternal;
            this.inlinksExternal = inlinksExternal;
            this.surt = surt;
            this.captureDateStart = captureDateStart;
        }
    }

    /**
     * Converts the object into a JSON ready for writing
     *
     * @param src object to export
     * @param typeOfSrc (unused)
     * @param context Hadoop context
     * @return JsonElement ready for export
     */
    @Override
    public JsonElement serialize(SetInlink inlinks, Type typeOfSrc, JsonSerializationContext context) {

        String target = inlinks.surt;

        JsonObject output = new JsonObject();

        JsonArray array = new JsonArray();

        Set<Inlink> inlinksAll = new HashSet<>();
        inlinksAll.addAll(inlinks.inlinksInternal);
        inlinksAll.addAll(inlinks.inlinksExternal);


        for (Inlink inlink: inlinksAll) {
            JsonObject jsonOutlink = new JsonObject();
            jsonOutlink.addProperty("date", inlink.getCaptureDateStart().toString());
            //jsonOutlink.addProperty("end", inlink.getCaptureDateEnd().toString());
            jsonOutlink.addProperty("source", inlink.getSource());
            jsonOutlink.addProperty("anchor", inlink.getAnchor());
            //jsonOutlink.addProperty("count", inlink.getCount());
            array.add(jsonOutlink);
        }
        

        output.addProperty("url", target);
        output.addProperty("count", inlinksAll.size());
        output.addProperty("countInternal", inlinks.inlinksInternal.size());
        output.addProperty("countExternal", inlinks.inlinksExternal.size());
        if (inlinks.captureDateStart == null) {
            output.add("captureDate", null);
        } else {
            output.addProperty("captureDate", inlinks.captureDateStart.toString());

        }
        output.add("inlinks", array);
        

        return output;
    }
    
}
