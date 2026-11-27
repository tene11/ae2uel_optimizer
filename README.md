# AE2UEL Optimizer

Performance and bug fixes for **AE2 Unofficial Extended Life** (Minecraft 1.12.2).

[English](#english) | [日本語](#日本語)

---

## English

### What it does

AE2UEL Optimizer optimizes and fixes how AE2UEL runs. **Every feature is on by default** and can be turned off one by one in `config/ae2uel_optimizer.cfg`.
Debug tools are off by default.

**Energy grid (quartz fibers / subnets)**
Reuses the list of networks linked through quartz fibers and skips a useless scan on every power inject/extract (`Collection.removeIf`), so networks with many subnets no longer get heavy.

**Crafting CPUs**
- Shares the per-network time budget evenly, so one CPU no longer always ends up last and slow.
- Remembers busy interfaces and Packagers for the rest of the tick, and skips the ingredient check for patterns whose targets are all busy.
- Updates the CPU status screen less often only while the server is under heavy load (normally every tick).

**FE P2P tunnels**
- Caches the block behind each output, skips outputs that are already full, and reuses the output list within a tick.
- Collects power for the ME Controller and Energy Acceptor and injects it once per tick.
- Collects the tunnel tax once per tick. By default the tax is the same as AE2; a multiplier and a per-tick cap can be set in the config.
- Moves FE as `long` inside the tunnel, so more than ~2.1 billion FE can pass at once. Uses `long` directly with Draconic Evolution, PackagedDraconic and Flux Networks.

**Bug fixes**
- "Ghost nodes": tiles from a chunk or dimension that was unloaded before AE2 finished setting them up stayed on the network forever. This could make a subnet connect to the main network, and items could leak into a subnet's storage bus.
- FE P2P: a loop-back connection could break the distribution, and `getEnergyStored` could overflow to a negative number.
- Betterer P2P: a new link could take over an unrelated P2P with the same frequency, unknown P2P types could crash, and every relink rebuilt the network.

**Commands** (OP only, read-only)
- `/ae2opti ghostcheck` — lists ghost nodes on all networks.
- `/ae2opti energylinks` — shows networks linked through quartz fibers and timing.
- `/ae2opti fibercheck` — checks for subnets merged into another network (with `debug.fiberCrosstalk`).

### Results

Measured with flare (spark) on a late-game Nomi-CEu world, Cleanroom 0.7.14, singleplayer, same world, only AE2UEL Optimizer added or removed.

**Heavy autocrafting**

| | With Optimizer | Without |
|---|---|---|
| TPS (measured) | **20.0** | 10.7 |
| Real time per tick | **29.3 ms** | 92.4 ms |
| AE2 total (`appeng.me`) | **5.1 ms/tick** | 63.5 ms/tick |
| Crafting CPU (`executeCrafting`) | **1.9 ms/tick** | 50.3 ms/tick |
| FE P2P + energy input | **0.8 ms/tick** | 14.4 ms/tick |

**Normal autocrafting**: AE2 time goes down to about 1/4–1/5 (for example 17.8 → 4.4 ms/tick). Crafting takes the same time as before.

### Requirements and compatibility

- **Required**: AE2 Unofficial Extended Life (tested with v0.56.6 / v0.56.7), MixinBooter
- Install on **both client and server**.
- **Works with AE2UEL add-ons in general.** The table below lists the ones with special handling or notes.
- Uses `@Inject` / `@WrapOperation` only (no `@Redirect` / `@Overwrite`), so it can stack with other mods that change the same code.
- Every mixin can be disabled in the `mixins` section of the config (needs a restart), in case one fails to apply with another mod.

| Mod | Status |
|---|---|
| Betterer P2P | Fixes are applied when installed |
| Draconic Evolution, PackagedDraconic, Flux Networks | `long` FE transfer supported |
| AE2Enhanced | Works. `crafting.skipCraftCheckWhenAllBusy` turns itself off |
| CrazyAE | CPU-related errors may occur |

[AE2-VM:Legacy](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy), which optimizes crafting calculations, is recommended alongside this mod.

If you find a problem, please open an issue with your log.

### Thanks

- **[AE2 Crafting Optimizer (ACO)](https://github.com/syarukasu/ae2-crafting-optimizer)** by syarukasu — the design of this mod owes a lot to ACO. Many thanks to syarukasu, and to the developers of the original mods.
- The AE2 Unofficial Extended Life team, and the authors of the add-ons this mod works with.

### Development

This mod was developed with a lot of help from Anthropic's AI assistant **Claude**.

### License

LGPL-3.0. See [LICENSE](LICENSE).

> English is not my first language, so I wrote the English text with help from AI.

---

## 日本語

### 機能

AE2UEL Optimizer は、AE2UELの動作を最適化、修正するmodです。**機能はすべてデフォルトでオン**です。`config/ae2uel_optimizer.cfg` から1つずつオフにできます。
デバッグ用の機能はデフォルトでオフです。

**電力まわり（石英ファイバー・サブネット）**
石英ファイバーでつながった網の一覧を使い回し、電力の出し入れのたびに走っていた無駄な走査（`Collection.removeIf`）も省いて、サブネットが大量にある環境で負荷が高くなるのを防ぎます。

**クラフト CPU**
- 網ごとの時間の予算を均等に分けます。特定の CPU だけずっと後回しで遅くなる不具合を直しました。
- 埋まっているインターフェースや Packager を tick の間覚えておき、送り先が全部埋まっているパターンは材料チェックを省きます。
- CPU の状況画面は、重いときだけ更新の間隔をあけます（普段は毎 tick）。

**FE P2P**
- 出口の先のブロックを覚えておく、満タンの出口を飛ばす、出口の一覧を tick の間使い回します。
- コントローラーと Energy Acceptor に届いた電力をまとめて、tick に1回だけ網に入れます。
- トンネル税を tick ごとにまとめて取ります。税の量はデフォルトでは元の AE2 と同じです。設定で倍率と1tickあたりの上限を変更できます。
- P2P の中を `long` で扱えるようになります。1回で約21億 FE を超えて運べます。Draconic Evolution・PackagedDraconic・Flux Networks は `long` のまま受け渡します。

**不具合修正**
- 「幽霊ノード」：AE2 が準備を終える前にアンロードされたチャンクやディメンションのタイルが、網に残り続けていました。サブネットがメインネットとつながり、ストレージバスにアイテムが混ざる原因になっていたと考えられます。
- FE P2P：ループ配線で配分が壊れる、`getEnergyStored` があふれてマイナスになる。
- Betterer P2P：同じ周波数の無関係な P2P を乗っ取る、知らない種類の P2P で落ちる、つなぎ直すたびに網が作り直される。

**コマンド**（OP 権限、読むだけ）
- `/ae2opti ghostcheck` — 全部の網から幽霊ノードを探して一覧にします。
- `/ae2opti energylinks` — 石英ファイバーでつながった網と、処理時間を表示します。
- `/ae2opti fibercheck` — サブネットが別の網と混ざっていないか調べます（`debug.fiberCrosstalk` と一緒に使います）。

### 効果

Nomi-CEu最終盤、Cleanroom 0.7.14、シングルプレイで、同じワールドでAE2UEL Optimizerの有無だけを変更しflare (spark) 計測。

**大量クラフト中**

| | あり | なし |
|---|---|---|
| 実測 TPS | **20.0** | 10.7 |
| 1tick の実処理時間 | **29.3 ms** | 92.4 ms |
| AE2 全体（`appeng.me`） | **5.1 ms/tick** | 63.5 ms/tick |
| クラフト CPU（`executeCrafting`） | **1.9 ms/tick** | 50.3 ms/tick |
| FE P2P・電力の受け入れ | **0.8 ms/tick** | 14.4 ms/tick |

**通常のクラフト中**：AE2 の処理時間は 1/4〜1/5 になります（例：17.8 → 4.4 ms/tick）。クラフト時間は変わらず行えます。

### 必要なもの・互換性

- **必須**：AE2 Unofficial Extended Life（v0.56.6 / v0.56.7 で確認）、MixinBooter
- **クライアントとサーバーの両方**に入れてください。
- **AE2UEL のアドオンとは、基本的に一緒に使えます。** 下の表は、個別に対応しているものや注意があるものです。
- `@Inject` と `@WrapOperation` だけを使い、`@Redirect` と `@Overwrite` は使っていません。同じ場所を書き換える他の Mod とも重ねがけできます。
- 他の Mod とぶつかって mixin の適用に失敗したときのために、設定の `mixins` カテゴリで mixin ごと読み込まないようにできます（再起動が必要）。

| Mod | 状態 |
|---|---|
| Betterer P2P | 導入時修正を当てる |
| Draconic Evolution・PackagedDraconic・Flux Networks | `long` での受け渡しに対応 |
| AE2Enhanced | 併用可。`crafting.skipCraftCheckWhenAllBusy` は自動でオフになる |
| CrazyAE | CPU絡みのエラーが出る場合あり |

クラフトの際の計算を最適化するmod [AE2-VM:Legacy](https://www.curseforge.com/minecraft/mc-mods/ae2-vm-legacy) との併用を推奨します。

不具合を見つけたら、ログを付けて Issue で教えてください。

### 謝辞

- **[AE2 Crafting Optimizer（ACO）](https://github.com/syarukasu/ae2-crafting-optimizer)**（syarukasu氏作）— この Mod の設計の考え方は ACO を大いに参考にしています。ACOの開発者のsyarukasu氏、また元になったmodの開発者のみなさんに感謝を述べます。
- AE2 Unofficial Extended Life の開発チームと、この Mod と一緒に動くアドオンの作者のみなさん。

### 開発について

この Mod の開発には、Anthropic の AI アシスタント **Claude** の力を大きく借りました。

### ライセンス

LGPL-3.0。[LICENSE](LICENSE) を見てください。
