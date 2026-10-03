package net.townymap.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a set of claimed chunks into polygon rings, the same shape squaremap would publish for them.
 *
 * <p>The API gives a town's claims as a flat list of chunks, while everything that draws a town wants
 * GeoJSON-style rings of block coordinates. Walking the boundary is how you get from one to the other:
 * every chunk side with no claimed chunk beyond it is an edge of the outline, and those edges chain
 * head-to-tail into closed loops. Emitting them in a consistent direction means the outer boundary comes
 * out one way round and any enclosed gap comes out the other, which is exactly the outer-ring-first,
 * holes-after convention {@code TownData} already expects.
 */
public final class ChunkRings {

    private static final int CHUNK = 16;

    private ChunkRings() {}

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Rings in block coordinates, outer boundary first. Empty if the set is empty. */
    public static List<int[][]> trace(Set<Long> chunks) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        // Every chunk side facing an unclaimed chunk, wound so that the edges meet end-to-start.
        Map<Long, List<long[]>> outgoing = new HashMap<>();
        for (long c : chunks) {
            int cx = (int) (c >> 32), cz = (int) c;
            int x0 = cx * CHUNK, z0 = cz * CHUNK, x1 = x0 + CHUNK, z1 = z0 + CHUNK;
            if (!chunks.contains(key(cx, cz - 1))) edge(outgoing, x0, z0, x1, z0);
            if (!chunks.contains(key(cx + 1, cz))) edge(outgoing, x1, z0, x1, z1);
            if (!chunks.contains(key(cx, cz + 1))) edge(outgoing, x1, z1, x0, z1);
            if (!chunks.contains(key(cx - 1, cz))) edge(outgoing, x0, z1, x0, z0);
        }

        List<int[][]> rings = new ArrayList<>();
        for (List<long[]> starts : new ArrayList<>(outgoing.values())) {
            while (!starts.isEmpty()) {
                int[][] ring = walk(outgoing, starts.get(0));
                if (ring.length >= 3) rings.add(ring);
            }
        }
        // Outer boundary first: it is the ring enclosing the most area.
        if (rings.size() > 1) {
            int widest = 0;
            long best = -1;
            for (int i = 0; i < rings.size(); i++) {
                long a = Math.abs(area2(rings.get(i)));
                if (a > best) { best = a; widest = i; }
            }
            rings.add(0, rings.remove(widest));
        }
        return rings;
    }

    private static void edge(Map<Long, List<long[]>> outgoing, int x0, int z0, int x1, int z1) {
        outgoing.computeIfAbsent(point(x0, z0), k -> new ArrayList<>())
                .add(new long[]{point(x0, z0), point(x1, z1)});
    }

    private static long point(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Follows edges from the given one until the loop closes, consuming each as it goes. */
    private static int[][] walk(Map<Long, List<long[]>> outgoing, long[] first) {
        List<int[]> pts = new ArrayList<>();
        long[] current = first;
        long start = current[0];
        while (true) {
            consume(outgoing, current);
            pts.add(new int[]{(int) (current[1] >> 32), (int) current[1]});
            if (current[1] == start) break;
            List<long[]> next = outgoing.get(current[1]);
            if (next == null || next.isEmpty()) break;   // open chain: stop rather than spin
            current = pick(next, current);
        }
        return simplify(pts);
    }

    private static void consume(Map<Long, List<long[]>> outgoing, long[] e) {
        List<long[]> at = outgoing.get(e[0]);
        if (at != null) {
            at.remove(e);
            if (at.isEmpty()) outgoing.remove(e[0]);
        }
    }

    /**
     * At a corner where four chunks meet diagonally two edges leave the same point. Carrying straight on
     * keeps the loop from cutting across itself; otherwise either is fine and the first is taken.
     */
    private static long[] pick(List<long[]> candidates, long[] incoming) {
        if (candidates.size() == 1) return candidates.get(0);
        int dx = (int) (incoming[1] >> 32) - (int) (incoming[0] >> 32);
        int dz = (int) incoming[1] - (int) incoming[0];
        for (long[] c : candidates) {
            int cdx = (int) (c[1] >> 32) - (int) (c[0] >> 32);
            int cdz = (int) c[1] - (int) c[0];
            if (cdx == dx && cdz == dz) return c;
        }
        return candidates.get(0);
    }

    /** Drops the points in the middle of a straight run -- a 181 chunk town is a handful of corners. */
    private static int[][] simplify(List<int[]> pts) {
        int n = pts.size();
        if (n < 3) return new int[0][];
        List<int[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int[] prev = pts.get((i - 1 + n) % n), cur = pts.get(i), next = pts.get((i + 1) % n);
            boolean collinear = (cur[0] - prev[0]) * (next[1] - cur[1])
                              == (cur[1] - prev[1]) * (next[0] - cur[0]);
            if (!collinear) out.add(cur);
        }
        return (out.size() >= 3 ? out : pts).toArray(new int[0][]);
    }

    /** Twice the signed area, for picking the ring that encloses the rest. */
    private static long area2(int[][] ring) {
        long sum = 0;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            sum += (long) ring[j][0] * ring[i][1] - (long) ring[i][0] * ring[j][1];
        }
        return sum;
    }
}
