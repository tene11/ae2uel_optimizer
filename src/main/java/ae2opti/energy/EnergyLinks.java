package ae2opti.energy;

import ae2opti.AE2OptiConfig;
import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergyGridProvider;
import appeng.me.cache.EnergyGridCache;
import appeng.parts.networking.PartQuartzFiber;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * [1-B] 石英ファイバーでつながった電力網（EnergyGridCache）の一覧を、tick ごとにキャッシュする。
 *
 * <p>元の AE2UEL は、自分の網で足りないとき（満タンで余りが出た・取り出し量が足りない）に、
 * 呼ばれるたびにファイバー → 隣の網 → そのファイバー…と、つながった範囲を PriorityQueue でたどり直す。
 * そのたびにファイバーの {@code providers()} が {@code new ArrayList} を作り、キューの比較のたびに
 * {@code getStoredPower()}（中で全セルを数え直す {@code refreshPower()}）が何度も走る。</p>
 *
 * <p>ここでは、つながった網の一覧を作って、同じ一覧に入っている網すべてで使い回す。
 * 作り直すのは下の条件のときだけ（並び順と満タン・空の印は、一覧とは別に毎 tick 新しくする）。</p>
 *
 * <p>並べ方は設定で2通り:</p>
 * <ul>
 *   <li>{@code energy.linkedGridTickOrder} がオフ: 呼ばれるたびに一覧を並べ直す（注入と需要は充電率の低い順、
 *       取り出しは保有量の多い順）。網ごとの保有量は、1回の呼び出しの中で1度だけ聞く。</li>
 *   <li>{@code energy.linkedGridTickOrder} がオン（既定）: 並べるのは tick ごとに1回だけ（その tick で最初に必要になったとき）。
 *       さらに、その tick に1度も受け取らなかった網は「満タン」、1度も出さなかった網は「空」の印を付け、
 *       その tick の残りでは飛ばす。印は、その網から電力が取り出された（満タンの印）／その網に電力が入れられようとした
 *       （空の印）・網にノードが出入りしたときに外す（{@code EnergyGridCacheMixin}）。
 *       元と違うのは、tick の途中で充電率が変わっても並び順が tick の初めのままになることだけ。</li>
 * </ul>
 *
 * <p>作り直す条件:</p>
 * <ul>
 *   <li>作ってから {@code energy.linkedGridRebuildInterval} tick（既定 5）たったとき（サーバーの tick 数で見る）。
 *       下のファイバーの出入りで確実に作り直されるので、これは取りこぼしがあったときのための保険。1 なら毎 tick 作り直す。</li>
 *   <li>どこかの網で {@link IEnergyGridProvider}（ファイバー）の addNode / removeNode が起きたとき
 *       （{@link #onProviderChanged} が全体共通のバージョン番号を進め、その網が入っていた一覧を手放す）。
 *       ファイバーの内側・外側どちらのノードが別の網へ移っても、AE2 は必ず元の網の removeNode と
 *       新しい網の addNode を呼ぶので、網の統合・分割・チャンクのアンロード・ファイバーの破壊はすべてここで拾える。</li>
 * </ul>
 *
 * <p>結果について: 受け取られる（取り出される）電力の合計は元と同じ。
 * ただし、元は「見つかった順＋充電率」で回るのに対し、こちらは一覧全体を充電率で並べるので、
 * どの網から先に入る（出る）かが元と変わることがある。</p>
 *
 * <p>つながりの中に、網（EnergyGridCache）と石英ファイバー（PartQuartzFiber そのもの）以外の
 * {@link IEnergyGridProvider} があったときは、その一覧は「対応外」として扱い、呼び出し側は元と同じ探索を使う
 * （ほかの Mod が自分で電力を持つ provider を足していても、結果が変わらないようにするため）。</p>
 *
 * <p>mixin パッケージの外に置くこと。サーバースレッドからだけ呼ばれる前提。</p>
 */
public final class EnergyLinks {

    /** これ以下の数ならその場で挿入ソートする */
    private static final int INSERTION_SORT_MAX = 32;

    /** ファイバーが網に出入りするたびに進める、全体共通のバージョン番号 */
    private static long version = 0L;

    // ---- /ae2opti energylinks 用の集計（前回の確認からの分） ----
    /** 一覧を作り直した回数 */
    private static long buildCount = 0L;
    /** 作り直しでたどった項目の数（網・ファイバーのつながり1つにつき1） */
    private static long buildVisited = 0L;
    /** 作り直しにかかった時間の合計（ナノ秒） */
    private static long buildNanos = 0L;
    /** 一覧を並べた回数 */
    private static long sortCount = 0L;
    /** 並べるのにかかった時間の合計（ナノ秒、保有量を聞く時間を含む） */
    private static long sortNanos = 0L;
    /** ファイバーの出入りでバージョンが進んだ回数 */
    private static long versionBumps = 0L;

    private EnergyLinks() {
    }

    /**
     * つながった網の一覧。同じ一覧に入っている網すべてが、同じオブジェクトを持つ。
     * 一覧は数 tick 使い回すことがあるので、並び順は「並べた tick」と一緒に持ち、tick が変わったら並べ直す。
     */
    public static final class Component {
        /** 作ったときの {@link #version} */
        final long version;
        /** 作ったときのサーバーの tick 数 */
        final int tick;
        /** つながっている網すべて（自分も含む）。見つけた順 */
        final EnergyGridCache[] grids;
        /** 網と石英ファイバーだけでつながっているなら true。false なら呼び出し側は元の探索を使う */
        final boolean supported;

        /** いま使っている tick（{@link #get} が呼ばれるたびに入れる）。満タン・空の印と並び順はこの tick で見る */
        int nowTick;
        /** 並び順を作った tick */
        private int orderTick;
        /** {@link #orderTick} の、充電率の低い順（注入・需要用）。まだ並べていなければ null */
        private EnergyGridCache[] byLowestPercentage;
        /** {@link #orderTick} の、保有量の多い順（取り出し用）。まだ並べていなければ null */
        private EnergyGridCache[] byHighestStored;

        Component(long version, int tick, EnergyGridCache[] grids, boolean supported) {
            this.version = version;
            this.tick = tick;
            this.nowTick = tick;
            this.orderTick = tick;
            this.grids = grids;
            this.supported = supported;
        }

        /** 自分も含めた網の数 */
        public int size() {
            return this.grids.length;
        }

        /** 作ったときの tick */
        public int tick() {
            return this.tick;
        }

        /** {@link #nowTick} の並び順。tick が変わっていたら、前の tick の並び順は捨てる */
        EnergyGridCache[] tickOrder(boolean byHighestStored) {
            if (this.orderTick != this.nowTick) {
                this.orderTick = this.nowTick;
                this.byLowestPercentage = null;
                this.byHighestStored = null;
            }
            if (byHighestStored) {
                if (this.byHighestStored == null) {
                    this.byHighestStored = sorted(this.grids.clone(), true);
                }
                return this.byHighestStored;
            }
            if (this.byLowestPercentage == null) {
                this.byLowestPercentage = sorted(this.grids.clone(), false);
            }
            return this.byLowestPercentage;
        }
    }

    /**
     * EnergyGridCache の addNode / removeNode で、ファイバー（IEnergyGridProvider）が出入りしたときに呼ぶ。
     *
     * <p>全体共通のバージョン番号を進めるのに加えて、{@code changed} が入っている一覧を、その一覧の網すべてから外す。
     * バージョンが変わっても、各網の一覧は次に {@link #get} が呼ばれるまで残る。{@link #get} は自分の網だけでは
     * 足りなかったときにしか呼ばれないので、満タンにも空にもならない網は、壊れた網（Grid・ノード・タイル）を
     * 一覧の中に持ち続けてしまう。ここで外しておけば、壊れた網はほかの網から参照されなくなる。</p>
     *
     * <p>同じ一覧の網はすべて同じ {@link Component} を持っているので、{@code changed} の一覧を外せば、
     * その一覧を持っている網は残らない。外したあとは、次に必要になったときに {@link #get} が作り直す。</p>
     */
    public static void onProviderChanged(EnergyGridCache changed) {
        version++;
        versionBumps++;
        final Component links = ((EnergyLinkHolder) changed).ae2opti$getLinks();
        if (links == null) {
            return;
        }
        for (EnergyGridCache grid : links.grids) {
            ((EnergyLinkHolder) grid).ae2opti$setLinks(null);
        }
        // links.grids に changed が入っていない場合（作り直しの途中で入れ替わった等）にも、自分の分は確実に外す
        ((EnergyLinkHolder) changed).ae2opti$setLinks(null);
    }

    /**
     * 前回呼んでからの集計を返し、0 に戻す。
     *
     * @return {作り直した回数, たどった項目の数, 作り直しの合計ナノ秒, 並べた回数, 並べる合計ナノ秒, バージョンが進んだ回数}
     */
    public static long[] takeStats() {
        final long[] stats = {buildCount, buildVisited, buildNanos, sortCount, sortNanos, versionBumps};
        buildCount = 0L;
        buildVisited = 0L;
        buildNanos = 0L;
        sortCount = 0L;
        sortNanos = 0L;
        versionBumps = 0L;
        return stats;
    }

    /**
     * {@code self} とつながった網の一覧を返す。今のバージョンで、作ってから {@code energy.linkedGridRebuildInterval} tick 以内のものがあればそれを使い、
     * 無ければ作り直して、一覧に入っているすべての網に覚えさせる。
     *
     * @return 対応外（ファイバー以外のつながりがある）なら null。呼び出し側は元の探索を使うこと
     */
    public static Component get(EnergyGridCache self) {
        final int tick = currentTick();
        final Component cached = ((EnergyLinkHolder) self).ae2opti$getLinks();
        if (cached != null && cached.version == version) {
            final long age = (long) tick - cached.tick;
            if (age >= 0 && age < AE2OptiConfig.energy.linkedGridRebuildInterval) {
                cached.nowTick = tick;
                return cached.supported ? cached : null;
            }
        }
        final long start = System.nanoTime();
        final Component built = build(self, tick);
        buildNanos += System.nanoTime() - start;
        return built.supported ? built : null;
    }

    /**
     * 自分の網に入れたあとの余り {@code leftover} を、つながったほかの網へ充電率の低い順に入れる。
     *
     * @return 最後まで入らなかった余り
     */
    public static double injectOthers(EnergyGridCache self, Component links, double leftover, Actionable mode) {
        if (AE2OptiConfig.energy.linkedGridTickOrder) {
            final int tick = links.nowTick;
            for (EnergyGridCache grid : links.tickOrder(false)) {
                if (grid == self) {
                    continue;
                }
                final EnergyLinkHolder holder = (EnergyLinkHolder) grid;
                if (holder.ae2opti$isFull(tick)) {
                    continue;
                }
                final double before = leftover;
                leftover = grid.injectProviderPower(leftover, mode);
                if (!(leftover < before)) {
                    // 1 も受け取らなかった。この tick の残りでは、電力が取り出されるまで飛ばす
                    holder.ae2opti$markFull(tick);
                }
                if (!(leftover > 0)) {
                    break;
                }
            }
            return leftover;
        }

        for (EnergyGridCache grid : othersSorted(self, links, false)) {
            leftover = grid.injectProviderPower(leftover, mode);
            if (!(leftover > 0)) {
                break;
            }
        }
        return leftover;
    }

    /**
     * 自分の網から取り出したあと、足りない分をつながったほかの網から保有量の多い順に取り出す。
     *
     * @param extracted 自分の網から取り出せた量
     * @return 取り出せた合計（自分の網の分も含む）
     */
    public static double extractOthers(EnergyGridCache self, Component links, double extracted, double toExtract,
                                       Actionable mode) {
        if (AE2OptiConfig.energy.linkedGridTickOrder) {
            final int tick = links.nowTick;
            for (EnergyGridCache grid : links.tickOrder(true)) {
                if (grid == self) {
                    continue;
                }
                final EnergyLinkHolder holder = (EnergyLinkHolder) grid;
                if (holder.ae2opti$isEmpty(tick)) {
                    continue;
                }
                final double got = grid.extractProviderPower(toExtract - extracted, mode);
                if (!(got > 0)) {
                    // 1 も出さなかった。この tick の残りでは、電力が入れられるまで飛ばす
                    holder.ae2opti$markEmpty(tick);
                }
                extracted += got;
                if (!(extracted < toExtract)) {
                    break;
                }
            }
            return extracted;
        }

        for (EnergyGridCache grid : othersSorted(self, links, true)) {
            extracted += grid.extractProviderPower(toExtract - extracted, mode);
            if (!(extracted < toExtract)) {
                break;
            }
        }
        return extracted;
    }

    /**
     * 自分の網の需要を聞いたあと、目標に届かなければ、つながったほかの網の需要を充電率の低い順に足す。
     *
     * @param required 自分の網の需要
     * @return 需要の合計（自分の網の分も含む）
     */
    public static double demandOthers(EnergyGridCache self, Component links, double required, double maxRequired) {
        if (AE2OptiConfig.energy.linkedGridTickOrder) {
            final int tick = links.nowTick;
            for (EnergyGridCache grid : links.tickOrder(false)) {
                // 満タンの印が付いた網は受け取れないので、需要も 0 として飛ばす
                if (grid == self || ((EnergyLinkHolder) grid).ae2opti$isFull(tick)) {
                    continue;
                }
                required += grid.getProviderEnergyDemand(maxRequired - required);
                if (!(required < maxRequired)) {
                    break;
                }
            }
            return required;
        }

        for (EnergyGridCache grid : othersSorted(self, links, false)) {
            required += grid.getProviderEnergyDemand(maxRequired - required);
            if (!(required < maxRequired)) {
                break;
            }
        }
        return required;
    }

    // ---- 内部 ----

    private static int currentTick() {
        final MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server == null ? Integer.MIN_VALUE : server.getTickCounter();
    }

    /**
     * {@code start} から、元の AE2UEL と同じように {@code providers()} をたどって、つながった範囲を全部集める。
     * 見つかった網すべてに、できた一覧を覚えさせる。
     */
    private static Component build(EnergyGridCache start, int tick) {
        final List<EnergyGridCache> grids = new ArrayList<>();
        // AE2 の部品は equals を上書きしていないので、元の HashSet と同じ「同じオブジェクトかどうか」で見る
        final Set<IEnergyGridProvider> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final ArrayDeque<IEnergyGridProvider> queue = new ArrayDeque<>();
        boolean supported = true;

        seen.add(start);
        queue.add(start);
        long visited = 0L;
        while (!queue.isEmpty()) {
            final IEnergyGridProvider next = queue.poll();
            final Collection<IEnergyGridProvider> neighbors;
            if (next instanceof EnergyGridCache) {
                grids.add((EnergyGridCache) next);
                // 網の providers() は Multiset そのもので、同じファイバーが2回入っていることがある
                // （内側・外側の両方が同じ網にあるとき）。何回入っていても行き先は同じなので、重複を除いた elementSet() だけをたどる。
                neighbors = ((EnergyLinkHolder) next).ae2opti$providerMultiset().elementSet();
            } else {
                if (next.getClass() != PartQuartzFiber.class) {
                    // ファイバーの継承クラスやほかの Mod の provider は、自分で電力を持つかもしれないので対応外にする。
                    // 一覧に入るほかの網にも「対応外」を覚えさせるため、たどるのは最後まで続ける。
                    supported = false;
                }
                neighbors = next.providers();
            }
            for (IEnergyGridProvider provider : neighbors) {
                visited++;
                if (provider != null && seen.add(provider)) {
                    queue.add(provider);
                }
            }
        }
        buildCount++;
        buildVisited += visited;

        final Component links = new Component(version, tick, grids.toArray(new EnergyGridCache[0]), supported);
        for (EnergyGridCache grid : links.grids) {
            ((EnergyLinkHolder) grid).ae2opti$setLinks(links);
        }
        return links;
    }

    /**
     * 一覧から自分を除き、並べ替えたものを返す（呼び出しごとに新しい配列。途中でほかの呼び出しが入っても壊れない）。
     * {@code energy.linkedGridTickOrder} がオフのときに使う。
     */
    private static EnergyGridCache[] othersSorted(EnergyGridCache self, Component links, boolean byHighestStored) {
        final EnergyGridCache[] all = links.grids;
        final EnergyGridCache[] others = new EnergyGridCache[all.length];
        int n = 0;
        for (EnergyGridCache grid : all) {
            if (grid != self) {
                others[n++] = grid;
            }
        }
        final EnergyGridCache[] result = n == others.length ? others : Arrays.copyOf(others, n);
        return sorted(result, byHighestStored);
    }

    /**
     * {@code grids} をその場で並べて返す。
     *
     * @param byHighestStored true なら保有量の多い順（元の COMPARATOR_HIGHEST_AMOUNT_STORED_FIRST）、
     *                        false なら充電率の低い順（元の COMPARATOR_LOWEST_PERCENTAGE_FIRST）
     */
    private static EnergyGridCache[] sorted(EnergyGridCache[] grids, boolean byHighestStored) {
        final int n = grids.length;
        if (n <= 1) {
            // 並べる必要がないので、保有量も聞かない（refreshPower を走らせない）
            return grids;
        }
        final long start = System.nanoTime();
        // 網ごとの保有量は、ここで1度だけ聞く
        final double[] keys = new double[n];
        for (int i = 0; i < n; i++) {
            final EnergyGridCache grid = grids[i];
            final double stored = grid.getProviderStoredEnergy();
            if (byHighestStored) {
                // 保有量は 0 以上なので、符号を反転して小さい順に並べれば「多い順」になる
                keys[i] = -stored;
            } else {
                keys[i] = (stored + 1) / (grid.getProviderMaxEnergy() + 1);
            }
        }
        sort(grids, keys);
        sortCount++;
        sortNanos += System.nanoTime() - start;
        return grids;
    }

    /** keys の小さい順に grids を並べる（同じ値どうしは元の順番のまま） */
    private static void sort(EnergyGridCache[] grids, double[] keys) {
        final int n = grids.length;
        if (n <= INSERTION_SORT_MAX) {
            for (int i = 1; i < n; i++) {
                final double key = keys[i];
                final EnergyGridCache grid = grids[i];
                int j = i - 1;
                while (j >= 0 && Double.compare(keys[j], key) > 0) {
                    keys[j + 1] = keys[j];
                    grids[j + 1] = grids[j];
                    j--;
                }
                keys[j + 1] = key;
                grids[j + 1] = grid;
            }
            return;
        }
        final Integer[] index = new Integer[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        Arrays.sort(index, (a, b) -> Double.compare(keys[a], keys[b]));
        final EnergyGridCache[] copy = grids.clone();
        for (int i = 0; i < n; i++) {
            grids[i] = copy[index[i]];
        }
    }
}
