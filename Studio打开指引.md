# Android Studio 打开指引（2026.2.1）

---

## 一、启动前的唯一警告

**Studio 自带 JBR 是 JDK 25，而项目的 AGP 8.13 不接受它。**

报错信息只有一行 `25.0.3`，看不出原因，极易误判成项目配置错误。

项目已在 `gradle.properties` 里指向 JDK 21：

```properties
org.gradle.java.home=D\:\\soft\\jdk21
```

但**首次打开 Studio 时仍需手动选一次**（见第三节）。

---

## 二、打开项目

>⚠️ **别点「New Project」** —— 项目已存在，新建会得到一个配置完全不同的空壳。

```
File → Open → 选 D:\work2\AI\SpeakInspire → OK
```

---

## 三、导入向导（5 步）

| 步骤 | 选什么 | 说明 |
|---|---|---|
| 1 导入设置 | `Import settings from Gradle model` | 不要选 Other |
| 2 Gradle 位置 | 保持 **wrapper（8.13）** | ⚠️ 别改成 bundled |
| 3 **Gradle JDK** | **`D:\soft\jdk21`** | ← **唯一必须改的一步** |
| 4 Android SDK | 应显示 `D:\soft\AndroidSdk` | 已由 `local.properties` 指定 |
| 5 Finish | 等同步完成 |首次下载依赖，几分钟正常 |

已验证正确时，`.idea/gradle.xml` 里会写入：

```xml
<option name="gradleJvm" value="temurin-21" />
```

---

## 四、同步完成后自查

**方法 1（最可靠）** —— 终端跑测试：

```bash
cd D:/work2/AI/SpeakInspire
gradlew.bat test
```

预期：**91 条全绿**。

**方法 2** —— 界面检查：

Settings → Build, Execution, Deployment → Build Tools → Gradle
确认 **Gradle JDK = jdk21**（不是 jbr-25）。

### 一个会误导人的现象

```bash
gradlew.bat --version
```

输出里的 `Launcher JVM: 25.0.3` **是正常的**——
它只反映 Gradle 启动器，不反映实际构建用的 JDK。
**判断配置是否生效只能看构建结果。**

---

## 五、已知不需要做的事

以下几项**都已就绪，不用管**：

| 项 | 状态 |
|---|---|
| 环境变量 `JAVA_HOME` / `ANDROID_HOME` | 全空也能构建 |
| SDK 路径 | `local.properties` 已指定 |
| 国内镜像 | `settings.gradle.kts` 两处都配好 |
| `.gitignore` | 已忽略 `.idea/`、`local.properties` |

若想在任意终端直接用 `java` / `adb`，运行 `设置环境变量.bat`（需管理员权限）。

---

## 六、还没配的：运行设备

`adb devices` 返回空列表 —— **目前没有可运行的目标**。

两条路：

| 方式 | 怎么做 | 说明 |
|---|---|---|
| **真机**（推荐） | 手机开 USB 调试，连电脑 | 拍视频类App 必须在真机上试，模拟器没有摄像头 |
| 模拟器 | SDK Manager → SDK Platforms 装一个系统镜像 → 建 AVD | 当前 `emulator` 已装，但**缺系统镜像**，跑不起来 |

本项目后续要做相机预览，真机是必需的。

---

## 七、已验证可用的命令

```bash
gradlew.bat test                      # 91 条测试
gradlew.bat :app:assembleDebug        # 出 APK（19 MB）
```

两条都在「JBR 25 在 PATH、无任何环境变量」的环境下验证通过。