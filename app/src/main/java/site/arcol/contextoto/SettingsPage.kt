package site.arcol.contextoto

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun SettingsSheet(
    colors: Palette, dark: Boolean, markQueriedWords: Boolean, silentInference: Boolean,
    customStatusBar: Boolean, bookmarkVisible: Boolean, current: Provider, settings: SecureSettings,
    updateStatus: String, updateInfo: UpdateInfo?, updateChecking: Boolean,
    focusStatus: String, modifier: Modifier,
    onDark: (Boolean) -> Unit, onMarkQueriedWords: (Boolean) -> Unit,
    onSilentInference: (Boolean) -> Unit, onCustomStatusBar: (Boolean) -> Unit,
    onBookmarkVisible: (Boolean) -> Unit, onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit, onSave: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var footerHeight by remember { mutableStateOf(112.dp) }
    val names = remember { listOf("DeepSeek 官方", "Command Code GOAT", "自定义") }
    var selected by remember { mutableStateOf(current.name) }
    val keys = remember { mutableStateMapOf<String, String>() }
    val models = remember { mutableStateMapOf<String, String>().apply { names.forEach { put(it, settings.modelForProvider(it)) } } }
    val protocols = remember { mutableStateMapOf<String, ApiProtocol>().apply { names.forEach { put(it, settings.protocolForProvider(it)) } } }
    var url by remember { mutableStateOf(settings.customBaseUrl) }
    var bodySize by remember { mutableStateOf(settings.bodySize) }
    var lineFactor by remember { mutableStateOf(settings.lineFactor) }
    var sideMargin by remember { mutableStateOf(settings.sideMargin) }
    var paragraphGap by remember { mutableStateOf(settings.paragraphGap) }
    var modelExpanded by remember { mutableStateOf(false) }
    var focusExpanded by remember { mutableStateOf(false) }
    var scaleExpanded by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var focusUrlInput by remember { mutableStateOf(settings.focusUrl) }
    var focusLinkEditing by remember { mutableStateOf(settings.focusUrl.isBlank()) }
    var focusCatalog by remember { mutableStateOf<FocusCatalog?>(null) }
    var focusSubjectId by remember { mutableIntStateOf(settings.focusSubjectId) }
    var focusItemId by remember { mutableIntStateOf(settings.focusItemId) }
    var focusCatalogStatus by remember { mutableStateOf("填写上报链接后读取目录") }
    var focusCatalogLoading by remember { mutableStateOf(false) }
    fun loadFocusCatalog() {
        if (focusCatalogLoading) return
        scope.launch {
            focusCatalogLoading = true
            focusCatalogStatus = "正在读取科目与事项…"
            val requestedLink = focusUrlInput
            try {
                val catalog = FocusReporter.catalog(requestedLink)
                if (requestedLink != focusUrlInput) return@launch
                focusCatalog = catalog
                val subject = catalog.subjects.firstOrNull { it.id == focusSubjectId }
                    ?: catalog.subjects.firstOrNull { it.name == "英语" && catalog.items.any { item -> item.subjectId == it.id } }
                    ?: catalog.subjects.firstOrNull { catalog.items.any { item -> item.subjectId == it.id } }
                focusSubjectId = subject?.id ?: 0
                val matching = catalog.items.filter { it.subjectId == focusSubjectId }
                focusItemId = matching.firstOrNull { it.id == focusItemId }?.id
                    ?: matching.firstOrNull { it.name == "二轮" }?.id ?: matching.firstOrNull()?.id ?: 0
                focusCatalogStatus = "目录已读取 · 请确认科目和事项"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (requestedLink == focusUrlInput) {
                    focusCatalog = null
                    focusCatalogStatus = error.message ?: "读取目录失败"
                }
            } finally { focusCatalogLoading = false }
        }
    }
    LaunchedEffect(focusExpanded) {
        if (focusExpanded && focusUrlInput.isNotBlank() && focusCatalog == null) loadFocusCatalog()
    }
    Box(modifier.imePadding()) {
        Column(Modifier.fillMaxSize().padding(bottom = footerHeight).clipToBounds().verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp).padding(top = 24.dp, bottom = 24.dp)) {
            Text("SETTINGS / ${BuildConfig.VERSION_NAME}", color = colors.word, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Spacer(Modifier.height(12.dp))
            Text("阅读与连接", color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 30.sp)
            SettingsCategory("Reading", "阅读外观", colors)
            SwitchSetting("深色主题", "低饱和度的明暗阅读界面", dark, colors, onDark)
            SwitchSetting("沉浸状态栏", "篇名、时间、进度和专注状态；保留挖孔安全区", customStatusBar, colors, onCustomStatusBar)
            SwitchSetting("跟随书签", "沿句子轮廓包裹，随阅读与最远查询位置推进", bookmarkVisible, colors, onBookmarkVisible)
            SettingsFoldout("正文比例", "${bodySize.roundToInt()} sp  ·  ${(lineFactor * 100).roundToInt()}% 行距",
                scaleExpanded, colors, { scaleExpanded = !scaleExpanded }) {
                RevealElement(0, "scale") {
                    Column {
                        SettingAction("恢复参考图比例", colors) {
                            onDark(true); bodySize = 20f; lineFactor = 1.58f; sideMargin = 18f; paragraphGap = 30f
                        }
                        ScaleStepper("字号", "${bodySize.roundToInt()} sp", { bodySize = (bodySize - 1).coerceAtLeast(16f) },
                            { bodySize = (bodySize + 1).coerceAtMost(28f) }, colors)
                        ScaleStepper("行距", "${(lineFactor * 100).roundToInt()}%", { lineFactor = (lineFactor - .05f).coerceAtLeast(1.25f) },
                            { lineFactor = (lineFactor + .05f).coerceAtMost(2f) }, colors)
                        ScaleStepper("左右边距", "${sideMargin.roundToInt()} dp", { sideMargin = (sideMargin - 2).coerceAtLeast(12f) },
                            { sideMargin = (sideMargin + 2).coerceAtMost(44f) }, colors)
                        ScaleStepper("段落间距", "${paragraphGap.roundToInt()} dp", { paragraphGap = (paragraphGap - 4).coerceAtLeast(12f) },
                            { paragraphGap = (paragraphGap + 4).coerceAtMost(72f) }, colors)
                    }
                }
            }
            SettingsCategory("Learning", "学习与标记", colors)
            SwitchSetting("已查询单词加粗", "保留正文及从句的原有颜色；默认关闭", markQueriedWords, colors, onMarkQueriedWords)
            SwitchSetting("静默推理", "后台逐句分析，会消耗所选模型额度；默认关闭", silentInference, colors, onSilentInference)
            SettingsCategory("Connections", "模型与专注", colors)
            SettingsFoldout("模型与连接", "$selected · ${models[selected].orEmpty().ifBlank { "选择模型" }}",
                modelExpanded, colors, { modelExpanded = !modelExpanded }) {
                RevealElement(0, "model-services") {
                    Column {
                        names.forEach { name -> ChoiceSetting(name, selected == name, colors) { selected = name } }
                        Spacer(Modifier.height(12.dp))
                        if (selected == "自定义") {
                            FieldLabel("API 端点", colors)
                            SettingInput("https://…/v1", url, colors, onChange = { url = it })
                        } else Text(providerBaseUrl(selected, url), color = colors.muted, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        FieldLabel("请求协议", colors)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ApiProtocol.entries.forEach { protocol ->
                                Text(protocol.label, Modifier.weight(1f)
                                    .background(colors.word.copy(alpha = if (protocols[selected] == protocol) .16f else .045f))
                                    .clickable { protocols[selected] = protocol }.padding(vertical = 12.dp, horizontal = 8.dp),
                                    color = if (protocols[selected] == protocol) colors.word else colors.muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                RevealElement(1, "model-credentials") {
                    Column(Modifier.padding(top = 16.dp)) {
                        FieldLabel("API Key", colors)
                        SettingInput("留空则使用已保存的密钥", keys[selected].orEmpty(), colors, secret = true,
                            onChange = { keys[selected] = it })
                        Text("密钥在本机加密保存。模型目录与查询共用当前端点和密钥。", color = colors.muted,
                            fontSize = 11.sp, lineHeight = 17.sp)
                    }
                }
                RevealElement(2, "model-picker") {
                    ModelPicker(selected, providerBaseUrl(selected, url), keys[selected].orEmpty(),
                        models[selected].orEmpty(), settings, colors) { models[selected] = it }
                }
            }
            SettingsFoldout("碎片专注", if (focusUrlInput.isBlank()) "未配置上报" else focusStatus,
                focusExpanded, colors, { focusExpanded = !focusExpanded }) {
                RevealElement(0, "focus-link") {
                    Column {
                        Text("阅读页在前台时每 20 秒上报；退出阅读立即停止。", color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(12.dp))
                        SettingAction(if (focusLinkEditing) "收起上报链接" else "修改上报链接", colors) { focusLinkEditing = !focusLinkEditing }
                        SettingsReveal(focusLinkEditing) {
                            SettingInput("HTTPS 链接（/catalog 或 /frame）", focusUrlInput, colors, secret = true, onChange = {
                                focusUrlInput = it; focusCatalog = null; focusSubjectId = 0; focusItemId = 0
                                focusCatalogStatus = "链接变更后请重新读取目录"
                            })
                        }
                    }
                }
                RevealElement(1, "focus-catalog") {
                    Column(Modifier.padding(top = 12.dp)) {
                        SettingAction(if (focusCatalogLoading) "正在读取…" else "读取科目与事项", colors,
                            enabled = !focusCatalogLoading && focusUrlInput.isNotBlank()) { loadFocusCatalog() }
                        Text(focusCatalogStatus, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                        focusCatalog?.let { catalog ->
                            Spacer(Modifier.height(16.dp)); FieldLabel("科目", colors)
                            catalog.subjects.filter { subject -> catalog.items.any { it.subjectId == subject.id } }.forEach { subject ->
                                ChoiceSetting("${subject.name}  #${subject.id}", focusSubjectId == subject.id, colors) {
                                    focusSubjectId = subject.id
                                    val choices = catalog.items.filter { it.subjectId == subject.id }
                                    focusItemId = choices.firstOrNull { it.name == "二轮" }?.id ?: choices.firstOrNull()?.id ?: 0
                                }
                            }
                            Spacer(Modifier.height(12.dp)); FieldLabel("事项", colors)
                            catalog.items.filter { it.subjectId == focusSubjectId }.forEach { item ->
                                ChoiceSetting("${item.name}  #${item.id}", focusItemId == item.id, colors) { focusItemId = item.id }
                            }
                        }
                        Text("SOURCE / ${FocusReporter.SOURCE}  ·  $focusStatus", color = colors.muted,
                            fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
            SettingsCategory("About", "版本与更新", colors)
            SettingAction(if (updateChecking) "正在检查…" else "检查更新", colors, !updateChecking, onCheckUpdate)
            Text(updateStatus, Modifier.fillMaxWidth().clickable(enabled = updateInfo?.available == true,
                onClick = onDownloadUpdate).padding(vertical = 12.dp), color = if (updateInfo?.available == true) colors.word else colors.muted,
                fontSize = 12.sp, lineHeight = 18.sp)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
            footerHeight = with(density) { it.height.toDp() }
        }.background(Brush.verticalGradient(listOf(
            Color.Transparent, colors.paper.copy(alpha = .45f), colors.paper.copy(alpha = .82f))))
            .padding(horizontal = 26.dp, vertical = 18.dp)) {
            saveError?.let { Text(it, color = colors.paragraph, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(Modifier.fillMaxWidth().clickable {
                val unchangedFocus = focusUrlInput == settings.focusUrl && focusSubjectId == settings.focusSubjectId && focusItemId == settings.focusItemId
                if (focusUrlInput.isNotBlank() && !unchangedFocus && focusCatalog?.item(focusSubjectId, focusItemId) == null) {
                    saveError = "请读取上报目录，选择匹配的科目与事项"; focusExpanded = true; return@clickable
                }
                if (models[selected].isNullOrBlank() || runCatching { modelListUrl(providerBaseUrl(selected, url)) }.isFailure) {
                    saveError = "请填写有效 API 端点与模型 ID"; modelExpanded = true; return@clickable
                }
                settings.rememberCacheProvider(settings.provider())
                settings.providerName = selected
                settings.customBaseUrl = url.trim().trimEnd('/')
                models.forEach { (name, model) -> settings.saveModel(name, model) }
                protocols.forEach { (name, protocol) -> settings.saveProtocol(name, protocol) }
                keys.filterValues { it.isNotBlank() }.forEach { (name, key) -> settings.saveKey(name, key) }
                settings.rememberCacheProvider(settings.provider())
                settings.bodySize = bodySize; settings.lineFactor = lineFactor; settings.sideMargin = sideMargin; settings.paragraphGap = paragraphGap
                settings.focusUrl = focusUrlInput
                settings.focusSubjectId = if (focusUrlInput.isBlank()) 0 else focusSubjectId
                settings.focusItemId = if (focusUrlInput.isBlank()) 0 else focusItemId
                keys.clear(); onSave()
            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("SAVE CHANGES", color = colors.word, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Spacer(Modifier.height(5.dp)); Text("保存并返回", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f)); Text("→", color = colors.word, fontFamily = ReadingFont, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun SettingsCategory(english: String, chinese: String, colors: Palette) {
    Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
        Text(english, color = colors.ink, fontFamily = ReadingFont, fontSize = 28.sp)
        Spacer(Modifier.weight(1f)); Text(chinese, color = colors.muted, fontFamily = FontFamily.Serif, fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun SwitchSetting(label: String, detail: String, enabled: Boolean, colors: Palette, change: (Boolean) -> Unit) {
    val tint by animateColorAsState(if (enabled) colors.word.copy(alpha = .12f) else colors.ink.copy(alpha = .04f), tween(220))
    val thumb by animateDpAsState(if (enabled) 24.dp else 4.dp, tween(260, easing = FastOutSlowInEasing))
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(tint)
        .toggleable(enabled, role = Role.Switch, onValueChange = change).padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = colors.muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Box(Modifier.size(44.dp, 24.dp).background(colors.word.copy(alpha = if (enabled) .3f else .12f))) {
            Box(Modifier.offset(x = thumb, y = 4.dp).size(16.dp).background(if (enabled) colors.word else colors.muted))
        }
    }
}

@Composable
private fun SettingsFoldout(title: String, summary: String, expanded: Boolean, colors: Palette, toggle: () -> Unit,
                            content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().background(colors.ink.copy(alpha = .035f)).clickable(onClick = toggle)
            .padding(horizontal = 14.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(title, color = colors.ink, fontFamily = FontFamily.Serif, fontSize = 19.sp)
                Text(summary, color = colors.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp))
            }
            Text(if (expanded) "−" else "+", color = colors.word, fontFamily = ReadingFont, fontSize = 27.sp)
        }
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(400, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(280, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(220))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp), content = content)
        }
    }
}

@Composable
private fun SettingsReveal(expanded: Boolean, content: @Composable () -> Unit) {
    val distance = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(expanded,
        enter = expandVertically(tween(400, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(400)) + scaleIn(tween(400, easing = FastOutSlowInEasing), .7f, TransformOrigin(.5f, 0f)) +
            slideInVertically(tween(400, easing = FastOutSlowInEasing)) { -distance },
        exit = shrinkVertically(tween(280, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(220)) + scaleOut(tween(280), .7f, TransformOrigin(.5f, 0f)) +
            slideOutVertically(tween(280)) { -distance }) { content() }
}

@Composable
private fun ChoiceSetting(label: String, chosen: Boolean, colors: Palette, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp).background(colors.word.copy(alpha = if (chosen) .13f else .025f))
        .clickable(onClick = select).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (chosen) colors.word else colors.ink, fontSize = 14.sp)
        if (chosen) Text("✓", color = colors.word, fontSize = 15.sp)
    }
}

@Composable
private fun FieldLabel(label: String, colors: Palette) {
    Text(label, color = colors.muted, fontSize = 11.sp, letterSpacing = .4.sp)
}

@Composable
private fun SettingAction(label: String, colors: Palette, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(colors.word.copy(alpha = .08f)).clickable(enabled = enabled, onClick = action)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = if (enabled) colors.word else colors.muted, fontSize = 13.sp)
        Text("→", color = if (enabled) colors.word else colors.muted, fontFamily = ReadingFont, fontSize = 19.sp)
    }
}

@Composable
private fun ModelPicker(name: String, baseUrl: String, keyInput: String, selected: String,
                        settings: SecureSettings, colors: Palette, select: (String) -> Unit) {
    val credential = keyInput.ifBlank { settings.savedKey(name) }
    val scope = rememberCoroutineScope()
    val client = remember { ModelCatalogClient() }
    var options by remember(name, baseUrl, credential) { mutableStateOf(settings.cachedModels(baseUrl, credential)) }
    var loading by remember(name, baseUrl, credential) { mutableStateOf(false) }
    var status by remember(name, baseUrl, credential) { mutableStateOf(if (options.isEmpty()) "可读取模型列表，也可手动填写 ID" else "已保存 ${options.size} 个模型，可刷新目录") }
    var search by remember(name, baseUrl) { mutableStateOf("") }
    var request by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(name, baseUrl, credential) { onDispose { request?.cancel() } }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        FieldLabel("当前模型 ID", colors)
        SettingInput("填写或从目录选择模型", selected, colors, onChange = select)
        SettingAction(if (loading) "正在拉取模型…" else "拉取模型列表", colors, !loading && credential.isNotBlank()) {
            request = scope.launch {
                loading = true; status = "正在读取端点的模型目录…"
                try {
                    val models = client.fetch(baseUrl, credential)
                    settings.saveModelCatalog(baseUrl, credential, models)
                    options = models
                    status = if (models.isEmpty()) "目录为空，可手动填写模型 ID" else "${models.size} 个可用模型 · 点击选择"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { status = error.message ?: "读取失败，请重试" }
                finally { loading = false }
            }
        }
        Text(status, color = colors.muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        if (options.isNotEmpty()) {
            SettingInput("搜索模型名称或 ID", search, colors, onChange = { search = it })
            val matches = options.filter { search.isBlank() || it.id.contains(search.trim(), true) || it.name.contains(search.trim(), true) }
            if (matches.isEmpty()) Text("没有匹配的模型", color = colors.muted, fontSize = 12.sp)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(matches, key = { it.id }) { option ->
                    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        .background(colors.word.copy(alpha = if (selected == option.id) .15f else .025f))
                        .clickable { select(option.id) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(option.name, Modifier.weight(1f), color = if (selected == option.id) colors.word else colors.ink,
                                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (selected == option.id) Text("✓", color = colors.word, fontSize = 15.sp)
                        }
                        if (option.name != option.id) Text(option.id, color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}
