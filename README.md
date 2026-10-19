# 随手记 SpeakInspire

提升口语表达能力的工具。核心链路：**随手记 → AI 追问 → 成文章 → 转选题 → 拍摄**。

```bash
gradlew.bat test                # 跑全部 308 条单元测试
gradlew.bat :app:assembleDebug  # 出 APK（app/build/outputs/apk/debug/）
```

包名 `com.hatio.speakinspire`（不要改，改了覆盖安装会失败）。当前版本 **V0.8**（versionCode 8）。

---

## 当前状态

| 层 | 状态 | 说明 |
|---|---|---|
| 数据层 | ✅ | Room 6 张表，note 不存 status；v1→v2 迁移已有 |
| 服务层 | ✅ | `RealAiService` 已接真实模型（DeepSeek / MiMo，OpenAI 兼容） |
| 领域层 | ✅ | 8 状态派生 + 手势判定，纯函数 |
| UI | ✅ | 五格导航 + 记录详情 + 编辑 + 追问 + 成文 + 转选题 + 设置 |
| 测试 | ✅ | **308 条，0 失败**（含追问→成文→选题的链路测试） |
| 未开始 | ⬜ | 拍摄后反馈（P2 的核心风险点） |

**已验证的完整链路**：记录 → 详情 → 追问（真实模型流式）→ 成文（来源视图）→ 转选题（骨架）→ 标记已拍摄。

### 环境

| 组件 | 位置 / 版本 |
|---|---|
| Android Studio | `D:\soft\AndroidStudio` |
| JDK（构建用） | `D:\soft\jdk21` |
| Studio 自带 JBR | JDK 25 —— **AGP 8.13 不接受**，见下 |
| Android SDK | `D:\soft\AndroidSdk`（API 36） |
| Gradle | 8.13（用项目 wrapper） |

`gradle.properties` 里已经有 `org.gradle.java.home=D\:\\soft\\jdk21`。

> `gradlew --version` 仍显示 `Launcher JVM: 25.0.3` —— **正常**，它只反映启动器。
> 判断配置是否生效只能看构建结果。

`JAVA_HOME` / `ANDROID_HOME` 全空也能构建，靠 `local.properties` + `org.gradle.java.home`。

命令行构建、模拟器启动见 `启动模拟器.bat`、`检查环境.bat`、`Studio打开指引.md`。

---

## 测试分布

| 测试类 | 用例 | 守住什么 |
|---|---:|---|
| `MappersTest` | 34 | Entity ↔ Model + JSON 编解码 |
| `IdeaRepositoryTest` | 27 | 聚合、状态派生、写操作返回值 |
| `RealAiServiceTest` | 24 | 真实响应解析、拒稿、错误归因、**第一问带原始记录** |
| `DeepeningUiStateTest` | 18 | 进度点 / 折叠摘要 / 收尾卡 |
| `TopicUiStateTest` | 18 | 角度选择、骨架展示 |
| `ComposeUiStateTest` | 17 | 来源视图、拒稿路径 |
| `PromptBoundaryTest` | 17 | 三套 prompt 的禁区 |
| `ProviderDialectTest` | 17 | 服务商差异 + SSE 容错 |
| `FakeAiServiceTest` | 16 | Fake 六项能力自测 |
| `RequestBuilderTest` | 15 | 请求体拼装、thinking 参数 |
| `ErrorMapperTest` | 12 | 错误归因 |
| `SettingsUiStateTest` | 10 | 设置页状态 |
| **`DeepeningFlowTest`** | **12** | **追问→成文→选题的真实链路** |
| `ProbeMethodTest` | 15 | **四种追问方法：彼此不同、边界相同** |
| `ModelOutputParserTest` | 8 | 成文 / 角度 / 骨架解析 |
| `GestureJudgeTest` | 7 | 右滑标记 / 左滑搁置 |
| `ResponseParserTest` / `SseParserTest` | 12 | 响应与帧解析 |
| `EditNoteTest` | 5 | 编辑写库 |
| `AiServiceContractTest` / `ComposeResultTest` | 7 | 三方法必须分开 |
| **合计** | **308** | **0 失败** |

### 测试的盲区（踩过）

前 276 条全在测「状态类」和「prompt 边界」，**ViewModel 的状态迁移一条没覆盖**。
于是「用户回答被丢掉」「选题挂到不存在的文章上」这类阻塞问题全部漏网。

`DeepeningFlowTest` 用 `FakeAiService` + 内存 DAO 跑真实调用链，补的就是这一层。

> 教训：**状态类测的是「定义」，链路测试的才是「行为」。** 只测前者会给人虚假的安全感。

---

## 三条不能破坏的约束

### 1. AI 的权力是分阶段的

| 阶段 | AI 能做 | AI 不能做 |
|---|---|---|
| 追问 | 只提问、攻击观点 | **不给结论/建议/替代方案** |
| 成文 | 重组/润色/补论证 | 不改变用户观点立场 |
| 转选题 | 拆解成意群短语骨架 | **不写逐字稿** |

追问时的「不补位」保护**思辨过程**，成文时的「补位」服务**成果表达**。保护对象不同，所以不冲突。

> 最容易犯的错：看到 AI 写的文章"读起来更好"，想在追问阶段也让它给建议。那会摧毁整个产品的核心价值。

### 2. 「来源」视图是允许补论证的必要配套

因为允许 AI 补充论证，**你无法再从文章判断哪些是你的观点**。段落级 `你说的 / AI 补` 标注是唯一能补上这个判断能力的地方。

### 3. 骨架必须是意群短语，不是孤立词

```
错：卡住 / 大脑空白 / 稿子
对：我明明准备过 / 但张嘴就卡住 / 稿子上的字全忘了
```

---

## 数据模型要点

- **note 表不存 status**，8 个状态全部由 `IdeaStateDeriver` 从关联数据算出
- `article : script = 1 : N` —— 文章是资产，选题是可派生的拍摄计划
- **文章 id 必须是 `a_$noteId`**（新文章）或库里已有 id；选题的 `articleId` 必须指向它。
  这两处对不上，表现就是「存了选题但选题 tab 里没有」
- API Key **不进 Room**，只在 Tink 加密的 SharedPreferences

## 追问的四种方法（V0.8）

追问不是一个动作，是一类动作。同样逼用户想清楚，「依据是什么」和
「当时具体做了什么」会把人带到完全不同的地方。所以 `ProbeMethod` 给了四种：

| 方法 | 把用户往哪带 | 适合什么 |
|---|---|---|
| 苏格拉底式（默认） | 从含糊到具体 | 有观点但说不出理由 |
| 连续追问为什么 | 从现象到根因 | 复盘一件事为什么没做成 |
| 反方质询 | 从自说到自辩 | 判断、主张、是非题 |
| 还原具体场景 | 从概括到细节 | 经历、故事、具体的一次 |

**关键设计：换手法，不换边界。**

`Prompts.probeSystem(method)` 的结构是「共享禁区 + 差异化手法」，
四种方法不是四份独立全文，只替换 `ProbeMethod.instruction`。
禁区（不给结论/建议/替代方案）压根不在方法里写，所以加第五种方法也不可能漏掉它。

`ProbeMethodTest.禁区段落不因方法而变` 逐字比较四种方法的禁区段——
有人给某种方法写「可以适当给点提示」，这条立刻红。

选择存在普通 SharedPreferences（不含敏感信息），与 `ProviderConfig` 分开：
那是「发给哪家模型」，这是「用什么方式问」。混在一起的话，换服务商会把问法也换掉。

---

## 追问的第一问为什么问不出来（V0.8 修）

`RealAiService.streamQuestion` 曾经只发 `context.rounds`。
第一问时 rounds 是空的，请求体里只剩一条 system message——
**模型手里没有任何素材**，于是「提不出问题来」，页面停在空白。

现在 `probeMessages()` 把 `originalNote` 固定成第一条 user 消息，
后续每轮都带着它。多花几十 token，换来的是第四轮时模型仍知道
「用户最初说的到底是什么」。

`RealAiServiceTest.第一问必须把原始记录发给模型` 守着这条。
**这一条做过反向验证**：把修复临时回退，测试立刻红，恢复后绿。

配套：模型一个字都没吐出来时，`DeepeningViewModel` 会给一句可执行提示
（「去设置里确认『关掉思考模式』是勾上的」），而不是让页面空着。

---

## 追问的 Round 语义（曾写错，务必看清）

一个 `Round` = 一个问题 + 可选的一个回答。

```
AI 问完    → rounds + Round(问题, null)
用户回答   → 把 answer 填进最后一轮（不是新增一轮）
AI 再问    → rounds + Round(新问题, null)
```

早期实现是「回答时新增一轮空问题的 Round，下一问到达时 `dropLast(1)` 删掉」——
于是每答一次就把刚说的那句话丢掉：折叠区空白、进度点不亮、成文素材为 0。
`DeepeningFlowTest` 守着这条。

---

## 写测试时踩过的坑

1. **KDoc 里写 `**补论证**` 会闭合注释** —— `*/` 被当成注释结束符。
2. **prompt 的「禁止清单」里必然含被禁的词** —— 断言要验证它只出现在禁止语境。
3. **`const val` 不能用 `.trimIndent()`** —— 改用 `val`。
4. **`org.gradle.java.home` 只管 daemon** —— `gradlew --version` 仍显示 JBR 25。

## 开发时踩过的坑

1. **静默 `return` 是最难查的失败** —— Repository 写操作要返回成功与否。
2. **`hiltViewModel` 的 key 要区分「对象」和「会话」** —— 编辑页按对象 id 做 key 会复用旧状态。
3. **`remember` 必须带 key** —— 异步加载的初始值否则永远停在空。
4. **自动退出最容易被误报成「坏了」** —— 保存后不要自动返回。
5. **schema 变更 = 升 version + 写 Migration + 用 `install -r` 测** —— 全新安装不触发迁移。
6. **报错必须配一条出路** —— 只有错误文案，用户唯一能做的还是退出。

---

## 下一步

1. **拍摄后反馈**（P2 核心）：拍完隔天回放对比，这是唯一能证明「能力在提升」的东西
2. 追问页的历史展开（现在折叠成一行，点了没反应）
3. 成文页的标题编辑入口（`editTitle` 已有，UI 未接）
4. 回收站页面（数据层已支持，UI 未接）
