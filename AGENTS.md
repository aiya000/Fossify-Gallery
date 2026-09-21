# AGENTS.md

このリポジトリで作業する AI エージェント向けの指示です。

## Git

- **現在、一時的に、ユーザーの確認なしに `git push` を許可しています**
    - この許可は一時的なものです。不要になったらこの箇条書きを消します
    - `main` への直接 push は対象外です。作業はブランチを切って行い、Pull Request で取り込みます

## Pull Request

- ベースはフォーク側（`aiya000/Fossify-Gallery`）の `main` です
- `gh pr create` には **`--repo aiya000/Fossify-Gallery` を必ず渡します**
    - このリポジトリはフォークなので、省略するとフォーク元の `FossifyOrg/Gallery` に向いた PR ができてしまいます

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
        - これは #78 のもので、まだ main にはありません。PR #77 と一緒に入ります
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

- 許可なしでよいのは `adb devices` と `adb install` だけです
    - `adb install` は画面に出ないので、ユーザーの操作を邪魔しません
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
