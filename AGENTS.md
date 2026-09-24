# AGENTS.md

このリポジトリで作業する AI エージェント向けの指示です。

## 前に見たことのある出来事

下の表は**索引**です。行だけ読んでください。
**いま扱っていることに当てはまる行があったときだけ**、その `agents/` のファイルを開きます。

当てはまる行が無ければ、何も開かなくていいです。

| おこったこと | したこと | 読むもの |
|---|---|---|
| 共有のフォルダで、サムネがぜんぶ出ない。警告アイコンも出ない。再スキャンしても変わらない | 5 MiB を超える PNG だけが落ちていた。Glide に渡す前に自分で縮めるようにした | [agents/thumbnails/no-thumbnail-for-a-big-png.md](./agents/thumbnails/no-thumbnail-for-a-big-png.md) |
| 端末を動かすテストが、直しを外したビルドでも緑になった | 「絵があるか」の測りかたを、色のばらつきから「1色が占める割合」に変えた | [agents/tests/a-driving-script-that-passes-without-the-fix.md](./agents/tests/a-driving-script-that-passes-without-the-fix.md) |
| 共有から読んだストリームを閉じても、サーバ側のハンドルが残る | smbj の `FileInputStream.close()` はハンドルを閉じない。`OpenFile` を持ち回して閉じるようにした | [agents/smb/closing-the-stream-does-not-close-the-file.md](./agents/smb/closing-the-stream-does-not-close-the-file.md) |
| 端末を動かすテストで、2回目の完了を待ったのに、待たずに素通りした | 1回目のログがバッファに残っていた。2回目の操作の直前に `logcat_reset` を入れた | [agents/tests/waiting-for-a-log-line-that-is-already-there.md](./agents/tests/waiting-for-a-log-line-that-is-already-there.md) |
| 端末を動かすテストが「ストレージのチップが画面に無い」で落ちた。チップは何も変えていない | フォルダの中にいたまま探していた。チップはフォルダ一覧のもの。戻ってから探すようにした | [agents/tests/the-toolbar-chips-belong-to-the-folder-list.md](./agents/tests/the-toolbar-chips-belong-to-the-folder-list.md) |
| 共有へ書き込んだら `STATUS_ACCESS_DENIED`。読み取りはずっと動いていた | アプリではなくフィクスチャ側。samba のイメージが `force user` で uid を固定していた | [agents/tests/the-fixture-share-was-never-writable.md](./agents/tests/the-fixture-share-was-never-writable.md) |
| 端末を動かすテストが「選択メニューに Delete が無い」で落ちた。アプリはちゃんと出している | `Delete` は `showAsAction="always"`。三点リーダではなく選択ツールバーのアイコンだった | [agents/tests/the-delete-icon-is-not-in-the-overflow.md](./agents/tests/the-delete-icon-is-not-in-the-overflow.md) |
| 共有でリネームしたら、共有のファイル名は変わったのにグリッドは古いまま。例外も出ない | DAO の宣言順と呼び出しの位置引数がずれていた。名前付き引数にした。再スキャンがずっと隠していた | [agents/database/a-rename-that-updated-nothing.md](./agents/database/a-rename-that-updated-nothing.md) |
| わざと直しを外したのに、台本の「もう出ていないはず」のチェックだけ緑のままだった | dump に入るのは手前の window ひとつ。ダイアログの後ろの Snackbar は見えない。出るほうを見るチェックに書き換えた | [agents/tests/a-dump-taken-while-a-dialog-is-up.md](./agents/tests/a-dump-taken-while-a-dialog-is-up.md) |
| エディタは写真を保存したのに、共有には何も書き戻らない。例外も出ない | `ACTION_EDIT` に `FLAG_ACTIVITY_NEW_TASK` が付いている。「Edit with」の一覧が立つと、編集する前に `RESULT_CANCELED` が返る。自前のエディタを名指しで開くようにした | [agents/editor/a-result-that-never-came-back.md](./agents/editor/a-result-that-never-came-back.md) |
| 台本でタップしたいメニューの文言が、アプリの `strings.xml` に無い | commons の AAR が持っている。ビルド成果物のマージ済みリソースをキー名で引いた | [agents/tests/the-menu-text-lives-in-the-commons-aar.md](./agents/tests/the-menu-text-lives-in-the-commons-aar.md) |
| 共有のスキャンが、固定データより十数ファイル多く数える。台本は何も足していない | samba の `recycle` モジュールが消したファイルを `.deleted/` にためていた。compose で外して、フォルダを消した | [agents/tests/the-fixture-samba-kept-every-deleted-file.md](./agents/tests/the-fixture-samba-kept-every-deleted-file.md) |
| 台本でトーストが出るのを待ったのに、アプリは出しているのに dump に一度も入らない | 2 秒のトーストは dump の 1 周より短い。他アプリの窓が前に出ることを `mCurrentFocus` で待つ形にした。chooser はタイトルではなく中身で見る | [agents/tests/a-toast-is-gone-before-the-dump.md](./agents/tests/a-toast-is-gone-before-the-dump.md) |
| 台本で「従量制の回線（モバイル回線）にいるとき」の振る舞いを見たい | エミュレータの Wi-Fi を `svc wifi disable` で切ると携帯回線に落ちて従量制になる。ホストにはそのまま届く。出口で必ず戻す | [agents/tests/mobile-data-on-the-emulator.md](./agents/tests/mobile-data-on-the-emulator.md) |
| 台本で「キーボードが出ているか」を見たら、出ているのに「出ていない」と言われた | `dumpsys input_method` の `mInputShown` は見た目と揺れる。`dumpsys window` の `type=ime ... visible=true` を数秒待って読むようにした | [agents/tests/is-the-keyboard-up.md](./agents/tests/is-the-keyboard-up.md) |
| ある機能が端末にはあって共有（や pCloud）に無い。忘れているのか、わざとなのか分からない | #107 で全部読み直して分けた。わざと違えてあるものは表にした。載っていなければ抜けなので、`MediaStorage` の capability に乗せて直す | [agents/storage/what-each-storage-does-differently-on-purpose.md](./agents/storage/what-each-storage-does-differently-on-purpose.md) |

### この索引に足すこと

作業のなかで「**これは次も起こる**」「**次も同じことをする**」と思ったものが出てきたら、
**頼まれるのを待たずに**、その場でここに足してください。

足しかたは2つだけです。

- 詳細は `agents/<英単語のカテゴリ>/<出来事の名前>.md` に書きます
    - カテゴリは `thumbnails`、`tests`、`smb` のような、ひとことの英単語です
    - ファイル名も英語です。中身は日本語で、この `AGENTS.md` と同じ書き方にします
    - 「おこったこと」「原因」「したこと」「次に気をつけること」「関係する場所」の順に書くと読みやすいです
- `AGENTS.md` には**上の表に1行足すだけ**です

**本文を `AGENTS.md` に書かないでください。**
索引が長くなるほど、毎回のセッションが索引を読むだけで重くなります。
索引は「開くかどうかを決めるための1行」であって、答えそのものではないのです。

足す価値があるのは、**コードや `git log` を読んでも分からないこと**です。

- ライブラリ側の制限や、その数値の出どころ
- 時間を溶かした落とし穴と、それをどう見分けたか
- 判断と、その理由
- ユーザーの好み

「どのファイルに何が書いてあるか」は足さなくていいです。コードを読めば分かります。

すでに近い行があるときは、行を増やさずに、そのファイルのほうを直してください。

## Git

- **現在、一時的に、ユーザーの確認なしに `git push` を許可しています**
    - この許可は一時的なものです。不要になったらこの箇条書きを消します
    - `main` への直接 push は対象外です。作業はブランチを切って行い、Pull Request で取り込みます

### 新しいブランチは `origin/main` から切ってください

```bash
git fetch origin
git switch -c <ブランチ名> --no-track origin/main
```

`git switch main` は、auto mode の判定に `[Merge Without Review]` で止められることがあります。
`gh pr merge` の直後に2回踏みました。ただのブランチ切り替えなので誤判定ですが、回避はできません。
同じ直後の素の `git fetch origin` も、同じ理由で1回止められました。`git fetch origin main` と
ブランチを名指しすると通ります。

**ローカルの `main` は古いままでかまいません。** 誰もそこからビルドしないからです。
ビルドも Pull Request のベースも、見ているのは `origin/main` のほうです。

- **`--no-track` を忘れないでください。** 付けないと upstream が `origin/main` になり、
  素の `git push` が `main` へ飛びます
- push は `git push -u origin <ブランチ名>` と、行き先を明示して行います
- ユーザーに `! git switch main` を頼むのは最後の手段です。モバイルから見ているときは
  `!` が打てないので、頼んでも進みません

## Pull Request

- ベースはフォーク側（`aiya000/Fossify-Gallery`）の `main` です
- `gh pr create` には **`--repo aiya000/Fossify-Gallery` を必ず渡します**
    - このリポジトリはフォークなので、省略するとフォーク元の `FossifyOrg/Gallery` に向いた PR ができてしまいます

## Issue のたたみかた: 片づいたら、聞かずに閉じる

**Pull Request がマージされて、その Issue の中身が本当に終わっているなら、エージェントが閉じてください。**
ユーザーに「閉じていいですか」と聞く必要はありません（2026-09-22 から。それまでは実機で
確認してもらうまで開けたままにしていました）。

ユーザーの言葉では「**エージェント中心のサイクルを作りたい**」です。進める・片づける・閉じるまでを
こちらが持って、ユーザーは**おかしかったときに報告する側**に回ります。

- **マージ ＝ 自動で全部閉じる、ではありません**
    - PR 本文の `Refs #N` は「関係する」であって「片づいた」ではありません。中身が終わったものだけを
      選んで閉じます
    - 例: PR #93 は #92 を片づけましたが、同じ本文が `Refs` している #71 と #76 は一部しか進んで
      いないので、開いたままにしました
- **だから PR 本文は `Closes #N` ではなく `Refs #N` のままにします**
    - GitHub に自動で閉じさせると、上の判断ができなくなります。判断はこちらでします
- **閉じるときは、何をもって終わったと判断したかを一言コメントに残します**
    - どの PR で入ったのか、どの台本が緑になったのか、何が残っているのか
- **残りがあるなら閉じません。** 半分進んだ Issue は、何が残っているかをコメントしてから開けておきます

### ユーザーから「これ、おかしいよ」と来たとき

1. まず**その問題に当たる Issue を探します**（閉じたものも含めて）
2. **すでに閉じている Issue だったら、reopen して直します。** 新しく立て直さないでください
   ---- 一度片づけたつもりのものがまた出てきた、という履歴そのものが手がかりになります
3. どの Issue にも当たらなければ、新しく立てます

## 報告のしかた: 部品ではなく、できるようになったことを書く

**セッションの終わりの報告は、機能・仕様の視点で書いてください**（2026-09-22 から）。
クラス名やメソッド名を並べた実装の報告は歓迎されません。

ユーザーが知りたいのは「アプリで何ができるようになったか」「何が変わったか」「まだ何ができないか」
です。どのファイルをどう変えたかは、必要になったときに `git log` と Pull Request が持っています。

- **「〜できるようになりました」から書き始めます。** 画面の名前と、ユーザーの操作の言葉で書きます
    - Good「共有の写真を、名前を変えずに上書き保存できるようになりました」
    - Bad「`SmbWriter.overwriteFile()` を追加し、`ensureWritablePath()` の分岐を外しました」
- **失敗したときにどうなるかも、仕様として書きます。** 内部の手順ではなく、ユーザーから見た結果です
    - Good「途中で失敗しても、共有に残るのは元の写真か新しい写真のどちらかです」
    - Bad「退避 → 書き込み → 退避削除の順で、失敗したら退避の名前を戻します」
- **まだできないことを必ず書きます。** どこまで進んだかは、残りが分かって初めて伝わります
- **試してほしいことがあるなら、操作の順で書きます**
- 実装の話は、聞かれたときにします。判断とその理由は、報告ではなく Pull Request 本文と
  `agents/` に書いてください。そちらのほうが長持ちします

## ビルドと動作確認

`.claude/skills/` の `debug-build` / `debug-install` / `release-build` / `release-install` を使います。
コンパイルだけ確かめたいときは `./gradlew :app:compileFossDebugKotlin`（`JAVA_HOME` に Android Studio の JBR が必要です）。

## 確かめかた: ユーザーにしか見られないもの以外は、自動テストで

**「動きました」と言う前に、自動テストを書きます。** 手で確かめてもらう作業を増やす提案は歓迎されません。
減らす提案のほうが歓迎されます。

- **ユーザーの手に残すのは、実機でしか見られないものだけです**
    - pCloud の転送、通知をタップして再生に入る、見た目や手ざわり、といったもの
- **それ以外は自動テストで確かめます**
    - JVM テスト: `app/src/test/`、`./gradlew :app:testFossDebugUnitTest`。Context を要らなくしたロジックを置きます
    - エミュレータ: `test-device/`。画面の操作と、その順序を見ます（`test-device/README.md`）
- **UI だけで純粋なテストが書けないものは、エミュレータ側に書きます**
    - どちらにも書けないときは、書かずに黙って進めず、**書けない理由を残します**

### テストを信じる前に

- **一発で緑になったテストは疑うこと。** わざとコードを壊して、赤くなることを見てから信じます
- **リソースやファイルを実行時に読むテストは、gradle がその変更に気づけません**
    - 入力として宣言しないと、タスクが UP-TO-DATE になり、**走らせずに「成功」と言います**
    - `app/build.gradle.kts` の `tasks.withType<Test>` が `src/main/res` を入力にしているのは、このためです

## 実機を操作するときは、ユーザーの許可を取ること

**`adb` で実機の画面を動かす前に、必ずユーザーに聞いてください。**

繋がっている端末は、ユーザーが今そのとき使っている端末です。
`adb shell am start` はユーザーが見ている画面を横取りしますし、`adb shell input` はユーザーの
操作とぶつかって、まったく別のアプリに入力が飛びます。

- 許可なしでよいのは `adb devices` と、**リリース版の `adb install`** だけです
    - `adb install` は画面に出ないので、ユーザーの操作を邪魔しません
    - **デバッグ版を実機に入れるときは聞いてください**（2026-09-22 から）。デバッグ版は、リリース版が
      壊れて使えないときの**緊急用の控え**になりました。黙って入れ替えると、いちばん必要なときに
      控えが無くなります。ふだん試すのはリリース版のほうです
    - エミュレータは別です。`test-device/` の台本は一日中デバッグ版を入れ直します
- `am start`、`input`、`screencap`、`uiautomator` などは、**毎回ユーザーに聞いてから**実行します
- 「今は端末に触らないで」と言われているあいだは、ビルドだけを行います
- 動作確認は、基本的にユーザーの手で行ってもらいます。エージェントが自分で画面を動かして確かめるのは、
  ユーザーが「動かしていいよ」と言ったときだけです

## 旧 application id への逃げ道

このフォークは application id を `io.github.aiya000.fossify.gallery` に変えています。
変更前の `org.fossify.gallery` としてビルドできるリビジョンを、2つの ref で残しています。

- ブランチ `original-application-id` -- 作業用です
    - ここからビルドしたアプリは application id が旧 id のままなので、端末に残っている旧アプリを**更新**でき、中のデータを保ったまま設定をエクスポートし直せます
    - 旧アプリ側に修正が要るときは、このブランチを進めます
- タグ `original-application-id-tag` -- 保険用です
    - ブランチを誤って消してしまっても、ここから復旧できます
    - ブランチを進めたら、タグも同じリビジョンに貼り直します

タグ名に `-tag` を付けているのは、ブランチと同名にすると `git rev-parse` などが
`refname is ambiguous` の warning を出し、タグのほうが優先されてしまうからです。

## セッションを終えるとき

`/create-handoff` を実行したら、**そのセッションで使った gradle デーモンを落としてください**。

```bash
JAVA_HOME=$HOME/bin/android-studio/jbr ./gradlew --stop
```

デーモンは何もしていなくても数 GB を持ったまま残ります。この WSL は 22GB しかなく、エディタの
language server が数 GB を使っているので、残したままにすると次のセッションのリリースビルドが
R8 の途中で OOM killer に殺されます。

`gradle.properties` のヒープを変えたときも、同じように先に落としてください。起動中のデーモンは
古い `-Xmx` を握ったままで再利用されないので、止めずにビルドすると**二本目が増えて**、かえって
足りなくなります。

落ちたことは `./gradlew --stop` の出力ではなく、pid で確かめてください。`ps --width 200` は
デーモンのコマンドラインを途中で切るので、生きているものを死んだと読み違えることがあります。
