# 手順のあいだの強制終了が、前の手順の転送を消していた

## おこったこと

`test-device/drive/72-a-folder-onto-every-storage.sh` で、pCloud へのコピーのマスが、
流すたびに違うところで赤くなりました（#144 を直している途中、2026-09-29）。

- pCloud スタブの記録では、フォルダは `AaDevCopy (1)` のようにちゃんとできている
- でも、そのあとの `uploadfile` / `copyfile` が一度も来ていない
- アプリのログにはエラーが何も無い
- そのマスだけを最後に流すと、緑になる

36 マス全部を流したときだけ出る #145（共有から pCloud への移動で写真が届かない）も、同じ形でした。

## 原因

72 は、マスごとに `app_stop`（`am force-stop`）と `app_start` で始め直していました。

強制終了は、アプリのプロセスごと、転送のサービス（`PCloudTransferService` / `SmbTransferService`）も止めます。
前のマスで OK を押したあと、フォルダを作って転送を渡すまでには少し時間がかかります。
その途中で次のマスの強制終了が来ると、転送は渡される前に、あるいは走っている途中で消えます。

#144 の直しで、pCloud が名前を断ったときに「(1)」で取り直す往復がひとつ増え、間に合わないマスが増えたので目立ちました。

## したこと

`lib.sh` に `app_restart_screen` を足して、72 はそれで始め直すようにしました。
タスクを消してランチャーから開き直すだけなので、プロセスもサービスも生きたままです。

## 次に気をつけること

- **転送を渡したあとの手順で、`app_stop` を使わないでください。** 画面を始め直したいだけなら `app_restart_screen` です
- 「フォルダはできたのに中身が来ない」「そのマスだけ流すと緑」は、まずこれを疑います
- スタブの記録（`pcloud-requests.log`）で、`createfolder` のあとに `uploadfile` / `copyfile` が来ているかを見ると見分けられます

## 関係する場所

- `test-device/drive/lib.sh` の `app_restart_screen`
- `test-device/drive/72-a-folder-onto-every-storage.sh` の `cell()`
- #144、#145
