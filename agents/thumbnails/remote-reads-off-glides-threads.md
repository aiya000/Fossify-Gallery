# 共有を見たあと、端末のサムネが長く出ない

## おこったこと

共有のトップレベルを重く更新してから端末に切り替えると、端末のアルバムやグループのサムネが
長いあいだ出ませんでした。アプリを切って入れ直すほどでした（#165）。

## 原因

- Glide は、端末のサムネもリモートのサムネも、**ひとつの小さなプール**（`glide-source-thread-N`、
  4 本くらい）でデコードします
- 共有のサムネは、写真を丸ごとネットから読んでから縮めます。読み始めた fetcher は、
  読み終わるまでそのスレッドを握ります。**Glide の `cancel()` はフラグを立てるだけ**で、
  走っている読み込みは止まりません
- Glide は、キューに残ったままの仕事はキャンセル時に捨てます。でも走り出したものは最後まで走ります
- それに加えて、`DiskCacheStrategy.RESOURCE` ではデコード中にもストリームから読むので、
  共有から直接ストリームを渡していると、**デコード中の Glide のスレッドでネットを読んでいる**ことになります

数本の共有の読み込みでプールがふさがると、端末のタイル（数ミリ秒）がその後ろで待たされます。

## したこと

- `helpers/RemoteThumbnails.kt`: リモートのサムネ専用のスレッドプール（3 本、優先度つきキュー）
    - Glide の `Priority` で並べ、同じ優先度は先着順です
- `SmbStreamLoader` と `PCloudStreamLoader` の fetcher を**非同期**にしました
    - `loadData()` は仕事をプールに渡してすぐ返ります。Glide は別スレッドからのコールバックを
      正式に受け付けます（`SourceGenerator` → `DecodeJob.reschedule()`。OkHttp 連携と同じ形です）
    - 共有の写真は `SmbFileCache` に落としてから `FileInputStream` を渡します。pCloud は
      バイト列に読み切ってから渡します。どちらも**デコード時にネットを触らない**ためです
    - キューで待っているあいだにキャンセルされた fetch は、読まずに `onLoadFailed` で返します
- `loadImageBase()` の優先度を、端末は `HIGH`、リモートは `NORMAL` にしました（上流は全部 `LOW`）
- `SmbFileCache.fetch()` に、同じコピー名ごとのロックを入れました。サムネとビューアが同じ写真を
  同時に取りに行くと、`.part` ファイルを壊し合うからです

## 次に気をつけること

- **Glide の fetcher の中で、長いブロッキング I/O をしないこと。** したくなったら
  `RemoteThumbnails.submit()` に渡して、終わったらコールバックする形にします
- **`onDataReady()` に渡すストリームは、端末の上のものにすること。** ネットのストリームを渡すと、
  デコードが Glide のスレッド上でネットを読みます
- 速いローカルネットでは、遅さそのものは再現できません。テストは「どのスレッドで読んだか」を
  ログで見ます（`RemoteThumbnails` タグ、`Read the thumbnail of … on <thread>`）。
  `test-device/drive/40` と `41` です
- サムネの読み込みが `SmbFileCache`（512 MB）を通るようになったので、大きなフォルダをスクロール
  するとキャッシュが入れ替わります。ビューアが取っておいたコピーが押し出されることがあります

## 関係する場所

- `app/src/main/kotlin/org/fossify/gallery/helpers/RemoteThumbnails.kt`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbStreamLoader.kt`
- `app/src/main/kotlin/org/fossify/gallery/helpers/PCloudStreamLoader.kt`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbFileCache.kt`
- `app/src/main/kotlin/org/fossify/gallery/extensions/Context.kt` の `loadImageBase()`
- `app/src/test/kotlin/org/fossify/gallery/helpers/RemoteThumbnailsTest.kt`
- `test-device/drive/40-share-thumbnails.sh`、`test-device/drive/41-remote-thumbnails-off-glides-threads.sh`
