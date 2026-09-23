# 共有のスキャンが、固定データより多いファイル数を返す

## おこったこと

台本 55 で共有を再スキャンしたら、`Walked the share: 2007 folders, 2032 files` と出た。
`manifest.env` の約束は 2016 ファイル。ごみ箱の 2 ファイルを歩いたにしては 16 も多い。
10-scan-whole-share.sh も、同じ理由で落ちる状態になっていた。

## 原因

固定データの samba イメージ（`dperson/samba`）の smb.conf に `vfs objects = ... recycle ...` が入っていて、
SMB 経由で消したファイルは消えずに、共有の中の隠しフォルダ `.deleted/` へ移されていた
（`recycle:repository = .deleted`、`recycle:versions = yes` なので同名は `Copy #n of` で積み上がる）。

削除・移動・上書きの台本が走るたびに 1 つずつ増えて、スキャナはドットフォルダも歩くので、
数か月ぶんの削除が数に乗っていた。

## したこと

- `docker-compose.yml` に `-g "vfs objects = catia fruit streams_xattr"` を足して、`recycle` を外した
  （`-g` は global セクションの末尾に足され、同じパラメータは最後の指定が勝つ）
- ホスト側の `test-device/fixture/share/.deleted/` を消した（`rm-dust`）
- `docker compose ... up -d` でコンテナを作り直した。設定は起動時に組み立てられるので、restart では効かない

## 次に気をつけること

- スキャン数が「固定データより少し多い」ときは、まず `fd -H -t d '^\.' test-device/fixture/share --max-depth 1` で
  隠しフォルダを見る。台本のせいでも、アプリのせいでもないことがある
- アプリのごみ箱 `.gallery-recycle-bin/` は、スキャナが名指しで避ける。`.deleted/` は避けないし、避けるべきでもない
  （ユーザーの NAS がそう設定されていれば、それはユーザーのフォルダ）

## 関係する場所

- `test-device/fixture/docker-compose.yml`
- `test-device/drive/55-delete-on-the-share.sh` -- 再スキャンの数を `$FIXTURE_MEDIA` に固定している
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbScanner.kt` の `isRecycleBinFolder()`
