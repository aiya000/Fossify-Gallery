# ダイアログが出ているあいだの dump には、その後ろの画面が入っていない

## おこったこと

「共有への上書きが断られなくなったこと」を確かめたくて、
台本に**断り文句がもう出ていない**ことを見るチェックを書きました。

```sh
confirm="$(ui_dump "85-confirm")"
if python3 "$DRIVE_DIR/ui.py" "$confirm" --text "cannot be written over" > /dev/null; then
    fail "the app still turns away a write over a medium of the share"
fi
```

わざと断りを復活させたビルドで試したところ、**このチェックは赤くなりませんでした**。
落ちたのは、その次に書いていた「上書きするか聞いてくるはず」のチェックのほうです。

## 原因

`uiautomator dump` は、**いちばん手前の window ひとつ**を吐きます。

このときの断り文句は Snackbar（`showInAppMessage()`）で、Activity の content view に出ます。
その手前に Save as ダイアログが立っているので、dump に入るのはダイアログの window だけで、
後ろの Activity は丸ごと入ってきません。

トーストは別 window なので dump に入ります（`45-properties-on-the-share.sh` が
"does not exist" を見られているのはそのためです）。
**Snackbar はトーストと違って、後ろに隠れる**、というのがここの差です。

## したこと

「前の振る舞いがもう無いこと」ではなく、
**「新しい振る舞いがあること」**を見るチェックに一本化しました。

```sh
if python3 "$DRIVE_DIR/ui.py" "$confirm" --text "already exists" > /dev/null; then
    pass "the app asks whether to write over it"
else
    fail "nothing asked about the name that is taken (view tree in $confirm)"
fi
```

断りを復活させたビルドでは、Save as ダイアログが出たまま何も聞かれないので、
こちらはちゃんと赤くなります。

## 次に気をつけること

- **ダイアログが出ている状態で、その後ろのものを見るチェックは書かないこと。** 効きません
    - Snackbar、`findViewById(android.R.id.content)` に足したもの、画面そのもの、すべて同じです
    - トーストと `AlertDialog` は別 window なので dump に入ります
- 「前の振る舞いが消えたこと」は、たいてい**「新しい振る舞いが出たこと」で言い換えられます**。
  言い換えられるなら、そちらを書いてください。後ろを見るチェックより丈夫です
- どうしても後ろを見たいときは、**ダイアログを閉じてから dump する**しかありません
- **これは「わざと壊して赤を見る」でしか分かりません。** 緑のままのビルドでは、
  効かないチェックと効いているチェックの区別が付きません

## 関係する場所

- `test-device/drive/85-overwrite-on-the-share.sh` -- 言い換えたあとのチェック
- `test-device/drive/lib.sh` の `ui_dump`
- `app/src/main/kotlin/org/fossify/gallery/extensions/Activity.kt` の `showInAppMessage()`
