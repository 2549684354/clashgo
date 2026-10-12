# 首页

## 卡片阴影

首页所有卡片（状态卡、概览四卡、快捷入口、延迟卡、系统卡）统一挂 `HomeShared.kt` 的 `Modifier.cardShadow(cornerRadius)`（4dp elevation 的 `Modifier.shadow`，shape 与卡片圆角一致：默认 16dp 用 `HomeCardCornerRadius`，12dp 的卡传 `12.dp`）。加在 padding 之后、`testTag` 之前，阴影落在卡片实际边界上。深色主题下黑影自然隐去，浅色主题提供「卡片浮在页面上」的体积感；miuix `Card` 无阴影参数，不要试图传 elevation，统一走这个助手。

## 状态卡

- **中性底色 + 仪表布局**：顶部是状态圆点、状态文字和隧道模式；中部是运行时长与当前订阅；操作区与内核署名都在卡内底部。只有状态圆点带状态色，数据保持视觉主体。
- **时长固定为 `HH:mm:ss`**：时长与订阅名统一 20.sp 字号、28.sp 行高与字重，时长启用等宽数字，两列首行按基线对齐。文字去掉字体额外留白，字间距为 0。停止或尚未取得时长时显示 `00:00:00`；满 100 小时后固定显示 `99:99:99`。订阅名最多两行，超长省略。
- **启动中与停止中都显示不定进度条**，两种操作的耗时都无法预估。端口只展示运行时配置中大于 0 的值，停止后清空。
- **卡内底部是居中的内核署名**：`Mihomo`（12.sp，次要色），运行时附版本与有效端口，` · ` 分隔。版本在构建期与 libmihomo 一同注入（ldflags 同源），停止后照样显示；只有拿不到端口时省略端口段。

## 操作区（状态卡内）

操作区并入状态卡，内容随状态切换：停止态是主色大胶囊「启动」（miuix `Button`，56dp 高、28dp 圆角，`AppIcons.Play` 图标 + 白色前景）；运行态是 `热重载 | 停止 | 重启` 三枚等宽胶囊 `TextButton`（52dp 高、26dp 圆角、16sp 半粗），各按 `StatusColors.actionButton` 的动作色做 22% 透明底 + 同色前景，`spacedBy(12.dp)` 分栏；过渡态是单枚禁用胶囊（启动中走 `warning`、停止中走 `primary`），语义仍由文字色区分。

按钮的体积感不只靠尺寸：所有胶囊一律挂 `Modifier.shadow(同色调投影, RoundedCornerShape(圆角))` + `Modifier.border(1.dp, tint.copy(alpha≈0.3), 同 shape)`——投影 ambient/spot 用按钮 tint 同色（API 28+ 生效，深浅主题都浮得起），描边把半透明色块的轮廓收拢，底色浓度低于 18% 会发虚。启动按钮投影 5dp、描边 40% 主色；动作胶囊投影 3dp、描边 32% 动作色。只加灰影不加描边在半透明底上几乎看不出来。

## 卡内水印绘制

速度卡折线图 [TrafficSparkline](../../../../android/app/src/main/kotlin/com/stelliberty/android/ui/screen/home/TrafficSparkline.kt) 与订阅卡用量条 [SubscriptionUsageBar](../../../../android/app/src/main/kotlin/com/stelliberty/android/ui/screen/home/SubscriptionUsageBar.kt) 共用 `HomeShared.kt` 的 `Watermark*` 常量（用量条的形状就是折线退化成的方波），新增水印同样复用，同行两卡的绘图区高度与浓度才一致。miuix `Card` 走 `squircleSurface`（fill + clip），子内容自动裁到圆角，卡内直接铺满绘制即可。

1. `insideMargin = PaddingValues(0.dp)` + 内层 `Box(fillMaxSize)`，文字放进 `Column(padding(16.dp))`，绘制层才能铺满（`BasicCard` 用 `propagateMinConstraints = true`，`fillMaxHeight` 的 Card 会把 min 约束传给 content）。
2. 绘制层用 `Modifier.matchParentSize()`，不参与测量，卡片高度由文字决定，同一 `IntrinsicSize.Min` Row 里的邻卡高度保持一致。
3. 绘制层自身加 `clipToBounds()`，把滑动中的曲线限制在卡内。

## 1Hz 数据的连续动画

- `TrafficHistory.seq` 单调递增，既是动画 key（能区分「新采样点与上一点等值」），也是动画目标值本身：`Animatable` 追踪 `seq` 这个绝对量，`animateTo(seq)`，下一帧继续以绝对量衔接；进度由 `1 - (seq - scroll.value)` 反推。新点随重组立即进入绘制，而 `LaunchedEffect` 至少晚一帧才启动动画；绝对量方案下动画未启动时进度天然为 0，画面保持连续（相对进度的 `snapTo(0) + animateTo(1)` 会在这一帧来回抖动）。
- 单格时长取 1100ms，略长于 1Hz 推送周期，动画总在跑完前被下一帧接上（稳态落后约 0.1 格），曲线持续滑动。
- 滚动窗口的 x 步长取 `capacity - 2`：窗口填满后，最老的点恰好从 `x=0` 滑到 `x=-step`，左边缘始终有内容。
- 滑入进度与纵轴上限都在 `onDrawBehind` 里读（只重绘不重组）。
- 滚动与纵轴缩放动画放在 `repeatOnLifecycle(STARTED)` 内：主页面保持组合，离屏时动画随生命周期暂停（仅暂停数据收集停不下进行中的动画）。
- 数据收集侧同样按可见性启停：`HomeViewModel` 以「前台 × 首页分页可见」两个信号的与为准（`setForegroundVisible` / `setHomeVisible`），流量与内存推流走 `collectWhileVisible`（隐藏即取消收集、断开 WS 通道），轮询走 `pollWhileVisible`；回前台重新订阅，流量窗口重新补零。

## 概览四卡与徽标

概览区四张卡（NET 网速、订阅、IP、SYS）结构同构：标题行（14.sp Medium + 右上角徽标）+ 两行 `InfoRow`。徽标配色统一走 `StatusColors.neutral`——状态表达交给卡内的折线/用量条等图形层，徽标只标注类别，各用各色会显乱。IP 卡在启动前后结构不变（地址 + 节点区域），只有取值随状态变：运行时显示代理出口 IP 与节点区域旗（`regionFlag` 把 ISO 两位码转 Unicode 区域指示符，非标准码原样显示），停止后回落本机 IP、区域显示横线。
