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
