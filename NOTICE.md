# NOTICE（附加版权声明与第三方组件说明）

本文件为「青科助手（修改版）」的**附加版权声明**，与项目的原始开源许可、原作者版权声明**相互分隔，不合并、不混淆**。

---

## 一、原项目信息

- **原项目仓库地址**：<https://github.com/2891954521/QUST-Assistant>
- **原作者**：2891954521
- **原始开源协议**：GNU General Public License v3.0（GPL-3.0）
- 许可原文见本仓库 [LICENSE](LICENSE) 文件，**保持完整，未做任何修改、替换或升级**；原作者版权声明保留原样，不删除、不改动。

## 二、修改版信息

- **修改版名称**：青科助手（修改版）
- **当前版本**：v3.6.0256（红米版）/ v3.6.0257（普适版）（见 Releases）
- **修改者**：zhangzhenzyc（GitHub 账号，<https://github.com/zhangzhenzyc>）
- **修改版版权**：版权所有 (C) 2026 zhangzhenzyc

## 三、本次修改范围

本次修改**仅**包括：

1. **清理冗余代码**：移除未使用的变量、未使用的 import、未使用的参数等无效片段；
2. **消除死循环/不可达代码**：移除不可达分支与无效循环；
3. **版权文件增补**：新增本 NOTICE、CHANGELOG、SECURITY、CONTRIBUTING 等文档，并在源码文件头部添加版权许可注释；
4. **修复气泡提示错位等显示问题**（详见 CHANGELOG.md）；
5. **隐私政策与用户协议**：隐私政策（assets/policy.html）恢复为原作者原文、逐字节一致；用户协议（assets/userAgreement.html）沿用原版软件内容，并在首次登录页新增其展示入口（详见下文「沿用隐私政策与用户协议说明」）。

**未改动**任何业务逻辑、功能、UI 界面与交互行为；优化后程序运行表现与原版一致。

所有修改内容均遵循原始 GPL-3.0 许可协议开源。

## 四、两份版权声明同时生效

- 本附加声明为独立声明，**不替代、不削弱**原始 GPL-3.0 许可以及原作者版权声明的法律效力；
- 原始许可条款与原作者版权内容维持原样；
- 原始许可与本附加声明**同时生效**。

## 五、第三方依赖库及其开源许可

| 组件 | 版本 | 许可协议 |
| --- | --- | --- |
| Kotlin / Kotlinx Serialization JSON | 1.9.x / 1.6.3 | Apache-2.0 |
| AndroidX Core / AppCompat | 1.12.0 / 1.6.1 | Apache-2.0 |
| Jetpack Compose（BOM 2024.04.00：runtime / foundation / ui / material / material3） | 1.6.5 / 1.2.1 | Apache-2.0 |
| Jetpack Glance（App Widget） | 1.0.0 | Apache-2.0 |
| AndroidX Activity Compose | 1.8.2 | Apache-2.0 |
| AndroidX Lifecycle（runtime / viewmodel-compose） | 2.7.0 | Apache-2.0 |
| AndroidX Navigation Compose | 2.7.7 | Apache-2.0 |
| AndroidX Work Runtime | 2.9.0 | Apache-2.0 |
| AndroidX Room | 2.6.1 | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| Jsoup | 1.12.1 | MIT |
| ML Kit Text Recognition (Chinese) | 16.0.1 | Apache-2.0（ML Kit 服务条款附加约束，见 <https://developers.google.com/ml-kit/terms>） |
| 友盟 U-SDK（common / asms / apm） | 9.6.8 / 1.8.2 / 1.2.0 | 友盟服务条款（Copyright (C) UMeng） |
| JUnit | 4.13.2 | EPL-1.0 |
| AndroidX Test（ext junit / espresso） | 1.1.5 / 3.5.1 | Apache-2.0 |

上述各组件版权归其各自作者所有；本仓库对它们的使用方式以各组件自身许可为准。

## 六、沿用隐私政策与用户协议说明

- **隐私政策**（`app/src/main/assets/policy.html`）：**沿用原作者原文**，与原版软件（QustHelper v3.6.0127）内的隐私政策逐字节一致，未做任何修改；原作者联系邮箱（2891954521@qq.com）保持原文原样。
- **用户协议**（`app/src/main/assets/userAgreement.html`）：**沿用原版软件内容**，逐字节一致，未做任何修改；本次仅在首次登录页新增其展示入口（条款勾选行中《免责声明》之后的《用户协议》）。
- **免责声明**：保持本修改版既有内容不变。
- 上述两份文件的版权归原作者（2891954521）所有；本仓库对它们的沿用符合原始 GPL-3.0 许可及原作者声明的约束。

## 七、反馈渠道说明

- **修改版（本仓库）相关问题与建议**：QQ 邮箱 2286891174@qq.com（修改者 zhangzhenzyc，仅用于提意见与漏洞反馈，详见 [SECURITY.md](SECURITY.md) 与 [CONTRIBUTING.md](CONTRIBUTING.md)）；
- **原作者渠道**：2891954521@qq.com（见原版隐私政策「如何联系我们」，及原项目仓库 <https://github.com/2891954521/QUST-Assistant>）。
