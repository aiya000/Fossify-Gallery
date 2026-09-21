# ストレージのチップが「無い」のではなく、フォルダの中にいた

## おこったこと

`test-device/drive/60-copy-to-pcloud.sh` で、コピーが終わったあとに行き先のストレージへ
切り替えようとして、こう落ちました。

```
FAILED: the storage chip is not on screen (view tree in .../60-storage-pcloud-after-chip.xml)
```

チップが消えるような変更は何もしていません。落ちた理由は画面がちがったことでした。

## 原因

台本は共有のフォルダを開いて、その中のメディアを選んでコピーを始めます。
コピーが終わっても、**アプリはそのフォルダの中にいたまま**です。

`switch_storage_to` が探す `storage_filter` のチップは、フォルダ一覧のツールバーのものです。
フォルダの中（メディアのグリッド）には無いので、「チップが画面に無い」という、
UI の不具合のように読める失敗になります。

## したこと

チップを探す前に、フォルダ一覧まで戻ります。戻れたことは、一覧にしかないもの
（共有のフォルダ名）が見えることで確かめます。

```sh
for _ in 1 2 3; do
    "${ADB[@]}" shell input keyevent KEYCODE_BACK
    sleep 2
    if ui_wait_exact_text "$FIXTURE_COPY_SOURCE_FOLDER" 10 "60-back"; then
        break
    fi
done
```

`KEYCODE_BACK` を3回まで送るのは、**選択が残っているときの1回目は選択を解くだけ**で、
画面から出ないからです。

## 次に気をつけること

- **ツールバーのものを探す前に、いまどの画面にいるかを台本の側で決めておきます。**
  グリッドとフォルダ一覧では、ツールバーの中身がちがいます
- 「〇〇が画面に無い」で落ちたときは、まず**画面がちがう**ことを疑います。
  失敗のメッセージは UI の不具合のように見えますが、たいていは台本の居場所の問題です
- 戻れたことは「戻るを押した」ではなく、**戻った先にしかないものが見えること**で確かめます

## 関係する場所

- `test-device/drive/lib.sh` の `switch_storage_to` / `open_overflow_menu`
- `test-device/drive/60-copy-to-pcloud.sh` の「and the copy shows up in the app」
