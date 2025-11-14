package pt.arquivo.imagesearch.indexing.data;
import java.time.LocalDateTime;

public class Inlink {

    private String source;
    private String surt;
    private String url;
    private String anchor;
    private LocalDateTime captureDateStart;
    private LocalDateTime captureDateEnd;
    private int count;

    public Inlink(Outlink outlink) {
        this.surt = outlink.getSurt();
        this.url = outlink.getUrl();
        this.anchor = outlink.getAnchor();
        this.captureDateStart = outlink.getCaptureDateStart();
        this.captureDateEnd = outlink.getCaptureDateEnd();
        this.source = outlink.getSource();
        this.count = 1;
    }

    public String getSurt() {
        return surt;
    }

    public String getUrl() {
        return url;
    }
    public String getAnchor() {
        return anchor;
    }

    public LocalDateTime getCaptureDateStart() {
        return captureDateStart;
    }

    public LocalDateTime getCaptureDateEnd() {
        return captureDateEnd;
    }

    public String getSource() {
        return source;
    }

    public int getCount() {
        return count;
    }

    public void incrementCount(int count) {
        this.count += count;
    }

    @Override
    public int hashCode() {
        return surt.hashCode() + source.hashCode();
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Inlink outlink = (Inlink) o;
        return outlink.surt.equals(this.surt) && outlink.source.equals(this.source);
    }
}


