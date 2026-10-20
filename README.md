# 随手记 SpeakInspire

提升口语表达能力的工具。核心链路：**随手记 → AI 追问 → 成文章 → 转选题 → 拍摄**。

```bash
gradlew.bat test                # 跑全部 351 条单元测试
gradlew.bat :app:assembleDebug  # 出 APK（app/build/outputs/apk/debug/）
```

包名 `com.hatio.speakinspire`（不要改，改了覆盖安装会失败）。当前版本 **V0.8.4**（versionCode 12）。

---

## 当前状态

| 层 | 状态 | 说明 |
|---|---|---|
| 数据层 | ✅ | Room 6 张表，note 不存 status；v1→v2 迁移已有 |
| 服务层 | ✅ | `RealAiService` 已接真实模型（DeepSeek / MiMo，OpenAI 兼容） |
| 领域层 | ✅ | 8 状态派生 + 手势判定，纯函数 |
| UI | ✅ | 五格导航 + 记录详情 + 编辑 + 追问 + 成文 + 转选题 + 设置 |
| 测试 | ✅ | **351 条，0 失败**；**全链路已在模拟器跑通** |
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
| `RealAiServiceTest` | 29 | 真实响应解析、拒稿、错误归因、**成文请求形状** |
| `DeepeningUiStateTest` | 23 | 进度点 / 折叠摘要 / 收尾卡 |
| `TopicUiStateTest` | 18 | 角度选择、骨架展示 |
| `ComposeUiStateTest` | 17 | 来源视图、拒稿路径 |
| `PromptBoundaryTest` | 17 | 三套 prompt 的禁区 |
| `ProviderDialectTest` | 17 | 服务商差异 + SSE 容错 |
| `FakeAiServiceTest` | 16 | Fake 六项能力自测 |
| `RequestBuilderTest` | 15 | 请求体拼装、thinking 参数 |
| `ErrorMapperTest` | 20 | 错误归因、**400 / 空回复都不再落到「看不出来」** |
| `SettingsUiStateTest` | 10 | 设置页状态 |
| **`DeepeningFlowTest`** | **12** | **追问→成文→选题的真实链路** |
| `ProbeMethodTest` | 15 | **四种追问方法：彼此不同、边界相同** |
| **`ProbeRequestJsonTest`** | **12** | **四个 builder 的请求体真解析一遍** |
| **`StreamAuditTest`** | **9** | **空回复分三种，各自说得出原因** |
| `ModelOutputParserTest` | 8 | 成文 / 角度 / 骨架解析 |
| `GestureJudgeTest` | 7 | 右滑标记 / 左滑搁置 |
| `ResponseParserTest` / `SseParserTest` | 12 | 响应与帧解析 |
| `EditNoteTest` | 5 | 编辑写库 |
| `AiServiceContractTest` / `ComposeResultTest` | 7 | 三方法必须分开 |
| **合计** | **351** | **0 失败** |

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

### V0.8.1：上一版修完之后反而报错了

补上原始记录之后，真机立刻变成「出了点问题，具体原因看不出来」。
原因是 `RequestBuilder.probe` 拼 `messages` 时**漏了 system 后面的逗号**：

```
"messages":[{"role":"system","content":"..."}{"role":"user","content":"..."}]
                                              ↑ 少一个逗号
```

服务商稳定返回 400。**`compose` 也漏了同一个逗号**——
所以「写成文章」其实从来没成功过，只是没人走到那一步。

这个 bug 活了这么久，是因为四个 builder 各拼各的 JSON，
而所有测试用的都是 `body.contains("...")`——**对语法错误完全免疫**。

现在四个入口共用 `RequestBuilder.chatBody` 一条拼接路径，
`ProbeRequestJsonTest` 自带一个极简 JSON 解析器，把每个 builder 的产出
**真解析一遍**。11 条测试里 7 条在修复前是红的。

### V0.8.1：报错必须说得出原因

400 以前落到 `UNKNOWN`，话术是「出了点问题，具体原因看不出来」。
可 400 恰恰是唯一一种「App 自己写错了」的错误——把它归成「看不出来」
等于放弃诊断，用户唯一的出路是重试，重试还是错，然后就放弃了。

现在 `ErrorKind.BAD_REQUEST` 单独归一类，`TransportException` 带上
`statusCode` 与 `detail`（服务商原文），`ErrorMapper.humanize(kind, detail)`
会把 `message` 字段截断后附在话术末尾。用户下次截图就能说清发生了什么。

### V0.8.1：去掉标题下的五个小灰点

它们看着像进度，实际什么也不说明：用户不知道自己该聊几轮，
而「还剩几个」正是这版刻意不显示的——倒计时会诱发敷衍作答。
一个永远数不出意义的点阵只是装饰。方法名留着，它回答的是
「我在设置里换的问法生效了吗」。

---

## V0.8.2：空回复不能再是一句「再试一次」

修完逗号之后仍然失败的话，剩下最可能的一条是**思考模式**。

DeepSeek 文档写明：思考模式**默认开启**。开着时模型先吐一长串
`reasoning_content`，正文要等很久；而 `max_tokens` 一旦被思维链吃光，
正文就永远不来——表现是「点了追问没有反应」。

而 `probe` 以前是「看 `cfg.thinkingDisabled` 决定发不发」。
那个值取决于历史配置、用户点没点过、旧版本写了什么——**太脆**。
一个用来省时间的选项，不该有能力让核心功能整个失效。

### 追问现在无条件关掉思考

```
probe(...)  →  forceNoThinking = true
```

不看配置，只对预置服务商（DeepSeek / MiMo）生效。
自定义服务商不强塞——谁也不知道对面认不认这个字段，
发一个它不认识的参数可能直接被 400 拒掉。

顺带把 `max_tokens` 从 200 提到 1024：追问只要一个问题，放宽不会让回答变长，
只是给正文留出被截断之前的空间。

### 三种空回复，三种说法

以前 SSE 里解析不出 `content` 的帧全被丢掉，于是三种完全不同的故障
在界面上长得一模一样，都是「模型没返回问题」：

| 实际发生了什么 | 现在报什么 | 用户该做什么 |
|---|---|---|
| 只收到 `reasoning_content` | 「只输出了思考过程，正文没出来」 | 关掉思考模式 |
| 流里夹着 `error` 帧 | 「服务商在返回过程中报了错」+ 原文 | 看原文：额度？审核？ |
| 一帧都没推 | 「连上了，但没有返回内容」 | 重试或换模型 |

`StreamAuditTest` 九条把三种情形分开钉住。

**这一条的意义不在于修好某个 bug，而在于：下次再坏，界面会自己说是哪种坏。**

---

## V0.8.3：追问终于通了，然后露出三个藏着的问题

真机上第一次完整聊下来（三问三答），立刻暴露了三处此前不可能看见的毛病——
**它们都依赖「追问有数据」这个前提，而追问在此之前从未成功过。**

### 1. 详情页把数据类打印到了界面上

```kotlin
text = "已追问 $rounds 轮"        // rounds 是 List<Round>
```

Kotlin 的字符串模板接受任何对象，于是整串
`[Round(question=你说的"好奇心"…, answer=…)]` 直接显示给用户。
正确写法是 `${rounds.size}`。

除了改插值，还给 `Round` 手写了 `toString()`：
万一以后又有人往文案里插一个 Round 或 Round 列表，
屏幕上出现的是「问：… 答：…」，而不是内部结构。

### 2. 成文请求的最后一条是 assistant

追问常常停在「AI 刚问完、用户还没答」的位置。直接拍平 `rounds`，
`messages` 就以 `assistant` 结尾——而 `response_format: json_object`
要求结尾是用户消息，否则模型会顺着那句问题**续写**，
而不是执行「写成文章」。结果要么被拒，要么吐一段非 JSON。

现在 `composeMessages()` 保证末尾一定有一条用户消息。

### 3. 成文失败丢掉了全部诊断信息

`ComposeResult.Failed` 曾经只持有 `ErrorKindLite`（四个枚举值），
服务商原文、解析拿到的响应体，**全在中间层被扔掉了**。
追问那边 V0.8.2 已经带上了 detail，成文这边漏了整整一条链路。

现在 `Failed` 带 `detail`：

| 失败点 | detail 里是什么 |
|---|---|
| HTTP 非 200 | 服务商返回的原文 |
| 响应没有 content | 原始响应前 300 字 |
| 有 content 但不含 paragraphs | **模型实际输出的那一段话** |
| paragraphs 解析为空 | 提取到的原始数组 |

成文也改成强制关思考——思考模式下模型倾向在思维链里把话说完，
正文只剩 `{}`，或者干脆不遵守 `response_format`。

---

## 追问的 Round 语义（曾写错，务必看清）

一个 `Round` = 一个问题 + 可选的一个回答。

```
AI 问完    → rounds + Round(问题, null)
用户回答   → 把 answer 填进最后一轮（不是新增一轮）
AI 再问    → rounds + Round(新问题, null)
```

早期实现是「回答时新增一轮空问题的 Round，下一问到达时 `dropLast(1)` 删掉」——
于是每答一次就把刚说的那句话丢掉：折叠区空白、成文素材为 0（进度点当时也没亮，
不过那排点 V0.8.1 已经去掉了）。`DeepeningFlowTest` 守着这条。

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

---

## V0.8.4：模拟器跑通全链路，修掉四个「只有前面成功了才显形」的洞

在模拟器里第一次把「记录 → 追问 → 成文 → 选题 → 标记已拍」完整走通。
过程中冒出来的问题，**全部依赖前一步真的成功**才能看见。

### 追问页从来不显示待答的问题（最严重）

列表只渲染两项：已回答的轮次、正在流式的内容。
流一结束，问题进了 `rounds` 但标着未回答——**没有任何一处会渲染它**。

用户看到的是：文字闪一下，然后页面空空，输入框却写着「回应它」。
他只能盲猜着回答。之前关于 prompt、JSON、思考模式的全部排查，
都绕过了这个更基础的事实。

现在 `DeepeningUiState.pendingQuestion`，界面必须渲染它，五条测试守着。

### 成文页停在「还没开始」

`LaunchedEffect` 里 `load(id)` 之后立刻判断 `hasContent`，
但 load 内部是 `viewModelScope.launch`——状态还没写进去，必然判成「没有文章」。
于是 `compose()` 在 `noteId` 还是空串时发出，`findIdea("")` 返回 null，静默退出。

顺序改由 ViewModel 保证：`load(noteId, autoCompose = true)`。

### 拒稿被当成文章

模型确实拒绝了，但它把理由写成
`{"title":"你聊得太少了…","paragraphs":[…]}`——一个**正常文章的形状**。
App 把它当成品渲染，还给了「存进思考库」按钮。

字数我们自己数得清：不足 300 字直接 `Rejected`，不发请求。

### 收尾是死路

成文页说「回去再聊聊」，回去却只看到收尾卡，输入框没了。
新增「还没说完，再聊几句」，`reopen()` 清掉 `finishedAt`。

### 骨架永远是 0 段

`chooseAngle` 传的 `ArticleDraft(dialogue = emptyList())`，
模型只拿到一句「切入角度：xxx」，没有素材可拆。
改为传文章正文，prompt 里补上 JSON schema。

---

## 模拟器窗口超出屏幕（V0.8.4）

本机桌面 **1280×720**，而 AVD 是 1080×2400 像素。
模拟器按缩放后的桌面居中，算出的窗口位置是 `(360, -730)`——
整块窗口在屏幕上方，进程在跑、adb 连得上，桌面上一片空白。

`fix-emulator-window.ps1` 负责把它摆回可见区域（幂等、带重试），
`启动模拟器.bat` 在系统起来后自动调用；启动参数加 `-scale 0.4`。

另外补了缺失的 `opengl32sw.dll`——没有它，gfxstream 会永远卡在初始化，
设备一直 offline。这个文件不是 SDK 自带的，从本机其他 Qt 应用复制一份即可。
