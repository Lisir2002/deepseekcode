package com.deepseek.coder.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.domain.models.ChatMessage
import com.deepseek.coder.domain.models.ChatRole
import com.deepseek.coder.ui.chat.ChatViewModel
import com.deepseek.coder.ui.components.WorkflowProgressCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onNavigateToEditor: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
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
                        Text(
                            text = ui.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
            if (ui.showWorkflowCard) {
                WorkflowProgressCard(
                    state = ui.workflowState,
                    classification = ui.classificationLabel,
                    plan = ui.plan,
                    activeStepIndex = ui.activeStepIndex,
                    completedSteps = ui.completedSteps,
                    selfCheckSummary = ui.selfCheckSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (!ui.clarifyingQuestions.isNullOrEmpty()) {
                ClarificationPanel(
                    questions = ui.clarifyingQuestions!!,
                    onSubmit = viewModel::answerClarifications
                )
            }

            if (ui.messages.isEmpty()) {
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
                    items(ui.messages, key = { it.id.toString() + "_" + it.timestampMs + "_" + it.role }) { msg ->
                        MessageBubble(
                            message = msg,
                            thinkingExpandedGlobal = ui.thinkingExpanded,
                            onToggleThinking = viewModel::toggleThinkingExpanded
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
    onToggleThinking: () -> Unit
) {
    when (message.role) {
        ChatRole.USER -> UserBubble(message)
        ChatRole.ASSISTANT -> AssistantBubble(message, thinkingExpandedGlobal, onToggleThinking)
        else -> OtherBubble(message)
    }
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
private fun AssistantBubble(m: ChatMessage, thinkingExpanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!m.reasoning.isNullOrBlank()) {
            ThinkingFoldCard(reasoning = m.reasoning, expanded = thinkingExpanded, onToggle = onToggle)
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
        } else if (m.pending) {
            AssistChip(
                onClick = {},
                label = { Text("模型思考中…", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingIcon = { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp) }
            )
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

@Composable
private fun ClarificationPanel(
    questions: List<String>,
    onSubmit: (List<String>) -> Unit
) {
    val answers = remember(questions) {
        Array(questions.size) { mutableStateOf("") }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "请补充信息以便我更好地帮你处理：",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            questions.forEachIndexed { i, q ->
                OutlinedTextField(
                    value = answers[i].value,
                    onValueChange = { answers[i].value = it },
                    label = { Text(q.take(60)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.Button(
                    onClick = {
                        onSubmit(answers.map { it.value })
                    }
                ) {
                    Text("提交")
                }
            }
        }
    }
}


