package ae2opti;

/**
 * CraftingGridCache に mixin で実装させる「ME網1つぶんの、このtickの使用時間」の入れ物。
 * mixin パッケージの外に置くこと。
 */
public interface CraftingBudgetHolder {
    long ae2opti$getUsedNanos();

    void ae2opti$addUsedNanos(long nanos);
}
