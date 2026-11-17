package com.dupfinder.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns matching pairs into groups. Each group has one reference image (the presumed original);
 * every other member carries a label describing how it differs from the reference, and is only
 * kept when all of those differences are enabled in the settings.
 */
public final class Grouper {
    public static final class Group {
        /** Index of the reference image; also members[0]. */
        public int ref;
        public int[] members;
        /** Bit flags of {@link Matcher} types per member; 0 for the reference. */
        public int[] labels;
    }

    public static List<Group> build(final Fp[] fps, final long[] dates, boolean[] alive, List<Matcher.Edge> edges, int enabledMask) {
        int n = fps.length;
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        List<Matcher.Edge> live = new ArrayList<>();
        for (Matcher.Edge e : edges) {
            if (alive != null && (!alive[e.a] || !alive[e.b])) continue;
            live.add(e);
            int a = find(p, e.a), b = find(p, e.b);
            if (a != b) p[Math.max(a, b)] = Math.min(a, b);
        }
        LinkedHashMap<Integer, List<Integer>> comps = new LinkedHashMap<>();
        for (Matcher.Edge e : live) {
            for (int v : new int[]{e.a, e.b}) {
                int r = find(p, v);
                List<Integer> l = comps.get(r);
                if (l == null) comps.put(r, l = new ArrayList<>());
                if (!l.contains(v)) l.add(v);
            }
        }
        LinkedHashMap<Integer, List<Matcher.Edge>> compEdges = new LinkedHashMap<>();
        for (Matcher.Edge e : live) {
            int r = find(p, e.a);
            List<Matcher.Edge> l = compEdges.get(r);
            if (l == null) compEdges.put(r, l = new ArrayList<>());
            l.add(e);
        }
        List<Group> out = new ArrayList<>();
        for (Integer root : comps.keySet()) {
            List<Integer> mem = comps.get(root);
            List<Matcher.Edge> es = compEdges.get(root);
            final int ref = pickReference(mem, es, fps, dates);
            // label = type of the direct relation to the reference, else the union along the shortest path
            final int[] label = new int[n];
            Arrays.fill(label, -1); // only entries of this component are read
            label[ref] = 0;
            for (Matcher.Edge e : es) {
                if (e.a == ref) label[e.b] = e.type;
                else if (e.b == ref) label[e.a] = e.type;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                int[] next = label.clone();
                for (Matcher.Edge e : es) {
                    if (label[e.a] >= 0 && label[e.b] < 0 && next[e.b] < 0) { next[e.b] = label[e.a] | e.type; changed = true; }
                    if (label[e.b] >= 0 && label[e.a] < 0 && next[e.a] < 0) { next[e.a] = label[e.b] | e.type; changed = true; }
                }
                System.arraycopy(next, 0, label, 0, n);
            }
            List<Integer> kept = new ArrayList<>();
            for (int v : mem) if (v != ref && label[v] > 0 && (label[v] & ~enabledMask) == 0) kept.add(v);
            if (kept.isEmpty()) continue;
            Collections.sort(kept, new Comparator<Integer>() {
                @Override public int compare(Integer x, Integer y) {
                    if (label[x] != label[y]) return Integer.compare(label[x], label[y]);
                    return Integer.compare(x, y);
                }
            });
            Group g = new Group();
            g.ref = ref;
            g.members = new int[kept.size() + 1];
            g.labels = new int[kept.size() + 1];
            g.members[0] = ref;
            for (int i = 0; i < kept.size(); i++) {
                g.members[i + 1] = kept.get(i);
                g.labels[i + 1] = label[kept.get(i)];
            }
            out.add(g);
        }
        return out;
    }

    /**
     * The presumed original: never an image that is a cropped part of another one; then the
     * largest picture; among equally large ones the one with the most unmodified copies (edits
     * are usually unique, originals get duplicated), then the oldest.
     */
    private static int pickReference(List<Integer> mem, List<Matcher.Edge> es, Fp[] fps, long[] dates) {
        List<Integer> cands = new ArrayList<>();
        for (int v : mem) {
            boolean isPart = false;
            for (Matcher.Edge e : es)
                if (e.whole >= 0 && e.whole != v && (e.a == v || e.b == v)) { isPart = true; break; }
            if (!isPart) cands.add(v);
        }
        if (cands.isEmpty()) cands = mem;
        long maxArea = 0;
        for (int v : cands) maxArea = Math.max(maxArea, (long) fps[v].w * fps[v].h);
        int best = -1, bestCopies = -1;
        for (int v : cands) {
            if ((long) fps[v].w * fps[v].h < maxArea * 0.98) continue;
            int copies = 0;
            for (Matcher.Edge e : es)
                if ((e.a == v || e.b == v) && (e.type & (Matcher.EDITED | Matcher.CROPPED)) == 0) copies++;
            boolean better = best < 0 || copies > bestCopies
                    || (copies == bestCopies && dates != null && dates[v] < dates[best]);
            if (better) { best = v; bestCopies = copies; }
        }
        return best;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }
}
