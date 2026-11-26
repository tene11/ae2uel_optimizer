package ae2opti;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mixins.ae2opti*.json（本体・Better P2P・DE・PackagedDraconic）の "plugin"。cfg の {@code mixins} カテゴリを見て、オフになっている mixin を適用しない。
 *
 * <p>late mixin が読み込まれる時点では、Forge の @Config（{@link AE2OptiConfig}）はまだ読み込まれていない。
 * そのため、ここでは config/ae2uel_optimizer.cfg を {@link Configuration} で直接読む。
 * 読むだけで保存はしない（ファイルを作ったり書き換えたりするのは、後で動く Forge の ConfigManager に任せる）。</p>
 *
 * <p>cfg がまだ無い・読めない・項目が無いときは、すべて「適用する」（デフォルトのオン）として扱う。</p>
 *
 * <p>このクラスは mixin パッケージの外に置くこと（{@link AE2OptiMixinLoader} と同じ理由）。
 * また、ここから {@link AE2OptiConfig} や AE2 のクラスを参照しないこと（早すぎる読み込みを避けるため）。</p>
 */
public class AE2OptiMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOG = LogManager.getLogger(Tags.MOD_NAME);

    /**
     * 設定ファイルの名前（config/ae2uel_optimizer.cfg）。jar の名前にそろえている。
     * {@link AE2OptiConfig} の {@code @Config(name = ...)} もこの定数を使う。
     * （AE2OptiConfig 側に置くと、Forge が設定項目として読もうとして起動に失敗するため、こちらに置いている）
     */
    public static final String CONFIG_NAME = "ae2uel_optimizer";

    /** cfg のカテゴリ名（{@link AE2OptiConfig#mixins} のフィールド名を小文字にしたもの） */
    private static final String CATEGORY = "mixins";

    /**
     * mixin のクラス名（パッケージなし） → cfg の項目名（{@link AE2OptiConfig.Mixins} のフィールド名）。
     * ここに無い mixin は、常に適用する。
     */
    private static final Map<String, String> KEYS = new HashMap<>();

    static {
        KEYS.put("EnergyGridCacheMixin", "energyGridCache");
        KEYS.put("CraftingCPUClusterMixin", "craftingCpuCluster");
        KEYS.put("CraftingGridCacheMixin", "craftingGridCache");
        KEYS.put("GridConnectionMixin", "gridConnection");
        KEYS.put("TickHandlerMixin", "tickHandler");
        KEYS.put("PartP2PFEPowerMixin", "p2pFePower");
        KEYS.put("PartP2PFEPowerInputMixin", "p2pFePower");
        // mixins.ae2opti.betterp2p.json（Better P2P があるときだけ読まれる）
        KEYS.put("GridServerCacheMixin", "betterP2P");
        KEYS.put("P2PCacheAccessor", "betterP2P");
        // mixins.ae2opti.draconic.json / mixins.ae2opti.packageddraconic.json（その Mod があるときだけ読まれる）
        KEYS.put("TileCraftingInjectorMixin", "draconicEvolution");
        KEYS.put("TileEnergyPylonMixin", "draconicEvolution");
        KEYS.put("EnergyStorageMarkedInjectorMixin", "packagedDraconic");
    }

    /** 項目名 → オンかどうか。onLoad で一度だけ読む。 */
    private final Map<String, Boolean> enabled = new HashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        final File file = new File(new File(Launch.minecraftHome, "config"), CONFIG_NAME + ".cfg");
        if (!file.isFile()) {
            // 初回起動など。全部デフォルト（オン）。
            return;
        }
        try {
            // Configuration は存在しないファイルを作ってしまうので、上で存在を確かめてから読む
            final Configuration cfg = new Configuration(file);
            if (!cfg.hasCategory(CATEGORY)) {
                return;
            }
            final ConfigCategory cat = cfg.getCategory(CATEGORY);
            for (String key : KEYS.values()) {
                final Property p = cat.get(key);
                if (p != null && p.isBooleanValue()) {
                    this.enabled.put(key, p.getBoolean(true));
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not read the 'mixins' category of {}; all mixins stay enabled.", file, e);
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        final String simpleName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        final String key = KEYS.get(simpleName);
        if (key == null) {
            return true;
        }
        final boolean apply = this.enabled.getOrDefault(key, Boolean.TRUE);
        if (!apply) {
            LOG.info("Mixin {} is disabled in the config (mixins.{}=false); not applying it to {}.",
                    simpleName, key, targetClassName);
        }
        return apply;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return Collections.emptyList();
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
