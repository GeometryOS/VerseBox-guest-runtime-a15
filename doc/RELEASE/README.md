# 发布

这一章约束 A15 运行时怎样发布一次：什么算一次发布，标题与正文从哪里来，产物版本与索引怎样对应，以及发布前要过哪些闸门。

顶层只放两样东西。构建产物与版本的说明和示例表写在这里，发布流程本身的五篇说明放在 FLOW 目录里。

| 入口 | 内容 |
| --- | --- |
| [ARTIFACTS.md](ARTIFACTS.md) | 产物清单、版本号来源与示例表 |
| [FLOW](FLOW/01-RELEASE-PIPELINE.md) | 流程五篇：流水线、标题与正文、索引、版本来源、验证与操作 |

## 这次落地了什么

全部改动都在本仓库。字节数与哈希是交付时的值。

| 路径 | 动作 | 字节 | sha256 |
| --- | --- | --- | --- |
| `.github/workflows/build.yml` | 修改 | 9624 | `65909cba866475e8653058333b79e3230e6b38af4e390df2d4c01d294bc01cc5` |
| `.github/workflows/release-publish.yml` | 新增 | 10136 | `4ec3068290f3b36ab2a848e5659259ea4e0533c7974bf63fc7b43f226c06e8f3` |
| `.github/scripts/collect-release-assets.mjs` | 新增 | 1800 | `f5b2b254e092b0ad83752305e7cfb4c08cb9cd560eb6e3ee036b09921273b597` |
| `.github/scripts/generate-release-notes.mjs` | 新增 | 6678 | `908236fcf664dfc60acf6a53b7039f5e7b38df561eef2402c3ae49a7e1795bdd` |
| `.github/scripts/update-release-index.mjs` | 新增 | 5138 | `7991c96ceb39f857f4ec8196c869611892c920814e829815d56bc21277cc3729` |
| `.github/scripts/lib/release-facts.mjs` | 新增 | 2301 | `efe3be50909a4650e084172433d4489ceb1c31b09aa0e81576a92ec545416e53` |
| `release/release.json` | 新增 | 3 | `37517e5f3dc66819f61f5a7bb8ace1921282415f10551d2defa5c3eb0985b570` |

构建工作流只改发布相关的部分。打标签自动建发布页的步骤被删掉，权限从写降为只读，推送触发加了索引目录的跳过规则。构建本身、缓存键、产物校验与附件名称都没有动，因为这条路径已经跑通，换掉它等于把已有的证据作废。

发布工作流是新增的入口，四道闸门与生成、写回两组步骤按顺序排开。三个脚本各管一件事，收集附件哈希、生成标题与正文、写入版本索引，需要共同读取的事实在 `lib/release-facts.mjs` 里读一次，避免三处各读一份之后说法不一致。

## 与其它文档的关系

宿主工程里有三份说明与这里的判据相关，分别是 `docs/android15-port/00-导读与结论.md`、`docs/android15-port/06-阶段3-prepared与清单.md` 与 `docs/rom-package/05-构建链改造.md`。它们描述的是宿主侧与打包器，规则发生变化时，本目录 FLOW 里的闸门要一起复核。

产物同时受两条链路约束。准备清单决定运行时里有什么，ROM 包决定宿主导入的是什么，两者都由本仓库构建，所以改动的判据也都在本仓库的 CI 里。
