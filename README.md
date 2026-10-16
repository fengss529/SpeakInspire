# 口语灵感 SpeakInspire

提升口语表达能力的工具。核心链路：**随手记 → AI 追问 → 成文章 → 转选题 → 拍摄**。

```bash
git log --oneline        # 查看历史
gradlew.bat test# 跑 91 条测试
```

---

## 环境状态：已可构建 + 可出 APK ✅

| 组件 | 状态 | 位置 / 版本 |
|---|---|---|
| Android Studio | ✅ | `D:\soft\AndroidStudio`（AI-262.9437.185） |
| JDK（构建用） | ✅ | `D:\soft\jdk21`（Temurin 21.0.12.1 LTS） |
| Studio 自带 JBR | ⚠️ | JDK 25.0.3 —— **AGP 8.13 不接受，见下方说明** |
| Android SDK | ✅ | `D:\soft\AndroidSdk`（API 36 / build-tools 35+36） |
| Gradle | ✅ | 8.13（wrapper 已生成，日常用 `./gradlew`） |
| adb | ✅ | 37.0.1 |

**当前结果：`91 条测试全绿`，`app-debug.apk`（19 MB）打包成功。**

### 全部装在 D:\soft 下

Studio 曾在 `C:\Program Files\Android\Android Studio`，现已统一移到 `D:\soft\AndroidStudio`。
若日后重装，注意保持这个路径约定。

### ⚠️ 必须知道：Studio 的 JBR 是 JDK 25，AGP 8.13 不接受

实测报错信息只有一行 `25.0.3`，看不出原因，很容易误判。

已在 `gradle.properties` 加：

```properties
org.gradle.java.home=D\:\\soft\\jdk21
```

这条让构建 daemon 用 JDK 21，**已实测：PATH 里只有 JBR 25、且无任何环境变量时构建依然成功**。

但 Gradle 启动器仍会用 JBR，在 Studio 里可能看到红色报错。第一次打开请改：

> Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle JDK** → 选 `D:\soft\jdk21`

注意 `gradlew --version` 仍会显示 `Launcher JVM: 25.0.3` —— 这是正常的，
它只反映启动器，不反映实际构建用的 JDK。**判断依据只能是构建结果。**

**详细步骤见 [Studio打开指引.md](Studio打开指引.md)。**

### 在 Studio 里打开项目

```
File → Open → 选 D:\work2\AI\SpeakInspire
```

SDK 路径已写在 `local.properties`，不需要手动指定。

### 环境变量（可选，命令行已不需要）

`JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` 目前都是空的，
但**构建照样正常**（靠 `local.properties` + `org.gradle.java.home`）。
若想在任意终端直接用 `java` / `adb`，运行 `设置环境变量.bat`。

### 命令行构建

```bash
cd D:/work2/AI/SpeakInspire
gradlew.bat test              # 跑 91 条测试
gradlew.bat :app:assembleDebug  # 出 APK
```

一次性设置环境变量：运行 `设置环境变量.bat`（需管理员权限）。

### ⚠️ 尚未配置：运行设备

`adb devices` 返回空 —— 目前没有可运行目标，**Studio 里点运行会提示无设备**。

需要接真机（开 USB 调试）或创建模拟器（当前 emulator 已装但**缺系统镜像**）。
本项目后续要做相机预览，真机是必需的。

---

## 测试结果

| 测试类 | 用例 | 守住什么 |
|---|---:|---|
| `PromptBoundaryTest` | 17 | AI 权限边界（三套 prompt 的禁区） |
| `IdeaStateDeriverTest` | 16 | 8 状态派生 + 手势判定 |
| `FakeAiServiceTest` | 16 | Fake 六项能力自测 |
| `ProviderDialectTest` | 16 | 服务商差异 + SSE 容错 |
| `ErrorMapperTest` | 12 | 7 种错误归因 |
| `GestureJudgeTest` | 7 | 右滑标记 / 左滑搁置 |
| `AiServiceContractTest` | 4 | 三方法必须分开 |
| `ComposeResultTest` | 3 | 拒稿路径 |
| **合计** | **91** | **0 失败** |

---

## 代码进度

### 已完成：服务层 + 领域层（不依赖 UI）

```
app/src/main/java/com/hatio/speakinspire/
├── data/
│   ├── model/       Models.kt + AiTypes.kt
│   ├── local/       Entities.kt（Room，note 表不存 status）
│   └── remote/      Providers.kt（SSE 容错）+ ErrorMapper.kt
├── domain/
│   └── IdeaState.kt 8 状态派生 + 手势判定（纯函数）
├── service/
│   ├── AiService.kt 唯一测试主缝隙
│   ├── FakeAiService.kt
│   └── Prompts.kt   三套 prompt
├── ui/theme/
│   └── Theme.kt     MD3 配色 + 8 状态色（对齐 v0.4 原型的设计令牌）
├── SpeakInspireApp.kt / MainActivity.kt  （最小骨架，可运行）
└── res/  strings / themes / colors / 矢量图标
```

### 未完成

- DAO / Repository / Hilt 模块
- ViewModel + Compose UI（五格导航、追问对话页等）
- Retrofit 真实实现（`RealAiService`）

---

## 三条不能破坏的约束

### 1. AI 的权力是分阶段的

| 阶段 | AI 能做 | AI 不能做 |
|---|---|---|
| 追问 | 只提问、攻击观点 | **不给结论/建议/替代方案** |
| 成文 | 重组/润色/**补论证** | 不改变用户观点立场 |
| 转选题 | 拆解成意群短语骨架 | **不写逐字稿** |

追问时的「不补位」保护**思辨过程**，成文时的「补位」服务**成果表达**。保护对象不同，所以不冲突。

> 最容易犯的错：看到 AI 写的文章"读起来更好"，想在追问阶段也让它给建议。那会摧毁整个产品的核心价值。

### 2. 「来源」视图是决策 (c) 的必要配套

因为允许 AI 补充论证，**你无法再从文章判断哪些是你的观点**。段落级 `你说的 / AI 补` 标注是唯一能补上这个判断能力的地方。

### 3. 骨架必须是意群短语，不是孤立词

```
错：卡住 / 大脑空白 / 稿子
对：我明明准备过 / 但张嘴就卡住 / 稿子上的字全忘了
```

---

## 写测试时踩过的四个坑（记录下来避免重犯）

1. **KDoc 里写 `**补论证**` 会闭合注释** —— `*/` 被当成注释结束符。中文表格里避免让 `**` 紧跟斜杠。
2. **prompt 的「禁止清单」里必然含被禁的词** —— 断言不能是简单的 `contains == false`，要验证它只出现在禁止语境。
3. **`const val` 不能用 `.trimIndent()`** —— 非常量表达式，编译期求值不了，改用 `val`。
4. **`org.gradle.java.home` 只管 daemon，不管启动器** —— `gradlew --version` 仍显示 JBR 25，但构建实际用 JDK 21。别被 `--version` 的输出误导。

---

## 下一步实现顺序

```
1. DAO + Entity ↔ Model 映射
2. RealAiService —— 追问流式 + 成文 JSON + 角度拆解
3. Repository + Hilt 模块
4. Compose UI（五格导航 → 追问对话页 → 文章来源视图）
```

第 2 步最容易出错，三个已知坑：

- MiMo 的 `thinking:{"type":"disabled"}` 必须显式发（DeepSeek 不需要，两家相反）
- SSE 必须容错 `: keep-alive` 注释帧（`ProviderDialect.extractDelta` 已实现并测过）
- 默认模型名不要用 `deepseek-v4-flash` 或 `mimo-v2.5-pro`（已过时/下线）