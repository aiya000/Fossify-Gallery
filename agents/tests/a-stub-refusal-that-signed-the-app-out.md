# pCloud の stub が知らない API に答えた番号で、アプリが pCloud からログアウトした

## おこったこと

`73-a-medium-onto-every-storage.sh` を初めて流したら、「pCloud の中での移動」のマスのあとから、
pCloud のマスが全部落ちた。

- ストレージのメニューに pCloud が無い（`nothing on screen says 'pCloud'`）
- 移動先ピッカーに pCloud のチップが無い
- 移動した写真は、移動先にも移動元にも残ったまま（移動元に残っていた）

アプリのバグに見えるが、そうではなかった。

## 原因

stub（`fixture/pcloud-stub.py`）が `renamefile` を知らなかった。知らない API への返事は
`RESULT_INVALID_REQUEST = 1000` で、名前は「不正なリクエスト」だった。

でも pCloud の 1000 番は **「Log in required」** で、アプリはそれを「トークンが死んだ」と読んで
（`PCloudException.requiresLogIn`）、pCloud のアカウントを消す。だから以降の pCloud のマスは、
チップもメニューも無い状態で走っていた。

`renamefile` は、pCloud の中でファイルを移動するときにアプリが使う API。それまでどのテストも
pCloud の中での移動を流していなかったので、stub に足りないことに誰も気づかなかった。

## したこと

- stub に `renamefile` を足した（`tofolderid` と `toname` へ移す。移動先に同じ名前があれば 2004 で断る）
- 知らない API には 5000（pCloud の「Internal error」）で答えるようにした。ログインの番号
  （1000 と 2000）は、本当にトークンを断るとき（`authorized()`）にしか使わない

## 次に気をつけること

- **pCloud のマスが、あるマスを境に全部落ちたら、まず stub のリクエストログ**
  （`runs/<時刻>/pcloud-requests.log`）の最後を見る。stub が答えられなかった API がそこにある
- stub に API を足すときは、README の「pCloud, without a pCloud account」の一覧にも足す
- stub の返事の番号は、アプリがどう読むかで選ぶ。pCloud のドキュメント上の意味と、アプリの
  `PCloudApi.kt` の読みかたの両方を見る

## 関係する場所

- `test-device/fixture/pcloud-stub.py` の `RESULT_UNANSWERED` と `serve_rename_file()`
- `app/src/main/kotlin/org/fossify/gallery/helpers/PCloudApi.kt` の `requiresLogIn`
- `test-device/drive/73-a-medium-onto-every-storage.sh`（#140）
