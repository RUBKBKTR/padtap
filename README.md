# PadTap

Bluetoothコントローラーで、Android Chrome のブラウザゲームを**タップ操作**するためのブックマークレット。

- 左スティック / 十字キー：画面上のカーソルを移動（右スティックは微調整）
- A：カーソル位置をタップ（押しっぱなしで長押し・ドラッグ）
- その他のボタン：設定した画面位置をタップ（ゲームごとに保存）
- SELECT＋START：ボタン配置の設定

## 使い方

Chrome でブックマークを作って、URL にこれを貼る：

```
javascript:void((d=>{if(window.__padtap)return __padtap.open();const s=d.createElement('script');s.src='https://cdn.jsdelivr.net/gh/riubekubecktor/padtap@main/padtap.min.js';s.onerror=()=>alert('PadTap: load failed');d.documentElement.appendChild(s)})(document))
```

ゲームのページでアドレスバーにブックマーク名を入力して、☆付きの候補をタップすると起動する。

## ファイル

- `padtap.js` — ソース
- `padtap.min.js` — 配信用（jsDelivr 経由で読み込まれる）

## 制限

- 別ドメインの iframe 内のゲームには届かない（ゲーム本体のURLを直接開く）
- `isTrusted` をチェックするゲームには効かない
- サイトのCSPが外部スクリプトを禁止していると読み込めない
