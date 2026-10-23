# 参与贡献

感谢关注 DeepSeek Harness Mobile！欢迎通过 Issue 与 Pull Request 参与项目。

## 开发环境

| 组件 | 版本要求 |
| --- | --- |
| JDK | 17+ |
| Android SDK | API 37（compileSdk） |
| Android Studio | 最新稳定版（Narwhal 或更新） |
| 设备 | arm64-v8a 真机（Android 8.0+） |

## 本地构建

```bash
# 注入 proot 运行时资产（构建 APK 必需）
./scripts/fetch-proot.sh

# 构建与检查
./gradlew assembleDebug lintDebug
```

## 提交规范

- 提交信息格式：`类型(范围): 描述`，例如 `feat(engine): 支持运行时增量更新`、`fix(ui): 修复日志页内存抖动`
- 常用类型：`feat` / `fix` / `docs` / `refactor` / `ci` / `chore`

## 代码约定

- Kotlin：2 空格缩进，UI 全部使用 Jetpack Compose（不新增 XML View 代码）；
- Shell：必须通过 `shellcheck -S error`，注释使用中文；
- 新增依赖：优先选用 Apache-2.0 / MIT 等宽松许可，**必须**同步更新 `THIRD_PARTY_NOTICES.md`（含完整许可文本与版权行）；
- 涉及 GPL 组件的修改：按 `THIRD_PARTY_NOTICES.md` 的「GPL 组件修改记录」登记修改日期与内容。

## 提交前检查清单

- [ ] `./gradlew lintDebug` 通过
- [ ] `shellcheck` 通过
- [ ] 新依赖已登记 `THIRD_PARTY_NOTICES.md`
- [ ] 真机（Android 8.0 与最新版本各一）完成冒烟测试
