# フィクスチャの共有は、読めていただけで書けなかった

## おこったこと

#28 の「端末のファイルを共有へコピーする」を初めて動かしたら、`SmbClient.create()` の
一発目でこう落ちました。

```
com.hierynomus.mssmb2.SMBApiException: STATUS_ACCESS_DENIED (0xc0000022):
    Create failed for \\10.0.2.2\gallery\Screens\to-the-share.jpg
    at com.hierynomus.smbj.share.DiskShare.openFile(DiskShare.java:169)
    at org.fossify.gallery.helpers.SmbClient.create(SmbClient.kt:249)
```

`STATUS_ACCESS_DENIED` は、**アプリが要求するアクセス権を間違えた**ようにしか読めません。
`AccessMask` や `SMB2CreateDisposition` を疑って、そこを何度か変えたくなります。

アプリは何も間違えていませんでした。**共有のほうが書けなかった**のです。

## 原因

`dperson/samba` イメージの `smb.conf` は、グローバルに

```
force user = smbuser
force group = smb
```

を持っています。`smbuser` はコンテナの中の uid 100 です。

`docker-compose.yml` は共有のユーザー `gallery` をホストの uid/gid（1000）で作りますが、
**`force user` はそれより後に効きます**。つまりファイル操作は全部 uid 100 として行われます。

一方 `fixture/share` はホストのユーザー（uid 1000）のもので、モードは 775 です。

- uid 100 は **読める**（other に r-x がある）
- uid 100 は **書けない**（other に w が無い）

読むことしかしていなかったあいだ、この設定はまったく表に出てきませんでした。
`10-scan` も `40-thumbnails` も `50-copy-off-share` も、全部ずっと緑だったのです。

## したこと

`docker-compose.yml` の `command` に、グローバル設定の上書きを2行足しました。
`-g` はグローバルセクションに追記するオプションで、samba は**同じパラメータは後のものを採る**ので、
イメージ側の設定に勝ちます。

```yaml
    command: >
      -u "...;...;${FIXTURE_UID:-1000};...;${FIXTURE_GID:-1000}"
      -s "...;/share;yes;no;no;..."
      -g "force user = ${FIXTURE_SMB_USER:-gallery}"
      -g "force group = ${FIXTURE_SMB_USER:-gallery}"
```

`-p`（コンテナ側で共有の所有者と権限を付け替える）は使いません。
ツリーがコンテナのものになってしまい、ホストのユーザーが走らせる `seed-share.sh` が
あとから何も足せなくなります。

コンテナを作り直したあと、**台本を8分かけて回す前に**、直接1バイト書いて確かめます。

```sh
docker exec gallery-fixture-share smbclient //localhost/gallery -U gallery%gallery \
    -c 'cd Screens; put /etc/hostname probe.txt'
ls -l test-device/fixture/share/Screens/
```

## 次に気をつけること

- **共有への書き込みが `STATUS_ACCESS_DENIED` で落ちたら、アプリより先に共有を疑います。**
  読めることは、書けることを何も保証しません
- 共有の設定を変えたら、**アプリを通さずに `smbclient` で1回書いて**から台本を回します。
  台本は共有を丸ごと歩くので、1周が数分かかります
- `docker exec gallery-fixture-share cat /etc/samba/smb.conf` が、実際に効いている設定です。
  `docker-compose.yml` に書いたことと、イメージが最初から持っているものは別です

## 関係する場所

- `test-device/fixture/docker-compose.yml` の `command`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbClient.kt` の `create()`
- `test-device/drive/70-copy-to-the-share.sh`
