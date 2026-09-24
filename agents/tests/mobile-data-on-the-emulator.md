# エミュレータを「モバイル回線」にする

## おこったこと

#124 で、「従量制の回線では再スキャンの前に聞く」を台本で確かめたくなりました。
アプリが見ているのは `ConnectivityManager.isActiveNetworkMetered` で、
これを台本から真にする方法が要りました。

## したこと

**エミュレータの Wi-Fi を切るだけ**で足ります。

```sh
adb -s emulator-5554 shell svc wifi disable   # 携帯回線に落ちる。システムは従量制として扱う
adb -s emulator-5554 shell svc wifi enable    # 戻す
```

`gallery-fixture`（API 35, google_apis）は Wi-Fi と携帯回線の両方を持っていて、
Wi-Fi を切ると携帯回線（`Transports: CELLULAR`）がデフォルトになります。
携帯回線には `NOT_METERED` が付かないので、アプリからは従量制に見えます。

**ホスト（`10.0.2.2`）には、携帯回線のままで届きます。** NAT は同じなので、
フィクスチャの共有も pCloud スタブも、そのまま見えます。
だから「はい」と答えたあとのスキャンが、本当に走るところまで見られます。

## 次に気をつけること

- **切り替わったことは `dumpsys connectivity --short` で待つこと**。
  `svc wifi disable` は即座に返りますが、携帯回線が検証されてデフォルトになるまでに数秒あります
    - `Active default network: <id>` の行で id を取り、`network{<id>}` の行に
      `Transports: CELLULAR` があるかを見ます。`92-ask-before-a-rescan-on-mobile-data.sh` の
      `default_transport_is` がその形です
- **台本の出口で必ず Wi-Fi を戻すこと**（`trap ... EXIT`）。戻し忘れると、そのあとの台本が
  ぜんぶ「従量制」で走って、設定次第で赤くなります
- `seed-app.sh` は `*_rescan_on_unmetered_only` を**わざと false** で書いています
  （アプリの既定は true）。この設定を見たい台本だけ `FIXTURE_RESCAN_ON_UNMETERED_ONLY=true` を渡します

## 関係する場所

- `test-device/drive/92-ask-before-a-rescan-on-mobile-data.sh`
- `test-device/fixture/manifest.env` の `FIXTURE_RESCAN_ON_UNMETERED_ONLY`
- `app/src/main/kotlin/org/fossify/gallery/helpers/SmbSyncPolicy.kt` / `PCloudSyncPolicy.kt` の `isNetworkAllowed`
