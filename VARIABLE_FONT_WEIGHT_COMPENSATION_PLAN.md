# 可变字体常规字重非线性补偿实现方案

## 1. 背景与问题分析

### 1.1 现状
当前 APP 通过 [`CustomFontManager`](app/src/main/java/Akari/NCM/player/data/CustomFontManager.kt) 与 [`OpenTypeFontInspector`](app/src/main/java/Akari/NCM/player/core/font/OpenTypeFontInspector.kt) 支持导入 OpenType 可变字体（Variable Font），并将 Compose 的 9 档字重（`W100`–`W900`）映射到字体的 `wght` 轴。

在 [`AmeExpressiveTypography`](app/src/main/java/Akari/NCM/player/ui/theme/Theme.kt) 及全应用 UI 中，实际使用的字重分为 5 组：
- **中低字重**：`Normal (W400)`、`Medium (W500)`、`SemiBold (W600)` —— 用于正文、副标题、列表项标题等。
- **高字重**：`Bold (W700)`、`ExtraBold (W800)` —— 用于页面主标题、按钮、播放页歌名及高亮歌词等。

### 1.2 问题根因
以 `Resource-Han-Rounded v1.910`（OpenType/CFF2，`wght 200–900`，`default = 400`）为例：
- 当前 [`OpenTypeFontInspector.adaptWeightToAxis`](app/src/main/java/Akari/NCM/player/core/font/OpenTypeFontInspector.kt#L90-L110) 对 `400..900` 采用 1:1 线性映射。
- 思源体系的 `400 (Regular)` 偏印刷正文取向，叠加圆体（`ROND = 100`）视觉减重及 CFF2 渲染特性，导致 **`W400`–`W600` 在手机小字号下明显偏细**；而 **`W700`/`W800` 粗体视觉饱满度完全正常**。
- 若采用全局平移（如全字重 `+100`），虽能改善正文，但会导致 `W700 → 800`、`W800 → 900` 粗体过粗、笔画糊连。

### 1.3 优化目标
设计一种**锚定高字重的非线性字重补偿方案**：
1. 仅对支持 `wght` 轴的可变字体生效，不影响系统默认字体或静态字体。
2. 抬高 `W400`–`W600` 的视觉粗细，向 `W700` 平滑衰减，`W700`–`W900` 保持原映射不变。
3. 提供可调节、可实时预览的 UI 控件，并持久化保存设置。

---

## 2. 核心算法设计：锚定高字重的非线性补偿

### 2.1 数学模型
在 `adaptWeightToAxis` 中新增可选参数 `lowWeightBoost: Float = 0f`（建议有效范围 `0f..120f`，默认 `0f` 以保持完全向后兼容）。

在传入 `OpenTypeFontInspector.adaptWeightToAxis` 进行 `wght` 轴范围映射**之前**，先对 Compose 字重 $w \in [100, 900]$ 计算补偿后的等效字重 $w'$：

$$
w' =
\begin{cases}
w + \Delta \cdot \dfrac{w - 100}{300}, & 100 \le w < 400 \quad (\text{向 } W100 \text{ 衰减为 } 0) \\[8pt]
w + \Delta \cdot \dfrac{700 - w}{300}, & 400 \le w < 700 \quad (\text{在 } W400 \text{ 处取最大值 } \Delta \text{，向 } W700 \text{ 线性衰减为 } 0) \\[8pt]
w, & 700 \le w \le 900 \quad (\text{高字重完全锚定不变})
\end{cases}
$$

随后再将 $w'$ 送入原有的分段线性映射逻辑（`100..400 -> minValue..defaultValue`，`400..900 -> defaultValue..maxValue`）并钳制在 `[minValue, maxValue]`。

> **设计说明**：
> - 当 `lowWeightBoost = 0f` 时，$w' \equiv w$，行为与重构前完全一致。
> - 当 `w = 400` 时获得全部补偿 $\Delta$（例如 $\Delta = +50$ 时，`W400 -> 450`，恰好对应思源体系的 `Book` 字重）。
> - `W700`、`W800`、`W900` 的结果恒定不变，确保播放页主标题与高亮歌词不会变粗。
> - 在 `100..400` 区间向 `100` 衰减，避免极细字重在接近 `minValue` 时发生越界或倒挂，同时保证单调递增（在 $\Delta \le 150$ 时，$w'$ 关于 $w$ 严格单调递增，不会出现低档位比高档位更粗的情况）。

### 2.2 数值对照表（以 `wght 200–900, default = 400`，$\Delta = +50$ 为例）

| Compose 字重 | 原映射 `wght` | 补偿后 `wght` ($\Delta = +50$) | 变化量 | 视觉效果 |
| :--- | :--- | :--- | :--- | :--- |
| `W100` | 200 | 200 | 0 | 保持极细基准 |
| `W200` | 267 | 278 | +11 | 轻微加粗 |
| `W300` | 333 | 355 | +22 | 过渡平滑 |
| **`W400` (Normal)** | **400** | **450** | **+50** | **对齐思源 `Book`，正文更扎实** |
| **`W500` (Medium)** | **500** | **533** | **+33** | **次要强调更清晰** |
| **`W600` (SemiBold)** | **600** | **617** | **+17** | **小标题微幅补强** |
| **`W700` (Bold)** | **700** | **700** | **0** | **完全锚定不变** |
| **`W800` (ExtraBold)** | **800** | **800** | **0** | **完全锚定不变** |
| `W900` (Heavy) | 900 | 900 | 0 | 完全锚定不变 |

---

## 3. 代码改造清单

### 3.1 `OpenTypeFontInspector.kt`
- **文件**：[`OpenTypeFontInspector.kt`](app/src/main/java/Akari/NCM/player/core/font/OpenTypeFontInspector.kt)
- **改动**：
  1. 为 `adaptWeightToAxis` 增加默认参数 `lowWeightBoost: Float = 0f`：
     ```kotlin
     fun adaptWeightToAxis(
         composeWeight: Int,
         minValue: Float,
         defaultValue: Float,
         maxValue: Float,
         lowWeightBoost: Float = 0f
     ): Float
     ```
  2. 新增辅助逻辑（或内联计算）`applyLowWeightCompensation(composeWeight: Int, boost: Float): Float`，将 `boost` 限制在 `0f..150f` 以保证字重单调性。

### 3.2 `CustomFontManager.kt`
- **文件**：[`CustomFontManager.kt`](app/src/main/java/Akari/NCM/player/data/CustomFontManager.kt)
- **改动**：
  1. **持久化 Key**：
     - 建议按字体 ID 维度持久化补偿值（例如 `KEY_WEIGHT_BOOST_PREFIX = "vf_weight_boost_"`，Key 为 `"vf_weight_boost_${fontId}"`），这样用户为 `ResourceHanRoundedJ-VF` 设置 `+50` 后，切换到其他可变字体（如 `Inter-VF`）时不会互相干扰；也可以同时提供默认值 `0`。
  2. **状态流**：
     - 新增 `_weightBoostFlow = MutableStateFlow(0)` 及公开只读 `val weightBoostFlow: StateFlow<Int>`。
     - 在 `init` / `applySelectionInternal` 中读取当前选中字体的补偿值并同步到 `_weightBoostFlow`。
  3. **更新与热重建**：
     - 新增方法 `setWeightBoost(context: Context, boost: Int)`：
       - 将 `boost` 钳制在 `0..120`（或 `0..100`）；
       - 持久化写入 `SharedPreferences`；
       - 更新 `_weightBoostFlow.value`；
       - 使用当前选中的 `FontItemInfo` 与新的 `boost` 重建 `FontFamily` 并更新 `_currentFontFamilyFlow.value`，驱动 `AmePlayerTheme` 实时无感刷新，无需重启 APP。
  4. **构建 `FontFamily`**：
     - `buildComposeFontFamily` 接收 `lowWeightBoost: Int = 0`，在遍历 `COMPOSE_WEIGHTS` 调用 `OpenTypeFontInspector.adaptWeightToAxis` 时传入 `lowWeightBoost.toFloat()`。
  5. **清理逻辑**：
     - `deleteImportedFont` 删除字体时顺便清理对应的 `vf_weight_boost_${id}` 键值。
     - `resetForTest` 中重置 `_weightBoostFlow.value = 0`。

### 3.3 `SettingsScreen.kt`
- **文件**：[`SettingsScreen.kt`](app/src/main/java/Akari/NCM/player/ui/screen/SettingsScreen.kt)
- **改动**（位于字体选择弹窗 `FontPickerDialog` 内）：
  1. 收集 `CustomFontManager.selectedFontInfoFlow` 与 `CustomFontManager.weightBoostFlow`。
  2. **显隐条件**：仅当当前选中的字体为支持 `wght` 轴且非字体集合的可变字体时展示调节区域：
     ```kotlin
     val canAdjustWeightBoost = selectedFontInfo?.inspection?.let {
         it.weightAxis != null && !it.isCollection
     } == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
     ```
  3. **UI 控件设计**：
     - 放置一张紧凑卡片「常规字重补偿（仅可变字体）」：
       - 标题行：左侧显示「常规字重补偿」，右侧显示当前值徽章（如 `默认 (+0)` 或 `+50`）及「重置」小按钮（当值 `> 0` 时可点）。
       - `Slider`：`valueRange = 0f..100f`，`steps = 9`（每档步进 `10`，即 `0, 10, 20, ..., 100`）。
       - 实时预览区：两行紧凑示例文本，直接应用当前 `MaterialTheme.typography`：
         - 正文预览（`FontWeight.Normal`）：`"正文预览：那些难以言说的情感 (W400)"`
         - 粗体预览（`FontWeight.Bold`）：`"粗体锚定：夢浮橋-ユメノウキハシ- (W700)"`
       - 底部辅助说明（`bodySmall`）：`"仅加粗常规与中等字重，粗体保持不变"`。

---

## 4. 测试与验证计划

### 4.1 单元测试
1. **`OpenTypeFontInspectorTest.kt`**：
   - 验证 `lowWeightBoost = 0f` 时所有原有测试用例结果完全不变。
   - 验证 `wght 200..900, default = 400` 且 `lowWeightBoost = 50f` 时：
     - `W400 -> 450f`
     - `W500 -> ~533.3f`
     - `W600 -> ~616.7f`
     - `W700 -> 700f`、`W800 -> 800f`、`W900 -> 900f`（高字重严格锚定）。
   - 验证极端 `maxValue`（如 `wght 200..700`）及 `lowWeightBoost` 超出范围时的钳制与单调性。
2. **`CustomFontManagerTest.kt`**：
   - 验证 `setWeightBoost` 的持久化读写与 `weightBoostFlow` 更新。
   - 验证切换不同字体时各自保留独立的补偿值，切回系统默认时恢复为 `0`。
   - 验证 `deleteImportedFont` 清理残留偏好项与 `resetForTest()` 重置状态。

### 4.2 真机视觉验收
1. **设置页**：导入并启用 `ResourceHanRoundedJ-VF`，将「常规字重补偿」调至 `+40` ~ `+60`，确认列表副标题、开关说明、分组标题的清晰度与视觉重量接近系统默认字体。
2. **播放页**：确认歌名与逐字歌词高亮（`Bold` / `ExtraBold`）粗细与调节前保持一致，无过度加粗或发糊现象；底部时间轴与翻译文本（`Normal` / `Medium`）更易读。
3. **兼容性**：切换回系统默认字体或静态字体（如 `ZenKurenaido-Regular`），确认调节卡片自动隐藏且字体排版不受影响。
