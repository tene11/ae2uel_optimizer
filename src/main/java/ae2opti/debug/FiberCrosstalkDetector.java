package ae2opti.debug;

import ae2opti.AE2Opti;
import ae2opti.AE2OptiConfig;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.me.GridConnection;
import appeng.parts.AEBasePart;
import appeng.parts.networking.PartQuartzFiber;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 1-E：石英ファイバーの混線（サブネットとメインネットの合体）を検出するデバッグ用の処理。
 *
 * <h2>何を「混線」とみなすか</h2>
 * 石英ファイバーは、内側ノード（ケーブル側）と外側ノード（隣のブロック側）の2つを持ち、
 * どちらも machine は同じ {@link PartQuartzFiber} になる。
 * 正常なら2つは別々の grid にいる。同じ grid に両方が入っていたら、
 * ファイバー以外の経路で2つの網がつながってしまっている（＝混線）。
 *
 * <h2>流れ</h2>
 * <ol>
 *   <li>{@code GridConnection.create} で2つの別の grid が合体したら、合体後の grid を調べる（{@link #afterConnect}）。</li>
 *   <li>見つかったら、座標・合体させた接続の両端・スタックトレースをログに出す。</li>
 *   <li>そのファイバーを追跡し、tick の終わりにまだ合体しているかを確認する（{@link #onTickEnd}）。
 *       これで「同じ tick の中ですぐ分かれた一瞬の合体」か「tick をまたいで続いた合体」かを切り分ける。</li>
 * </ol>
 *
 * ノードの取得には {@code AENetworkProxy.getNode()} を使わない（ノードがまだ無いと作ってしまうため）。
 * 調べるのは grid に登録済みのノードだけなので、元の状態を一切変えない。
 *
 * すべてサーバースレッドからだけ呼ばれる前提。
 *
 * このクラスはイベント購読に登録しない（メソッドの引数に AE2 のクラスがあるため。
 * FML が起動時に引数の型を読み込むと、late mixin より先に {@code GridConnection} が読み込まれるおそれがある）。
 * tick のイベントは {@link FiberCrosstalkTickHandler} が受けてここへ渡す。
 */
public final class FiberCrosstalkDetector {

    private static final Logger LOG = AE2Opti.LOGGER;
    private static final String PREFIX = "[1-E fiber crosstalk] ";

    /**
     * {@link #beforeConnect} で記録した「2つの別の grid の合体になるか」。
     * create が入れ子になっても前後の対応がずれないようにスタックで持つ。
     */
    private static final ArrayDeque<Boolean> PENDING = new ArrayDeque<>();

    /** 検出済みで、まだ合体が続いているか確認中のファイバー（machine → 追跡情報） */
    private static final Map<IGridHost, Tracked> TRACKED = new IdentityHashMap<>();

    /** この mod が数えているサーバーの tick 数（ログで経過を出すためだけに使う） */
    private static long tick;
    private static int nextId = 1;
    private static int reportsThisTick;
    private static int suppressedThisTick;

    private FiberCrosstalkDetector() {
    }

    // ------------------------------------------------------------------
    // GridConnectionMixin から呼ばれる
    // ------------------------------------------------------------------

    /**
     * {@code new GridConnection(...)} の直前に呼ばれる。両側がそれぞれ別の grid にいれば、この接続で合体が起きる。
     */
    public static void beforeConnect(IGridNode a, IGridNode b) {
        final IGrid ga = a.getGrid();
        final IGrid gb = b.getGrid();
        PENDING.push(ga != null && gb != null && ga != gb);
    }

    /**
     * {@code GridConnection.create} の return 直前に呼ばれる。合体だったときだけ、合体後の grid を調べる。
     */
    public static void afterConnect(GridConnection connection) {
        if (PENDING.isEmpty()) {
            return;
        }
        final boolean merged = PENDING.pop();
        if (!merged || connection == null || !AE2OptiConfig.debug.fiberCrosstalk) {
            return;
        }
        final IGrid grid = connection.a().getGrid();
        if (grid == null) {
            return;
        }

        for (Crosstalk found : scan(grid)) {
            final Tracked already = TRACKED.get(found.fiber);
            if (already != null && already.isStillMerged()) {
                // 同じ混線がまだ続いているだけ（合体後の網がさらに別の網と合体した等）。ログは出さない。
                already.extraMerges++;
                continue;
            }
            final Tracked t = new Tracked(nextId++, found, tick);
            TRACKED.put(found.fiber, t);
            report(t, connection);
        }
    }

    // ------------------------------------------------------------------
    // 検出
    // ------------------------------------------------------------------

    /** 1つの混線（同じ grid に入っていた、1本のファイバーの2つのノード） */
    public static final class Crosstalk {
        public final IGridHost fiber;
        public final IGridNode first;
        public final IGridNode second;
        public final IGrid grid;

        Crosstalk(IGridHost fiber, IGridNode first, IGridNode second, IGrid grid) {
            this.fiber = fiber;
            this.first = first;
            this.second = second;
            this.grid = grid;
        }

        /** ファイバーの座標と向き */
        public String describeFiber() {
            return describeHost(this.fiber);
        }
    }

    /**
     * grid の中で、内側ノードと外側ノードの両方が入っているファイバーを探す。
     * ファイバーが無い・1本ぶんのノードしか無い網では、ほぼ何もせずに返る。
     */
    public static List<Crosstalk> scan(IGrid grid) {
        final IMachineSet nodes = grid.getMachines(PartQuartzFiber.class);
        if (nodes.size() < 2) {
            return Collections.emptyList();
        }
        List<Crosstalk> result = null;
        final Map<IGridHost, IGridNode> seen = new IdentityHashMap<>();
        for (IGridNode node : nodes) {
            final IGridHost host = node.getMachine();
            final IGridNode other = seen.put(host, node);
            if (other != null && other != node) {
                if (result == null) {
                    result = new ArrayList<>(1);
                }
                result.add(new Crosstalk(host, other, node, grid));
            }
        }
        return result == null ? Collections.emptyList() : result;
    }

    /** {@code /ae2opti fibercheck} の結果 */
    public static final class CheckResult {
        public int grids;
        public int fibers;
        public final List<Crosstalk> crosstalks = new ArrayList<>();
    }

    /**
     * 今ある全部の網を調べる（{@code /ae2opti fibercheck} 用）。読むだけで、何も変えない。
     */
    public static CheckResult checkAll() {
        final CheckResult r = new CheckResult();
        final Map<IGridHost, Boolean> fibers = new IdentityHashMap<>();
        for (Grid grid : TickHandler.INSTANCE.getGridList()) {
            if (grid.isEmpty()) {
                continue;
            }
            r.grids++;
            for (IGridNode node : grid.getMachines(PartQuartzFiber.class)) {
                fibers.put(node.getMachine(), Boolean.TRUE);
            }
            r.crosstalks.addAll(scan(grid));
        }
        r.fibers = fibers.size();
        return r;
    }

    // ------------------------------------------------------------------
    // 追跡（一瞬の合体か、続いている合体か）
    // ------------------------------------------------------------------

    private static final class Tracked {
        final int id;
        final IGridHost fiber;
        final IGridNode first;
        final IGridNode second;
        final String where;
        final long detectedTick;
        boolean firstCheckDone;
        int extraMerges;

        Tracked(int id, Crosstalk c, long detectedTick) {
            this.id = id;
            this.fiber = c.fiber;
            this.first = c.first;
            this.second = c.second;
            this.where = c.describeFiber();
            this.detectedTick = detectedTick;
        }

        /**
         * 2つのノードが、今も同じ grid に「登録されたまま」か。
         * GridNode は破棄されても myGrid を null に戻さないので、grid への登録の有無まで見る。
         */
        boolean isStillMerged() {
            final IGrid g = this.first.getGrid();
            if (g == null || g != this.second.getGrid() || g.isEmpty()) {
                return false;
            }
            final IMachineSet set = g.getMachines(PartQuartzFiber.class);
            return set.contains(this.first) && set.contains(this.second);
        }
    }

    /**
     * サーバーの tick の終わりに {@link FiberCrosstalkTickHandler} から呼ばれる。
     * 追跡中のファイバーがまだ合体しているかを確認する。
     */
    public static void onTickEnd() {
        // 例外などで after が呼ばれなかった分の掃除（通常は空）
        PENDING.clear();

        if (suppressedThisTick > 0) {
            LOG.warn(PREFIX + "{} more detection(s) in this tick were not logged in detail (debug.fiberCrosstalkMaxReportsPerTick = {}).",
                    suppressedThisTick, AE2OptiConfig.debug.fiberCrosstalkMaxReportsPerTick);
        }
        reportsThisTick = 0;
        suppressedThisTick = 0;

        if (!TRACKED.isEmpty()) {
            final Iterator<Tracked> it = TRACKED.values().iterator();
            while (it.hasNext()) {
                final Tracked t = it.next();
                final boolean merged = t.isStillMerged();
                final long age = tick - t.detectedTick;

                if (!t.firstCheckDone) {
                    t.firstCheckDone = true;
                    if (merged) {
                        LOG.warn(PREFIX + "#{} is STILL merged at the end of the tick -> it persists across ticks (not a momentary merge). fiber: {}",
                                t.id, t.where);
                    } else {
                        LOG.warn(PREFIX + "#{} was already split again before the end of the tick -> momentary merge within the same tick. fiber: {}",
                                t.id, t.where);
                        it.remove();
                    }
                    continue;
                }

                if (!merged) {
                    LOG.warn(PREFIX + "#{} split again after {} tick(s) (other merges into it meanwhile: {}). fiber: {}",
                            t.id, age, t.extraMerges, t.where);
                    it.remove();
                } else if (age >= AE2OptiConfig.debug.fiberCrosstalkTrackTicks) {
                    LOG.warn(PREFIX + "#{} is still merged after {} ticks; stopped tracking. Use /ae2opti fibercheck to check it again. fiber: {}",
                            t.id, age, t.where);
                    it.remove();
                }
            }
        }

        tick++;
    }

    /** サーバー停止時に呼ぶ。前のワールドのノードを持ち越さないようにする。 */
    public static void reset() {
        PENDING.clear();
        TRACKED.clear();
        reportsThisTick = 0;
        suppressedThisTick = 0;
    }

    // ------------------------------------------------------------------
    // ログ
    // ------------------------------------------------------------------

    private static void report(Tracked t, GridConnection connection) {
        if (reportsThisTick >= AE2OptiConfig.debug.fiberCrosstalkMaxReportsPerTick) {
            suppressedThisTick++;
            return;
        }
        reportsThisTick++;

        final IGrid grid = t.first.getGrid();
        LOG.warn(PREFIX + "#{} DETECTED: quartz fiber at {} has its inner and outer node on the same grid (grid nodes: {}). "
                        + "Merged by connection {} <-> {} (tick {}).",
                t.id, t.where, grid == null ? "?" : grid.getNodes().size(),
                describeNode(connection.a()), describeNode(connection.b()), tick);

        if (AE2OptiConfig.debug.fiberCrosstalkStackTrace) {
            LOG.warn(PREFIX + "#" + t.id + " stack trace (what triggered the merge):",
                    new Throwable("fiber crosstalk #" + t.id));
        }
    }

    /** ファイバーなどパーツの座標と向き。取れなければクラス名だけ返す。 */
    public static String describeHost(IGridHost host) {
        if (host instanceof AEBasePart) {
            final AEBasePart part = (AEBasePart) host;
            try {
                return "[" + part.getLocation() + ", side=" + part.getSide() + "]";
            } catch (RuntimeException e) {
                // タイルがもう無い等。座標が取れないだけなので続ける。
                return "[" + host.getClass().getSimpleName() + " (location unavailable)]";
            }
        }
        return "[" + host.getClass().getSimpleName() + "]";
    }

    /** 接続の端のノード（machine のクラス名と座標） */
    private static String describeNode(IGridNode node) {
        if (node == null) {
            return "[null]";
        }
        final IGridHost machine = node.getMachine();
        final String name = machine == null ? "?" : machine.getClass().getSimpleName();
        String location;
        try {
            location = String.valueOf(node.getGridBlock().getLocation());
        } catch (RuntimeException e) {
            location = "location unavailable";
        }
        return name + "[" + location + "]";
    }
}
