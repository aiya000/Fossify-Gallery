# 2回目を待ったつもりが、1回目のログで素通りしていた

## おこったこと

`test-device/drive/` の台本で、同じ操作を2回して、2回目の完了を `wait_for_log` で待っていました。

```sh
ui_tap_text "$FIXTURE_LOCAL_DESTINATION_NAME" "50-pick-destination-again"
wait_for_log "Copied 1 of 1 off the share" 180 "50-copy-again"
```

この `wait_for_log` は、**1回目のログがまだバッファに残っているので、待たずにすぐ返ります**。
2回目がまだ始まってもいないのに「終わった」ことになって、そのあとのファイルの確認が早すぎる
タイミングで走ります。

今回は後続がファイルシステムの確認だったので、落ちるとしても「無いはず」ではなく
「まだ無い」で落ちる形でした。つまり**失敗が嘘になる**ほうで、通ってしまうほうではありません。
ですが逆向きの台本、たとえば `refute_log` で「出てはいけない」を見るものだと、
**1回目のログを見て落ちる**ことになります。

## 原因

`wait_for_log` は `app_log` の**全体**を毎回 `rg` で見ます。
`logcat` のバッファは前の操作のぶんを持ったままなので、同じ文言を2回待つことができません。

## したこと

2回目の操作を始める**直前**に `logcat_reset` を呼びます。

```sh
ui_tap_text "This device" "50-pick-local-again"
logcat_reset
ui_tap_text "$FIXTURE_LOCAL_DESTINATION_NAME" "50-pick-destination-again"
```

`capture_log` でファイルに落としたぶんは消えないので、1回目のログは残ります。

## 次に気をつけること

- **同じ文言を2回待つ台本を書いたら、あいだに `logcat_reset` を入れます**
- 待つ文言に、回ごとに変わるもの（ファイル名、件数、行き先）が入っているなら、その必要はありません。
  入れられるならそちらのほうが素直です
- `wait_for_log` が**すぐ**返ったときは疑います。ネットワーク越しの操作が1秒で終わることはありません

## 関係する場所

- `test-device/drive/lib.sh` の `wait_for_log` / `logcat_reset` / `capture_log`
- `test-device/drive/50-copy-off-share.sh` の「copying the same file a second time」
