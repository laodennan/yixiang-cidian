# 意象词典

一款文学意象词典 iOS App：输入一个意象词（比如「月亮」），就能查到相关的古诗句和生动现代表达，像查词典一样查灵感。

## 功能

- **词典**：搜索意象词（模糊匹配），查看词条详情。
- **分类**：按「自然 / 时节 / 情感」浏览词条。
- **词条详情**：每个词条包含「古诗句」（带作者与篇名）和「生动表达」（现代原创句子），每句都有一键复制；点亮星星可收藏。
- **收藏**：所有收藏的词条汇总。
- **AI 改写**：输入自己写的一句话（可附加一个意象提示），调用你配置的 OpenAI 兼容接口改写得更生动，结果可一键复制。

## 离线词库

内置 `Yixiang/lexicon.json`，共 25 个词条（自然 13 个、时节 6 个、情感 6 个）、94 首古诗句、101 句现代生动表达，全部离线可用，不联网。想加词条：直接编辑这个 JSON 文件（每条需有 `word`、`category`、`poems`、`expressions`），重新打包即可。

## 配置 AI 改写接口（在 App 内完成）

1. 打开 App → 「AI 改写」页 → 右上角「设置」。
2. 填写三项：
   - **Base URL**：默认 `https://api.openai.com/v1`；用 DeepSeek、通义千问等 OpenAI 兼容接口时，改成对应地址即可。
   - **API Key**：你的接口密钥（密码框输入）。
   - **模型名**：默认 `gpt-4o-mini`，改成你接口支持的模型名。
3. API Key 只保存在本机，不会上传到任何服务器（除你配置的接口外）。

## 用 GitHub Actions 打包 IPA

本项目不含任何第三方依赖，不需要任何特殊权限（entitlements），可直接在云端 Mac 上编译。

1. 把本目录内容推到你自己的 GitHub 仓库（`main` 分支）。
2. 打开仓库 → **Actions** → **Build IPA** → **Run workflow**（或直接 push 到 `main` 也会触发）。
3. 构建成功后，在该次运行的 **Artifacts** 里下载 `Yixiang-unsigned-ipa`，得到 `Yixiang-unsigned.ipa`。

> 注意：这是**未签名**的 IPA，不能直接安装，必须经过下一步自签。

## 自签安装（AltStore / SideStore）

1. 在 iPhone 上装好 AltStore 或 SideStore，并按其指引完成信任配置。
2. 把 `Yixiang-unsigned.ipa` 传到手机（隔空投送、网盘、文件 App 均可）。
3. 用 AltStore / SideStore 打开该 IPA 并签名安装。
4. 个人免费证书签名的 App 有效期为 7 天，到期后用同一工具续签即可；付费开发者证书有效期更长。

补充说明：工程的 Bundle ID 默认为 `com.example.yixiang`。如果你的签名工具要求唯一的 Bundle ID，可在 Xcode 工程设置里修改 `PRODUCT_BUNDLE_IDENTIFIER` 后重新打包。

## 技术信息

- SwiftUI，iOS 17.0+，无第三方依赖。
- 工程结构：`Yixiang.xcodeproj`（单 Target、单 Scheme）+ `Yixiang/` 下的源码与词库资源。
- 最低系统版本、Bundle ID 等均可在 Xcode 工程设置中调整。
