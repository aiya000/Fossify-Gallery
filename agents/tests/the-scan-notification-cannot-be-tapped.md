# 台本で、読み込み中の通知の「Stop」を押す

## おこったこと

#80（止められた読み込みが続きから再開する）の台本で、「通知の Stop で止めたら、続きは持たない」を見ようとしました。
通知シェードを開いて「Stop」をタップする形で書きましたが、3 つの理由で一度も押せませんでした。

1. 読み込みの通知は silent なので、シェードでは**たたまれて**いて、「Stop」が出ていない
2. 行を下にドラッグして広げると、今度は `uiautomator dump` が `ERROR: could not get idle state.` で失敗する
3. フィクスチャの共有は **15 秒ほどで読み終わる**ので、シェードを開いて 2 回 dump するあいだに、読み込みのほうが終わってしまう

## 原因

- 通知は読み込みのあいだ 0.5 秒ごとに書き換わり（`RemoteScanService.PROGRESS_INTERVAL_MILLIS`）、
  総数のない進捗バーも回り続けます。uiautomator は画面が止まるのを待ってから dump するので、いつまでも待てません
- `animator_duration_scale` を 0 にしてバーを止めても、書き換えそのものは止まらないので、だめでした
- 共有の読み込みが 2026-09 ごろより速くなっていて、9 月の台本が前提にしていた「数分かかる」はもう成り立ちません

## したこと

通知のボタンが送るのと同じ intent を、サービスへ直接送りました。
サービスは export されていないので shell からは届きませんが、エミュレータの `su` で root から送れば届きます。

```bash
"${ADB[@]}" shell su 0 am startservice \
    -n "$FIXTURE_PACKAGE/org.fossify.gallery.jobs.RemoteScanService" \
    -a org.fossify.gallery.ABORT_REMOTE_SCAN
```

画面でボタンを押したことにはなりませんが、確かめたいのは「止めたあとアプリが何をするか」なので、それで足ります。

## 次に気をつけること

- 読み込みの最中に画面を dump するチェックは、通知シェードを開いた時点でほぼ通らない。シェードを使わずに済む形を先に考える
- 「読み込みの途中で何かをする」台本は、**15 秒で読み終わる**ことを前提に待ち時間を決める。
  `20-storage-switch.sh` の rule 1-a は、スワイプが間に合わずに落ちるようになっていた（`origin/main` でも同じ。#171）
- `su` が使えるのはエミュレータ（google_apis のイメージ）だけ。実機では使えない

## 関係する場所

- `test-device/drive/88-a-called-off-walk-picks-up-where-it-stopped.sh` の case b
- `app/src/main/kotlin/org/fossify/gallery/jobs/RemoteScanService.kt` の `ACTION_ABORT`
