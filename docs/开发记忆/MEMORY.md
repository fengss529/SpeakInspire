# 工作区长期记忆：SpeakInspire 项目

## 项目路径

- 根目录：`D:\work2\AI\SpeakInspire`
- 设计文档：`D:\work2\AI\outputs\口语灵感App-设计文档.md`
- 可交互原型：`D:\work2\AI\outputs\口语灵感App原型v0.4.html`

## 工具链位置（统一装在 D:\soft 下）

| 工具 | 路径 |
|---|---|
| Android Studio | `D:\soft\AndroidStudio` |
| JDK（构建用） | `D:\soft\jdk21` |
| Studio 自带 JBR | `D:\soft\AndroidStudio\jbr`（JDK 25，**AGP 8.13 不接受**） |
| Android SDK | `D:\soft\AndroidSdk`（API 36） |
| Gradle 独立版 | `D:\soft\gradle-8.13`（日常用项目 wrapper） |

**Studio 曾在 `C:\Program Files\Android\Android Studio`，后按用户「干净安装」原则移到 `D:\soft`。**
搜索时不要只找 Program Files。

## 两个必须记住的坑

### 1. JDK 25 冲突

AGP 8.13 不接受 JDK 25，报错只有一行 `25.0.3`。已在 `gradle.properties` 加：
```properties
org.gradle.java.home=D\:\\soft\\jdk21
```

`gradlew --version` 仍显示 `Launcher JVM: 25.0.3` —— **正常**，它只反映启动器。
判断配置是否生效**只能看构建结果**。

### 2. 环境变量全空也能构建

`JAVA_HOME` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` 都是空的，
但构建正常——靠 `local.properties`（SDK 路径）+ `gradle.properties`（JDK 路径）。

## 项目现状（2026-10-29）

- 版本 **V0.11.0**（versionCode 22），**546 条测试全绿**（debug + release 各 546）
- 提交 `bbb127c`（批 B）、`f3648f6`（版本号）
- V0.10 之前：发芽（DB v3）、设置入口全局化、Markdown 导出、去线性化三入口
- V0.11 批 A：统计页月历、详情页逐段内联编辑、选题去滑动
- V0.11 批 B：DB v5（Article.styleId / Script.materialsJson）、
  成文四风格（随感/观点文/口播稿/清单法）、成文吃发芽（三芯片 +
  第三档标注「AI 补·含他人观点」）、选题素材区（4 类，无出处即丢弃）
- 未完成：拍摄后反馈（P2 的核心风险点，仍是最大留存风险）

## ⚠️ 解析模型回复：判断类型必须看原始值（V0.9.0）

`ResponseParser.extractContent` 曾写成「解完引号再判断是否以 `{` 开头」。
JSON 模式的回复（成文/拆角度/骨架）content 本身就是 `{...}` 字符串 →
全被当成「不是正文」丢掉，表现为模型正常返回但解析出 0 段。
**只有原始值第一个字符是 `"` 才是字符串正文。**

## 版本号只有一个来源

`buildConfig = true`，设置页用 `stringResource(R.string.version_label, BuildConfig.VERSION_NAME)`，
格式串 `V%1$s`。**不要**再往 strings.xml 里手写版本号。

## ⚠️ 追问页必须渲染「待答的问题」（V0.8.4，最严重的一个洞）

界面只渲染「已回答的轮次」+「正在流式的内容」。流一结束，问题进了 rounds
但标着未回答 —— **没有任何一处会渲染它**。

用户看到：文字闪一下，页面空空，输入框写着「回应它」。只能盲猜作答。
之前所有 prompt/JSON/thinking 的排查都绕过了这个事实。

现在 `DeepeningUiState.pendingQuestion`，界面必须画它。5 条测试守着。

## ⚠️ 追问必须无条件关思考（V0.8.2）

DeepSeek 文档：**思考模式默认开启**。开着时先吐 `reasoning_content`，
`max_tokens` 被思维链吃光 → 正文一个字不来 → 表现「点了追问没反应」。

所以 `RequestBuilder.probe` 传 `forceNoThinking = true`，**不看** `cfg.thinkingDisabled`。
那个值取决于历史配置/用户点没点过/旧版本写了什么，太脆。
只对预置服务商生效；自定义不强塞（对面可能不认这个字段，会 400）。

`max_tokens` 也从 200 提到 1024。

## 成文三个约束（V0.8.3，别改回去）

1. **messages 最后一条必须是 user**。追问常停在「问题没答」，
   拍平后以 assistant 结尾 → `json_object` 模式会让模型续写而不是成文。
   `composeMessages()` 末尾补指令。
2. **成文也强制关思考**。思考模式下模型在思维链里把话说完，正文只剩 `{}`。
3. **`ComposeResult.Failed(kind, detail)`**，detail 必须有。
   四个失败点各带线索：HTTP 原文 / raw 片段 / 模型输出 / 原始数组。

## 字符串模板会把集合打印成内部结构（V0.8.3）

`"已追问 $rounds 轮"` 把 `List<Round>` 的 `toString()` 打到界面上。
**编译器不管这类错误**。Kotlin 模板接受任何对象。
已给 `Round` 手写 `toString()` 兜底，但根因是插值该用 `.size`。

## 空回复必须能分三种（V0.8.2）

`StreamAudit` 记账：流结束零正文就抛可归因异常，而不是返回空流。

| 实际 | 异常 | 话术 |
|---|---|---|
| 只收到 reasoning_content | `THINKING_ONLY` | 去关思考模式 |
| 流里夹 error 帧 | `STREAM_ERROR` + 原文 | 看原文（额度/审核） |
| 一帧都没推 | `NO_CONTENT` | 重试或换模型 |

**返回空流是最坏的做法——它把三种不同的失败藏成一句「再试一次」。**

## ⚠️⚠️ 请求体必须真解析，不能只 contains（V0.8.1 血的教训）

`probe` 和 `compose` 拼 messages 时**都漏了 system 后面的逗号**，
产出 `[{system}{user}]` 非法 JSON → 服务商稳定 400。

后果：追问第一问直接报「出了点问题」，**「写成文章」其实从来没成功过**。

这个 bug 活了整个开发周期，因为 4 个 builder 各拼各的 JSON，
而所有测试都用 `body.contains("...")` —— **对语法错误完全免疫**。

现在：
- 四个入口共用 `RequestBuilder.chatBody` 一条路径，逗号只写一次
- `ProbeRequestJsonTest` 自带极简 JSON 解析器，把每个 builder 的产出真解析一遍
- `ErrorKind.BAD_REQUEST` 单独一类，`TransportException` 带 `statusCode` + `detail`，
  报错会附上服务商原文

**判断标准：断言"包含某段文字"不等于断言"这是个合法请求"。**

## 追问请求必须带 originalNote（V0.8 修，别再退回去）

`RealAiService.streamQuestion` 曾只发 `context.rounds`。
第一问时 rounds 为空 → 请求体只剩一条 system → 模型没素材 → **提不出问题来**。

现在 `probeMessages()` 把 `originalNote` 固定成第一条 user 消息，每轮都带。
`RealAiServiceTest.第一问必须把原始记录发给模型` 守着，且做过反向验证。

## 追问方法学（V0.8）

`ProbeMethod` 四种：苏格拉底（默认）/ 连续五问 / 反方质询 / 还原场景。

**结构是「共享禁区 + 差异化手法」**：`Prompts.probeSystem(method)`
只替换 `method.instruction`，禁区不在方法里写。加第五种方法也不可能漏掉边界。
`ProbeMethodTest` 逐字比较四种方法的禁区段。

方法经由 `DeepeningContext.probeMethod` 传下去（不是服务实现自己读配置），
这样链路测试能断言「设置选了哪种 → 请求就是哪种」。

存储用 `ProbeMethodStore` 接口 + 普通 SharedPreferences（非敏感，不进 Room）。

**结束追问是用户的权利，不是模型的**。模型即使觉得聊透了也只能把问题收拢到结论上，
不许输出「你想清楚了」这类收尾语——那句话会被当成又一个问题，用户无从回答。

## ⚠️ 服务商行为（2026-10-16 实测，不要凭直觉改）

**deepseek-flash 也必须显式发 `thinking:{"type":"disabled"}`。**

| 表现 | 结果 |
|---|---|
| 不发 thinking | 返回 reasoning_content，**content 为空** |
| 发 disabled | reasoning_content=null，content 立即有值 |

不关掉的话，追问的逐字浮现会变成**漫长的空白等待**。
之前记的「DeepSeek 靠换模型名控制」是**错的**。

**不要发 `reasoning_content:null`** —— 那是响应字段，当请求参数发会出错。

模型名：不要用 `deepseek-v4-flash`（遗留名），现为 `deepseek-flash`。

## 「已拍摄」只是标记（重要）

**这个 App 不负责拍摄。** 用户用手机里的相机或其他短视频 app 拍完，
回来点一下「我拍好了」就完了。

所以**没有取景框、没有录制按钮、没有「拍摄中」的中间态**——
那些会让人以为要在这个 App 里拍完，那这个标记就没人点了。

**推论：模拟器完全够用，不需要真机。**

## 权限边界（不可破坏）

追问阶段 AI 只提问不给答案；成文阶段才允许补论证。详见设计文档与 README。
`PromptBoundaryTest` 17 条断言守着这条线，**红了不要改断言去迁就代码**。

已在真实模型上验证：追问返回的是纯提问，没有任何答案或建议。

「来源」视图（逐段标注`你说的`/`AI 补`）是「允许补论证」的**必要配套**，
不是可选的信任功能。测试里守着「默认开启」。

## 品牌信息（2026-10-16 定）

- **App 名：随手记**（原名「口语灵感」，已改）
- **图标**：五根竖条声波，中间最高向两侧递减
  刻意避开麦克风圆形轮廓——那是录音机的符号，而这个 App 不录音
- **版权：© 2026 Kevin Ho**，显示在设置页底部
- **包名不变**：com.hatio.speakinspire（改了会导致升级覆盖失败）

改名时容易漏的地方：`Prompts.kt` 里 AI 的系统提示引用了旧名字，
不一起改的话模型会自称「口语灵感」而 App 叫「随手记」，很突兀。

## 模拟器（2026-10-20 更新：现在能用）

**两个独立故障，都要修：**

1. **缺 `opengl32sw.dll`** → 卡在 `Initializing gfxstream backend`，
   设备一直 offline。从剪映目录复制一份到 `D://soft//AndroidSdk//emulator//`。
2. **窗口跑到屏幕外**（top=-730）。本机桌面 1280x720，AVD 1080x2400。
   `fix-emulator-window.ps1` 摆正，启动脚本自动调用。启动加 `-scale 0.4`。

**网络可修**（之前记的"无法联网"是错的）：
```
adb root
adb shell ip route add default via 10.0.2.2 dev eth0
```
之后 TCP 443 通，可以做真实 AI 测试。

**uiautomator dump 必须 `export MSYS_NO_PATHCONV=1`**，
否则 Git Bash 把 `/sdcard/ui.xml` 转成 Windows 路径，表现为「dump 为空」。

## 模拟器截屏黑屏的解法

**两个独立的原因，症状都是「界面正常但截图黑」：**

**1. GPU 翻译层不兼容** —— 本机 Intel Iris Xe 与模拟器的
`Google (Intel)` 硬件加速翻译层冲突。
用 `-gpu swiftshader_indirect`（CPU 软件渲染）绕过。

**2. 代理环境变量冲突（更隐蔽）** —— 本机设了 `HTTP_PROXY` 和
`http_proxy` 两套（大小写各一），模拟器会**静默退出**：
进程起来、内存 400MB+，几十秒后消失，日志停在参数打印那行。
启动前必须清掉这 4 个变量。

**用 `启动模拟器.bat`**（已处理以上两点 + 清锁文件）。

**判断黑屏归属**：先看 `uiautomator dump` 有没有完整文字 ——
有就是截图通道问题（模拟器的），没有才是 App 没渲染。

代价：软件渲染下系统慢，偶尔弹 System UI ANR，点 Wait 恢复即可。

## 两条必须记住的数据约定（2026-10-19 审核发现）

### 1. Round = 一个问题 + 可选一个回答

```
AI 问完    → rounds + Round(问题, null)
用户回答   → 把 answer 填进**最后一轮**
AI 再问    → rounds + Round(新问题, null)
```

曾经写成「回答时新增一轮 → 下一问 `dropLast(1)` 把上一轮删掉」，
结果**每答一次就丢掉刚说的那句话**，成文素材恒为 0、永远拒稿。
`DeepeningFlowTest` 守着这条，改这块先看它。

### 2. 文章 id 与选题的 articleId 必须一致

- 新文章 id 用确定性的 `a_$noteId`（重复保存是 upsert，不是插新篇）
- 已有文章必须沿用库里 `idea.article.id`
- 选题的 `articleId` **只能**从 `idea.article.id` 读，不能拼字符串

对不上的表现：选题存了但在选题 tab 里看不到（外键指向不存在的文章）。

## 测试的正确分层

**状态类测试测「定义」，链路测试测「行为」。**
276 条状态/prompt 测试全绿，主链路其实是断的——
连续三轮真机阻塞问题全在 ViewModel 层。
修完这次后 286 条全绿，其中 `DeepeningFlowTest` 9 条跑真实链路
（FakeAiService + 内存 DAO）。**以后每修一个真机问题，先加一条会红的链路测试。**

## ⚠️⚠️ 有子表的 DAO 写入必须 @Upsert，不能用 REPLACE（V0.8.11）

Room 生成的 `_Impl` 会执行 `PRAGMA foreign_keys = ON`，
而 `INSERT OR REPLACE` 在 SQLite 里是**先 DELETE 再 INSERT** ——
父行改一个字，子表因 `ON DELETE CASCADE` 整片清空，且没有任何报错。

已造成：改一次原始记录 → 追问历史 + 文章 + 选题全没；
重新成文保存 → 选题消失。

现在 note / deepening / article / script / provider 全用 `@Upsert`，
只有无子表的 `trashed` 保留 REPLACE。`DaoWriteStrategyTest` 守着（读源码数注解——
这个 bug 在内存假 DAO 上永远复现不了，必须看 `build/generated/ksp/**/NoteDao_Impl.java`
生成的 SQL 才算验证）。

## ⚠️ prompt 里「谁写在后面」就是优先级（V0.11）

`draftSystem` 的顺序是：风格段 → 用户补充 → 共用段（含「一篇能被称为文章的最低条件」）。
共用段第 3 条写着「不许用序号」——它排在清单法的分点要求**之后**，
模型把后读到的当更强的指令，于是**清单法生成出来是一篇散文**，
一个点都没有。四种风格真机各跑一遍才发现，测试当时全绿。

**推论：共用段里的规矩如果和某种风格冲突，必须在共用段里显式开例外，
而且要写在禁令之后。** 顺序不是排版问题，是语义。

清单法还要求序号写进段落正文——输出是段落数组，
不写「1.」读者看不出这是分点的。

## ⚠️ 出处的判据不能只问「填没填」（V0.11）

`Material.hasSource` 只判空串，于是这条过关了：

    引用：临场组织语言时，大脑优先处理立场安全……
    —— 用户原文，社会心理学关于表达优先级的说法

字面有出处，实际指向用户自己 + 一个学科名。用户会以为有外部依据，照念。

`MaterialKit.hasRealSourceText` 两层：
1. **白名单在前**——带书名号或四位年份 → 放行。
   放后面会误杀「《社会心理学》戴维·迈尔斯」。
2. 否则过伪出处词表（用户原文 / 某项研究 / 学科名 / 不详）。

出处的**长度不能当判据**：「海明威」三个字就是完整出处，
按字数卡会把它误杀。词表放在 `MaterialKit.PSEUDO_SOURCE_MARKERS`。

实测：模型一次给 3 条，过滤后留 2（1 条金句出处是
「社会心理学中的沉默效应」被丢）。偏少是合理的——
宁缺毋滥，已在 prompt 里引导它转向「用户自己经历里的例子」。

## ⚠️ 撤调试日志别用 git checkout

V0.11 用 `git checkout Mappers.kt` 撤一行日志，把同一文件里批 B 的
`styleId` 与 `materials` 映射一起回退了，表现为选题素材区一条都不显示。
**改一行就用 Edit 改回去**，checkout 是按文件回退的。
