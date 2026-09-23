# メニューの文言が `strings.xml` に無い

## おこったこと

台本で「Restore selected files」や「Empty the recycle bin」をタップしたくて、
`app/src/main/res/values/strings.xml` を探したが、そのキーが無かった。
`R.string.restore_selected_files` はコードにあるのに、文言がどこにも書かれていないように見えた。

## 原因

commons ライブラリ（`org.fossify.commons`）が持っている文言。AAR の中にあって、リポジトリには無い。
`menu/*.xml` の `@string/...` のうち、アプリ側の `strings.xml` に無いものはぜんぶこれ。

## したこと

一度でもビルドしていれば、マージ済みのリソースがここに展開されている。
アプリ側と commons 側の両方が一枚になっているので、キー名で引けば文言が出る。

```sh
rg -n 'name="restore_selected_files"' app/build/intermediates/merged-not-compiled-resources/ --glob 'values.xml'
```

gradle のキャッシュ（`~/.gradle/caches/*/transforms/*/jetified-commons-*/res/values/values.xml`）にもあるが、
場所がバージョンごとに変わるので、ビルド成果物のほうが早い。

## 次に気をつけること

- 台本に書く文言は、この方法で**正確な文字列**を確かめてから書く。
  「Empty recycle bin」ではなく「Empty the recycle bin」で、`--exact` はその差で落ちる
- commons のバージョンを上げたら、文言が変わっていることがある。台本が突然「画面に無い」で落ちたら、まずここを引き直す

## 関係する場所

- `test-device/drive/55-delete-on-the-share.sh` -- ごみ箱のメニューを 3 つ引いている
- `test-device/drive/lib.sh` の `tap_action` -- ツールバーとオーバーフローの両方から `--exact` で探す
