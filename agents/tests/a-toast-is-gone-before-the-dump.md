# トーストを待つチェックは、dump が追いつかない

## おこったこと

「地図に表示」が共有の写真の EXIF をちゃんと読んだことを確かめたくて、
GPS の無い写真で **"Unknown location" のトーストが出ること**を見るチェックを書きました。
タップ直後から dump を 6 回まわして、どれかに入っていれば緑、という形です。

アプリはトーストを出していたのに（目で見えます）、**6 回の dump のどれにも入りませんでした**。

## 原因

`toast()` の既定は `LENGTH_SHORT` で、画面に出ているのは 2 秒です。
`ui_dump` は `uiautomator dump` と `adb pull` で、このエミュレータでは 1 回に 2〜3 秒かかります。

しかも「共有から取ってくる…」のトーストが先に出て、その後ろに "Unknown location" が
**キューで並ぶ**ので、出るタイミングは「タップから何秒後」で決めうちできません。
運がよければ入る、というチェックにしかならないのです。

「ダイアログの後ろは dump に入らない」（[a-dump-taken-while-a-dialog-is-up.md](./a-dump-taken-while-a-dialog-is-up.md)）とは別の話で、
トーストは別 window なので入ること自体は入ります。**入っている瞬間を撮れない**のが問題です。

同じ回で、「壁紙に設定」の chooser に **タイトル "Set as" が view tree に無い**ことでも落ちました。
chooser のシートは、並んでいるアプリ名と用途（"Wallpaper"）しか tree に載せません。

## したこと

トーストではなく、**前に出てくる他アプリの窓**を証人にしました。

- 写真に GPS を **Pillow で書き込んでから**共有に置き、「地図に表示」で **Google Maps が
  前に出てくる**ことを `dumpsys window` の `mCurrentFocus` で待ちます
    - Pillow で GPS を書くときは、`exif.get_ifd(0x8825)` が返す dict に足しても書き出されません。
      `exif[0x8825] = {...}` と **本体にぶら下げる**必要があります
- 「他のアプリで開く」「壁紙に設定」は、`com.android.intentresolver` が前に出ることと、
  シートに **"Wallpaper"** が並ぶことを見ます。タイトルは見ません
- 「印刷」は `com.android.printspooler` が前に出ることを見ます
- 他アプリを開いたあとは `am force-stop` で落として戻ります。初回起動の Maps は
  何画面も深いので、BACK では戻れません

```sh
focused_window() {
    "${ADB[@]}" shell dumpsys window | tr -d '\r' | rg -o 'mCurrentFocus=.*' | head -n 1
}
```

## 次に気をつけること

- **トーストを「出ること」の証人にしないこと。** 2 秒は dump の 1 周より短いです
    - 「出ないこと」を見るチェック（45 の "does not exist"）も、同じ理由で
      **出ていても見逃す**ので、緑が何も言っていない可能性があります
- 証人になるのは、**時間が経っても残るもの**です。ログの行、他アプリの窓、
  ダイアログ、共有の上のファイル
- chooser を見るときは **タイトルではなく中身**（アプリ名や用途）で見ること
- 他アプリの窓を待つ `wait_for_foreign_window` は `47-hand-out-a-medium-of-the-share.sh` に
  あります。2 本目が要るようになったら `lib.sh` に上げてください

## 関係する場所

- `test-device/drive/47-hand-out-a-medium-of-the-share.sh` -- 窓を待つ形にしたあとのチェックと、GPS の書き込み
- `test-device/drive/lib.sh` の `ui_dump`
- `app/src/main/kotlin/org/fossify/gallery/extensions/Activity.kt` の `showFileOnMap()` と `withLocalMediaFiles()`
