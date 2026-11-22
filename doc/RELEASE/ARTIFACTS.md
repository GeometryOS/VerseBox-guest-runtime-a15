# 构建产物与版本

这个仓库的 release 会带有六件产物。在这篇文档我们会以一次已知的发布为例，它的commit是 [`24331ca`](https://github.com/GeometryOS/VerseBox-guest-runtime-a15/commit/24331caff329c59ffd987a333c078cfb75a35412)，对应的 release 是 [`VerseBox A15 v1.0.1`](https://github.com/GeometryOS/VerseBox-guest-runtime-a15/releases/tag/v1.0.1)

## 1. 带哪六件

| 文件 | 字节 | 里面是什么 |
| --- | --- | --- |
| `a15-runtime-v1.0.1.tar.gz` | 692811663 | prepared 树的压缩包，运行时目录原样，给复现与比对用 |
| `versebox-android15-1.0.1.zip` | 693068315 | ROM 包，宿主导入的那一份，装 payload 与两份清单 |
| `versebox-android15-1.0.1-min.zip` | 703 | 最小占位包，只有一个自述文件，声明支持这个系统 |
| `apk-resident-libs-android15.tar` | 18391040 | APK 侧必须常驻的原生库，清单在 `rom/resident-native.txt` |
| `pack-android15-1.0.1.json` | 723 | 打包报告，记着载荷哈希、载荷字节、文件数、承载方式 |
| `SHA256SUMS` | 496 | 上面五件的哈希，一行一件，不含自身 |

## 2. 它们由什么长出来

```text
prepared/
   │
   ├── tar ──▶ a15-runtime-<标签>.tar.gz
   │
   └── pack-rom.py ──▶ versebox-android15-<ROM 版本>.zip
                              │
                              ├──▶ versebox-android15-<ROM 版本>-min.zip
                              ├──▶ apk-resident-libs-android15.tar
                              └──▶ pack-android15-<ROM 版本>.json

五件一起 ── sha256sum ──▶ SHA256SUMS
```

三条链路，一条汇聚。prepared 是唯一输入，ROM 包与最小占位包、常驻库、打包报告都从它派生，最后五项一起算哈希。任何一步失败，六件里就缺一件，发布工作流的第三道闸门会先拦下来。

## 3. 三个数字最容易看错

`payloadBytes` 是 1687638362，比任何一个文件都大。它算的是 payload 目录里未压缩内容的总字节，压缩包自然小一半。

ROM 包与运行时 tar 包都在 690 MB 上下，两者压的不是同一批东西。tar 压的是 prepared 整棵树，zip 压的是 payload 与两份清单，目录结构不同，不能拿一个的大小去推另一个。

最小占位包只有 703 字节，不是坏包。它里面只有一个自述文件，作用是让宿主知道这个系统受支持，真正的客体内容在完整包里。

## 4. 两种哈希别混

zip 与 tar 的 sha256 证明下载完整。字节对得上就说明文件没在传输里坏掉，与内容是什么无关。

`payloadSha256` 证明内容身份。它只对 payload 目录里的字节算，压缩方式、时间戳、目录顺序都不影响它，所以它是 ROM 的身份而不是包的指纹。ROM 包里的 `versebox_properties.yaml` 记着这一个值，索引里也记着同一个值，两处相同就能证明这个 zip 就是索引里那一次构建产出的。

这里有一条实测结论值得记住。这个标签中间被删掉重打过一次，前后两次构建的 `payloadSha256` 并不相同，一次是 `b807b0c162f27573` 那组，一次是 `b85dd66a43e82c72` 这组。索引按版本去重，同一个版本只留最后一条，所以引用哈希时以索引为准，不要拿早前日志或对话里的旧值去核对。

## 5. 索引里那一条长什么样

```json
{
  "version": "v1.0.1",
  "title": "VerseBox A15 v1.0.1",
  "romVersion": "1.0.1",
  "romKey": "android15@1.0.1",
  "payloadSha256": "b85dd66a43e82c72770d6c46357a14c4c5dd6436ce542bee136fd36dd50e2c35",
  "payloadBytes": 1687638362,
  "payloadFiles": 3573,
  "commit": "24331caff329c59ffd987a333c078cfb75a35412",
  "runUrl": "https://github.com/GeometryOS/VerseBox-guest-runtime-a15/actions/runs/43988698104",
  "publishedAt": "2026-11-17T00:39:35.816Z",
  "assets": [
    { "name": "a15-runtime-v1.0.1.tar.gz", "bytes": 692811663, "sha256": "703c19eee4cec320a993d561698de6d4d10d7f7509a87e2dd16a2e9cf0d08c3f" },
    { "name": "dist/apk-resident-libs-android15.tar", "bytes": 18391040, "sha256": "21149bb7917c89355ed30713ef8d9f8017073f543ceaf23ba306ffbc1d035018" },
    { "name": "dist/pack-android15-1.0.1.json", "bytes": 723, "sha256": "ddda40a29cdc51a78ffcbd5ea3f5018ba180b89b8725bca981b371983b63aa05" },
    { "name": "dist/versebox-android15-1.0.1-min.zip", "bytes": 703, "sha256": "667f293f014473697e78f80b5925b13883bef00a29bf78269420e35f36d239f3" },
    { "name": "dist/versebox-android15-1.0.1.zip", "bytes": 693068315, "sha256": "53ba10aa40576f82b297357fff891a3ce55606665f9fe4198c055206351a4867" }
  ]
}
```

`payloadFiles` 是 3573，比清单里的原生库条数多。payload 里除了原生库还有数据文件与库的副本，两个数字本来就不是一回事。

## 6. 发布页上的名字会短一截

上传到发布页时目录前缀会被去掉。索引里写 `dist/versebox-android15-1.0.1.zip`，发布页上看到的是 `versebox-android15-1.0.1.zip`。核对文件名时按最后一段比，不要按整串比。

## 7. 到手先做两件事

```bash
sha256sum -c SHA256SUMS
unzip -p versebox-android15-1.0.1.zip versebox_properties.yaml \
  | grep -E '^(id|romVersion|apiLevel|payloadSha256|payloadBytes):'
```

第一条校验收到的五个文件与清单逐个对上。第二条读 ROM 包自己的自述，`payloadSha256` 与索引里那一条相同，就说明这份包既没坏，也没被换过。
