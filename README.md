<h1 align="center">Gboard Patches JP</h1>

<p align="center">
  全世界向けの機能改善と日本語入力向けの独自機能を搭載した、Gboard 用 Morphe パッチ集
</p>

## 概要

Gboard Patches JP は、[jasonwu1994/Gboard-patches](https://github.com/jasonwu1994/Gboard-patches) のフォークです。上流の全パッチに加え、日本語入力向けの独自機能を搭載しています。

Patches 設定画面は日本語を含む 13 言語に対応しており、日本語訳は上流の翻訳を採用しています。

## 収録パッチ

本リポジトリ独自のパッチです。各項目の名称は、Morphe 上で表示されるパッチ名です。

<details>
  <summary><code>Japanese QWERTY Glide</code></summary>

  日本語キーボードの ABC レイアウト上で、英単語のグライド入力に対応します。入力途中の候補表示と、確定時の半角スペース挿入にも対応します。既定値は無効です。
</details>

<details>
  <summary><code>Extended Dictionary</code></summary>

  Gboard 標準の単語リスト形式（テキストまたは ZIP）のファイルを、単語リストへ一括インポートします。インポートした単語は単語リスト内の専用区画「（拡張）」へ格納するため、利用者自身の登録単語には影響しません。日本語の単語には品詞を付与して Mozc での利用に対応し、削除時はインポート済みの単語のみを削除します。Gboard の Mozc ユーザー辞書のインポート上限も 200,000 件から 1,000,000 件へ拡大します。日本語用の単語リスト（SudachiDict 由来、Wikidata 由来）は <a href="https://github.com/c-ardinal/gboard-extended-dictionary">c-ardinal/gboard-extended-dictionary</a> で公開しています。
</details>

## 導入方法

本リポジトリを Morphe のソースとして追加します。

- [Morphe で開く](https://morphe.software/add-source?github=c-ardinal/Gboard-patches-jp)
- 手動追加の場合は `https://github.com/c-ardinal/Gboard-patches-jp` を入力

## ビルド方法

ローカル環境での Gradle 実行前に、以下のいずれかの方法で Morphe の GitHub Packages レジストリへの認証情報を設定します。

- `~/.gradle/gradle.properties` へ `gpr.user` と `gpr.key` を記述
- 環境変数 `GITHUB_ACTOR` と `GITHUB_TOKEN` を設定

Android 用パッチバンドルのビルド：

```powershell
.\gradlew.bat :patches:buildAndroid
```

パッチ情報の再生成：

```powershell
.\gradlew.bat generatePatchesList
```

生成物：

- `patches/build/libs/*.mpp`
- `patches-list.json`
- `patches-bundle.json`

## ライセンス

[GNU General Public License v3.0](LICENSE) で公開しています。
