# SpeakInspire / 随手记 —— 项目长期记忆

## 路径与工具链

- 仓库 `D:\work2\AI\SpeakInspire`；设计文档、原型、APK 在 `D:\work2\AI\outputs`
- Android Studio `D:\soft\AndroidStudio`｜构建 JDK `D:\soft\jdk21`｜SDK `D:\soft\AndroidSdk`（API 36）
- Studio 自带 JBR 是 JDK 25，**AGP 8.13 不接受**。已在 `gradle.properties` 写死
  `org.gradle.java.home=D\:\\soft\\jdk21`；`gradlew --version` 仍显示 25.0.3 属正常
  （那只反映启动器），**只能看构建结果判断是否生效**。
- `JAVA_HOME`/`ANDROID_HOME` 全空也能构建：靠 `local.properties` + `gradle.properties`。

## 当前状态（2026-11-11）

- **V0.12.2**（versionCode 25），**567 条测试全绿**
- tag 与 Release 均已发：`v0.11.0`（说明仍为空）/ `v0.12.0` / `v0.12.1` / `v0.12.2`
- 未做：拍摄后反馈（P2 最大留存风险）；v0.11.0 Release 正文待补

## 产品边界（不可破坏）

- 追问只提问、不给答案；成文才允许补论证。`PromptBoundaryTest` 19 条守着，
  **红了不要改断言迁就代码**。「来源」视图（你说的/AI 补）是必要配套，不是可选功能。
- 结束追问是用户的权利，不是模型的。
- **本 App 不负责拍摄**：没有取景框、没有录制按钮。所以模拟器够用，不需真机。
- 品牌：App 名「随手记」，包名 `com.hatio.speakinspire` 不许改（改了升级覆盖失败），
  版权 © 2026 Kevin Ho。改名要连 `Prompts.kt` 里的自称一起改。

## ⚠️ 踩过的坑（每条都真出过问题）

**请求体必须真解析，不能只 `contains`**：四个 builder 曾各拼 JSON，全漏了 system 后的逗号
→ 稳定 400，「写成文章」从来没成功过，而 `body.contains` 对语法错误完全免疫。
现在共用 `RequestBuilder.chatBody`，`ProbeRequestJsonTest` 自带解析器真解析一遍。

**有子表的 DAO 必须 `@Upsert`，不能 REPLACE**：Room 开了 `PRAGMA foreign_keys=ON`，
`INSERT OR REPLACE` 是先 DELETE → 子表 CASCADE 整片清空且不报错。
`DaoWriteStrategyTest` 读**生成的 `_Impl` SQL** 才算验证（内存假 DAO 复现不了）。

**追问必须无条件关思考**：思考模式下 `max_tokens` 被思维链吃光，正文一个字不来。
`RequestBuilder.probe` 传 `forceNoThinking = true`，**不看** `cfg.thinkingDisabled`。
deepseek-flash 也要显式发 `thinking:{"type":"disabled"}`（"靠换模型名控制"是错的）；
**不要发 `reasoning_content:null`**，那是响应字段。

**空回复必须能分类**：`StreamAudit` 记账 → `THINKING_ONLY` / `STREAM_ERROR`（带原文）/
`NO_CONTENT`。返回空流是最坏做法——把三种不同失败藏成一句「再试一次」。

**成文空白正文（V0.12.1）**：`content` 全是空格换行、`finish_reason=stop`。
`whyNoContent()` 分四种归因；**重试必须换说法**（末尾补纠偏话），原样重发还是同一串空白。

**追问页必须渲染待答问题**：`rounds` 里那条 `answer=null` 的问题，界面曾一处都不画，
用户只能盲猜作答。现在 `DeepeningUiState.pendingQuestion`。

**成文三约束**：messages 末条必须 user（否则模型续写）；成文也强制关思考；
`ComposeResult.Failed(kind, detail)` 的 detail 必须有线索。

**Round 语义**：一个 Round = 一个问题 + 可选一个回答；回答填进**最后一轮**，
再问才 `+Round`。曾写成「回答时新增轮 + 下问 dropLast」，每答一次丢一句。

**`ResponseParser.extractContent` 判断类型看原始值**：JSON 模式的 content 本身是
`{...}` 字符串，解完引号再判断会被丢掉，表现为 0 段。只有原始值首字符是 `"` 才是正文。

**`probeMessages` 必须带 originalNote**：第一问 rounds 为空，不发原文模型提不出问题。

**prompt 里「谁写在后面」就是优先级**（两次栽在这）：
① `draftSystem` 共用段的「不许用序号」排在清单法要求之后 → 清单法生成成散文；
② `probeSystem` 方法段排第二、后面压一百行共用要求，共用段自己示范了另外两种手法
→ 三种方法问成一个味道。**修法都是把强势那段真的挪到最后**，光写「以某段为准」没用。
另：共用段里的规矩和某风格冲突时，例外必须写在禁令**之后**。

**出处的判据不能只问「填没填」**：`MaterialKit.hasRealSourceText` 两层——
白名单（书名号/四位年份）在前，再过伪出处词表（用户原文/某项研究/学科名/不详）。
**长度不能当判据**（"海明威"三个字是完整出处）。

**同一 Composable 的两个路由容易漏传参数**：`Route.Article` 曾漏 `onToggleChip`，
Kotlin 默认值 `= {}` 让编译不报错、点了还有涟漪、只是什么都不发生。默认值能藏 bug。

**不生效的开关比没有更糟**：删界面选项时必须全局搜它的文案，否则错误提示还在
「去设置里勾上某某」。用户会以为调过它就修好了。

**撤调试日志别用 `git checkout`**：它是按文件回退，曾把同文件里另一批改动一起回退。

**字符串模板插值**：`"已追问 $rounds 轮"` 会把 `List` 的 `toString()` 打到界面上，
编译器不管。该用 `.size`。

## 环境命令

- 推送（`~/.gitconfig` 有空 proxy 会盖掉一切，必须显式指定）：
  `git -c "http.https://github.com.proxy=socks5://127.0.0.1:7890" push`
  沙箱注入的 `https_proxy=57092` 对 github 返 502，先 unset。aliyun 镜像海外 IP 502，CI 跳过。
- Release：REST API（没装 gh），**附件名必须 ASCII**，中文会被吃掉。
- 模拟器：`-gpu swiftshader_indirect`（Intel 核显硬件加速会黑屏）；启动前清掉 4 个代理变量
  （否则静默退出）；用 `启动模拟器.bat`。软件渲染下**截图全黑是常态**，
  改用 `uiautomator dump` 的 bounds 量布局。
- dump 前必须先 `rm -f /sdcard/ui.xml`：dump 失败不写文件也不报错，会读到上次的树。
  且需 `export MSYS_NO_PATHCONV=1`，落盘路径写 `D:/...`。
- 拉数据库用 `adb exec-out run-as <pkg> cat`（`adb shell cat` 会 CRLF 转换，sqlite 报 malformed），
  db/-wal/-shm 三个都要。
- R8 冒烟包：`assembleRelease -PuseDebugSigningForRelease=true` 才能覆盖模拟器里的 debug 包。

## 尚未验证/待办

- V0.12.2 的追问方法差异**只改了 prompt，需真机对比确认**
- 用户设置里风格补充要求曾填 `shorter`，可能影响成文长度
