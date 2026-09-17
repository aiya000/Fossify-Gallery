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
