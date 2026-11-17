package com.dupfinder.app;

import com.dupfinder.core.Fp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Stores fingerprints on disk so that a repeated scan only has to decode new or changed images. */
final class FpCache {
    private static final int MAGIC = 0x44555031 + Fp.SIDE; // format version + thumbnail size

    static final class Entry {
        long size, date;
        Fp fp;
    }

    private FpCache() {}

    static Map<Long, Entry> load(File f) {
        Map<Long, Entry> map = new HashMap<>();
        if (!f.exists()) return map;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC) return map;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long id = in.readLong();
                Entry e = new Entry();
                e.size = in.readLong();
                e.date = in.readLong();
                Fp p = new Fp();
                p.size = e.size;
                p.hash = in.readLong();
                p.w = in.readInt();
                p.h = in.readInt();
                p.tw = in.readUnsignedByte();
                p.th = in.readUnsignedByte();
                p.std = in.readFloat();
                p.g = new byte[p.tw * p.th];
                in.readFully(p.g);
                p.hist = new float[Fp.BINS];
                for (int k = 0; k < Fp.BINS; k++) p.hist[k] = in.readFloat();
                e.fp = p;
                map.put(id, e);
            }
        } catch (Exception ex) {
            map.clear(); // corrupt or truncated cache: just rebuild it
        }
        return map;
    }

    static void save(File f, ImageItem[] items, Fp[] fps) {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
            int n = 0;
            for (Fp p : fps) if (p != null) n++;
            out.writeInt(MAGIC);
            out.writeInt(n);
            for (int i = 0; i < items.length; i++) {
                Fp p = fps[i];
                if (p == null) continue;
                out.writeLong(items[i].id);
                out.writeLong(items[i].size);
                out.writeLong(items[i].date);
                out.writeLong(p.hash);
                out.writeInt(p.w);
                out.writeInt(p.h);
                out.writeByte(p.tw);
                out.writeByte(p.th);
                out.writeFloat(p.std);
                out.write(p.g);
                for (int k = 0; k < Fp.BINS; k++) out.writeFloat(p.hist[k]);
            }
            out.flush();
        } catch (Exception ex) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }
}
