package ae2opti.energy;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergyGridProvider;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.cache.EnergyGridCache;
import appeng.parts.networking.PartQuartzFiber;
import com.google.common.collect.Multiset;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [1-B] {@code /ae2opti energylinks} 用。読むだけで、網の状態は変えない。
 *
 * <p>調べること:</p>
 * <ul>
 *   <li>網ごとの「ファイバーの一覧」（EnergyGridCache の {@code energyGridProviders}、Multiset）に、
 *       同じファイバーが何回入っているか。ふつうは「そのファイバーのノードがその網にいくつあるか」と同じ回数
 *       （1回。内側・外側の両方が同じ網なら2回）。それより多ければ登録が外れずに残っている（重複）、
 *       その網にノードが1つも無いのに入っていれば古い登録、少なければ登録漏れ。</li>
 *   <li>ファイバーでつながった網のまとまり（グループ）の数と大きさ。
 *       AE2 の網の一覧（TickHandler）に無いのにファイバーの先にある網があれば、それも数える。</li>
 *   <li>前回の確認から、一覧の作り直しが何回あったか（{@link EnergyLinks#takeStats()}）。</li>
 * </ul>
 */
public final class EnergyLinkChecker {

    /** 前回の確認をしたときのサーバーの tick 数。まだなら MIN_VALUE */
    private static int lastCheckTick = Integer.MIN_VALUE;

    private EnergyLinkChecker() {
    }

    /** 登録の回数がおかしいファイバー1件 */
    public static final class Anomaly {
        public final IEnergyGridProvider provider;
        /** 一覧に入っている回数 */
        public final int count;
        /** そのファイバーのノードがその網にいくつあるか（＝本来入っているはずの回数） */
        public final int expected;
        /** その網のノード数 */
        public final int gridNodes;

        Anomaly(IEnergyGridProvider provider, int count, int expected, int gridNodes) {
            this.provider = provider;
            this.count = count;
            this.expected = expected;
            this.gridNodes = gridNodes;
        }
    }

    /** 調べた結果 */
    public static final class CheckResult {
        public int grids;
        /** ファイバーでほかの網とつながっている網の数 */
        public int linkedGrids;
        /** つながった網のまとまりの数（2つ以上の網が入っているもの） */
        public int groups;
        /** いちばん大きいまとまりの網の数 */
        public int largestGroup;
        /** 網と石英ファイバー以外のつながりがあるまとまりの数（1-B は元の探索を使う） */
        public int unsupportedGroups;
        /** AE2 の網の一覧に無いのに、ファイバーの先にあった網の数 */
        public int orphanGrids;

        /** 全部の網の、ファイバーの一覧に入っている回数の合計 */
        public long entries;
        /** そのうち、重複を除いた数 */
        public long distinct;
        /** 本来より多く入っている回数の合計 */
        public long duplicated;
        /** その網にノードが無いのに入っている回数の合計 */
        public long stale;
        /** 本来より少ない回数の合計 */
        public long missing;
        public final List<Anomaly> anomalies = new ArrayList<>();

        /** 前回の確認からの tick 数。初回なら -1 */
        public long ticks;
        public long rebuilds;
        public long rebuildVisited;
        /** 作り直しの合計時間（ナノ秒） */
        public long rebuildNanos;
        /** 一覧を並べた回数 */
        public long sorts;
        /** 並べるのにかかった合計時間（ナノ秒、保有量を聞く時間を含む） */
        public long sortNanos;
        public long versionBumps;
    }

    public static CheckResult checkAll(MinecraftServer server) {
        final CheckResult r = new CheckResult();
        final Map<EnergyGridCache, Grid> caches = new IdentityHashMap<>();

        for (Grid grid : TickHandler.INSTANCE.getGridList()) {
            if (grid.isEmpty()) {
                continue;
            }
            r.grids++;
            final IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
            if (!(energy instanceof EnergyGridCache)) {
                continue;
            }
            final EnergyGridCache cache = (EnergyGridCache) energy;
            caches.put(cache, grid);
            checkEntries(r, grid, ((EnergyLinkHolder) cache).ae2opti$providerMultiset());
        }

        countGroups(r, caches);

        final int now = server.getTickCounter();
        final long[] stats = EnergyLinks.takeStats();
        r.ticks = lastCheckTick == Integer.MIN_VALUE ? -1L : (long) now - lastCheckTick;
        r.rebuilds = stats[0];
        r.rebuildVisited = stats[1];
        r.rebuildNanos = stats[2];
        r.sorts = stats[3];
        r.sortNanos = stats[4];
        r.versionBumps = stats[5];
        lastCheckTick = now;
        return r;
    }

    public static void reset() {
        lastCheckTick = Integer.MIN_VALUE;
        EnergyLinks.takeStats();
    }

    /** 網1つぶんの「ファイバーの一覧」と、その網に実際にあるノードを比べる */
    private static void checkEntries(CheckResult r, Grid grid, Multiset<IEnergyGridProvider> multiset) {
        // その網のノードのうち、持ち主が IEnergyGridProvider のものを、持ち主ごとに数える
        final Map<IGridHost, Integer> nodesPerHost = new IdentityHashMap<>();
        int gridNodes = 0;
        for (IGridNode node : grid.getNodes()) {
            gridNodes++;
            final IGridHost host = node.getMachine();
            if (host instanceof IEnergyGridProvider) {
                nodesPerHost.merge(host, 1, Integer::sum);
            }
        }

        r.entries += multiset.size();
        final Set<IEnergyGridProvider> elements = multiset.elementSet();
        r.distinct += elements.size();

        for (IEnergyGridProvider provider : elements) {
            final int count = multiset.count(provider);
            final Integer nodes = provider instanceof IGridHost ? nodesPerHost.get(provider) : null;
            final int expected = nodes == null ? 0 : nodes;
            if (count > expected) {
                if (expected == 0) {
                    r.stale += count;
                } else {
                    r.duplicated += count - expected;
                }
                r.anomalies.add(new Anomaly(provider, count, expected, gridNodes));
            } else if (count < expected) {
                r.missing += expected - count;
                r.anomalies.add(new Anomaly(provider, count, expected, gridNodes));
            }
        }
        // ノードはあるのに一覧に1回も入っていないもの
        for (Map.Entry<IGridHost, Integer> e : nodesPerHost.entrySet()) {
            final IEnergyGridProvider provider = (IEnergyGridProvider) e.getKey();
            if (!elements.contains(provider)) {
                r.missing += e.getValue();
                r.anomalies.add(new Anomaly(provider, 0, e.getValue(), gridNodes));
            }
        }
    }

    /** ファイバーでつながった網のまとまりを数える（EnergyLinks.build と同じたどり方。何も書き換えない） */
    private static void countGroups(CheckResult r, Map<EnergyGridCache, Grid> caches) {
        final Set<IEnergyGridProvider> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<EnergyGridCache> orphans = Collections.newSetFromMap(new IdentityHashMap<>());

        for (EnergyGridCache start : caches.keySet()) {
            if (seen.contains(start)) {
                continue;
            }
            int grids = 0;
            boolean supported = true;
            final ArrayDeque<IEnergyGridProvider> queue = new ArrayDeque<>();
            seen.add(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                final IEnergyGridProvider next = queue.poll();
                final Collection<IEnergyGridProvider> neighbors;
                if (next instanceof EnergyGridCache) {
                    grids++;
                    if (!caches.containsKey(next)) {
                        orphans.add((EnergyGridCache) next);
                    }
                    neighbors = ((EnergyLinkHolder) next).ae2opti$providerMultiset().elementSet();
                } else {
                    if (next.getClass() != PartQuartzFiber.class) {
                        supported = false;
                    }
                    neighbors = next.providers();
                }
                for (IEnergyGridProvider provider : neighbors) {
                    if (provider != null && seen.add(provider)) {
                        queue.add(provider);
                    }
                }
            }
            if (grids > 1) {
                r.groups++;
                r.linkedGrids += grids;
                r.largestGroup = Math.max(r.largestGroup, grids);
                if (!supported) {
                    r.unsupportedGroups++;
                }
            }
        }
        r.orphanGrids = orphans.size();
    }
}
