package ae2opti;

import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Arrays;
import java.util.List;

/**
 * AE2 のクラスを対象にするので late mixin として読み込む。
 * このクラスは mixin パッケージの外に置くこと（中に置くと MixinBooter 11 でクラッシュする）。
 *
 * <p>他の Mod 向けの mixin 設定は、その Mod が入っているときだけ読み込む。
 * 任意の Mod なので、無いときに読み込むと対象クラスが見つからずに起動できなくなるため。</p>
 * <ul>
 *     <li>mixins.ae2opti.betterp2p.json … Better P2P（betterp2p）</li>
 *     <li>mixins.ae2opti.draconic.json … Draconic Evolution（draconicevolution）</li>
 *     <li>mixins.ae2opti.packageddraconic.json … PackagedDraconic（packageddraconic）</li>
 * </ul>
 */
public class AE2OptiMixinLoader implements ILateMixinLoader {

    private static final String MAIN_CONFIG = "mixins.ae2opti.json";
    private static final String BETTER_P2P_CONFIG = "mixins.ae2opti.betterp2p.json";
    private static final String DRACONIC_CONFIG = "mixins.ae2opti.draconic.json";
    private static final String PACKAGED_DRACONIC_CONFIG = "mixins.ae2opti.packageddraconic.json";

    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList(MAIN_CONFIG, BETTER_P2P_CONFIG, DRACONIC_CONFIG, PACKAGED_DRACONIC_CONFIG);
    }

    @Override
    public boolean shouldMixinConfigQueue(Context context) {
        switch (context.mixinConfig()) {
            case BETTER_P2P_CONFIG:
                return context.isModPresent("betterp2p");
            case DRACONIC_CONFIG:
                return context.isModPresent("draconicevolution");
            case PACKAGED_DRACONIC_CONFIG:
                return context.isModPresent("packageddraconic");
            default:
                return true;
        }
    }
}
