package ae2opti;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * config/ae2uel_optimizer.cfg の中身（ファイル名は {@link AE2OptiMixinPlugin#CONFIG_NAME}）。
 *
 * category = "" にしているので、下の static フィールド（energy / crafting / p2p / grid / debug / mixins）が
 * そのまま cfg の一番上のカテゴリになる（Forge がフィールド名を小文字にして使う）。
 * カテゴリの中の項目は、各クラスの public な「static でない」フィールド。
 *
 * <p>方針（2026-11-26 変更）：軽量化・不具合修正はすべてデフォルトでオン。デバッグ用のものだけデフォルトでオフ。
 * 結果が元の AE2UEL と少し変わるものは、コメントにそう書いておき、困ったときに設定で切れるようにする。</p>
 *
 * <p>{@link Mixins} だけは起動の早い段階で {@link AE2OptiMixinPlugin} が cfg を直接読む。
 * そのため、項目名を変えるときは {@link AE2OptiMixinPlugin} の対応表も一緒に変えること。</p>
 */
@Config(modid = Tags.MOD_ID, name = AE2OptiMixinPlugin.CONFIG_NAME, category = "")
public class AE2OptiConfig {

    @Config.Comment("Energy grid (EnergyGridCache) optimizations.")
    public static final Energy energy = new Energy();

    @Config.Comment("Crafting CPU optimizations.")
    public static final Crafting crafting = new Crafting();

    @Config.Comment("P2P tunnel optimizations and Better P2P fixes.")
    public static final P2P p2p = new P2P();

    @Config.Comment("ME network (grid) bug fixes.")
    public static final GridFixes grid = new GridFixes();

    @Config.Comment("Debug tools. Leave these off for normal play.")
    public static final Debug debug = new Debug();

    @Config.Comment({
            "Turn whole mixins on or off. Requires a game restart.",
            "Use this when a mixin conflicts with another mod (for example a mixin apply error in the log at startup).",
            "When a mixin is off, the settings in the other categories that depend on it do nothing."
    })
    @Config.RequiresMcRestart
    public static final Mixins mixins = new Mixins();

    public static class Energy {
        @Config.Comment({
                "Skip the PriorityQueue/HashSet search in extractAEPower/injectPower when the grid",
                "is not linked to any other energy grid. Same result as vanilla AE2UEL.",
                "When the grid is linked to other grids (for example through quartz fibers), injectPower,",
                "extractAEPower and getEnergyDemand try the grid's own storage first, and only search the",
                "linked grids when that was not enough. Same result as vanilla AE2UEL.",
                "Needs mixins.energyGridCache."
        })
        public boolean fastPath = true;

        @Config.Comment({
                "[1-B] When the grid's own storage was not enough and the grid is linked to other grids through",
                "quartz fibers, use a list of the linked grids that is built once per tick (and rebuilt as soon as",
                "a quartz fiber joins or leaves any grid), instead of walking the fibers with a PriorityQueue on",
                "every call. The list is sorted once per call: lowest charge first for injection and demand,",
                "most stored first for extraction. Each grid's stored power is read only once per call.",
                "The total amount moved is the same as vanilla AE2UEL, but which linked grid gets (or gives)",
                "power first can differ. If anything other than a quartz fiber links the grids, the vanilla",
                "search is used instead. Needs mixins.energyGridCache."
        })
        public boolean linkedGridCache = true;

        @Config.Comment({
                "[1-B] With energy.linkedGridCache: sort the linked grids only once per tick instead of on every call,",
                "and skip, for the rest of the tick, a linked grid that accepted nothing (full) or gave nothing (empty).",
                "The mark is cleared as soon as power is taken out of (full) or put into (empty) that grid, or a node",
                "joins or leaves it. Saves reading every linked grid's stored power (which recounts all its cells)",
                "on every call. The order between linked grids uses their charge at the start of the tick.",
                "The total amount moved is the same as vanilla AE2UEL, unless a linked grid's storage is emptied",
                "in the middle of a tick without going through the ME network (it then takes power again next tick).",
                "Needs mixins.energyGridCache."
        })
        public boolean linkedGridTickOrder = true;

        @Config.Comment({
                "[1-B] With energy.linkedGridCache: rebuild the list of linked grids at least every this many ticks.",
                "The list is always rebuilt right away when a quartz fiber joins or leaves any network,",
                "so this is only a safety net. 1 = rebuild every tick (the old behavior).",
                "The order between linked grids and the full/empty marks are still renewed every tick."
        })
        @Config.RangeInt(min = 1, max = 200)
        public int linkedGridRebuildInterval = 5;

        @Config.Comment({
                "Skip EnergyGridCache's removeIf scan when nothing is waiting to be removed.",
                "Same result as vanilla AE2UEL; it only avoids iterating every power storage on each extract/inject.",
                "Needs mixins.energyGridCache."
        })
        public boolean skipEmptyRemoval = true;
    }

    public static class Crafting {
        @Config.Comment({
                "Limit how many patterns a crafting CPU may push per tick, based on measured time.",
                "Keeps TPS stable with huge co-processor counts, at the cost of slower crafting.",
                "Needs mixins.craftingCpuCluster."
        })
        public boolean throttleCpu = true;

        @Config.Comment("Target time (ms) one crafting CPU may spend in a single tick.")
        @Config.RangeInt(min = 1, max = 50)
        public int targetMillisPerCpu = 5;

        @Config.Comment("The throttle never goes below this many effective co-processors (or the CPU's real count, if smaller).")
        @Config.RangeInt(min = 0)
        public int minCoProcessors = 8;

        @Config.Comment({
                "Share a time budget between all crafting CPUs on the same ME network.",
                "The budget (sharedMillisPerGrid) is split evenly between the CPUs that are crafting.",
                "Time left over by CPUs that did not need their share goes to the CPUs that did.",
                "The share is decided from the previous tick, so the order AE2 visits the CPUs no longer matters",
                "(before, the same CPUs were always the ones slowed down).",
                "If a network still uses more than twice its budget in a tick, the remaining CPUs push only",
                "minCoProcessorsWhenGridBusy (+1) patterns, but never the same CPU two ticks in a row.",
                "Needs mixins.craftingCpuCluster and mixins.craftingGridCache."
        })
        public boolean sharedGridBudget = true;

        @Config.Comment("Total time (ms) all crafting CPUs of one ME network may spend in a single tick.")
        @Config.RangeInt(min = 1, max = 50)
        public int sharedMillisPerGrid = 10;

        @Config.Comment({
                "Lowest effective co-processors for a CPU whose speed is limited by its share of the network budget",
                "(used instead of minCoProcessors), and the limit for the over-budget emergency stop.",
                "0 = AE2's minimum (1 pattern)."
        })
        @Config.RangeInt(min = 0)
        public int minCoProcessorsWhenGridBusy = 0;

        @Config.Comment({
                "Remember crafting targets (interfaces, PackagedAuto machines, ...) that answered 'busy' for the rest of the tick,",
                "and do not ask them again from other patterns or CPUs on the same ME network.",
                "Only 'busy' answers are remembered. A target that frees up in the middle of a tick is used from the next tick.",
                "Needs mixins.craftingCpuCluster and mixins.craftingGridCache."
        })
        public boolean cacheBusyMediums = true;

        @Config.Comment({
                "Skip the material check (canCraft) of a pattern when every target for it is already known to be busy this tick.",
                "Nothing could be pushed anyway, so the result is the same as vanilla AE2UEL.",
                "Needs crafting.cacheBusyMediums.",
                "Turned off automatically while AE2Enhanced is installed (it caches canCraft failures,",
                "and a skipped check could be taken as a material shortage)."
        })
        public boolean skipCraftCheckWhenAllBusy = true;

        @Config.Comment({
                "Crafting CPU status screens (the CPU screen and the Crafting Status screen) normally send changes every tick.",
                "With this on, they still update every tick, but after a heavy update (see cpuGuiSlowSendMicros)",
                "or while the server is busy (see cpuGuiSlowMspt) they wait cpuGuiSlowInterval ticks before the next one.",
                "Changes are collected while waiting, so nothing is lost; the numbers just update less often.",
                "Needs mixins.craftingCpuGui."
        })
        public boolean cpuGuiAdaptiveUpdate = true;

        @Config.Comment("Ticks between status screen updates while things are heavy. 1 = always every tick.")
        @Config.RangeInt(min = 1, max = 40)
        public int cpuGuiSlowInterval = 5;

        @Config.Comment({
                "Slow down when one status screen update costs at least this many microseconds per tick. 0 = ignore this check.",
                "(1000 microseconds = 1 ms; a whole tick is 50 ms.)"
        })
        @Config.RangeInt(min = 0)
        public int cpuGuiSlowSendMicros = 500;

        @Config.Comment("Slow down while the server's average MSPT (last 100 ticks) is at least this many ms. 0 = ignore this check.")
        @Config.RangeInt(min = 0, max = 1000)
        public int cpuGuiSlowMspt = 40;
    }

    public static class P2P {
        @Config.Comment({
                "[P-1] FE P2P outputs remember the block next to them for the rest of the tick,",
                "instead of looking it up (getTileEntity / hasCapability / getCapability) on every transfer.",
                "Forgotten when the tick changes or the block is broken or replaced.",
                "Same result as vanilla AE2UEL unless a mod changes its capability in the middle of a tick",
                "without replacing its tile entity. Needs mixins.p2pFePower."
        })
        public boolean cacheAttachedStorage = true;

        @Config.Comment({
                "[P-2] FE P2P inputs skip pointless calls in the second (overflow) pass.",
                "With p2p.newDistribution: an output that accepted nothing in the first pass is skipped in the second",
                "pass only if the second amount is not larger. Same result as vanilla AE2UEL.",
                "With p2p.newDistribution off: the whole second pass is skipped when no output accepted anything in",
                "the first pass. This differs from vanilla for receivers that refuse small amounts but accept large",
                "ones (for example GregTech converters). Needs mixins.p2pFePower."
        })
        public boolean skipEmptySecondPass = true;

        @Config.Comment({
                "Use ae2opti's own FE P2P distribution instead of AE2UEL's (long-based, same order and amounts).",
                "Needed for the long transfer and the settings below. Also fixes: re-entrant calls (loop wiring) mixing",
                "up the output queue, totals overflowing int, and returning more than was offered. Needs mixins.p2pFePower."
        })
        public boolean newDistribution = true;

        @Config.Comment({
                "[P-5] Reuse each input's list of outputs for the rest of the tick. Needs p2p.newDistribution."
        })
        public boolean cacheOutputs = true;

        @Config.Comment({
                "[P-3] Remember how much each output refused (accepted nothing of) in this tick, and skip it for",
                "the rest of the tick when offered the same or a smaller amount. Larger amounts are still offered.",
                "Fewer calls when outputs are full, but an output that empties later in the tick waits until the next tick.",
                "Needs p2p.newDistribution."
        })
        public boolean skipFullOutputs = true;

        @Config.Comment({
                "[long] Hand amounts above int to receivers that ae2opti taught to accept long",
                "(Draconic Evolution injectors and pylons, PackagedDraconic marked injectors). Needs p2p.newDistribution."
        })
        public boolean longReceivers = true;

        @Config.Comment({
                "[long] For ordinary FE receivers, split amounts above int into int-sized calls until the receiver",
                "stops accepting. Receivers that limit each call (not each tick) may then charge faster than intended.",
                "Needs p2p.newDistribution."
        })
        public boolean splitIntTransfer = true;

        @Config.Comment("[long] At most this many int-sized calls per output per transfer when splitting.")
        @Config.RangeInt(min = 1)
        public int splitMaxCallsPerTransfer = 64;

        @Config.Comment({
                "[P-4] Buffer FE going from P2P outputs into AE2 controllers and energy acceptors, and inject it once",
                "at the end of the tick. Far fewer injectPower calls, but the network gets the power up to 1 tick late",
                "and the buffer is lost if the block is removed.",
                "Accepts only what the network can take at the start of the tick (asked once per tick with a simulated",
                "injection), so the tunnel tax is not paid on power the network cannot store. Needs p2p.newDistribution."
        })
        public boolean acceptorBuffer = true;

        @Config.Comment({
                "[P-4] Size of each controller / energy acceptor buffer, in FE.",
                "Also the most FE one controller / energy acceptor takes from P2P per tick."
        })
        @Config.RangeDouble(min = 1)
        public double acceptorBufferRF = 10000000.0;

        @Config.Comment({
                "Tunnel tax multiplier. AE2 charges 5% of the transferred FE (converted to AE) from the input's network.",
                "1.0 = same as AE2, 0 = no tax. Needs p2p.newDistribution."
        })
        @Config.RangeDouble(min = 0)
        public double tunnelTaxMultiplier = 1.0;

        @Config.Comment("Most tunnel tax (AE) one P2P input pays per tick. 0 = no limit (same as AE2). Needs p2p.newDistribution.")
        @Config.RangeDouble(min = 0)
        public double tunnelTaxCapPerTick = 0.0;

        @Config.Comment({
                "[P-6] Pay the tunnel tax once per input at the end of the tick instead of on every transfer.",
                "Same amount, only paid later in the tick. Needs p2p.newDistribution."
        })
        public boolean batchTunnelTax = true;

        @Config.Comment({
                "[long] Let Flux Networks hand long amounts to FE P2P inputs (instead of cutting them to int).",
                "Only used when Flux Networks is installed. Requires a game restart."
        })
        @Config.RequiresMcRestart
        public boolean fluxNetworksLongInput = true;

        @Config.Comment({
                "[long] Let Draconic Evolution pylons in output mode hand long amounts to an adjacent FE P2P input",
                "(instead of cutting the core's energy to int). Needs mixins.draconicEvolution."
        })
        public boolean draconicPylonLongOutput = true;

        @Config.Comment({
                "[Better P2P] Use AE2's frequency generator when Better P2P links tunnels.",
                "Fixes Better P2P picking a frequency that is already in use and turning an unrelated input into an output.",
                "Needs mixins.betterP2P."
        })
        public boolean betterP2PSafeFrequency = true;

        @Config.Comment({
                "[Better P2P] Refuse to link a tunnel type Better P2P does not know (for example one added by another addon)",
                "with a chat message, instead of throwing a NullPointerException. Needs mixins.betterP2P."
        })
        public boolean betterP2PUnknownTypeCheck = true;

        @Config.Comment({
                "[Better P2P] When only the frequency changes (input stays input, output stays output),",
                "update the tunnel in place like AE2's memory card does, instead of removing and re-adding the part.",
                "Avoids a network re-path for every link. ME and interface P2P tunnels always use Better P2P's original way.",
                "The tunnel part is kept instead of being re-created; turn this off if a link misbehaves. Needs mixins.betterP2P."
        })
        public boolean betterP2PInPlaceRelink = true;
    }

    public static class GridFixes {
        @Config.Comment({
                "[1-H] Do not connect a tile entity to an ME network if its chunk or dimension was unloaded",
                "before AE2 finished setting it up (it would become a 'ghost node' that stays on the network forever).",
                "AE2 connects newly loaded tiles at the end of the server tick; a dimension unloaded earlier in the same",
                "tick (for example a Compact Machines or Advanced Rocketry dimension) left its tiles in that queue.",
                "Only skips tiles that are no longer part of a loaded world. Use /ae2opti ghostcheck to look for",
                "ghost nodes that already exist. Needs mixins.tickHandler."
        })
        public boolean skipUnloadedTileInit = true;
    }

    public static class Debug {
        @Config.Comment({
                "[1-E] Detect quartz fiber crosstalk: log when a grid merge puts both the inner and the outer node",
                "of a quartz fiber on the same grid (a subnet joined the main network), with location and stack trace.",
                "Also enables /ae2opti fibercheck. Read-only; does not change AE2 behavior.",
                "The merge detection needs mixins.gridConnection (the command does not)."
        })
        public boolean fiberCrosstalk = false;

        @Config.Comment("[1-E] Also log the stack trace of the call that caused the merge.")
        public boolean fiberCrosstalkStackTrace = true;

        @Config.Comment("[1-E] At most this many detections are logged in detail per tick; the rest are only counted.")
        @Config.RangeInt(min = 1, max = 1000)
        public int fiberCrosstalkMaxReportsPerTick = 20;

        @Config.Comment("[1-E] Keep checking a detected crosstalk for this many ticks to log when it splits again.")
        @Config.RangeInt(min = 1, max = 72000)
        public int fiberCrosstalkTrackTicks = 200;

        @Config.Comment({
                "[1-H] Log each tile entity that grid.skipUnloadedTileInit kept from joining a network",
                "(class, location, reason; at most 20 per tick). A one-line count is always logged."
        })
        public boolean ghostNodeLog = false;
    }

    /**
     * mixin ごとのオンオフ。ここは {@link AE2OptiMixinPlugin} が起動時に cfg から直接読む
     * （この時点ではまだ Forge の @Config が読み込まれていないため）。
     * ゲーム中に変えても次の起動まで反映されない。
     */
    public static class Mixins {
        @Config.Comment("EnergyGridCache: energy.fastPath, energy.linkedGridCache and energy.skipEmptyRemoval.")
        @Config.RequiresMcRestart
        public boolean energyGridCache = true;

        @Config.Comment("CraftingCPUCluster: crafting.throttleCpu, crafting.sharedGridBudget, crafting.cacheBusyMediums and crafting.skipCraftCheckWhenAllBusy.")
        @Config.RequiresMcRestart
        public boolean craftingCpuCluster = true;

        @Config.Comment("CraftingGridCache: the per-network budget and busy-target memory used by crafting.sharedGridBudget and crafting.cacheBusyMediums.")
        @Config.RequiresMcRestart
        public boolean craftingGridCache = true;

        @Config.Comment("ContainerCraftingCPU: crafting.cpuGuiAdaptiveUpdate (status screen update interval).")
        @Config.RequiresMcRestart
        public boolean craftingCpuGui = true;

        @Config.Comment({
                "GridConnection: merge detection for debug.fiberCrosstalk.",
                "Does nothing while debug.fiberCrosstalk is off."
        })
        @Config.RequiresMcRestart
        public boolean gridConnection = true;

        @Config.Comment("TickHandler: grid.skipUnloadedTileInit.")
        @Config.RequiresMcRestart
        public boolean tickHandler = true;

        @Config.Comment("PartP2PFEPower: p2p.cacheAttachedStorage and p2p.skipEmptySecondPass.")
        @Config.RequiresMcRestart
        public boolean p2pFePower = true;

        @Config.Comment({
                "Better P2P's GridServerCache: the p2p.betterP2P* settings.",
                "Only loaded when Better P2P is installed."
        })
        @Config.RequiresMcRestart
        public boolean betterP2P = true;

        @Config.Comment({
                "Draconic Evolution injectors and pylons: long FE from P2P (p2p.longReceivers, p2p.draconicPylonLongOutput).",
                "Only loaded when Draconic Evolution is installed."
        })
        @Config.RequiresMcRestart
        public boolean draconicEvolution = true;

        @Config.Comment({
                "PackagedDraconic marked injectors: long FE from P2P (p2p.longReceivers).",
                "Only loaded when PackagedDraconic is installed."
        })
        @Config.RequiresMcRestart
        public boolean packagedDraconic = true;
    }

    @Mod.EventBusSubscriber(modid = Tags.MOD_ID)
    public static class Handler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent event) {
            if (Tags.MOD_ID.equals(event.getModID())) {
                ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
            }
        }
    }
}
