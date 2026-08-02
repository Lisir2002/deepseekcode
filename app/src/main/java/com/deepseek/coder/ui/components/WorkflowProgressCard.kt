package com.deepseek.coder.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deepseek.coder.domain.workflow.OrchestratorEvent
import com.deepseek.coder.domain.workflow.WorkflowPlan
import com.deepseek.coder.domain.workflow.WorkflowState
import com.deepseek.coder.domain.workflow.WorkflowStep

/**
 * Collapsible workflow-progress card.
 *
 * Shown in ChatScreen above the list of assistant messages during streaming
 * and left in place (collapsed) afterwards so users can audit how a request
 * was routed / decomposed / self-checked.  Collapsible so it never crowds the
 * actual chat content.
 */
@Composable
fun WorkflowProgressCard(
    state: WorkflowState,
    classification: String?,
    plan: WorkflowPlan?,
    activeStepIndex: Int,
    completedSteps: Set<Int>,
    selfCheckSummary: String?,
    // ===== P2-T2-02 新增：Scope 纠错 + 三档粒度 + Rerank 开关 =====
    currentScope: String? = null,            // "ANDROID_KOTLIN" / "WEB_FRONTEND" / "GENERAL"
    currentGranularity: String = "MEDIUM",   // "COARSE" / "MEDIUM" / "FINE"
    rerankEnabled: Boolean = true,
    scopeHints: List<String> = emptyList(),
    onScopeChanged: (String) -> Unit = {},    // 用户通过下拉纠错后回调
    onGranularityChanged: (String) -> Unit = {},
    onRerankToggled: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    text = "Orchestrator · ${state.display}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "展开/收起"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))

                    // ========== P2-T2-02: Scope 下拉纠错 + Granularity Chips + Rerank 开关 (一行) ==========
                    ScopeGranularityRow(
                        currentScope = currentScope,
                        currentGranularity = currentGranularity,
                        rerankEnabled = rerankEnabled,
                        scopeHints = scopeHints,
                        onScopeChanged = onScopeChanged,
                        onGranularityChanged = onGranularityChanged,
                        onRerankToggled = onRerankToggled
                    )
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))

                    if (classification != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(
                                onClick = {},
                                label = { Text("意图：$classification") },
                                enabled = false
                            )
                        }
                    }
                    if (plan != null) {
                        StepLadder(
                            plan = plan,
                            activeStepIndex = activeStepIndex,
                            completedSteps = completedSteps
                        )
                    }
                    if (selfCheckSummary != null) {
                        Text(
                            "自检：$selfCheckSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state in STATES_WITH_PROGRESS) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ==================== P2-T2-02: Scope Chip 下拉纠错 + 三档粒度 + Rerank 开关 ====================
@Composable
private fun ScopeGranularityRow(
    currentScope: String?,
    currentGranularity: String,
    rerankEnabled: Boolean,
    scopeHints: List<String>,
    onScopeChanged: (String) -> Unit,
    onGranularityChanged: (String) -> Unit,
    onRerankToggled: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- 第一行：Scope Chip (下拉纠错) + Rerank 开关 ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScopeDropdownChip(currentScope, scopeHints, onScopeChanged)
            Spacer(Modifier.weight(1f))
            Text(
                "Rerank",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Switch(checked = rerankEnabled, onCheckedChange = onRerankToggled)
        }
        // ---- 第二行：三档粒度 FilterChip 单选 ----
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            listOf(
                Triple("COARSE", "粗粒度 · Top-down", "高层架构 + M1-M3 里程碑"),
                Triple("MEDIUM", "中粒度 · Balanced", "标准 5~9 步交付（默认）"),
                Triple("FINE", "细粒度 · Bottom-up", "单文件/单函数逐任务推进")
            ).forEach { (tag, title, hint) ->
                FilterChip(
                    selected = currentGranularity == tag,
                    onClick = { onGranularityChanged(tag) },
                    label = { Text(title) })
            }
            AssistChip(
                onClick = {},
                label = {
                    val hint = when (currentGranularity) {
                        "COARSE" -> "高层架构 + M1-M3 里程碑"
                        "FINE" -> "单文件/单函数逐任务推进"
                        else -> "标准 5~9 步交付（默认）"
                    }
                    Text("🎯 $hint", style = MaterialTheme.typography.labelSmall)
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                )
            )
        }
    }
}

@Composable
private fun ScopeDropdownChip(
    currentScope: String?,
    scopeHints: List<String>,
    onScopeChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (currentScope) {
        "ANDROID_KOTLIN" -> "🛠 Scope：Android · Kotlin"
        "WEB_FRONTEND" -> "🌐 Scope：Web 前端"
        "GENERAL" -> "🧰 Scope：通用编程"
        null -> "🔍 Scope：自动识别中…"
        else -> "❓ Scope：$currentScope"
    }
    val color = when (currentScope) {
        "ANDROID_KOTLIN" -> Color(0xFF3DDC84)
        "WEB_FRONTEND" -> Color(0xFF61DAFB)
        "GENERAL" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Column {
        AssistChip(
            onClick = { expanded = true },
            leadingIcon = {
                Text(
                    "⬇",
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            },
            label = { Text(label, color = color, fontWeight = FontWeight.SemiBold) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("ANDROID_KOTLIN" to "🛠 Android · Kotlin（Compose/Room/Hilt）",
                "WEB_FRONTEND" to "🌐 Web 前端（React/Vue/Next/TS）",
                "GENERAL" to "🧰 通用编程（后端/脚本/DevOps）"
            ).forEach { (tag, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onScopeChanged(tag)
                    },
                    leadingIcon = {
                        if (tag == currentScope) Text("✅", color = Color(0xFF059669))
                        else Spacer(Modifier.width(18.dp))
                    }
                )
            }
            if (scopeHints.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(horizontal = 8.dp))
                DropdownMenuItem(
                    text = { Text("🔧 dispatch.scope_hint 微调（当前：${scopeHints.joinToString()}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { expanded = false }
                )
            }
        }
    }
}

@Composable
private fun StepLadder(
    plan: WorkflowPlan,
    activeStepIndex: Int,
    completedSteps: Set<Int>
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().height((48 * plan.steps.size.coerceAtMost(6)).dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(plan.steps, key = { it.index }) { step ->
                StepRow(
                    step = step,
                    state = when {
                        step.index in completedSteps -> StepState.DONE
                        step.index == activeStepIndex -> StepState.ACTIVE
                        else -> StepState.PENDING
                    }
                )
            }
        }
    }
}

@Composable
private fun StepRow(step: WorkflowStep, state: StepState) {
    val (indicatorBg, indicatorFg, label) = when (state) {
        StepState.PENDING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "等待"
        )
        StepState.ACTIVE -> Triple(
            MaterialTheme.colorScheme.primary,
            Color.White,
            "进行中"
        )
        StepState.DONE -> Triple(
            Color(0xFF059669),
            Color.White,
            "已完成"
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier
                .background(indicatorBg, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = indicatorFg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "步骤 ${step.index + 1} · ${step.title}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private enum class StepState { PENDING, ACTIVE, DONE }

private val WorkflowState.display: String
    get() = when (this) {
        WorkflowState.IDLE -> "就绪"
        WorkflowState.CLASSIFY -> "意图分类"
        WorkflowState.CLARIFY_QUESTION -> "需求澄清"
        WorkflowState.GOVERN_CONTEXT -> "上下文治理"
        WorkflowState.DECOMPOSE -> "任务拆解"
        WorkflowState.EXECUTE -> "生成代码"
        WorkflowState.SELF_CHECK -> "代码自检"
        WorkflowState.RETRY_FIX -> "自动修复"
        WorkflowState.DONE -> "完成"
        WorkflowState.FAILURE -> "失败"
    }

private val STATES_WITH_PROGRESS = setOf(
    WorkflowState.EXECUTE,
    WorkflowState.CLASSIFY,
    WorkflowState.DECOMPOSE,
    WorkflowState.SELF_CHECK,
    WorkflowState.RETRY_FIX
)
