# 台本で「キーボードが出ているか」を見る

## おこったこと

#132（「名前を付けて保存」でキーボードを最初から出さない）の台本を書いたとき、
`dumpsys input_method` の `mInputShown=true` でキーボードを見ようとしました。

直す前のビルド（キーボードを自分で呼んでいる）で回したのに、「キーボードは出ていない」で**緑になりました**。
スクリーンショットには、はっきりキーボードが写っていました。
同じ台本をもう一度回すと、今度は `true` を返しました。答えが揺れます。

## 原因

`mInputShown` は入力メソッドの管理側が「見せるよう頼まれたか」の覚え書きで、画面に実際に出ているかとは
いつも一致しません（Android 15 のエミュレータで確かめました）。

キーボードはアプリとは別の window なので、`uiautomator dump` にも出てきません。

## したこと

`dumpsys window` の、キーボードぶんの inset を読むことにしました。

```
InsetsSource id=3 type=ime frame=[0,1517][1080,2400] visibleFrame=... visible=true
```

これは window manager が「画面のこの帯をキーボードが取っている」と持っている値なので、見た目と一致します。
`test-device/drive/lib.sh` の `keyboard_is_shown` / `wait_for_keyboard` がそれです。

キーボードは滑って出てくるので、1 回読むのではなく数秒待ちます。
「出ていないはず」のチェックも、その数秒のあいだ一度も出なかったことを見ます。

## 次に気をつけること

- キーボードを見るチェックは、**必ず直す前のビルドで赤を見てから**信じること。今回は一発緑を疑って見つかった
- キーボードが出ていると、ダイアログのボタンが隠れてタップできない。戻るキーを 1 回送ると、キーボードだけが下りる

## 関係する場所

- `test-device/drive/lib.sh` の `keyboard_is_shown` / `wait_for_keyboard`
- `test-device/drive/99-save-as-proposes-a-new-name-on-the-device.sh`
