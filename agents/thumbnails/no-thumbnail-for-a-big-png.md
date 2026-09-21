# 共有の大きい PNG だけ、サムネが出ない

## おこったこと

ネットワーク共有のあるフォルダを開くと、19枚ぜんぶのタイルがまっ黒でした。
フォルダを再スキャンしても変わりません。

いちばん紛らわしかったのは、**読み込み失敗の警告アイコンすら出なかった**ことです。
「まだ読み込み中」に見えるので、ネットワークが遅いのだと思ってしまいます。

## 原因

2つのことが重なっていました。

1. **Glide は、ストリームを巻き戻せる範囲でしか画像の大きさを調べられません**
    - `Downsampler.MARK_POSITION` = 5 MiB です
    - JPEG は先頭の数 KB で大きさを答えるので、28 MB でも平気です
    - PNG は `BitmapFactory` が**最後まで読んでから**答えるので、5 MiB を超えると巻き戻しに失敗して、そこで終わります
2. **その失敗を、Picasso へのフォールバックが飲み込んでいました**
    - `loadImageBase()` は PNG の読み込みが失敗すると Picasso に渡し直します
    - Picasso は `file://$path` を開くので、`smb:/...` や `pcloud:/...` では何も読めません
    - しかも `tryLoadingWithPicasso()` は例外を握りつぶすので、`onError` が呼ばれず、警告アイコンも出ません

端末のローカルファイルなら 1 は Picasso が救ってくれます。
**Picasso のフォールバックは、まさにこの「Glide が decode できない PNG」のために upstream が足したもの**です。
リモートのパスにだけ、その救いが無かったということです。

## したこと

- `helpers/ThumbnailPolicy.kt` を作って、判断を1か所に集めました
    - `mustBeSampledBeforeGlide(path, size)` -- 巻き戻せない大きさの PNG か
    - `canBeRetriedWithPicasso(type, path)` -- Picasso に渡してよいか（リモートは不可）
- `SmbStreamLoader` は、巻き戻せない PNG を**自分で縮めてから** Glide に渡します
    - 大きさは PNG の IHDR（先頭 24 バイト）から読みます
    - `BitmapFactory` に聞くと、それ自体がファイル全部を読む行為なので使えません
    - 共有から取るのは1回だけです

## 次に気をつけること

- **`isPCloudPath()` を見かけたら、`isSmbPath()` も要るかどうかを確かめてください**
    - このバグは「pCloud だけ除外して SMB を忘れた」行でした
    - 両方まとめて聞きたいときは `isRemotePath()` があります
- **pCloud は同じ問題を持ちません。** pCloud はサーバが小さいサムネを作って返すので、
  大きいファイルがアプリに届くことがそもそも無いのです
- テストは `agents/tests/a-driving-script-that-passes-without-the-fix.md` も見てください

## 関係する場所

- `app/src/main/kotlin/org/fossify/gallery/helpers/ThumbnailPolicy.kt`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbStreamLoader.kt`
- `app/src/main/kotlin/org/fossify/gallery/extensions/Context.kt` の `loadImage()` / `loadImageBase()`
- `app/src/test/kotlin/org/fossify/gallery/helpers/ThumbnailPolicyTest.kt`
- `test-device/drive/40-share-thumbnails.sh`
