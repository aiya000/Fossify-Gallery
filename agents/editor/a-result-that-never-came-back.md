# エディタの結果が、編集する前に「キャンセル」で返ってくる

## おこったこと

共有のメディアを編集できるようにして（#103）、エミュレータで台本を回したときのことです。

- エディタは開いた。写真もちゃんと出ている
- 「Overwrite original」を押すと、エディタはキャッシュのコピーに保存した
  （`MediaScannerConnection: Scanned .../cache/smb-edit/to-be-edited.jpg`）
- なのに、共有には何も書き戻らない。ログには `SmbWrite` の行が1つも出ない

`onActivityResult` に一時的なログを足して分かったのは、
**書き戻しを待っている画面に、結果がまったく届いていない**ということでした。

## 原因

commons の `openEditorIntent()` は、`ACTION_EDIT` のインテントに
**`FLAG_ACTIVITY_NEW_TASK` を付けてから** `startActivityForResult()` を呼びます。

端末にエディタが2つ以上あると、あいだにシステムの「Edit with」の一覧が立ちます。
一覧は別のアプリの画面なので、`NEW_TASK` が効いて**新しいタスク**になり、
Android はその場で `RESULT_CANCELED` を返します
—— ユーザーがまだ何も選んでいない時点で、です。

呼び出した側から見ると、こうなります。

1. 鉛筆を押す → コピーを作って `remoteEdit` に覚える → エディタを開く
2. **その直後に** `onActivityResult(RESULT_CANCELED)` が来る
3. コピーはまだ手つかずなので「保存せずに閉じた」と読んで、`remoteEdit` を捨てる
4. ユーザーがそのあと編集して保存しても、**覚えているものが無い**ので何も起きない

実機で pCloud の編集（#66）が動いていたのは、
その端末ではこのアプリのエディタが既定になっていて、一覧が立たなかったからです。
一覧が立たなければ、同じアプリの画面なので `NEW_TASK` は無視され、結果はふつうに返ります。

## したこと

**リモートのメディアを編集するときだけ、このアプリのエディタを名指しで開く**ようにしました
（`Activity.openRemoteEditor()`）。暗黙のインテントを使わないので、一覧はそもそも立ちません。

他のアプリのエディタを選べなくなりますが、ここではそれでいいのです。
渡すのは**このアプリのキャッシュの中のコピー**で、外のアプリが書ける場所ではありません。

端末のメディアの編集は今までどおりで、`openEditor()` のまま
—— こちらは「別のアプリで編集する」こと自体が機能です。

## 次に気をつけること

- **`startActivityForResult()` の結果が来ないときは、まずインテントのフラグを疑うこと。**
  `FLAG_ACTIVITY_NEW_TASK` が付いていると、結果は返ってきません
  （logcat の `ActivityTaskManager` に "ignoring requestCode" が出ることもあります）
- **「結果が来ない」は「来ないこと」では見つかりません。**
  今回は「来ないはずのものが、早すぎるタイミングで来ていた」のが本当のところでした。
  結果を受ける場所にログを1行足すのが、いちばん早い確かめかたです
- **台本では、一覧が立ったこと自体を失敗として扱っています。**
  一覧が立つ＝編集が共有に届かない、なので、これは警告ではなく赤です
  （`test-device/drive/97-edit-on-the-share.sh`）
- エミュレータには Markup と Photos が入っています。実機とエディタの数が違うので、
  **実機で動いたから大丈夫、は通りません**

## 関係する場所

- `app/src/main/kotlin/org/fossify/gallery/extensions/Activity.kt` -- `openRemoteEditor()`
- `app/src/main/kotlin/org/fossify/gallery/activities/SimpleActivity.kt` -- `editMedium()`、`handleRemoteEditResult()`
- `test-device/drive/97-edit-on-the-share.sh`
