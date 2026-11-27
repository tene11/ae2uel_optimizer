# Changelog

## [1.0.0] - 2026-11-27

First release. / 最初のリリース。

### Added
- Energy grid: try the network's own storage first, reuse the list of networks linked through quartz fibers, and skip the empty `removeIf` scan. Networks with many subnets no longer get heavy.
- Crafting CPUs: share the per-network time budget evenly, remember busy interfaces/Packagers within a tick, skip the ingredient check when every target is busy, and slow down CPU status screen updates only under heavy load.
- FE P2P: cache output targets, skip full outputs, inject into ME Controllers/Energy Acceptors once per tick, collect the tunnel tax once per tick, and move FE as `long` (with Draconic Evolution, PackagedDraconic and Flux Networks support).
- Config: every feature can be turned off, and every mixin can be disabled (`mixins` category).
- Commands: `/ae2opti ghostcheck`, `/ae2opti energylinks`, `/ae2opti fibercheck`.

### Fixed
- Ghost nodes: tiles from a chunk or dimension unloaded before AE2 finished setting them up stayed on the network. This could connect a subnet to the main network and leak items into its storage bus.
- One crafting CPU always ending up last and slow.
- FE P2P: loop-back connections breaking the distribution, and `getEnergyStored` overflowing to a negative number.
- Betterer P2P: new links taking over an unrelated P2P with the same frequency, crashes with unknown P2P types, and network rebuilds on every relink.

### 追加
- 電力まわり：まず自分の網の電力で試す、石英ファイバーでつながった網の一覧を使い回す、無駄な `removeIf` の走査を省く。サブネットが大量にあっても重くならないようにしました。
- クラフト CPU：網ごとの時間の予算を均等に分ける、埋まっているインターフェース・Packager を tick の間覚える、送り先が全部埋まっているときは材料チェックを省く、重いときだけ CPU の状況画面の更新間隔をあける。
- FE P2P：出口の先を覚える、満タンの出口を飛ばす、コントローラー・Energy Acceptor へは tick に1回まとめて入れる、トンネル税を tick ごとにまとめて取る、`long` での受け渡し（Draconic Evolution・PackagedDraconic・Flux Networks に対応）。
- 設定：機能を1つずつオフにでき、mixin ごと読み込まないようにもできる（`mixins` カテゴリ）。
- コマンド：`/ae2opti ghostcheck`・`/ae2opti energylinks`・`/ae2opti fibercheck`。

### 修正
- 幽霊ノード：AE2 が準備を終える前にアンロードされたチャンクやディメンションのタイルが網に残り続け、サブネットがメインネットとつながってストレージバスにアイテムが混ざる原因になっていた。
- 特定のクラフト CPU だけずっと後回しで遅くなる。
- FE P2P：ループ配線で配分が壊れる、`getEnergyStored` があふれてマイナスになる。
- Betterer P2P：同じ周波数の無関係な P2P を乗っ取る、知らない種類の P2P で落ちる、つなぎ直すたびに網が作り直される。
