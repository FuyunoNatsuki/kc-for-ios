# 艦ブラ — iPhone用 艦これ専用ブラウザ

DMM版「艦隊これくしょん」を iPhone / iPad で快適に遊ぶための、WKWebView ベースの軽量ブラウザです。

## 機能
- ゲーム画面（1200×720）だけを切り出して画面いっぱいに等倍率フィット
- 横画面固定・ステータスバー/ホームバー非表示・端スワイプ誤爆防止
- 自動ロック無効、マナーモードでもゲーム音を再生
- 長押しメニュー・文字選択・ダブルタップズームを抑止
- スクリーンショット保存、ログイン状態の保持／ワンタップでログアウト
- **情報パネル**（左端の ☰ の下のボタンで開閉）
  - 艦隊：HP・損傷（小破/中破/大破）・cond（疲労）・補給の要否
  - 遠征・入渠・建造：残り時間のカウントダウン
  - 任務：受注中の任務と進捗（任務画面で表示したページ分）
- 戦闘後に大破艦がいると画面上部に赤い警告、パネルボタンも赤に
- 遠征帰還（1分前）・入渠完了・建造完了をiPhoneに通知

情報はゲームのAPI応答を **読み取るだけ** で、通信の改変や自動操作は行いません。
ただし運営は外部ツールの利用を認めていないため、使用は自己責任でお願いします。
戦闘後のHPは被ダメージから計算した予測値で、ダメコンや一部の特殊な戦闘では誤差が出ることがあります。

---

## インストール方法（Macなし）

### ① GitHubでアプリをビルドする（iPhoneだけでOK）
1. GitHubで新しいリポジトリを作る（Public推奨。Privateだと無料のビルド時間が少なめ）
2. このフォルダの中身をすべてアップロード
   （`.github/workflows/build.yml` も含めること。隠しフォルダなので、
   Webからは「Add file → Create new file」でパスに `.github/workflows/build.yml` と入力し、中身を貼り付けると確実です）
3. 「Actions」タブでビルドが走るのを待つ（約5〜10分）
4. 完了すると「Releases」に `v1.0.<ビルド番号>` として `KanBrowser.ipa` が置かれ、
   SideStore 用のソース `source.json` も自動で更新される

### ② iPhoneにインストールする
`.ipa` は未署名なので、自分のApple IDで署名してくれるツールで入れます。

**おすすめ：SideStore（最初の1回だけPCが必要／Windows・Linux・Chromebookも可）**
1. iPhoneに App Store から **LocalDevVPN** を入れる
2. PCに iTunes（Windowsの場合）と **iloader** を入れ、iPhoneをケーブル接続
3. iloader で Apple ID にサインインして SideStore をインストール
4. iPhoneで 設定 → 一般 → VPNとデバイス管理 で開発元を「信頼」、
   プライバシーとセキュリティ → デベロッパモード をオン（再起動）
5. LocalDevVPN を接続してから SideStore を開き、「Sources」→「＋」で次のURLを追加
   ```
   https://raw.githubusercontent.com/FuyunoNatsuki/kc-for-ios/main/source.json
   ```
6. 追加したソースの「艦ブラ」から「FREE（入手）」をタップしてインストール

以降は PC不要です。
- **アプリの更新**：GitHubに push するたびに新しい版がソースに載り、SideStore の「My Apps」に
  「UPDATE」が出るので、タップすれば最新版に更新されます（設定やデータはそのまま）。
- **7日の期限**：無料Apple IDは7日で切れますが、SideStore がバックグラウンドで自動更新します
  （無料Apple IDはSideStore込みで3アプリまで）。

ソースを使わずに入れたい場合は、Releases から `KanBrowser.ipa` を「ファイル」アプリに保存し、
SideStore の「My Apps」の「＋」から選んでも入れられます（この場合は更新も手動です）。

**PCが一切ない場合**
- iOS 27 以降なら、iPhoneだけで SideStore を入れられる「SideInstaller」という方法もあります。
  ただし配布元の証明書でいったん入れる仕組みのため、Apple IDを入力する点も含めて自己責任です。
  公式サイトとGitHub以外から入手したものは使わないでください。
- 有料の Apple Developer Program（年額）に入れば、GitHub Actions で署名して TestFlight 経由で配信する方法も取れます。

### Macがある場合
```
brew install xcodegen
xcodegen
open KanBrowser.xcodeproj
```
ターゲットの Signing で自分の Apple ID を Team に選び、iPhone を接続して Run。

---

## 使い方
- 起動すると DMM GAMES の艦これページが開くので、DMMアカウントでログイン
- 初回起動時の通知の許可は「許可」に（遠征通知に使います）
- 画面左端の ☰ でメニュー、その下のボタンで情報パネル
- ゲーム画面がずれる場合はメニューの「表示位置の微調整」で調整

## 注意
- 艦これは日本国内からの接続のみ対応です。
- DMMやゲーム側の仕様変更で、フィット表示や情報パネルが動かなくなることがあります。
  フィット表示は「画面にフィット」をオフにすれば通常表示で遊べます。
