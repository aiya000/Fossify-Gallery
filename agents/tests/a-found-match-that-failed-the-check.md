# 見つかったのに「見つからない」になったチェック

## おこったこと

`78-add-a-share-in-the-settings.sh` で、名前を変えたあとに設定ファイルを読んで「新しい名前で保存されたか」を見るチェックが赤くなった。
画面のダンプでは新しい名前が入っていて、そのあとの手順ではその名前で行を見つけられていた。アプリは正しく保存していた。

## 原因

チェックが次の形だった。

```bash
prefs | rg -q "name=\"smb_name_1\">Renamed<"
```

`prefs` は `adb shell ... | tr -d '\r'` の関数。`rg -q` は最初の一致で終わってパイプを閉じるので、
まだ書いている途中の `adb` / `tr` が SIGPIPE で落ちる。台本は `set -o pipefail` なので、
パイプライン全体が「失敗」になり、`if` は見つからなかったほうへ進む。

ファイルが小さくて `rg` より先に書き終わると通るので、通ったり落ちたりする。

## したこと

いったん変数に受けてから探す形にした。

```bash
saved="$(prefs)"
printf '%s' "$saved" | rg -q "..."
```

`printf` は書き終わってから `rg` が閉じても困らない大きさなので、これで落ちない。

## 次に気をつけること

- **`<adb を含むコマンド> | rg -q` を `if` に書かない。** 一度変数に受ける
- `lib.sh` の `expect_log` / `refute_log` は `captured_log`（ファイルの `cat`）を読むので同じ形だが、
  ログはスナップショットのファイルなので今のところ落ちていない。`app_log` を直接 `rg -q` に渡す形を
  増やすときは同じことを疑う

## 関係する場所

- `test-device/drive/78-add-a-share-in-the-settings.sh`
- `test-device/drive/lib.sh`（`set -euo pipefail`）
