package pt.arquivo.imagesearch.indexing.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.apache.hadoop.io.Writable;

public class TextDocumentDataOutlinkPair implements Serializable, Writable {

    private TextDocumentData textDocumentData;
    private Outlink outlink;

    public TextDocumentDataOutlinkPair() {
        this.textDocumentData = null;
        this.outlink = null;
    }

    public TextDocumentDataOutlinkPair(TextDocumentData textDocumentData, Outlink outlink) {
        this.textDocumentData = textDocumentData;
        this.outlink = outlink;
    }

    public TextDocumentData getTextDocumentData() {
        return textDocumentData;
    }

    public void setTextDocumentData(TextDocumentData textDocumentData) {
        this.textDocumentData = textDocumentData;
    }

    public Outlink getOutlink() {
        return outlink;
    }

    public void setOutlink(Outlink outlink) {
        this.outlink = outlink;
    }

    @Override
    public void write(DataOutput out) throws java.io.IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(this);
        byte[] data = baos.toByteArray();
        int size = data.length;
        out.writeInt(size);
        out.write(data);
    }

    @Override
    public void readFields(DataInput in) throws java.io.IOException {
        int size = in.readInt();
        byte[] data = new byte[size];
        in.readFully(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        try {
            TextDocumentDataOutlinkPair pair = (TextDocumentDataOutlinkPair) ois.readObject();
            this.textDocumentData = pair.getTextDocumentData();
            this.outlink = pair.getOutlink();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }
}
