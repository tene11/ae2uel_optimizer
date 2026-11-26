package ae2opti;

import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;

import java.util.List;

/**
 * CraftingGridCache に mixin で実装させる「ME網1つぶんの、クラフトCPU用の情報」の入れ物。
 * mixin パッケージの外に置くこと。
 *
 * <ul>
 *   <li>このtickに網のCPUが使った合計時間（緊急停止の判定に使う）</li>
 *   <li>前のtickの集計（CPUごとの持ち時間＝均等割りの計算に使う）</li>
 *   <li>このtick「埋まっている」と分かった送り先（ICraftingMedium）の一覧</li>
 *   <li>パターン → 送り先の一覧の、同じオブジェクトかどうかで引くキャッシュ</li>
 * </ul>
 */
public interface CraftingBudgetHolder {

    /** このtick、この網のCPUが使った合計時間（ナノ秒） */
    long ae2opti$getUsedNanos();

    /** 1つのCPUの updateCraftingLogic にかかった時間を足す */
    void ae2opti$addUsedNanos(long nanos);

    /**
     * executeCrafting まで進んだCPUの結果を、このtickの集計に入れる。
     *
     * @param hungry 許された回数を使い切った（もっと送りたかった）なら true
     * @param nanos  かかった時間（ナノ秒）
     */
    void ae2opti$recordCpu(boolean hungry, long nanos);

    /**
     * 前のtickの集計から、このtickに1つのCPUが使ってよい時間を返す。
     * 予算を動いているCPUの数で均等に割り、使い切らなかったCPUの余りは、足りなかったCPUに回す。
     *
     * @param budgetNanos 網全体の予算（ナノ秒）
     */
    long ae2opti$getShareNanos(long budgetNanos);

    /** onUpdateTick の中（CPUを回している最中）なら true。送り先のキャッシュはこの間だけ使う */
    boolean ae2opti$inUpdateTick();

    /** このtick、すでに「埋まっている」と答えた送り先なら true */
    boolean ae2opti$isKnownBusy(Object medium);

    /** 「埋まっている」と答えた送り先を、このtickの間だけ覚える */
    void ae2opti$markBusy(Object medium);

    /**
     * {@code getMediums(details)} と同じ結果を、パターンのハッシュ計算をせずに返す。
     *
     * <p>元の {@code getMediums} はパターンを鍵にしたハッシュ表を引くので、そのたびにパターンの
     * hashCode / equals が走る（PackagedAuto のパターンなどは重い）。こちらは同じオブジェクトかどうかで引き、
     * パターンが組み直されたとき（recalculateCraftingPatterns）に空にする。</p>
     */
    List<ICraftingMedium> ae2opti$getMediumsCached(ICraftingPatternDetails details);
}
