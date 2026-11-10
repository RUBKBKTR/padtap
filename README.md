# PadTap

Bluetoothコントローラーで、Android の画面を**タップ操作**するツール。

## 📱 アプリ版（Shizuku）

**[APKをダウンロード](https://github.com/riubekubecktor/padtap/releases/latest/download/padtap.apk)**

- どのアプリ・ブラウザでも動く。本物のマルチタッチを送る
- コントローラーを独占するので、Bでブラウザが戻ったりしない
- ゲームごとの配置をプロファイルで保存
- 必要なもの：[Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api)（ワイヤレスデバッグで開始。再起動のたびに開始し直す）

仕組み：Shizuku の権限（shell）で `/dev/input` からコントローラーを直接読み取り（EVIOCGRAB で独占）、`InputManager.injectInputEvent` でタッチを注入。カーソルや配置マークは「他のアプリの上に表示」のオーバーレイ。ソースは `android/`。CI でエミュレーター上の仮想コントローラーを使った自動テストをしている。

## 🔖 ブックマーク版

- 左スティック / 十字キー：画面上のカーソルを移動（右スティックは微調整）
- A：カーソル位置をタップ（押しっぱなしで長押し・ドラッグ）
- その他のボタン：設定した画面位置をタップ（ゲームごとに保存）
- SELECT＋START：ボタン配置の設定

### 使い方

Chrome でブックマークを作って、URL にこれを貼る：

```
javascript:void((d=>{if(window.__padtap)return __padtap.open();const s=d.createElement('script');s.src='https://cdn.jsdelivr.net/gh/riubekubecktor/padtap@main/padtap.min.js';s.onerror=()=>alert('PadTap: load failed');d.documentElement.appendChild(s)})(document))
```

ゲームのページでアドレスバーにブックマーク名を入力して、☆付きの候補をタップすると起動する。

### ファイル

- `padtap.js` — ソース
- `padtap.min.js` — 配信用（jsDelivr 経由で読み込まれる）

### 制限

- 別ドメインの iframe 内のゲームには届かない（ゲーム本体のURLを直接開く）
- `isTrusted` をチェックするゲームには効かない
- サイトのCSPが外部スクリプトを禁止していると読み込めない
