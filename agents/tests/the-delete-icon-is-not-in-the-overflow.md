# 選択メニューを探したのに「Delete が出ていない」と言われる

## おこったこと

端末を動かすテストで、選択中のメニューに項目があるかを見るとき、
`open_overflow_menu` を叩いてから `ui.py --text "Delete"` を探すと、**何も見つかりません**。

アプリ側では正しく出しているのに、台本は「共有のメディアに Delete が出ていない」と言って落ちます。
これは**本物のバグとまったく同じ見た目**なので、コード側を疑って時間を溶かします。

## 原因

`Delete` は三点リーダの中に入っていません。
`cab_media.xml` と `cab_directories.xml` で `showAsAction="always"` が付いているためです。

```xml
<item
    android:id="@+id/cab_delete"
    android:icon="@drawable/ic_delete_vector"
    android:showAsAction="always"
    android:title="@string/delete" />
```

`always` の項目は選択モードのツールバーに**アイコンとして**並びます。
オーバーフローを開くと、そのアイコンは画面から外れるわけではありませんが、
開いたメニューの view tree を見に行くので、探し方が噛み合いません。

## したこと

オーバーフローを開かず、**選択した直後の画面をそのまま `ui_dump` して探す**ようにしました。

```sh
selection="$(ui_dump "55-selected")"
python3 "$DRIVE_DIR/ui.py" "$selection" --text "Delete" --exact
```

uiautomator はアイコンに `content-desc` としてタイトルを載せるので、
`ui.py` の `--text` はそのまま当たります（`ui.py` は text と content-desc の両方を見ます）。

## 次に気をつけること

- **メニュー項目を探す前に、それが `always` かどうかをメニュー XML で確かめること**
    - `always` → 選択直後の画面をそのまま dump する
    - それ以外 → `open_overflow_menu` してから dump する
- `cab_media.xml` では `cab_confirm_selection` / `cab_delete` / `cab_share` が `always` です
- `cab_directories.xml` では `cab_delete` / `cab_properties` / `cab_pin` などが `always` です
- 「出ていない」で落ちたときは、**まず run ディレクトリの xml を開く**こと
  探し方が違うだけなのか、本当に出ていないのかは、それを見ればすぐ分かります

## 関係する場所

- `test-device/drive/55-delete-on-the-share.sh` -- そのまま dump して探している例
- `test-device/drive/50-copy-off-share.sh` -- `Copy to` は `always` ではないので、オーバーフローを開く例
- `test-device/drive/lib.sh` の `open_overflow_menu`
- `app/src/main/res/menu/cab_media.xml`、`app/src/main/res/menu/cab_directories.xml`
