package ae2opti;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * サーバーの重さ（直近100tickの平均 MSPT）を返す。
 * 同じtickの中で何度呼ばれても、計算は1回だけにする。
 *
 * <p>mixin パッケージの外に置くこと。</p>
 */
public final class ServerLoad {

    private static int cachedTick = Integer.MIN_VALUE;
    private static double cachedMspt;

    private ServerLoad() {
    }

    /** 直近100tickの平均 MSPT（ミリ秒）。サーバーが無いときは 0 */
    public static double averageMspt() {
        final MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return 0.0;
        }
        final int tick = server.getTickCounter();
        if (tick != cachedTick) {
            final long[] times = server.tickTimeArray;
            long sum = 0L;
            for (long t : times) {
                sum += t;
            }
            cachedMspt = times.length == 0 ? 0.0 : sum / (double) times.length / 1_000_000.0;
            cachedTick = tick;
        }
        return cachedMspt;
    }
}
