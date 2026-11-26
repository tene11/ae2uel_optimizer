package ae2opti.command;

import ae2opti.AE2Opti;
import ae2opti.AE2OptiConfig;
import ae2opti.debug.FiberCrosstalkDetector;
import ae2opti.energy.EnergyLinkChecker;
import ae2opti.ghost.GhostNodeChecker;
import ae2opti.ghost.GhostNodeGuard;
import appeng.api.networking.IGridHost;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * {@code /ae2opti} コマンド。
 *
 * <ul>
 *   <li>{@code /ae2opti fibercheck}：今ある全部の網を調べ、内側ノードと外側ノードが同じ網に入っている
 *       石英ファイバー（＝合体が続いている状態）を一覧にする。設定 {@code debug.fiberCrosstalk} が true のときだけ動く。
 *       読むだけで、網の状態は変えない。</li>
 *   <li>{@code /ae2opti ghostcheck}：今ある全部の網のノードを調べ、持ち主のタイルがもうワールドに無い
 *       幽霊ノード（1-H）を一覧にする。設定に関係なくいつでも使える。読むだけで、網の状態は変えない。</li>
 *   <li>{@code /ae2opti energylinks}：石英ファイバーでつながった網のまとまりと、網ごとのファイバーの一覧に
 *       同じファイバーが余分に入っていないか（重複・古い登録・登録漏れ）を調べる。前回の確認からの、
 *       1-B の一覧の作り直し回数も出す。設定に関係なくいつでも使える。読むだけで、網の状態は変えない。</li>
 * </ul>
 */
public class CommandAE2Opti extends CommandBase {

    private static final String SUB_FIBERCHECK = "fibercheck";
    private static final String SUB_GHOSTCHECK = "ghostcheck";
    private static final String SUB_ENERGYLINKS = "energylinks";

    /** チャットに出す件数の上限（全件はサーバーログに出す） */
    private static final int MAX_CHAT_ENTRIES = 10;

    @Override
    public String getName() {
        return "ae2opti";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "commands.ae2opti.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1 && SUB_FIBERCHECK.equals(args[0])) {
            fiberCheck(sender);
            return;
        }
        if (args.length == 1 && SUB_GHOSTCHECK.equals(args[0])) {
            ghostCheck(sender);
            return;
        }
        if (args.length == 1 && SUB_ENERGYLINKS.equals(args[0])) {
            energyLinks(server, sender);
            return;
        }
        throw new WrongUsageException(this.getUsage(sender));
    }

    private void fiberCheck(ICommandSender sender) {
        if (!AE2OptiConfig.debug.fiberCrosstalk) {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.fibercheck.disabled"),
                    TextFormatting.YELLOW));
            return;
        }

        final FiberCrosstalkDetector.CheckResult r = FiberCrosstalkDetector.checkAll();
        final int found = r.crosstalks.size();

        sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.fibercheck.result",
                r.grids, r.fibers, found), found == 0 ? TextFormatting.GREEN : TextFormatting.RED));

        for (int i = 0; i < found; i++) {
            final FiberCrosstalkDetector.Crosstalk c = r.crosstalks.get(i);
            final String where = c.describeFiber();
            final int gridNodes = c.grid.getNodes().size();
            AE2Opti.LOGGER.warn("[1-E fiber crosstalk] fibercheck: fiber at {} has its inner and outer node on the same grid (grid nodes: {}).",
                    where, gridNodes);
            if (i < MAX_CHAT_ENTRIES) {
                sender.sendMessage(new TextComponentTranslation("commands.ae2opti.fibercheck.entry", where, gridNodes));
            }
        }
        if (found > MAX_CHAT_ENTRIES) {
            sender.sendMessage(new TextComponentTranslation("commands.ae2opti.fibercheck.more", found - MAX_CHAT_ENTRIES));
        }
    }

    private void ghostCheck(ICommandSender sender) {
        final GhostNodeChecker.CheckResult r = GhostNodeChecker.checkAll();
        final int found = r.ghosts.size();

        sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.ghostcheck.result",
                r.grids, r.nodes, found), found == 0 ? TextFormatting.GREEN : TextFormatting.RED));

        for (int i = 0; i < found; i++) {
            final GhostNodeChecker.Ghost g = r.ghosts.get(i);
            final String where = GhostNodeGuard.describe(g.tile);
            final String owner = g.describeOwner();
            AE2Opti.LOGGER.warn("[1-H ghost node] ghostcheck: {} at {} is still on a grid ({} nodes) but its tile is gone: {}.",
                    owner, where, g.gridNodes, g.reason.name());
            if (i < MAX_CHAT_ENTRIES) {
                sender.sendMessage(new TextComponentTranslation("commands.ae2opti.ghostcheck.entry", owner, where,
                        new TextComponentTranslation("commands.ae2opti.ghostcheck.reason." + g.reason.key), g.gridNodes));
            }
        }
        if (found > MAX_CHAT_ENTRIES) {
            sender.sendMessage(new TextComponentTranslation("commands.ae2opti.ghostcheck.more", found - MAX_CHAT_ENTRIES));
        }
        if (r.unknownOwner > 0) {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.ghostcheck.unknown", r.unknownOwner),
                    TextFormatting.GRAY));
        }
        sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.ghostcheck.prevented",
                GhostNodeGuard.getSkippedTotal()), TextFormatting.GRAY));
    }

    private void energyLinks(MinecraftServer server, ICommandSender sender) {
        final EnergyLinkChecker.CheckResult r = EnergyLinkChecker.checkAll(server);
        final long problems = r.duplicated + r.stale + r.missing;

        sender.sendMessage(new TextComponentTranslation("commands.ae2opti.energylinks.groups",
                r.grids, r.linkedGrids, r.groups, r.largestGroup));
        if (r.unsupportedGroups > 0) {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.unsupported",
                    r.unsupportedGroups), TextFormatting.YELLOW));
        }
        if (r.orphanGrids > 0) {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.orphans",
                    r.orphanGrids), TextFormatting.RED));
            AE2Opti.LOGGER.warn("[1-B energy links] energylinks: {} energy grid(s) are reachable through quartz fibers but are not in AE2's grid list.",
                    r.orphanGrids);
        }
        sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.entries",
                r.entries, r.distinct, r.duplicated, r.stale, r.missing),
                problems == 0 ? TextFormatting.GREEN : TextFormatting.RED));

        final int found = r.anomalies.size();
        for (int i = 0; i < found; i++) {
            final EnergyLinkChecker.Anomaly a = r.anomalies.get(i);
            final String where = a.provider instanceof IGridHost
                    ? FiberCrosstalkDetector.describeHost((IGridHost) a.provider)
                    : "[" + a.provider.getClass().getSimpleName() + "]";
            AE2Opti.LOGGER.warn("[1-B energy links] energylinks: {} is registered {} time(s) on a grid with {} nodes, expected {}.",
                    where, a.count, a.gridNodes, a.expected);
            if (i < MAX_CHAT_ENTRIES) {
                sender.sendMessage(new TextComponentTranslation("commands.ae2opti.energylinks.entry",
                        where, a.gridNodes, a.count, a.expected));
            }
        }
        if (found > MAX_CHAT_ENTRIES) {
            sender.sendMessage(new TextComponentTranslation("commands.ae2opti.energylinks.more", found - MAX_CHAT_ENTRIES));
        }

        if (r.ticks < 0) {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.rebuilds.first",
                    r.rebuilds, r.rebuildVisited, r.versionBumps), TextFormatting.GRAY));
        } else {
            sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.rebuilds",
                    r.ticks, r.rebuilds, r.rebuildVisited, r.versionBumps), TextFormatting.GRAY));
        }
        sender.sendMessage(colored(new TextComponentTranslation("commands.ae2opti.energylinks.timing",
                micros(r.rebuildNanos, r.rebuilds), r.sorts, micros(r.sortNanos, r.sorts),
                r.ticks > 0 ? micros(r.rebuildNanos + r.sortNanos, r.ticks) : "-"), TextFormatting.GRAY));
        AE2Opti.LOGGER.info("[1-B energy links] energylinks: grids={}, linked={}, groups={}, largest={}, unsupported={}, orphans={}, entries={}, distinct={}, duplicated={}, stale={}, missing={}, ticks={}, rebuilds={}, visited={}, rebuildNanos={}, sorts={}, sortNanos={}, versionBumps={}",
                r.grids, r.linkedGrids, r.groups, r.largestGroup, r.unsupportedGroups, r.orphanGrids,
                r.entries, r.distinct, r.duplicated, r.stale, r.missing, r.ticks, r.rebuilds, r.rebuildVisited,
                r.rebuildNanos, r.sorts, r.sortNanos, r.versionBumps);
    }

    /** 合計ナノ秒 ÷ 回数 を、マイクロ秒（小数1桁）の文字列にする。回数が 0 なら "-" */
    private static String micros(long totalNanos, long count) {
        if (count <= 0) {
            return "-";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", totalNanos / 1000.0 / count);
    }

    private static TextComponentTranslation colored(TextComponentTranslation text, TextFormatting color) {
        text.getStyle().setColor(color);
        return text;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUB_FIBERCHECK, SUB_GHOSTCHECK, SUB_ENERGYLINKS);
        }
        return Collections.emptyList();
    }
}
