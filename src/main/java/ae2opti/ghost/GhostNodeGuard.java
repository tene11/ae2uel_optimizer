package ae2opti.ghost;

import ae2opti.AE2Opti;
import ae2opti.AE2OptiConfig;
import ae2opti.Tags;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 1-H：幽霊ノード対策。タイルが「まだワールドの一部か」を判定する。
 *
 * <p>AE2 の {@code TickHandler} は、読み込まれたタイルを待ち行列に入れておき、サーバー tick の最後に
 * {@code onReady()}（＝ノードを作って網につなぐ）を呼ぶ。そのとき {@code isInvalid()} しか見ていない。
 * 1.12.2 ではチャンクのアンロードで {@code invalidate()} は呼ばれない（{@code onChunkUnload()} だけ）ので、
 * 待ち行列に入ったあとでチャンクやディメンションがアンロードされたタイルにも {@code onReady()} が走り、
 * ワールドにないタイルのノード（幽霊ノード）ができて、誰にも片付けられずに残る。</p>
 *
 * <p>特に {@code DimensionManager.unloadWorlds} は ServerTick END より前に走り、
 * AE2 の {@code unloadWorld} は待ち行列を掃除しないので、ディメンションのアンロードで起きやすい。</p>
 *
 * <p>判定（{@link #check}）：</p>
 * <ol>
 *   <li>ワールドが無い／アンロード済み（{@link WorldEvent.Unload} を受けたワールド）</li>
 *   <li>タイルが invalidate 済み（コマンドで既存ノードを調べるとき用）</li>
 *   <li>チャンクが読み込まれていない（{@link WorldServer} のときだけ）</li>
 *   <li>チャンクにあるタイルが本人ではない（アンロード後に同じ tick で読み込み直された場合など）</li>
 * </ol>
 *
 * <p>チャンクは {@code ChunkProviderServer.loadedChunks} を直接見る。{@code getLoadedChunk} や
 * {@code isBlockLoaded} は、チャンクのアンロード予約（{@code unloadQueued}）を取り消してしまう副作用があるため使わない。
 * {@link WorldServer} 以外（他 Mod の仮のワールドなど）は、チャンクの判定をせず「アンロード済みか」だけを見る。</p>
 *
 * <p>イベント購読に登録されるクラスなので、AE2 のクラスを参照しないこと（vanilla と Forge の型だけ）。</p>
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class GhostNodeGuard {

    /** 幽霊になる理由。null は「問題なし」。 */
    public enum Reason {
        /** タイルにワールドが設定されていない */
        NO_WORLD("no_world"),
        /** ワールド（ディメンション）がアンロード済み */
        WORLD_UNLOADED("world_unloaded"),
        /** タイルが invalidate 済み */
        INVALID("invalid"),
        /** タイルのチャンクが読み込まれていない */
        CHUNK_UNLOADED("chunk_unloaded"),
        /** チャンクにあるタイルが別のインスタンス */
        REPLACED("replaced");

        /** 言語ファイルのキーの末尾（commands.ae2opti.ghostcheck.reason.xxx） */
        public final String key;

        Reason(String key) {
            this.key = key;
        }
    }

    /** 1tickに詳しくログを出す件数の上限（残りは数だけ） */
    private static final int MAX_REPORTS_PER_TICK = 20;

    /** {@link WorldEvent.Unload} を受けたサーバー側のワールド。GC されたら自然に消える。 */
    private static final Set<World> UNLOADED_WORLDS = Collections.newSetFromMap(new WeakHashMap<>());

    /** この tick に onReady を止めた数 */
    private static int skippedThisTick;

    /** 起動してから onReady を止めた数（コマンド表示用） */
    private static long skippedTotal;

    private GhostNodeGuard() {
    }

    /**
     * タイルがまだワールドの一部かを調べる。
     *
     * @return 幽霊になる理由。問題なければ null
     */
    @Nullable
    public static Reason check(TileEntity te) {
        final World world = te.getWorld();
        if (world == null) {
            return Reason.NO_WORLD;
        }
        if (UNLOADED_WORLDS.contains(world)) {
            return Reason.WORLD_UNLOADED;
        }
        if (te.isInvalid()) {
            return Reason.INVALID;
        }
        if (world instanceof WorldServer) {
            final BlockPos pos = te.getPos();
            final Chunk chunk = ((WorldServer) world).getChunkProvider().loadedChunks
                    .get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (chunk == null) {
                return Reason.CHUNK_UNLOADED;
            }
            if (chunk.getTileEntityMap().get(pos) != te) {
                return Reason.REPLACED;
            }
        }
        return null;
    }

    /**
     * AE2 の TickHandler が待ち行列のタイルに {@code onReady()} を呼ぶ直前に呼ばれる（{@code TickHandlerMixin}）。
     *
     * @return true なら onReady を呼んでよい。false なら飛ばす
     */
    public static boolean allowReady(TileEntity te) {
        final Reason reason = check(te);
        if (reason == null) {
            return true;
        }
        skippedThisTick++;
        skippedTotal++;
        if (AE2OptiConfig.debug.ghostNodeLog && skippedThisTick <= MAX_REPORTS_PER_TICK) {
            AE2Opti.LOGGER.info("[1-H ghost node] Skipped AE2 init of {} at {}: {}.",
                    te.getClass().getName(), describe(te), reason.name());
        }
        return false;
    }

    /** ログ・チャット用の場所の文字列（例: {@code dim 7 (12, 64, -30)}） */
    public static String describe(TileEntity te) {
        final World world = te.getWorld();
        final BlockPos pos = te.getPos();
        final String dim = world != null ? String.valueOf(world.provider.getDimension()) : "?";
        return "dim " + dim + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    public static long getSkippedTotal() {
        return skippedTotal;
    }

    public static void reset() {
        UNLOADED_WORLDS.clear();
        skippedThisTick = 0;
        skippedTotal = 0;
    }

    @SubscribeEvent
    public static void onWorldUnload(WorldEvent.Unload event) {
        final World world = event.getWorld();
        if (!world.isRemote) {
            UNLOADED_WORLDS.add(world);
        }
    }

    /** AE2 の TickHandler（初期化待ちタイルの onReady もここで走る）より後に動くよう、LOWEST にしている。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || skippedThisTick == 0) {
            return;
        }
        // めったに起きない（ディメンションのアンロードなど）ので、件数だけは常に1行出す
        AE2Opti.LOGGER.info("[1-H ghost node] Kept {} unloaded tile entit{} from joining an ME network this tick{}.",
                skippedThisTick, skippedThisTick == 1 ? "y" : "ies",
                AE2OptiConfig.debug.ghostNodeLog ? "" : " (set debug.ghostNodeLog=true for details)");
        skippedThisTick = 0;
    }
}
