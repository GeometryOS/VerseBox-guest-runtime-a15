e，这里存放着用于将 Android 15 运行时系统打包为 VerseBox 可导入的 ROM 文件（`.zip`）的工具链。它将 `prepared/` 目录整合、计算哈希并生成最终的包。

## 目录结构

- `pack-rom.py`：打包器。负责合并目录、调整清单并计算哈希。
- `rom_hash.py`：`payloadSha256` 的唯一实现，保证打包器与客户端解包器的哈希计算结果严格一致。
- `rom.properties.yaml`：ROM 的基本身份信息（id、名称、默认版本号以及 libMode）。

## 使用方式

在运行时仓库的根目录下执行🤔：

```sh
# 默认使用 rom.properties.yaml 配置并输出到 dist/
python rom/pack-rom.py --prepared prepared

# 覆盖版本号与库模式
python rom/pack-rom.py --prepared prepared --rom-version 1.1.0 --lib-mode apk

# 仅校验已有的 ROM 包
python rom/pack-rom.py --verify-only dist/versebox-android15-1.0.0.zip