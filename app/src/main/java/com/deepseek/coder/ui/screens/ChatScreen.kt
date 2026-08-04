package com.deepseek.coder.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.data.skill.AttachedFile
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.domain.skill.Skill
import com.deepseek.coder.domain.skill.SkillCategory
import com.deepseek.coder.ui.chat.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateToEditor: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val availableSkills by viewModel.availableSkills.collectAsStateWithLifecycle()
    val scrollState = rememberLazyListState()

    // Auto-scroll to bottom when messages grow
    LaunchedEffect(ui.messages.size, ui.streaming) {
        if (ui.messages.isNotEmpty()) scrollState.animateScrollToItem(ui.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DeepCoder 对话")
                        if (sessionId != null && sessionId != "new") {
                            Text(
                                "#${sessionId.take(8)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空对话")
                    }
                    IconButton(onClick = onNavigateToSkills) {
                        Icon(Icons.Default.Workspaces, contentDescription = "Skill 管理")
                    }
                    IconButton(onClick = onNavigateToSessions) {
                        Icon(Icons.Default.History, contentDescription = "历史会话")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToEditor,
                icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                text = { Text("打开编辑器") }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(12.dp)
                ) {
                    AnimatedVisibility(visible = ui.error != null) {
                        Column(Modifier.padding(bottom = 8.dp)) {
                            Text(
                                text = ui.error.orEmpty(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            // §3.9 决策 3：回路失败重试按钮
                            if (ui.canRetry) {
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = viewModel::retry, modifier = Modifier.height(36.dp)) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("从失败点重试", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    // 已附加文件 chip（Phase 2）
                    if (ui.attachedFiles.isNotEmpty()) {
                        AttachedFilesRow(
                            files = ui.attachedFiles,
                            onRemove = viewModel::removeAttachedFile
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 附加文件按钮（Phase 2，SPEC §4.2）
                        val context = LocalContext.current
                        val scope = rememberCoroutineScope()
                        val filePicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            if (uri != null) {
                                scope.launch {
                                    runCatching {
                                        val displayName = uri.lastPathSegment?.substringAfterLast('/')
                                            ?: "attached_file"
                                        context.contentResolver.openInputStream(uri)?.use { stream ->
                                            viewModel.addAttachedFile(displayName, stream)
                                        }
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.AttachFile, contentDescription = "附加文件")
                        }
                        OutlinedTextField(
                            value = ui.input,
                            onValueChange = viewModel::onInputChanged,
                            placeholder = { Text("问我写代码吧，支持 Markdown / 代码块…") },
                            modifier = Modifier.weight(1f),
                            maxLines = 6,
                            minLines = 1
                        )
                        Spacer(Modifier.width(8.dp))
                        if (ui.canCancel) {
                            IconButton(onClick = viewModel::cancel) {
                                Icon(Icons.Default.Close, contentDescription = "取消", tint = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            IconButton(
                                onClick = viewModel::send,
                                enabled = ui.canSend
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送")
                            }
                        }
                    }
                    if (ui.streaming) {
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("流式生成中…", style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (ui.lastUsage != null) {
                        val u = ui.lastUsage!!
                        Text(
                            text = "上次：prompt ${u.promptTokens} + completion ${u.completionTokens}" +
                                    if (u.reasoningTokens > 0) " (thinking ${u.reasoningTokens})" else "" +
                                            " = ${u.totalTokens} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SkillPickerRow(
                currentSkillId = ui.currentSkillId,
                availableSkills = availableSkills,
                onSelect = viewModel::selectSkill
            )
            // 过滤展示消息：隐藏 TOOL 角色与空白 toolCalls 的中间 assistant 消息
            // （tool 信息由折叠卡片展示，SPEC §6.2 决策 5/11）
            val displayMessages = ui.messages.filter { msg ->
                msg.role != ChatRole.TOOL &&
                    !(msg.role == ChatRole.ASSISTANT &&
                        msg.text.isBlank() &&
                        msg.toolCalls.isNotEmpty() &&
                        !msg.pending)
            }
            if (displayMessages.isEmpty()) {
                EmptyStateHint(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(
                        displayMessages,
                        key = { _, msg -> msg.id.toString() + "_" + msg.timestampMs + "_" + msg.role }
                    ) { index, msg ->
                        val isLast = index == displayMessages.lastIndex
                        MessageBubble(
                            message = msg,
                            thinkingExpandedGlobal = ui.thinkingExpanded,
                            onToggleThinking = viewModel::toggleThinkingExpanded,
                            availableSkills = availableSkills,
                            toolCalls = if (isLast && msg.role == ChatRole.ASSISTANT) ui.pendingToolCalls else emptyList()
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateHint(modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("欢迎使用 DeepCoder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "示例问题：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val examples = listOf(
                "用 Kotlin 写一个线程安全的 LRU 缓存",
                "Python 实现快速排序并给出复杂度分析",
                "解释下面代码的潜在 bug，并给出修复方案"
            )
            examples.forEach { Text("·  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    thinkingExpandedGlobal: Boolean,
    onToggleThinking: () -> Unit,
    availableSkills: List<Skill> = emptyList(),
    toolCalls: List<ChatViewModel.ToolCallRecord> = emptyList()
) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantBubble(
            m = message,
            thinkingExpanded = thinkingExpandedGlobal,
            onToggle = onToggleThinking,
            availableSkills = availableSkills,
            toolCalls = toolCalls
        )
        else -> OtherBubble(message)
    }
}

/**
 * 思考中状态文案（区分阶段，让用户知道模型在做什么而非"卡住"）。
 */
private fun thinkingStatusLabel(reasoning: String?, pending: Boolean): String? {
    if (!pending) return null
    return if (!reasoning.isNullOrBlank()) "思考中（${reasoning.length} 字）…"
    else "等待模型响应…"
}

@Composable
private fun UserBubble(m: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(Color(0xFF4F46E5))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = m.text,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    m: ChatMessage,
    thinkingExpanded: Boolean,
    onToggle: () -> Unit,
    availableSkills: List<Skill>,
    toolCalls: List<ChatViewModel.ToolCallRecord>
) {
    val skill = m.skillId?.let { sid -> availableSkills.firstOrNull { it.id == sid } }
    // 流式期间 reasoning 正在输出时自动展开思考卡片，让用户看到实时进度（避免"卡住"体感）
    val effectiveExpanded = thinkingExpanded || (m.pending && !m.reasoning.isNullOrBlank())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 工具调用折叠卡片（SPEC §6.2 决策 5/15，位于最终文本上方）
        toolCalls.forEach { record -> ToolCallCard(record) }

        if (!m.reasoning.isNullOrBlank()) {
            ThinkingFoldCard(reasoning = m.reasoning, expanded = effectiveExpanded, onToggle = onToggle)
        }
        if (m.text.isNotBlank()) {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(14.dp)) {
                    RenderTextOrCodeBlock(text = m.text)
                }
            }
        } else if (m.pending && toolCalls.isEmpty()) {
            // 区分阶段：reasoning 已输出时显示字数进度，否则显示"等待响应"
            val label = thinkingStatusLabel(m.reasoning, m.pending) ?: "模型思考中…"
            AssistChip(
                onClick = {},
                label = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
            )
        }
        // Skill tag chip（SPEC §6.3 决策 8，default_chat 不显示）
        if (skill != null && skill.id != "default_chat") {
            SkillTagChip(skill)
        }
    }
}

@Composable
private fun OtherBubble(m: ChatMessage) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Text(
            modifier = Modifier.padding(12.dp),
            text = "${m.role.value.uppercase()}: ${m.text}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ThinkingFoldCard(reasoning: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable(onClick = onToggle).fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                Text(
                    "思考过程（${reasoning.count { it == '\n' } + 1} 行）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore, null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Serif,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RenderTextOrCodeBlock(text: String) {
    // CodeBlock v1 placeholder: simple pre-formatted fence detection (```lang ... ```)
    val clipboard = LocalClipboardManager.current
    val segments = remember(text) { parseMessageSegments(text) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        segments.forEach { seg ->
            when (seg) {
                is Segment.Text -> Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                is Segment.Code -> {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .background(Color(0xFF1E293B))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    seg.lang.ifBlank { "code" },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF94A3B8)
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { clipboard.setText(AnnotatedString(seg.code)) }) {
                                    Icon(Icons.Default.ContentCopy, null, tint = Color(0xFF94A3B8))
                                }
                            }
                            Text(
                                seg.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed class Segment {
    data class Text(val text: String) : Segment()
    data class Code(val lang: String, val code: String) : Segment()
}

private fun parseMessageSegments(text: String): List<Segment> {
    val result = mutableListOf<Segment>()
    var i = 0
    val buffer = StringBuilder()
    while (i < text.length) {
        val fenceStart = text.indexOf("```", i)
        if (fenceStart < 0) {
            buffer.append(text, i, text.length)
            break
        }
        if (fenceStart > i) buffer.append(text, i, fenceStart)
        // Flush text segment
        if (buffer.isNotEmpty()) {
            result.add(Segment.Text(buffer.toString()))
            buffer.clear()
        }
        // skip first newline after ```
        var langEnd = text.indexOf('\n', fenceStart + 3)
        if (langEnd < 0) langEnd = text.length
        val lang = text.substring(fenceStart + 3, langEnd).trim()
        val codeStart = if (langEnd < text.length) langEnd + 1 else langEnd
        val fenceEnd = text.indexOf("```", codeStart)
        if (fenceEnd < 0) {
            // Unclosed code fence: treat rest as code
            val code = text.substring(codeStart)
            result.add(Segment.Code(lang = lang, code = code))
            i = text.length
            break
        }
        val code = text.substring(codeStart, fenceEnd).trimEnd('\n')
        result.add(Segment.Code(lang = lang, code = code))
        i = fenceEnd + 3
    }
    if (buffer.isNotEmpty()) result.add(Segment.Text(buffer.toString()))
    return result.ifEmpty { listOf(Segment.Text(text)) }
}

// ===================================================================
// Skill 系统 UI 组件（SPEC-Skill-v1.2 §6）
// ===================================================================

/**
 * Skill 选择器行（SPEC §6.1）：显示当前 skill 的 FilterChip，点击展开下拉列表切换。
 */
@Composable
private fun SkillPickerRow(
    currentSkillId: String,
    availableSkills: List<Skill>,
    onSelect: (String) -> Unit
) {
    val current = availableSkills.firstOrNull { it.id == currentSkillId }
        ?: availableSkills.firstOrNull { it.id == "default_chat" }
        ?: return
    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            FilterChip(
                selected = current.id != "default_chat",
                onClick = { expanded = true },
                label = { Text(current.name) },
                leadingIcon = {
                    Icon(skillIcon(current.icon), contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "切换 skill", modifier = Modifier.size(18.dp))
                }
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availableSkills.filter { it.enabled }.forEach { skill ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    skillIcon(skill.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = skillTagColor(skill.category)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        skill.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (skill.id == current.id) {
                                    Spacer(Modifier.weight(1f))
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        onClick = {
                            onSelect(skill.id)
                            expanded = false
                        }
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "@skill_name 可临时切换",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Skill tag chip（SPEC §6.3 决策 8）：assistant 消息左下角的小标识。
 */
@Composable
private fun SkillTagChip(skill: Skill) {
    AssistChip(
        onClick = {},
        label = { Text(skill.name, style = MaterialTheme.typography.labelSmall) },
        leadingIcon = {
            Icon(skillIcon(skill.icon), contentDescription = null, modifier = Modifier.size(14.dp))
        }
    )
}

/**
 * 工具调用折叠卡片（SPEC §6.2 决策 5/15）：工具名 + 摘要，点击展开详情。
 */
@Composable
private fun ToolCallCard(record: ChatViewModel.ToolCallRecord) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "tool_chevron")
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    record.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "· ${record.durationMs}ms${if (record.cacheHit) " · 缓存" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore, null,
                    modifier = Modifier.size(16.dp).rotate(rotation)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("参数", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        record.args.ifBlank { "{}" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text("结果", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        record.result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/** skill.icon 字符串映射到 Material ImageVector。 */
private fun skillIcon(icon: String): ImageVector = when (icon) {
    "Lightbulb" -> Icons.Default.Lightbulb
    "Code" -> Icons.Default.Code
    "RateReview" -> Icons.Default.RateReview
    else -> Icons.Default.QuestionAnswer
}

/**
 * 已附加文件 chip 行（Phase 2，SPEC §4.2）：FlowRow 展示每个文件，点击 × 删除。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachedFilesRow(
    files: List<AttachedFile>,
    onRemove: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        files.forEach { file ->
            InputChip(
                selected = false,
                onClick = {},
                label = {
                    Text(
                        "${file.displayName} (${formatSize(file.sizeBytes)})",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "移除 ${file.displayName}",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onRemove(file.path) }
                    )
                }
            )
        }
    }
}

/** 文件大小友好显示。 */
private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
    else -> "%.1fMB".format(bytes / (1024.0 * 1024))
}

/** SkillCategory 映射到 tag 颜色（SPEC §6.3：改动红/理解蓝/生成绿/问答紫）。 */
private fun skillTagColor(category: SkillCategory): Color = when (category) {
    SkillCategory.CODE_MODIFY -> Color(0xFFEF4444)
    SkillCategory.CODE_UNDERSTAND -> Color(0xFF3B82F6)
    SkillCategory.CODE_GENERATE -> Color(0xFF22C55E)
    SkillCategory.QA_ASSIST -> Color(0xFF8B5CF6)
}




