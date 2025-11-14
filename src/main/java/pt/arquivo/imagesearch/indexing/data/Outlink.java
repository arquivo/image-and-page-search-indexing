package pt.arquivo.imagesearch.indexing.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;

import org.apache.hadoop.io.Writable;

public class Outlink implements Serializable, Writable {

    private String source;
    private String surt;
    private String url;
    private String anchor;
    private LocalDateTime captureDateStart;
    private LocalDateTime captureDateEnd;
    private int count;

    public Outlink() {
        this.surt = null;
        this.url = null;
        this.anchor = null;
        this.captureDateStart = null;
        this.captureDateEnd = null;
    }

    public Outlink(String surt, String url, String anchor, LocalDateTime captureDate, String source) {
        this.surt = surt;
        this.url = url;
        this.anchor = anchor;
        this.captureDateStart = captureDate;
        this.captureDateEnd = captureDate;
        this.source = source;
    }

    public String getSurt() {
        return surt;
    }

    public void setSurt(String surt) {
        this.surt = surt;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAnchor() {
        return anchor;
    }

    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }

    public LocalDateTime getCaptureDateStart() {
        return captureDateStart;
    }

    public void setCaptureDateStart(LocalDateTime captureDate) {
        this.captureDateStart = captureDate;
    }

    public LocalDateTime getCaptureDateEnd() {
        return captureDateEnd;
    }

    public void setCaptureDateEnd(LocalDateTime captureDate) {
        this.captureDateEnd = captureDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getCount() {
        return count;
    }

    public void incrementCount() {
        this.count++;
    }

    @Override
    public int hashCode() {
        return surt.hashCode() + source.hashCode() + anchor.hashCode() + captureDateStart.hashCode();
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Outlink outlink = (Outlink) o;
        return outlink.surt.equals(this.surt) && outlink.anchor.equals(this.anchor) && outlink.source.equals(this.source) && outlink.captureDateStart.equals(this.captureDateStart);
    }

    @Override
    public void write(DataOutput out) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(this);
        byte[] data = baos.toByteArray();
        int size = data.length;
        out.writeInt(size);
        out.write(data);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
        int size = in.readInt();
        byte[] data = new byte[size];
        in.readFully(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        try {
            Outlink outlink = (Outlink) ois.readObject();
            this.surt = outlink.surt;
            this.url = outlink.url;
            this.anchor = outlink.anchor;

        } catch (ClassNotFoundException e) {
            System.err.println("Error reading TextDocumentData from Writable");
        }
    }

}


