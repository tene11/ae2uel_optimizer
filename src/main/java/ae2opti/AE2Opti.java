package ae2opti;

import ae2opti.command.CommandAE2Opti;
import ae2opti.debug.FiberCrosstalkDetector;
import ae2opti.ghost.GhostNodeGuard;
import ae2opti.p2p.P2PTickHandler;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION,
        dependencies = "required-after:appliedenergistics2;required-after:mixinbooter;after:fluxnetworks")
public class AE2Opti {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} loaded", Tags.MOD_NAME);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // Flux Networks が受け渡し方法を登録し終わった後に、P2P 用の long の受け渡し方法を先頭に入れる。
        // FluxNetworksCompat は Flux Networks のクラスを読み込むので、無いときは触らない。
        if (Loader.isModLoaded("fluxnetworks") && AE2OptiConfig.p2p.fluxNetworksLongInput) {
            ae2opti.compat.fluxnetworks.FluxNetworksCompat.register();
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAE2Opti());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        FiberCrosstalkDetector.reset();
        GhostNodeGuard.reset();
        P2PTickHandler.clear();
    }
}
