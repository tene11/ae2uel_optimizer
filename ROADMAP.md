# AE2UEL Optimizer（ae2opti）ロードマップ

このファイルは、spark プロファイル（2026-11-25 計測）と AE2UEL v0.56.7 のソースを読んだ結果から、「次に何をやるか」をまとめたものです。

## 要約（まずここだけ読めば OK）

| 優先度 | テーマ | 問題 | やること |
|---|---|---|---|
| 1（不具合） | サブネットの混線・不安定 | ディメンション移動などのときに、まれにサブネットとメインネットが混ざる | **1-E** 合体した瞬間をログに残す → **1-H** アンロード済みのタイルが「幽霊ノード」になる穴をふさぐ（本命、実装済み・確認待ち）→ **1-F** ケーブル付け直し時の一瞬の合体を防ぐ |
| 1（負荷） | 石英ファイバーと電力処理（約14%） | ファイバーがあると近道が効かない。満タン時はつながった網を全部たどる | **1-A** 自分の網で足りたら即座に返す（結果は元と同じ）→ **1-B** つながった網の一覧を tick ごとにキャッシュ（本命、実装済み・確認待ち）→ 1-C は任意 |
| 1.5 | P2P FE（18.5%） | 満タンでも出口ごとに2回呼ぶ。毎回隣のブロックを探し直す | P-1 出口の先をキャッシュ、P-2 無駄な2周目を飛ばす、P-3 0を返した出口を飛ばす、**P-4 まとめて1回で流し込む** |
| 2 | クラフト CPU（10.5% → 2.28%） | `executeCrafting` が重い。同じ CPU だけ後回しになる | 均等割りで後回しを修正、busy 判定をキャッシュ、送り先が全部埋まっているときは材料チェックを省略（実装済み・確認待ち） |
| 3 | AE2FC の Fluid Level Maintainer（4.1%） | ストレージが更新されるたびにクラフトジョブを作り直す | 1tick に1回までにまとめる（AE2FC があるときだけ有効） |
| 4 | P2P を long 化（＋必要なら BigInteger） | FE は1回あたり `int`（約21億）が上限 | P2P の中を `long` にする。`long` 対応の API を持つ相手には `long` で渡し、それ以外は分割して渡す。貯める値だけ `HugeCount` 方式。Better P2P の不具合（周波数の衝突など）も opti 側の mixin で直す |
| 5（保留） | Lazy AE2 | Level Maintainer の GUI の不具合 | **Lazy AE2 Patch で対応**。足りない部分があれば追加で直す |

**進める順番**: 1-E → 1-H → 1-F → 1-A → 1-B → P-1〜P-4 → 優先度2 → 3 → 4

**共通ルール**: 他 Mod の mixin とぶつからないようにチェックします。`@Redirect` と `@Overwrite` は使わず、`@WrapOperation` と `@Inject` で書きます。

**参考にする Mod**: ACO（設計の考え方）、AE2Enhanced（1.12.2 での `HugeCount` と `long` の受け渡し）、CrazyAE（1.12.2 での mixin の差し込み場所）。opti は LGPL で公開するので、GPL の Mod は設計の参考だけにします。

---

## 再計測（2026-11-26、1-A・1-H・P2P 対策を入れた後）

- 条件: Server thread のみ、約216秒、4ms 間隔。TPS 19.8、MSPT 平均 21.1ms（中央値 19.6ms、最大 393ms）。待ち時間（sleep）が全体の 42.6%。
- 前回と時間帯・クラフトの稼働状況が違うので、クラフトと AE2FC の数字は比べられない。電力まわりは比べてよい。

| 場所 | 全体に対する割合 | 前回 |
|---|---|---|
| `TickHandler.onTick`（AE2 の網の処理全部） | 4.6% | — |
| `EnergyGridCache.injectPower` | 1.46%（うち満タン時の探索の `PriorityQueue.add` が 0.38%） | 14.0% |
| `EnergyGridCache.extractAEPower` | 0.28%（`PriorityQueue.add` は消えた） | 2.8% |
| `CraftingCPUCluster.executeCrafting` | 2.28% | 10.5% |
| AE2FC `TileFluidLevelMaintainer` | 0.20% | 4.1% |
| P2P FE（受け取り＋tick 終わりの処理） | 0.84% ＋ 0.50% | 18.5% |

- 自分の処理時間（self）で見ると、Minecraft 16.9%、GregTech 7.9%、AE2UEL 3.7%、Ender IO 3.1%。AE2 はもう大きな原因ではない。
- 残りの `PriorityQueue.add`（0.38%）は、注入側で網が満タンのときにつながった網をたどる部分。1-B で消せるが、効果は小さい。


- 環境: Cleanroom 0.7.14 / 1.12.2、クライアントの内蔵サーバー、1人プレイ
- 範囲: Server thread のみ、約64秒、サンプリング間隔 4ms
- サーバーの状態: TPS 19.7、MSPT の平均 37.6ms、最大 458ms、タイルエンティティ約13,000
- Mod 別の占有率: AE2UEL 44%、Ender IO 23%、GregTech 22%、Solar Flux 10%
  - 呼び出しが重なるので、合計は100%を超えます。

| 場所 | 割合 | 備考 |
|---|---|---|
| `EnergyGridCache.injectPower` | 14.0% | 本来の注入処理 `injectProviderPower` は 0.1% だけ |
| `CraftingCPUCluster.executeCrafting` | 10.5% | |
| AE2FC `TileFluidLevelMaintainer` | 4.1% | その中で `CraftingJob` の生成に 3.3% |
| `EnergyGridCache.extractAEPower` | 2.8% | その中で `PriorityQueue.add` に 2.2% |
| AE2 UEL Things のディスクセル | 0.05% | 問題なし |

---

## 共通ルール：他 Mod の mixin とぶつからないようにチェックする

どの項目を実装するときも、**同じクラス・同じメソッドを他の Mod が書き換えていないか**を先に確認します。

**書き方のルール**
- `@Redirect` と `@Overwrite` は使いません。他の Mod と同じ場所を触ると、どちらかが失敗するためです。
- 代わりに、重ねがけできる `@WrapOperation`、`@ModifyExpressionValue`、`@Inject` を使います。
- 他の Mod が入っているときだけ必要な mixin は、後読み（MixinBooter の `ILateMixinLoader`）にして、その Mod がなければ読み込まないようにします。

**チェックのしかた**
- [ ] 実装前に、対象クラスを触っている Mod のソース（または jar の mixin 設定）を確認します。
- [ ] テスト環境（Modパック一式）で起動し、ログに mixin の競合や適用失敗（`InvalidInjectionException`、`@Redirect conflict` など）が出ていないか確認します。
- [ ] 競合しそうな Mod を入れた状態と外した状態の両方で、動作を確認します。

**今わかっている重なり**（確認済みのもの）

| 対象 | 触っている Mod | 内容 | 関係する項目 |
|---|---|---|---|
| `CraftingCPUCluster.executeCrafting` | CrazyAE | `@Redirect` ×3（`isValidItemForSlot`、`InventoryCrafting` の生成、`Platform.getContainerItem`） | 優先度2 |
| `CraftingCPUCluster.executeCrafting` | NAE2 | `pushPattern` を包んでいる（`wrapOperation`） | 優先度2 |
| `CraftingCPUCluster.addTile` | CrazyAE | `@Overwrite` | 優先度2 |
| `CraftingCPUCluster.accelerator` | CrazyAE | 最大 `Integer.MAX_VALUE` まで増やす（65536X アクセラレータ） | 既存の間引き処理 |
| `GridConnection.canSupportMoreChannels` | CrazyAE | `@Inject` | 1-E（触るのは `create` なので別の場所） |
| `GridServerCache`（Better P2P） | これから opti が触る | — | 優先度4 |
| Level Maintainer（Lazy AE2） | Lazy AE2 Patch | GUI の修正 | 優先度5 |

**まだ確認していないもの**
- AE2-VM（`shinyashen/AE2-VM-1.12`）が触っている場所。opti とは「併用推奨・ぶつからないようにする」方針です。
- その他のアドオン（AE2 Stuff Unofficial、PackagedAuto、AE2FC、ae2wtlib など）の mixin。

---

## 設定ファイル（config/ae2uel_optimizer.cfg）

- 方針（2026-11-26 変更）：軽量化・不具合修正の設定はすべてデフォルトでオン。デバッグ用（`debug` カテゴリ）だけオフ。結果が元と少し変わるものは設定のコメントにそう書く。

- [x] **カテゴリ分け** ※実装済み・実機での確認待ち（2026-11-25）
  - `energy` / `crafting` / `debug` / `mixins` の4カテゴリにしました（`AE2OptiConfig` の `category = ""` ＋カテゴリごとのクラス）。
  - 項目名が変わったので、古い cfg（`general` カテゴリ）は削除して作り直してください。
- [x] **mixin ごと読み込まない設定**（`mixins` カテゴリ、再起動が必要）※実装済み・実機での確認待ち
  - `AE2OptiMixinPlugin`（mixins.ae2opti.json の `"plugin"`）が、起動時に cfg を直接読んで、オフの mixin を適用しません。
  - 他の Mod と mixin がぶつかって起動時に適用に失敗する場合に、ユーザーが自分で避けられるようにするためです。
  - 外した mixin に頼っている設定は、何もしなくなります（コメントに「Needs mixins.xxx」と書いてあります）。
  - `// 要ローカル検証`: `mixins.energyGridCache=false` で起動し、ログに「Mixin EnergyGridCacheMixin is disabled in the config」が出て、ほかの mixin は通常どおり適用されること。
- **新しい項目を足すときの決まり**
  - 軽量化・不具合修正はデフォルトでオン（2026-11-26 変更）。デバッグ用だけデフォルトでオフ。結果が元と少しでも変わるものは、設定のコメントにそう書く。
  - 新しい mixin クラスを足したら、`AE2OptiConfig.Mixins` に項目を足し、`AE2OptiMixinPlugin.KEYS` にも対応を書く。

---

## 優先度1：石英ファイバー（Quartz Fiber）まわりの電力処理

### 原因

1. **近道（fast path）が効いていない**
   - `EnergyGridCacheMixin` の近道は、`energyGridProviders` が空のときだけ働きます。
   - v0.56.7 で `IEnergyGridProvider` を実装しているのは `PartQuartzFiber` だけです。
   - そのため、ファイバーが1本でもつながっている網では、近道が一度も使われません。
2. **自分の網だけで足りても、ファイバーを全部キューに積んでいる**
   - 元の処理は `this` を poll したあと、`leftover` の値を見る前に `this.providers()`（つまり全ファイバー）を `PriorityQueue` に追加します。
   - `extractAEPower` の 2.2% は、ほぼこのコストです。
3. **満タンのときは、つながった網を全部たどる**
   - 自分の網が満タンで `leftover > 0` になると、ファイバー → サブネット → そのファイバー…と、つながっている範囲を最後までたどります。
   - そのたびに、ファイバーの `providers()` が `new ArrayList` を作ります。
   - 呼ばれる回数は「Ender IO の出力 × P2P の出力数 × （シミュレート＋本番の2回）」なので、かなり多くなります。
   - **サブネットが多いほど、たどる範囲が広がって重くなります。** 「サブネットが多いと重い」の主な原因はこれだと考えられます。
4. **`getStoredPower()` が呼ばれるたびにセルを全部数え直す**
   - v0.56.7 では `getStoredPower()` が毎回 `refreshPower()` を呼び、全セルを走査します（旧版にあった90tickごとの制限は外れています）。
   - キューで網どうしを比べるたびに呼ばれるので、その分だけ全セル走査が発生します。profile では 1.8% でした。

### やること

- [x] **1-A：自分の網で先に試す近道**（結果は元と完全に同じ）※実装済み・実機での確認待ち
  - 注入側（`injectPower`）・取り出し側（`extractAEPower`）・需要の問い合わせ（`getEnergyDemand`）とも実装済み・実機での確認待ち（2026-11-26）。設定は `energy.fastPath`（既定オン）。
    - `run/mods` の NAE2・AE2Enhanced・AE2-VM は `EnergyGridCache` を mixin していない。CrazyAE は未確認。
    - `// 要ローカル検証`: spark で `extractAEPower` の中の `PriorityQueue.add`（2.2%）が消えていること。ファイバー越しのサブネットが今までどおり電力を受け取れること（サブネットが電力不足にならない）。
  - ファイバーがあっても、まず `injectProviderPower` / `extractProviderPower` を自分の網で実行します。
  - 注入なら `leftover <= 0`、取り出しなら `extracted >= toExtract` になった時点で、そのまま返します。
  - 結果が元と同じになる理由: 元の処理で次にキューへ入るのはファイバーだけです。ファイバーの比較は `getProviderStoredEnergy() == 0` なので副作用がありません。
  - 見込み: 取り出し側の 2.2% がほぼなくなります。注入側は、満タンでないときの分だけ減ります。
- [x] **1-B：つながった網の一覧を tick ごとにキャッシュする**（設定で切り替えられるようにする）※実装済み・実機での確認待ち（2026-11-26）
  - 実装: `energy/EnergyLinks`（一覧の作成・キャッシュ・並べ替えと注入／取り出し／需要）、`energy/EnergyLinkHolder`（EnergyGridCache に一覧を持たせる入れ物）、`mixin/EnergyGridCacheMixin`（`addNode` / `removeNode` の HEAD でバージョンを進める、1-A のあとの探索を差し替え）。設定は `energy.linkedGridCache`（既定オン）。
  - 一覧は、同じ一覧に入っている網すべてで同じオブジェクトを共有する（サブネットが N 個あっても、作り直しは 1tick に1回だけ）。tick はサーバーの `getTickCounter()` で見る。
  - 網（`EnergyGridCache`）と石英ファイバー（`PartQuartzFiber` そのもの。継承クラスは含めない）以外の `IEnergyGridProvider` がつながりの中にあったら「対応外」にして、1-A の探索（元と同じ手順）に戻る。
  - 並べるのは自分以外の網が2つ以上のときだけ。1つなら保有量も聞かない（`refreshPower` が走らない）。
  - `fastPath=false` でも `linkedGridCache=true` なら、自分の網を先に試す部分（1-A と同じ、結果は元と同じ）は動く。
  - 確認（開発環境の外で）: スタブを使ったシミュレーションで、ランダムなつながり方 20,000 通りについて、受け取った（取り出した）合計と、全部の網の保有量の合計が元の探索と一致した。
  - **実機の計測（2026-11-26、Nomi-CEu、約419秒、大量クラフト＋P2P が多く動いている時間帯）**
    - `injectPower` 6.3%（Energy Acceptor 4.3%・コントローラー 2.0% から）。内訳: `EnergyLinks.get` → `build`（一覧の作り直し）**2.6%**、`injectOthers` 3.7%（並べ替え 2.35%＝`refreshPower` 1.4%＋`sort` 0.9%、各網への注入 1.3%）。
    - AE2 の Energy Acceptor・コントローラーは FE を受け取るたびにすぐ `injectPower` するので、メインの網が満タンのあいだは呼ばれるたびに「ほかの網へ配る」処理まで進む。
    - `addNode` / `removeNode` はプロファイルに出ていない（ファイバーの出入りは多くない）のに、作り直しが 1tick あたり約1.3ms。中身はほぼ Multiset の数え上げと「たどった」記録で、ファイバーごとに1回呼ばれるはずの `PartQuartzFiber.providers()` は 0 サンプル。→ **網のファイバーの一覧（`energyGridProviders`）に同じファイバーが何重にも入っている**見込み。元の AE2 でも呼ばれるたびにこの重複をなめていることになる。
  - **追加（2026-11-26）**
    - 作り直しでは、網の `energyGridProviders` を重複を除いた `elementSet()` でたどる（何回入っていても行き先は同じなので結果は変わらない）。`EnergyLinkHolder.ae2opti$providerMultiset()` を追加。シミュレーション（重複ありのつながり方 20,000 通り）でも合計は元と一致。
    - 確認用コマンド `/ae2opti energylinks`（OP権限、設定に関係なく使える、読むだけ）: つながった網のまとまりの数と大きさ、網ごとのファイバーの登録回数を「その網にあるそのファイバーのノード数」と比べた余分・古い登録・登録漏れ、AE2 の網の一覧に無いのにファイバーの先にある網、前回の確認からの作り直し回数・たどった数・ファイバーの出入り回数。おかしいものは全部サーバーログに `[1-B energy links]` で出す。
    - `// 要ローカル検証`: Nomi-CEu で `/ae2opti energylinks` を2回（間を空けて）実行し、余分の回数と、1tick あたりの作り直し回数を見る。余分が多ければ、AE2 側の登録漏れの経路を探す（次の項目）。
  - **`/ae2opti energylinks` の結果（2026-11-26、Nomi-CEu）**: 網 143 個のうち 67 個が1つのまとまり（メイン＋サブネット 66 個）。ファイバーの登録 132 回・重複を除いて 132 本で、**余分・古い登録・登録漏れはすべて 0**（重複の見込みは外れ）。作り直しは 684 tick で 684 回（**1tick に1回、想定どおり**）、1回あたりたどる数は約 264。ファイバーの出入りは 0 回。
    - 264 項目なら数十 µs のはずなので、プロファイルの `build` 2.6% は spark の Java サンプラーの偏り（セーフポイントの近くに時間が寄る）で大きく見えていた可能性が高い。→ 下の計測時間で確かめる。
    - 本当に重いのは `injectOthers` の、呼ばれるたびに 66 個の網で `refreshPower` と並べ替えをする部分（2.35%）と考えられる。
  - **追加（2026-11-26 その2）**
    - **並べ替えを tick に1回に**（`energy.linkedGridTickOrder`、既定オン）: 並び順を tick で最初に必要になったときに1回だけ作り、一覧（Component）の中に持つ。注入で1も受け取らなかった網には「満タン」、取り出しで1も出さなかった網には「空」の印を付け、その tick の残りでは飛ばす。
      - 印を外す条件（`EnergyGridCacheMixin`）: 満タン → その網の `extractProviderPower`（MODULATE）で1以上出たとき。空 → その網の `injectProviderPower`（MODULATE）が呼ばれたとき（引数の `amt` が途中で書き換わるので HEAD で外す。外しすぎても1回余分に聞くだけ）。どちらも、網に `addNode` / `removeNode` があったとき。
      - 元と違うのは、tick の途中で充電率が変わっても並び順が tick の初めのままになることだけ。ME 網を通さずにセルの電力が減った場合だけ、その網には次の tick から入る。
      - シミュレーション（1tick に注入・取り出しを 30 回混ぜる × 5,000 通り、オン・オフ両方）で、毎回の結果と最後の合計が元の探索と一致。
    - **計測時間をコマンドに追加**: 作り直し1回・並べ替え1回の平均 µs と、1tick あたりの合計 µs（`System.nanoTime` で直接測るので spark の偏りが無い）。
    - `// 要ローカル検証`: `/ae2opti energylinks` を2回実行して時間を見る。spark を取り直して、`injectOthers` の中の `refreshPower` と `sort` が消えたか、`build` が本当に小さいかを確かめる。サブネットの機械が止まらないこと、メインを満タンにしたとき溢れた分がサブネットへ流れること。
  - **計測結果（2026-11-27、Nomi-CEu、1,429 tick）**: 作り直し 1,429 回（1tick に1回）・1回 146.7µs、並べ替え 1,429 回（1tick に1回）・1回 80.9µs、**1tick あたり 227.7µs**。
    - spark で 2.6%（1tick 約1.3ms）に見えた `build` は、直接測ると約0.15ms。spark の Java サンプラーの偏りで約9倍に見えていた。
    - 並べ替えは呼び出しごと → 1tick に1回になった（B が効いている）。
  - **追加（2026-11-27）作り直しの間隔**（`energy.linkedGridRebuildInterval`、既定 5、1〜200）: 一覧は、ファイバーの出入りがあればすぐ作り直すので、tick ごとの作り直しは保険だった。保険を「作ってから N tick たったら」にした。並び順と満タン・空の印は、一覧とは別に毎 tick 新しくする（Component に `nowTick`・`orderTick` を持たせ、`get` のたびに `nowTick` を入れる）。1 にすると前と同じ動き。
    - 見込み: 作り直しが平均 146.7 / 5 ≒ 29µs/tick、並べ替えと合わせて 1tick あたり約110µs。
    - シミュレーション: 100 tick で作り直しは間隔 1 → 100 回、5 → 20 回、20 → 5 回、並べ替えはどれも 100 回。tick を進めながら注入・取り出しを混ぜる 5,000 通り（間隔 1・5 × 並べ替えの設定オン・オフ）で、毎回の結果と合計が元の探索と一致。
    - **実機の結果（2026-11-27、Nomi-CEu、821 tick）**: 作り直し 164 回（≒ 821 ÷ 5）・1回 133.9µs、並べ替え 821 回（1tick に1回）・1回 87.2µs、**1tick あたり 114.0µs**（間隔1のときの 227.7µs から半分）。
    - 残りの大部分は並べ替えの中の `refreshPower`（66 網ぶん、1tick に1回）。さらに削るなら 1-C だが、1tick の約0.2% なので急がない。
    - 次: 同じような状況で spark を取り直し、電力まわり全体（`injectPower` 6.3% だったもの）がどれだけ下がったかを見る。
  - `// 要ローカル検証`:
    - 起動ログに `EnergyGridCacheMixin` の適用失敗が出ないこと（`addNode` / `removeNode` への `@Inject` を追加した）。
    - spark で `injectPower` の中の `PriorityQueue.add`（0.38%）と `PartQuartzFiber.providers` が消えること。
    - ファイバー越しのサブネットが今までどおり電力を受け取れること。網を満タンにしたとき、溢れた分がサブネットへ流れること。
    - ファイバーの設置・破壊、ケーブルの切断（網の分割・統合）、サブネットのチャンクのアンロード・再読み込みのあとも、電力の行き先が正しいこと（古い一覧を使っていないこと）。
  - ファイバー越しにつながっている `EnergyGridCache` の一覧を、1tickに1回だけ作ります。
  - 作り直す条件: tick が変わったとき、または `IEnergyGridProvider` の `addNode` / `removeNode` が起きたとき（全体共通のバージョン番号を進める）。
  - `leftover > 0` のときは、一覧を充電率の低い順に1回だけ並べて、順番に `injectProviderPower` します。取り出しは保有量の多い順に同様に行います。
  - 網ごとの保有量は、1回の呼び出しの中で1度だけ取得します。これで `refreshPower` の重複がなくなります。
  - 注意: 受け取られる電力の合計は元と同じです。ただし、充電率が同じ網どうしでは、どの網から先に入るかが元と変わることがあります。
  - `// 要ローカル検証`: 網の統合・分割、チャンクのアンロード、ファイバーの破壊があったときに、キャッシュが古い網を指していないか確認します。
  - 参考: 現行の AE2（1.19以降）でも、深く入れ子になったネットワークでの石英ファイバーの性能改善（#7164 / PR #7258）が入っています。設計の参考にします。
- [ ] **1-C：`refreshPower` の回数を抑える**（任意。設定で切り替え、元と完全には一致しない）
  - `getStoredPower()` の再集計を1tickに1回までにします。
  - その tick の中では、`globalAvailablePower`（取り出し時に差分で更新されている値）を使います。
  - 1-B で十分に軽くなるなら見送ります。
- [ ] **1-D：サブネットの待機電力の取り出しを確認する**
  - 自分のセルを持たないサブネットは、毎tick `onUpdateTick` → `extractAEPower` でファイバー越しに電力を取りに行きます。
  - 1-A と 1-B で軽くなるはずなので、サブネットを大量に置いたテストワールドで計測して確かめます。

### 混線・不安定（不具合）

**症状**: まれにサブネットがメインネットとつながり、メインネットに入るはずのアイテムがサブネットのストレージバスに混ざる。

**ソースで確認できたこと**（AE2UEL v0.56.7）

- 中央のケーブルを付け直すと、ファイバー越しに一瞬だけ網がつながります。
  - ケーブルの色を変える `PartCable.changeColor` は、内部で中央ケーブルを `removePart` してから `addPart` し直しています。
  - `CableBusContainer.addPart` の流れは次のとおりです。
    1. `bp.addToWorld()` で中央ケーブルのノードが作られる。このときノードの接続可能面は初期値の全6面です。**→ 誤り。初期値は0面（1-F の再調査を参照）**
    2. 作られた直後に `findConnections` が走り、ファイバーが付いている面の隣（＝別の網）とも `GridConnection` を作ってしまいます。
    3. その後の `updateConnections()` でファイバーの面が外れ、接続が切れます。
  - その結果、2つの網が**同じ呼び出しの中で一度合体して、すぐに分かれます**。両方の網の再起動（リパス）と、ストレージの再構築が起きます。
  - 同じ処理を通るので、ケーブルの種類の置き換え（例: ガラスケーブル → 高密度ケーブル）や、Building Gadgets などのブロック交換でも起きる可能性があります。`// 要ローカル検証`
- `CableBusContainer.getGridNode(side)` は、側面のパーツの外向きノードが `null` のとき、中央ケーブルのノードを返します。
  - ファイバーの外側ノードが未準備・破棄済みのタイミングで隣のブロックから問い合わせが来ると、中央ノードが返ります。
  - いまは中央ノード側の `canConnect` で弾かれていますが、上の「全6面」の瞬間と重なると素通りします。

**まだ確定していないこと**

- アイテムの混入は、上の一瞬の合体だけでは説明しきれません。合体から分離までが同じ呼び出しの中で終わり、tick をまたがないためです。
- どこかで**合体したまま戻らない**経路があるか、ストレージバスの設置の仕方の問題かを切り分ける必要があります。

**やること**

- [x] **1-E：混線の検出ツール（デバッグ用）** ※実装済み・開発環境で動作確認済み（2026-11-26）
  - 確認結果: NAE2・AE2Enhanced・AE2-VM・AE2FC・Better P2P と同時に入れても、opti の mixin 4つはすべて適用された。検出・tick 終わりの判定・`/ae2opti fibercheck` も動いた。
  - 注意: 組み立て中（ファイバーを付ける前の隣り合ったケーブルが直接つながっている間）は、本当につながっているので DETECTED が出る。調べるときは組み立て終わってから。
  - 実装: `mixin/GridConnectionMixin`（`create` の `new GridConnection` 直前と return 直前に `@Inject`）、`debug/FiberCrosstalkDetector`、`debug/FiberCrosstalkTickHandler`、`command/CommandAE2Opti`
  - 使い方: `config/ae2uel_optimizer.cfg` の `debug` カテゴリで `fiberCrosstalk=true` → ログの `[1-E fiber crosstalk]` を探す。`/ae2opti fibercheck`（OP権限）で今の状態を一覧
  - ログの見方: `DETECTED`（座標・合体させた接続の両端・スタックトレース）のあと、tick の終わりに「`momentary merge within the same tick`（一瞬）」か「`persists across ticks`（続いている）」のどちらかが出る。続いている場合は分かれた時点の tick 数も出る
  - 設定（`debug` カテゴリ）: `fiberCrosstalkStackTrace`（スタックトレース）、`fiberCrosstalkMaxReportsPerTick`（1tickに詳しく出す件数、既定20）、`fiberCrosstalkTrackTicks`（追跡する tick 数、既定200）。合体の検出には `mixins.gridConnection=true`（既定）が必要
  - `// 要ローカル検証`: mixin が適用されること（起動ログ）、色塗りでケーブルを付け直したときに DETECTED → momentary が出ること（1-F の経路の確認）
  - **一瞬の合体も捕まえるため、`GridConnection.create` の直後をフックします。**
    - 2つの grid がマージされたときに、マージ後の grid の `energyGridProviders`（＝ファイバー）を見ます。
    - 内側ノードと外側ノードが同じ grid になっているファイバーがあれば、座標とスタックトレースをログに出します。
    - スタックトレースから、色塗り・ケーブル交換・チャンク読み込みなど、何が引き金だったかが分かります。
  - 合体が続いている状態を確認するためのコマンド（例: `/ae2opti fibercheck`）も用意します。
  - どちらも設定で有効にしたときだけ動くようにします（デフォルトは無効）。
  - メモ: 混入は液体ストレージバスでごく少量なので、本人の見立ては「一瞬の合体中に入った」です。
    - ただし、ソース上は分離（`GridConnection.destroy` → `validateGrid`）も同じ呼び出しの中ですぐに行われます。
    - なので、合体している間に液体を入れる処理が同期的に走っているのか、1tick以上合体が続く別の経路があるのかを、ログで切り分けます。
- [ ] **1-F：中央ケーブル付け直し時の一瞬の合体を防ぐ** ※**前提が誤りの見込み（2026-11-26 再調査）**。実機（1-E）で確認してから閉じる
  - 再調査の結果: `AEBasePart` のコンストラクタが `proxy.setValidSides(EnumSet.noneOf(...))` を呼んでいる。中央ケーブルの接続可能面の初期値は**全6面ではなく0面**。
    - そのため `addToWorld()` の直後の `findConnections` は、どの面ともつながらない。その後の `updateConnections()` で、パーツの無い面だけが有効になる。ファイバーの面は最初から除かれているので、一瞬の合体は起きない。
    - 接続には両側の `canConnect`（`isValidDirection`）が必要。そのため `getGridNode(side)` が中央ノードを返しても、ファイバーの面は弾かれる。
    - チャンク読み込み時も、`readFromNBT` → `addPart` → `updateConnections` で接続可能面が決まってから `addToWorld` されるので同じ。
  - 上の「ソースで確認できたこと」の「接続可能面は初期値の全6面」という記述は誤り。
  - 確認方法: `debug.fiberCrosstalk=true` で、ファイバーの隣のケーブルを色塗り・種類の交換をする。DETECTED が出なければ 1-F は不要として閉じる。
  - 実機メモ（2026-11-26）: 色塗りで DETECTED（persists）が1件出たが、無色のケーブルをサブネットと同じ青に塗ってサブネットと直接つないだもので、AE2 の正しい動作だった（1-F の経路ではない）。1-F の経路での DETECTED は出ていない。
  - 案: `addPart` で中央ケーブルを付ける直前に、側面パーツの状況から接続可能面を先に設定します（`updateConnections` 相当）。そのあとで `addToWorld()` を呼ばせます。
  - 案: `getGridNode(side)` で、側面に外向きノードを持つパーツ（ファイバーなど）があるのにノードが `null` のときは、中央ノードを返さず `null` を返します。
  - 効果: 色塗りやケーブル交換でのサブネットの再起動もなくなります（「不安定」の一因）。
- [x] **1-H：読み込み直後にアンロードされたタイルが「幽霊ノード」になる穴をふさぐ** ※実装済み・実機での確認待ち（2026-11-26）
  - 実装: `mixin/TickHandlerMixin`（`onTick` の `AEBaseTile.onReady()` 呼び出しを `@WrapOperation`）、`ghost/GhostNodeGuard`（判定・ワールドのアンロード記録・ログ）、`ghost/GhostNodeChecker`（コマンド用）
  - 判定: `onReady()` の直前に、次のどれかなら飛ばす。①ワールドがアンロード済み（`WorldEvent.Unload` を受けたワールド）②チャンクが読み込まれていない ③チャンクにあるタイルが本人ではない。
    - チャンクは `ChunkProviderServer.loadedChunks` を直接見る。`getLoadedChunk` / `isBlockLoaded` はアンロード予約（`unloadQueued`）を取り消す副作用があるので使わない。
    - `WorldServer` 以外のワールド（他 Mod の仮のワールド）は ① だけ見る。
  - 設定: `grid.skipUnloadedTileInit`（既定オン）、`debug.ghostNodeLog`（既定オフ、1件ずつ詳しく出す）、`mixins.tickHandler`（既定オン、再起動が必要）
  - ログ: 飛ばしたときは、件数だけ毎回1行 `[1-H ghost node] Kept N unloaded tile entities ...` が出る（めったに起きないため）。
  - コマンド: `/ae2opti ghostcheck`（OP権限、設定に関係なく使える、読むだけ）。全部の網のノードを調べ、持ち主のタイルがもうワールドに無いもの（アンロード済みディメンション／チャンク未読み込み／撤去済み／別のタイルに置き換わった）を一覧にする。起動後に防いだ件数も出す。
  - 他の Mod: `run/mods` の NAE2・AE2Enhanced・AE2-VM は `TickHandler` を mixin していない（AE2Enhanced は呼び出しているだけ）。CrazyAE はまだ未確認。`// 要ローカル検証`
  - メモ: Forge の `dormantChunkCacheSize`（既定 0）を有効にすると、AE2 はもともと読み込み直したタイルを初期化し直さない。この対策とは関係ないが、混線を調べるときは 0 のままにする。
  - `// 要ローカル検証`:
    - 起動ログに TickHandlerMixin の適用失敗が出ないこと。`mixins.tickHandler=false` で「Mixin TickHandlerMixin is disabled in the config」が出ること。
    - 普段のプレイ（チャンクの読み込み・ディメンション移動・ケーブルの設置）で、`[1-H ghost node] Kept ...` が出ないこと（出るなら判定が厳しすぎる）。
    - Compact Machines / Advanced Rocketry などのディメンションを出入りしたあと、`/ae2opti ghostcheck` が 0 件になること。オフにして同じ操作で件数が出れば、穴が実際に開いていた証拠になる。
  - `TickHandler` は、初期化待ちのタイルに `onReady()` を呼ぶ前に `isInvalid()` しか見ていません（`TickHandler.java` の SERVER END の処理）。
  - 1.12.2 では、チャンクがアンロードされても `onChunkUnload()` が呼ばれるだけで、`invalidate()` は呼ばれません。`// 要ローカル検証`
  - そのため、読み込まれたチャンクが AE2 の初期化より先にアンロードされると、そのタイルにも `onReady()` → `addToWorld()` が走ります。
    - 起きやすいのは、負荷で処理が遅れたときや、チャンクの読み込み・アンロードが短時間に繰り返されたときです。
  - その結果、ワールドにないタイルのノード（幽霊ノード）ができて、そのまま残ります。
    - `AENetworkProxy.isWorldAccessible()` は固定値なので、幽霊ノードも周りのブロックとつながります。
  - タイミングの補足:
    - 通常のチャンクのアンロードは、次の tick の `ChunkProviderServer.tick()` で行われます。それより前に、AE2 の初期化処理（tick の最後）が終わっています。
    - なので、普通にチャンクを読み込んでアンロードする流れでは、この穴に入る余地はほぼありません。
    - 起きうるのは、**ディメンションごとのアンロード**（`DimensionManager.unloadWorlds`）です。これは tick の最後の初期化処理より前に走ります。
    - たとえば、Compact Machines や Advanced Rocketry のディメンションが、1 tick の中で読み込まれてすぐアンロードされる場合です。`// 要ローカル検証`
  - **ソースで確認できた追加の穴**: `TickHandler.unloadWorld`（`WorldEvent.Unload`）の処理は次のとおりです。
    - そのワールドのノードを `destroy()` します。
    - ところが、初期化待ちのキュー（`repo.tiles`）からは、そのワールドのタイルを取り除いていません。
    - そのため、アンロード済みのワールドのタイルにも、tick の最後で `onReady()` が呼ばれます。その結果、誰も片付けない幽霊ノードが残ります。
  - 本人の報告: 混入はディメンション移動のときに起きることが多かった（サブネットは常時読み込み）。→ この経路と合っています。
  - 混線の直接の原因かはまだ未確定です。ただ、網の不安定化や負荷の原因にはなりえます。
  - 直し方の案（正常な場合の動きは変わらず、壊れた状態だけがなくなります）:
    - (a) `onReady()` の前に、次の3つを確認します。満たさなければそのタイルを飛ばします。
      - そのタイルのワールドが、まだ読み込まれているワールドであること（`DimensionManager.getWorld(dim) == tile.getWorld()`）
      - `world.isBlockLoaded(pos)` が true であること
      - `world.getTileEntity(pos) == tile` であること
    - (b) `unloadWorld` の処理で、初期化待ちのキューからそのワールドのタイルも取り除きます。
- [x] **1-G：アイテム混入の再現と原因の特定** ※**おそらく修正済み**（2026-11-27、1-H と石英ファイバーまわりの修正後、混入は出ていない）
  - 明確な原因は特定できていない。網が不安定だったのが原因と見ている。有力なのは 1-H（アンロードされたディメンションのタイルが幽霊ノードとして網にくっつく）。「ディメンション移動のときに起きやすかった」という報告と合う。1-F の経路（色塗りなどでの一瞬の合体）は、調べ直したら起きない作りだった。
  - もとから「まれに」起きる不具合なので、しばらく様子を見る。もしまた混入したら、`debug.fiberCrosstalk=true` にしてログ（`[1-E fiber crosstalk]`）を取る。あわせて `/ae2opti ghostcheck` の「起動後に防いだ件数」を見る（0 でなければ 1-H が働いていた証拠）。
  - 以下は当初のメモ。
  - 色塗り・ケーブル交換は**していない**ので、1-F が原因の可能性は低いです。読み込み時や高負荷時に起きている見込みです。
  - 1-E のログを有効にした状態で、再ログインやチャンクの読み込み直しを繰り返して、合体した瞬間のスタックトレースを取ります。
  - 見つかった経路に応じて修正を決めます。
- **進める順番**: 1-E（検出ログ）→ 1-H（幽霊ノード対策）→ 1-F → 1-A → 1-B

### 確認方法

- 同じワールド・同じ位置で、spark を `--thread "Server thread"` 付き・60秒で前後比較します。
- 前後で `injectPower` / `extractAEPower` の inclusive の割合を比べます。
- 動作確認:
  - サブネットの機械が止まらないこと
  - P2P FE 経由で ME 網に電力が入ること
  - 網を満タンにしたとき、溢れた分がサブネットへ流れること

---

## 優先度1.5：P2P FE トンネル（入口の inclusive が 18.5%）

**流れ**
- Ender IO のコンジットが、毎 tick P2P FE の入口に電力を流しています（13.4%）。Solar Flux からも流れています（4.8%）。
- 入口は、すべての出口に配って回ります。
- 出口の先は主に AE2 の Energy Acceptor やコントローラーです（`ForgeEnergyAdapter` が 12.7%）。そこから `injectPower` に入ります（＝優先度1の問題）。

**P2P 自体の問題点**（v0.56.7 の `PartP2PFEPower.InputEnergyStorage.receiveEnergy`）
1. **受け取り先が満タンでも、出口ごとに2回ずつ呼んでいる**
   - 1周目で均等に配り、余った分を2周目で全部の出口にもう一度配ります。
   - 満タンのときは、出口の数 × 2 回分、無駄に `receiveEnergy` → `injectPower`（全体探索）が走ります。
2. **呼ばれるたびに、隣のブロックを探し直している**（3.6%）
   - `getAttachedEnergyStorage()` が、毎回 `getTileEntity` → `hasCapability` → `getCapability` をしています。
   - 内訳: `World.getTileEntity` 1.0%、`HashMap` 0.9%、GregTech のカバーの capability 0.5%、Ender IO の `findCapability` 0.4%。

**やること**
- [x] P-1：出口の先の `IEnergyStorage` をキャッシュします。隣のブロックが変わったとき、または tick が変わったときに破棄します。
  - ※実装済み・実機での確認待ち（2026-11-26）。`PartP2PFEPowerMixin`、設定 `p2p.cacheAttachedStorage`（デフォルトでオン）。TileEntity が無効になったときと tick が変わったときに捨てる。
- [x] P-2：1周目でどの出口も 0 しか受け取らなかったら、2周目を飛ばします。ほぼ元と同じ結果になります。
  - ※実装済み・実機での確認待ち（2026-11-26）。`PartP2PFEPowerInputMixin`、設定 `p2p.skipEmptySecondPass`（2026-11-26 にデフォルトでオンへ変更）。
  - 2026-11-26 修正：新しい配分処理では「2周目ごと飛ばす」をやめ、1周目に断った出口には2周目の量が1周目以下のときだけ渡さない形にした。少ない量は断るが多い量なら受け取る相手（GTCEu のコンバーターなど）に届かなくなっていたため。元の配分処理（newDistribution オフ）のときは従来どおり2周目ごと飛ばす。
- [x] P-3（任意、設定で切り替え）：同じ tick で 0 を返した出口は、その tick の残りは飛ばします。
  - ※実装済み・実機での確認待ち（2026-11-26）。`p2p.skipFullOutputs`（デフォルトでオン）。
  - 2026-11-26 修正：「この tick は満タン」ではなく「この tick に断った量」を覚え、それ以下の量のときだけ飛ばす形にした（多い量なら渡してみる）。1周目の少ない量で断られた出口に、2周目のまとまった量まで渡されなくなっていたため。
- [x] P-4（任意、設定で切り替え）：**まとめて流し込む**
  - ※実装済み・実機での確認待ち（2026-11-26）。`ae2opti.p2p.AcceptorBuffers`、`p2p.acceptorBuffer`（デフォルトでオン）／`p2p.acceptorBufferRF`。対象はコントローラーと Energy Acceptor だけ。ブロックが壊れたりチャンクが読み込まれていなかったりするとバッファの中身は捨てる。
  - 2026-11-26 修正：受け取る量を「網がその tick の初めに受け取れた量」（tick に1回 `injectExternalPower(SIMULATE)` で聞く）までにした。網に入りきらない分まで受け取り、その分のトンネル税で入口の網の電力を使い切ってしまう恐れがあったため。`p2p.acceptorBufferRF` のデフォルトも 2147483647 → 10000000 に下げた。
  - Energy Acceptor やコントローラーに小さなバッファを持たせます。受け取った電力はいったんバッファに貯め、tick の終わりに1回だけ `injectPower` します。
  - 重さは、流す量ではなく呼ばれる回数で決まります。なので、呼ばれる回数を「1台あたり 1 tick に1回」まで減らせます。
  - シミュレート時の返答は、バッファの空き容量で答えます。
  - 注意: 網に電力が届くのが最大 1 tick 遅れます。
- メモ: 優先度1の 1-A / 1-B を入れれば、出口の先で起きている `injectPower` の負荷はまとめて下がります。P2P 側の対策は、その残りを減らすためのものです。

**P2P まとめ対応の追加候補**（2026-11-26 にソースを読んで見つけたもの。今のビルドのテスト後に、P-3／P-4 とまとめて実装する）
- [x] P-5（軽量化）※実装済み・確認待ち、`p2p.cacheOutputs`（オン）：出口一覧を tick 内で使い回す。`receiveEnergy` 1回で `getOutputs()` を3回呼び、そのたびに `TunnelIterator` が種類で絞り込んでいる（約0.7%）。
- [x] P-6（軽量化）※実装済み・確認待ち、`p2p.batchTunnelTax`（オン）：通行料（`queueTunnelDrain` → `extractAEPower`）を本番の受け渡しごとではなく tick ごとにまとめて取る。
- [x] P-7（不具合）※実装済み・確認待ち（呼び出しごとのローカルな一覧＋配っている最中の入れ子は 0 を返す）：入れ子で呼ばれると壊れる。出口を回すキュー `outputs` が呼び出し同士で共有されていて、出口から同じ入口に電力が戻るループ配線だと配る途中で中身が混ざる（未使用の `iteratingOutputs` はこれを防ぐつもりだったと思われる）。
- [x] P-8（不具合）※実装済み・確認待ち：`getEnergyStored` / `getMaxEnergyStored` が全出口の値を `int` で足していて、大容量の先（DE など）だとあふれてマイナスになる。`long` で足して `int` の上限で止める（優先度4と一緒に直す）。
- Better P2P は最新版（v1.3.3）前提。v1.3.2 の CME（別スレッドでのリンク処理）は対応しない。

## 優先度2：クラフト CPU（10.5%）

**メモ（2026-11-26 再計測）**: 既存の間引き（throttleCpu・sharedGridBudget）で 2.28% まで下がり、いったんひと区切り。内訳: `DualityInterface.pushPattern` 0.78%（本来の仕事なのでほぼ削れない）、`canCraft` 0.27%、`isBusy`（インターフェイス＋Packager）0.27%、`executeCrafting` 自身 0.22%。

**今回やること（2026-11-26 決定）**: 候補A（isBusy のキャッシュ）＋候補C（軽さを保って速くする）＋後回しの不具合を均等割りで直す。候補B（材料不足のパターンの canCraft を抑える）は今回はやらない。

- [x] **後回しの不具合を均等割りで直す** ※実装済み・実機での確認待ち（2026-11-26）
  - 原因: `CraftingGridCache` が CPU を回す順番（`HashSet` の順）は毎 tick 同じ。網の予算を使い切ったあとに回ってくる CPU がいつも同じになり、ずっと遅いままだった。`minCoProcessorsWhenGridBusy=0` だと、AE2 が直近3 tick の使用数を引くので「3〜4 tick に1パターン」まで落ちていた。
  - 直し方: 網の予算（`sharedMillisPerGrid`）を、**前の tick に動いた CPU の数で均等に割った持ち分**を各 CPU の目標時間にする。使い切らなかった CPU の余りは、足りなかった CPU（許された回数を使い切った CPU）で分ける。持ち分は前の tick の集計で決めるので、回る順番に左右されない。
  - 各 CPU の目標時間は `targetMillisPerCpu` と持ち分の小さいほう。持ち分のほうが小さいときは、cap の下限を `minCoProcessors` ではなく `minCoProcessorsWhenGridBusy` にする（CPU が多いときに合計が予算を大きく超えないように）。
  - `throttleCpu=false` でも `sharedGridBudget=true` なら、持ち分を目標にした cap の自動調整が動く。
  - 緊急停止: それでも網が予算の2倍を使った tick は、残りの CPU を `minCoProcessorsWhenGridBusy` に抑える。ただし**前の tick に止められた CPU は止めない**（同じ CPU が続けて後回しにならない）。止められた tick は cap を動かさない。
  - 実装: `CraftingGridCacheMixin`（tick の頭で前の tick の集計を保存、`getShareNanos`）、`CraftingCPUClusterMixin`（`executeCrafting` の HEAD で「動いた」印、RETURN で集計に入れる）
  - `// 要ローカル検証`:
    - CPU を3つ以上同じ網に置き、同時に大きなジョブを流す。CPU の GUI で、どの CPU も同じくらいの速さで進むこと（以前は特定の CPU だけ極端に遅かった）。
    - 予算を小さく（例: `sharedMillisPerGrid=2`）して同じことをし、spark で `CraftingGridCache.onUpdateTick` が予算付近に収まること。
- [x] **候補A：埋まっている送り先を tick 内で覚える**（`crafting.cacheBusyMediums`、既定オン）※実装済み・実機での確認待ち（2026-11-26）
  - `executeCrafting` の `ICraftingMedium.isBusy()` を `@WrapOperation`。「埋まっている」という答えだけを、その tick の間、**網ごと**に覚える（同じ網のほかのパターン・ほかの CPU は聞き直さない）。「空いている」は覚えない（パターンを送ると埋まるため）。
  - 覚えるのは `CraftingGridCache.onUpdateTick` の中だけ。tick の頭と終わりで空にする。
  - 結果が元と変わりうるのは、tick の途中で送り先が空く場合だけ（次の tick から使われる）。CPU の処理中に送り先が空くことは普通ないので、実質同じ。
  - AE2Enhanced との関係: AE2Enhanced も `isBusy` を `@WrapOperation`（送るのに失敗した送り先を数 tick「埋まっている」扱いにする）し、`DualityInterface.isBusy` の結果を tick ごとに覚えている。重ねがけで動く。AE2Enhanced の「失敗した送り先」は CPU ごとだが、こちらが内側・外側どちらになっても、その tick の間ほかの CPU からも埋まっている扱いになることがある（同じ tick の中だけなので実害は小さい見込み）。
- [x] **候補C：送り先が全部埋まっているパターンは材料チェックをしない**（`crafting.skipCraftCheckWhenAllBusy`、既定オン）※実装済み・実機での確認待ち（2026-11-26）
  - `executeCrafting` は、送り先を見る**前に** `canCraft`（材料のシミュレート取り出し）をする。さらに1つ送れるたびに全パターンを最初から回し直すので、送り先が埋まっているパターンの `canCraft` が何度も無駄に走る。
  - `canCraft` を `@WrapOperation` し、そのパターンの送り先（`getMediums`）が**すべて**候補Aで「埋まっている」と分かっていれば、`canCraft` を呼ばずに false を返す。元の処理でも何も送られないので結果は同じ（違いは `visitedMediums` の列が回されないことだけ）。
  - 1パターンあたりの時間が減るので、同じ時間予算でより多く送れる（＝軽さを保ったまま速くなる）。
  - **AE2Enhanced があるときは自動でオフ**: AE2Enhanced は `canCraft` の失敗を「CPU の在庫が変わるまで」覚える（`CanCraftCacheService`）。こちらが内側で false を返すと材料不足として覚えられ、送り先が空いても止まったままになりうるため。起動ログに `AE2Enhanced found: crafting.skipCraftCheckWhenAllBusy is turned off` が出る。
  - `// 要ローカル検証`: AE2Enhanced なしの環境で、ブロッキングモードのインターフェイスや PackagedAuto を使う大きなジョブが最後まで終わること（止まったままのパターンが出ないこと）。
- [x] ~~**候補C の続き**: cap の戻し方を速くするか~~ → **やらない**（下の大量クラフト中の計測で、間引きはほぼ効いていなかったため）
- [x] **CPU の状況画面の更新間隔を重さに合わせて変える**（`crafting.cpuGuiAdaptiveUpdate`、既定オン）※実装済み・実機での確認待ち（2026-11-26）
  - AE2 は CPU の画面（`ContainerCraftingCPU`、それを継承するクラフト状況の画面も）を開いている間、変わったアイテムを毎 tick、GZIP で圧縮した3つのパケットにして送る。大量クラフト中の spark で 1.03%。
  - 本人の希望: 普段は毎 tick（リアルタイムにクラフトしている感じを出したい）、重いときだけ5 tick ごと。
  - 実装: `mixin/ContainerCraftingCPUMixin`。`detectAndSendChanges` の中の `IItemList.isEmpty()` を `@WrapOperation` し、待っている間は true に見せる。一覧はリセットされずにたまり、送るときに最新の数をまとめて送るので取りこぼしはない。予想時間などの @GuiSync は毎 tick のまま。
  - 遅くする条件（どちらか）: 1回の送信が1 tick あたりに直して `cpuGuiSlowSendMicros`（既定 500µs）以上、またはサーバーの直近100 tick の平均 MSPT が `cpuGuiSlowMspt`（既定 40ms）以上。遅いときの間隔は `cpuGuiSlowInterval`（既定 5）。mixin ごと外すのは `mixins.craftingCpuGui`。
  - メソッド名は MCP 名（`detectAndSendChanges`）と SRG 名（`func_75142_b`）の両方を書いている。
  - 他の Mod: AE2Enhanced（`MixinContainerCraftingCPUThrottle`）も同じ `isEmpty()` を `@WrapOperation`（待っている数が多いときに間引く）。重ねがけで動く。CrazyAE はクラフト状況の画面の `detectAndSendChanges` を `@Overwrite` しているが、最後に `super` を呼ぶので通る。
  - `// 要ローカル検証`:
    - 開発環境と本番の jar の両方で、起動ログに `ContainerCraftingCPUMixin` の適用失敗が出ないこと（MCP 名と SRG 名の両方を書いた方式で当たるか）。`ServerLoad` の `tickTimeArray` / `getTickCounter` が本番で動くこと。
    - 大量クラフト中に CPU の画面を開いて、普段は数字がなめらかに動くこと。`cpuGuiSlowMspt=1` にすると5 tick ごとのカクカクした動きになること（条件が効いている確認）。
    - 画面を閉じて開き直したとき、数字がずれていないこと。

### 大量クラフト中の計測（2026-11-26、今回の変更を入れる前の opti 1.0.0）

- 条件: Server thread、約713秒、4ms 間隔、TPS 20.0、MSPT 中央値 18.9〜20.2ms。AE2Enhanced・CrazyAE なし、NAE2 あり。
- `CraftingGridCache.onUpdateTick` は、大量クラフト中の1分ごとの平均で **1 tick あたり 1.5〜3ms**（後半はクラフトが減って 0 に近づく）。網の予算（10ms）にも CPU ごとの目標（5ms）にも平均では届いておらず、**速さを決めているのは opti の間引きではなく、AE2 本来の上限（コプロセッサ数と直近3 tick の使用数）と送り先の空き**。
- `executeCrafting` 2.63% の内訳: `pushPattern`（NAE2 のラップ込み）0.73%、`isBusy` 0.45%（インターフェイス 0.29%・Packager 0.16%）、`canCraft` 0.36%、`visitedMediums` まわり（HashMap・stream・`getMediums`）約0.38%、`AEItemStack.fromItemStack` 0.16%。
- `ContainerCraftingCPU.detectAndSendChanges` 1.03%（画面を開いていたため）。
- 次の候補（未着手）: `visitedMediums` まわり（約0.38%）。ループの中で `visitedMediums.get(details)` を何度も呼んでいて、パターンのハッシュ計算が重い。

### 大量クラフト中の計測 その2（2026-11-26、後回し修正・A・C・CPU 画面を入れた版）

- 条件: 本番の Nomi-CEu（AE2UEL v0.56.6、Better P2P 1.3.2、AE2Enhanced なし）、Server thread、約591秒、TPS 20.0、MSPT 中央値 21.0ms。
- クラフトの量が前回より多い（`pushPattern` 0.73% → 1.14%）。なので `pushPattern` 1回あたりで比べる。
- `executeCrafting` 4.12%。`pushPattern` に対する比は前回と同じ約3.6倍で、**1パターンあたりの重さは減っていなかった**。
  - **A（isBusy のキャッシュ）は効いた**: `isBusy` 0.27%（前回 0.45%）。`pushPattern` 1回あたりでは 0.62 → 0.24 に減った。
  - **C は逆に重くなっていた**: `skipCanCraftWhenAllBusy` 全体で 1.36%。中身は `canCraft` 本体 0.44% に対し、**こちらが呼んだ `CraftingGridCache.getMediums` が 0.79%**。パターンを鍵にしたハッシュ表を引くたびに、パターンの `hashCode` / `equals` が走るのが原因（PackagedAuto の `PackageCraftingPatternHelper.hashCode` 0.33%、`PatternHelper.equals` 0.19%）。
  - → **修正（2026-11-26）**: パターン → 送り先の一覧を、同じオブジェクトかどうかで引く `IdentityHashMap` にキャッシュする（`CraftingGridCache` ごと、`recalculateCraftingPatterns` の頭と終わりで空にする、4096件を超えたら空にする）。`getMediums` は同じパターンについて、パターンが組み直されるまで1回しか呼ばれなくなる。`// 要ローカル検証`: 再計測で `getMediums` がほぼ消えること。パターンを追加・削除したあと、新しいパターンも普通に動くこと。
- CPU 画面: `ContainerCraftingStatus` 0.44%（前回 1.10%）、`ContainerCraftingCPU` 0.39%（前回 1.03%）。画面を開いていた時間が違うので参考値だが、6割くらい減っている。
- AE2UEL v0.56.6 と v0.56.7 で、`CraftingCPUCluster`・`ContainerCraftingCPU`・`recalculateCraftingPatterns` のソースは同じ（差分なし）。

### 大量クラフト中の計測 その3（2026-11-26、getMediums のキャッシュを入れた版）

- 条件: 本番の Nomi-CEu、Server thread、約210秒（短め）、TPS 20.0、MSPT 中央値 21.4ms。クラフト量はさらに多い（`pushPattern` 1.71%）。
- **`executeCrafting` ÷ `pushPattern` が 3.6倍 → 2.57倍**（1パターンあたり約3割軽くなった）。
  - C のラッパーのうち `getMediums` は消えた。オーバーヘッドは `isKnownBusy` の 0.12% だけ（`canCraft` 本体 0.55%）。
  - `pushPattern` 1回あたり: `isBusy` 0.62 → 0.15、`canCraft`（C 込み）0.49 → 0.40。
- 残り（次の候補）: `AEItemStack.fromItemStack` 0.24%、元の処理の `getMediums`（visitedMediums の補充）0.15%、`PatternHelper.isValidItemForSlot` 0.13%、`isKnownBusy` 0.12%。
- 後回しの修正（均等割り）は spark では見えないので、ゲーム内で確認する（CPU を3つ以上並べて同時にジョブを流す）。
- 今わかっている重なり（追加分）:
  - AE2Enhanced: `isBusy`（PushCooldown）・`canCraft`（CanCraft・Special の2つ）・`pushPattern`（複数）・`getMediums`（`@Redirect`、メモ化）・`updateCraftingLogic` の HEAD を触っている。opti が触る `accelerator` の読み出しと `executeCrafting` の HEAD には重なりなし。
  - CrazyAE: `CraftingGridCache.onUpdateTick` の TAIL に `@Inject`（10 tick ごとに CPU 一覧を作り直す）。opti の HEAD／RETURN とは別の場所。
  - NAE2: `executeCrafting` の HEAD／RETURN に `@Inject`、`pushPattern` を `@WrapOperation`。重なりなし。
- [ ] busy だったインターフェースや Packager を、同じ tick の中で再判定しないようにキャッシュできないか検討します。→ 候補A で対応

## 優先度3：AE2 Fluid Crafting の Fluid Level Maintainer（4.1%）

- [ ] `onStorageUpdate` がストレージの更新イベントのたびに `doWork` を呼んでいます（3.7%）。その先の `MultiCraftingTracker.handleCrafting` が、毎回 `CraftingJob` を生成します（3.3%）。
  - `CraftingJob` を作るたびに `MECraftingInventory` が網の在庫を丸ごとコピーします。`ItemListIgnoreCrafting.add` だけで 2.7% あります。
- [ ] 別 Mod なので、AE2FC が入っているときだけ有効になる任意の mixin として扱います。
  - 案: `onStorageUpdate` からの `doWork` を、1tickに1回までにまとめる。
  - 案: 同じ要求ですでにジョブを計算中なら、次のジョブを作らない。
- [ ] AE2FC の元の挙動（補充の反応速度）が大きく変わらない範囲にします。

## 優先度4：P2P FE を long で受け渡しできるようにする（必要なら BigInteger）

**目的**: Forge Energy の `int` の上限（1回あたり約21億FE）を超えて、P2P で電力を運べるようにする。

**基本の考え方**: P2P の中は `long` で計算します。外とのやりとりは、相手に合わせた方法を使い分けます。

1. **P2P の中**
   - `PartP2PFEPower.InputEnergyStorage.receiveEnergy` の配分処理を、mixin で `long` 版の内部メソッドに置き換えます。
   - `amountPerOutput`、`overflow`、`total` をすべて `long` にします。
   - `int` の入り口は、その `long` 版を呼ぶだけの薄い窓口にします。
2. **出口 → 隣のブロック**（アダプタ方式。AE2Enhanced の `platform/energy/adapter` を参考にします）
   - `long` 対応の API を持つブロック: そのまま `long` で渡します。
     - 候補: GregTech CEu の `IEnergyContainer`（EU、`long`）、Draconic Evolution など。
   - それ以外: FE の `receiveEnergy(int)` を、`int` の上限ごとに区切って繰り返し呼びます（AE2Enhanced の `fallbackInject` と同じやり方）。
     - 回数には上限を設けます（設定値）。受け取りが 0 になったら打ち切ります。
   - 出口の先が AE2 の Energy Acceptor の場合: `long` から `double` に変換して、そのまま注入します。P-4 のバッファを入れる場合は、バッファを `long` にします。
3. **入口 ← 送り側**
   - Ender IO のコンジットなど、FE（`int`）で送ってくる Mod は、送り側の仕様で1回あたり `int` までです。ここは変えられません。
   - `long` で送れる Mod から受け取るには、入口に追加の受け口（capability）を足します。
     - 例: GregTech の EU（`IEnergyContainer`）を受け取って、FE に換算する。
   - 案: 入口が隣の `long` 対応ストレージから能動的に吸い出すモード（設定で切り替え）。
4. **P2P の通行料（tax）**
   - `queueTunnelDrain(PowerUnits.RF, total)` は、運んだ量に比例して AE 電力を消費します。
   - 量が桁違いになると通行料も巨大になるので、上限（または率）を設定できるようにします。
5. **BigInteger（ついで）**
   - 1tick で動く量が `long`（約9.2×10¹⁸）を超えることは、まずありません。なので、1回の受け渡しは `long` で、あふれたら上限で止めれば十分です。
   - `BigInteger` が必要になるのは、「貯める」「累計する」値だけです。
     - 例: P-4 のバッファ、統計表示。
     - AE2Enhanced の `HugeCount` のように、ふだんは `long`、あふれたときだけ `BigInteger` にします。
   - AE2 に見せる値は `Long.MAX_VALUE / 2` で止めます。複数の供給元を合算したときに、桁があふれてマイナスにならないようにするためです（AE2Enhanced と同じ理由）。

### 調査結果と決定事項（2026-11-26）

> **実装状況（2026-11-26）**：下の設計どおり実装済み・実機での確認待ち。
> 本体：`ae2opti.p2p`（`FEP2PDistributor`・`TunnelTaxes`・`AcceptorBuffers`・`P2PTickHandler`・`ILongEnergyReceiver`・`FEP2PState`）と、`PartP2PFEPowerMixin`／`PartP2PFEPowerInputMixin`（`p2p.newDistribution` がオンのとき入口の処理を置き換える）。
> 連携：`ae2opti.compat.draconic.mixin`（`mixins.ae2opti.draconic.json`）、`ae2opti.compat.packageddraconic.mixin`（`mixins.ae2opti.packageddraconic.json`）、`ae2opti.compat.fluxnetworks`（postInit で `tileEnergyHandlers` の先頭に登録）。
> ついでの不具合修正：2周目に「渡された量より多く受け取った」と返すことがあったのを止めた。DE インジェクター／PD Marked Injector はマイナスを受け取らないようにした。
> 動作確認は Nomi-CEu で行う。

対象は **FE（RF）の P2P だけ**。ふだんは `int`、相手が `long` で扱えるときだけ `long` で受け渡す。

**ソースを読んで分かったこと**（DE 1.12.2-2.3.28 / Brandon's Core 2.4.20 / PackagedDraconic 1.0.4.24 / Flux Networks 4.1.1.34）
- DE・PackagedDraconic とも、中は `long` で貯めているが、外からの受け口は FE（`int`）だけ。DE の `IExtendedRFStorage` は読み取り専用。
- 受け取り量の上限は「1tick」ではなく「1回の呼び出し」ごと。
  - DE `TileCraftingInjector.receiveEnergy`：1回あたり `min(maxReceive, コスト − 充電済み, コスト ÷ chargeSpeedModifier)`（chargeSpeedModifier = 300 − 段階×80）。
  - PackagedDraconic `EnergyStorageMarkedInjector.receiveEnergy`：1回あたり `min(コスト − 充電済み, max(コスト ÷ chargeRate, 1), maxReceive)`。
  - DE `TileEnergyStorageCore.receiveEnergy`（パイロン経由）：空き容量のぶんだけ。
- `int` に分けて何回も渡すと、インジェクターは DE が想定する充電速度より速く充電されてしまう。`long` で1回だけ渡せば、元の計算式どおり速度の上限が守られる。
- Flux Networks は中が `long` だが、FE 機械へは `ForgeEnergyHandler.addEnergy` で `Math.min(amount, Integer.MAX_VALUE)` に切り詰めている（Flux Point → P2P は 1tick 約21億 FE まで）。受け渡し方法の一覧 `TileEntityHandler.tileEnergyHandlers`（public static な List）に先頭から追加できる。
- DE パイロン（出力モード）：`update()` で `TileEnergyBase.sendEnergyToAll(world, pos, getEnergyStored(null))` を呼ぶ。コアの量を `int` に切り詰めて渡すので、1tick あたりパイロン1つで約21億 FE まで。`sendEnergyToAll` は Brandon's Core 側で、6方向に `EnergyHelper.insertEnergy`（`int`）するだけ。
- DE のエネルギークリスタル（Relay / IO）は容量自体が `int`（CoFH の `EnergyStorage`）なので対象外。

**設計**
1. **P2P の中**：配分の計算をすべて `long` にする（上の 1. のとおり）。
2. **入口**
   - Flux Networks：P2P FE 入口用の `ITileEnergyHandler` を `tileEnergyHandlers` の先頭に入れ、`addEnergy(long)` をそのまま P2P の `long` 配分に流す。
   - DE パイロン（出力モード）：`TileEnergyPylon.update` の `sendEnergyToAll` を包み、隣が P2P FE 入口なら、コアの `long` の量から直接 `long` で渡し、渡した分をコアの `energy`（`ManagedLong`）から引く。残りの方向は元の処理のまま。
   - それ以外の FE 機械からはこれまでどおり `int`。
3. **出口**
   - DE インジェクター／PackagedDraconic の Marked Injector：mixin で「`long` で1回だけ受け取る口」（ae2opti 側のインターフェース）を足す。中身は元の計算式をそのまま使う。
   - DE パイロン（入力モード）／コア：`long` の口を足す（容量だけで決まるので、分けて渡しても結果は同じ）。
   - AE2 の Energy Acceptor／コントローラー：`long` → `double` でそのまま注入。
   - それ以外の FE 機械：`int` の上限ごとに分けて、相手が受け取らなくなるまで繰り返す。
4. **設定**（ユーザー決定：分けて渡す処理は設定で切り替え、デフォルトは大きめ）
   - `p2p.longTransfer`（`long` 受け渡し全体のオンオフ）
   - `p2p.splitIntTransfer`（普通の FE 機械に分けて渡すか。デフォルトでオン）
   - `p2p.splitMaxCallsPerTransfer`（1回の受け渡しで呼ぶ回数の上限。2026-11-26 にデフォルトを 10000 → 64 に下げた。何でも受け取る相手に 1tick で大量に呼ぶのを防ぐため）
   - `p2p.tunnelTaxMultiplier` / `p2p.tunnelTaxCapPerTick`（下の 6. を参照）
5. **読み込み**：DE（`draconicevolution`）・PackagedDraconic（`packageddraconic`）・Flux Networks（`fluxnetworks`）向けの mixin は、それぞれ別の mixin 設定にして、その Mod があるときだけ読む（Better P2P と同じ方式、`Context.isModPresent`）。
6. **通行料（トンネル税）**（ユーザー決定 2026-11-26）
   - デフォルトは元の AE2 と同じ：運んだ FE × 5%（`TUNNEL_POWER_LOSS`）を AE に換算（1 RF = 0.5 AE）して払う＝運んだ量の 2.5% ぶんの AE。
     - 目安：`int` の上限ぶん（約21億 FE）で約 54M AE、1兆 FE/tick なら 25,000M AE/tick。クリエイティブのエネルギーセルがあれば払える。
   - 設定で「易化版」を選べるようにする（どちらもデフォルトは元と同じ）。
     - `p2p.tunnelTaxMultiplier`：税の倍率（デフォルト 1.0、0 で税なし）
     - `p2p.tunnelTaxCapPerTick`：トンネル1つ・1tick あたりの税の上限（AE、デフォルト 0 ＝上限なし）
   - 税は `double` で計算して `extractAEPower(double)` に渡すので、`long` の量でもあふれない。
7. **確認**：DE・PackagedDraconic まわりの動作確認は Nomi-CEu で行う。`// 要ローカル検証`：DE の `ManagedLong` を直接書き換えたときの同期・保存（`markDirty`）。

**既存の手本**: AE2UEL には、GregTech の EU 用 P2P（`PartP2PGTCEPower`）が最初から入っています。これは `long`（電圧 × アンペア、5% ロス）で動いています。FE 側を `long` 化するときの設計の手本になります。発電機が GregTech なら、これをそのまま使えます。

### Better P2P（AE2-UEL 版 v1.3.3、Kotlin、GPL-3.0、mixin なし）の修正もあわせて行う

ソースを読んで分かったこと:

- **(不具合) 周波数の衝突で、無関係な P2P を乗っ取る**
  - `GridServerCache.linkP2P` は、新しい周波数を `System.currentTimeMillis().toShort()` で作っています。既存の周波数と重複していないかは確認していません。
  - 運悪く、同じ種類の既存の入口と周波数が重なると、「すでに使われている」と判定されます。そして、**関係のない既存の入口が出口に変えられてしまいます**。
  - 確率は、リンク1回あたり「既存の入口の数 ÷ 65535」くらいです。
  - 直し方: AE2UEL の `P2PCache.newFrequency()` を使います。これは 0 と既存の周波数を避けて作ってくれます。
- **(不具合候補) 未知の P2P の種類で例外になる**
  - `linkP2P` は `getP2PFromClass(input.javaClass)!!` を使っています。Better P2P が知らない P2P の種類（他のアドオンが追加したものなど）だと、ここで NPE になります。
  - 対策: 新しい P2P のクラスは作らず、既存の `PartP2PFEPower` を mixin で拡張する方針にします。そうすれば Better P2P との互換は保てます。
- **(負荷) リンクや種類の変更のたびに、パーツを外して付け直している**
  - `updateP2P` / `changeP2PType` は、`removePart` と `addPart` を行います。そのたびにノードが作り直され、網のリパス（再起動）が起きます。
  - 一括変更（`changeAllP2Ps`）では、この処理が P2P の数だけ連続して走ります。
  - 改善案: 種類が同じなら、`P2PCache.updateFreq` と output フラグの変更だけで済ませます。
- ※上の3件は実装済み・実機での確認待ち（2026-11-26）。`ae2opti.betterp2p.mixin.GridServerCacheMixin` ＋ `P2PCacheAccessor`、別設定 `mixins.ae2opti.betterp2p.json`（Better P2P があるときだけ読む）。
  - 周波数：`p2p.betterP2PSafeFrequency`（オン）。入口の無い出口が残っている周波数も避ける。
  - 未知の種類：`p2p.betterP2PUnknownTypeCheck`（オン）。チャットにメッセージを出して失敗にする。
  - 付け直しの負荷：`p2p.betterP2PInPlaceRelink`（デフォルトでオン）。入口／出口の向きが変わらないときだけ `updateFreq` で済ませる。ME P2P とインターフェース P2P は元の処理のまま。
- 手を入れる方法: **ae2opti 側で mixin する方針**（本家への PR はしない）。
  - 上の3つの修正は、すべて `GridServerCache` クラスの中で完結します。
  - Better P2P が入っているときだけ読み込む、後読み（late）の mixin にします。MixinBooter の `ILateMixinLoader` を使います。
  - Kotlin 製でも中身は JVM のクラスなので、mixin で触れます。`// 要ローカル検証`: Kotlin が生成するメソッド名やバイトコードの形を確認します。
  - 将来、UI まで含めた大きな改修をしたくなったら、フォークに切り替えます。
    - その場合は GPL-3.0 で公開する必要があります。
    - modid も分ける（元の jar と差し替える形にする）必要があります。

**注意**
- Better P2P 以外にも、P2P を触る Mod と競合しないか確認します。`// 要ローカル検証`
- Draconic の `long` での書き込みは、AE2Enhanced ではリフレクションで内部のフィールドを直接書き換えています。DE の公開 API（`IExtendedRFStorage`）は読み取り専用です。そのため壊れやすく、採用するかは慎重に判断します。
- AE2Enhanced は GPL-3.0 です。設計の参考にとどめ、コードは流用しません。

## 優先度5（保留）：Lazy AE2 の不具合

- Level Maintainer の GUI のちらつきと誤表示（本家 #53）は、**Lazy AE2 Patch（作者: sjcl）を導入して対応**します。自前では実装しません。
  - 原因メモ: `TileLevelMaintainer.onTileSyncPacket` が、開いている GUI がどのタイルのものかを確認せずに、数値を上書きしています。
- Lazy AE2 Patch でも直っていないものが見つかったら、そのとき opti 側で対応します。Lazy AE2 本体は MIT なので、mixin でもフォークでも自由に直せます。
  - 候補（本家の未解決 issue。中身は未確認）:
    - #61 Level Maintainer が搬入したアイテムを消す
    - #62 ケーブルを接続すると、維持対象がすべて1回ずつクラフトされる
    - #63 Fluix Aggregator をパイプで自動化すると、同じアイテムが2スロットに入る
    - #58 Mass Assembler のメモリリーク
- 参考: AE2FC の Fluid Level Maintainer は作りが違うので、#53 と同じ不具合は起きません。ただし、量が `int` で扱われている点と、負荷の問題（優先度3）は別にあります。

## 対象外（今回はやらない）

- Ender IO コンジット / GregTech 側の負荷（それぞれ約22%）。ae2opti の範囲外です。
- AE2 UEL Things（今回の計測では影響なし）。
