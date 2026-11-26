package ae2opti.command;

import ae2opti.AE2Opti;
import ae2opti.AE2OptiConfig;
import ae2opti.debug.FiberCrosstalkDetector;
import ae2opti.ghost.GhostNodeChecker;
import ae2opti.ghost.GhostNodeGuard;
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
 * </ul>
 */
public class CommandAE2Opti extends CommandBase {

    private static final String SUB_FIBERCHECK = "fibercheck";
    private static final String SUB_GHOSTCHECK = "ghostcheck";

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

    private static TextComponentTranslation colored(TextComponentTranslation text, TextFormatting color) {
        text.getStyle().setColor(color);
        return text;
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUB_FIBERCHECK, SUB_GHOSTCHECK);
        }
        return Collections.emptyList();
    }
}
