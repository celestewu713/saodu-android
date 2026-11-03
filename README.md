# 掃毒小幫手 Android 版

檢查手機上已安裝的每一個 APP：算出安裝檔（APK）的 SHA-256 指紋，交給雲端版掃毒小幫手查詢 VirusTotal。
APP 裡不存 VirusTotal 金鑰，只需要輸入親友密碼。

## 安裝
用手機打開本專案的 [Releases → 最新版](../../releases/tag/latest)，下載 `saodu.apk` 並安裝。
第一次安裝時，手機會詢問是否允許「安裝未知來源的應用程式」，請允許瀏覽器安裝。

## 開發
- 每次推送到 `main`，GitHub Actions 會自動打包並更新「最新版」。
- 簽章檔存在 GitHub Secrets（`SIGNING_KEYSTORE_BASE64`、`SIGNING_PASSWORD`），不放進程式庫；固定同一把簽章，更新版才能直接覆蓋安裝。
