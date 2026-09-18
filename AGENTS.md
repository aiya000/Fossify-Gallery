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
