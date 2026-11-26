package ae2opti.ghost;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.hooks.TickHandler;
import appeng.me.Grid;
import appeng.parts.AEBasePart;
import net.minecraft.tileentity.TileEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 1-H：{@code /ae2opti ghostcheck} 用。今ある全部の網のノードを調べ、
 * 持ち主のタイルがもうワールドに無い（＝幽霊ノード）ものを一覧にする。読むだけで、網の状態は変えない。
 *
 * <p>ノードの持ち主（{@link IGridNode#getMachine()}）がタイルならそのタイル、
 * パーツ（{@link AEBasePart}）ならそれが付いているケーブルのタイルを {@link GhostNodeGuard#check} で調べる。
 * どちらでもない持ち主（他 Mod の独自実装など）は判定できないので、数だけ数えて飛ばす。</p>
 */
public final class GhostNodeChecker {

    private GhostNodeChecker() {
    }

    /** 見つかった幽霊ノード1件 */
    public static final class Ghost {
        public final IGridNode node;
        public final TileEntity tile;
        public final GhostNodeGuard.Reason reason;
        /** そのノードが入っている網のノード数 */
        public final int gridNodes;

        Ghost(IGridNode node, TileEntity tile, GhostNodeGuard.Reason reason, int gridNodes) {
            this.node = node;
            this.tile = tile;
            this.reason = reason;
            this.gridNodes = gridNodes;
        }

        /** 持ち主の種類（タイルかパーツのクラス名） */
        public String describeOwner() {
            final IGridHost host = this.node.getMachine();
            return host != null ? host.getClass().getSimpleName() : this.tile.getClass().getSimpleName();
        }
    }

    /** 調べた結果 */
    public static final class CheckResult {
        public int grids;
        public int nodes;
        /** 持ち主のタイルが分からず判定できなかったノードの数 */
        public int unknownOwner;
        public final List<Ghost> ghosts = new ArrayList<>();
    }

    public static CheckResult checkAll() {
        final CheckResult r = new CheckResult();
        for (Grid grid : TickHandler.INSTANCE.getGridList()) {
            r.grids++;
            final int gridNodes = grid.getNodes().size();
            for (IGridNode node : grid.getNodes()) {
                r.nodes++;
                final TileEntity te = ownerTile(node);
                if (te == null) {
                    r.unknownOwner++;
                    continue;
                }
                final GhostNodeGuard.Reason reason = GhostNodeGuard.check(te);
                if (reason != null) {
                    r.ghosts.add(new Ghost(node, te, reason, gridNodes));
                }
            }
        }
        return r;
    }

    @Nullable
    private static TileEntity ownerTile(IGridNode node) {
        final IGridHost host = node.getMachine();
        if (host instanceof TileEntity) {
            return (TileEntity) host;
        }
        if (host instanceof AEBasePart) {
            return ((AEBasePart) host).getTile();
        }
        return null;
    }
}
