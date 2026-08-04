package com.deepseek.coder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.domain.skill.OutputFormat
import com.deepseek.coder.domain.skill.SkillCategory
import com.deepseek.coder.ui.chat.UserSkillEditorViewModel

/**
 * 用户自定义 Skill 编辑器（Phase 4，SPEC §7）。
 *
 * 新建/编辑/删除一条用户自定义 skill。保存或删除成功后回调 [onDone] 返回管理页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserSkillEditorScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: UserSkillEditorViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(s.saved, s.deleted) {
        if (s.saved || s.deleted) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.isEditing) "编辑 Skill" else "新建 Skill") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (s.loading) {
            Column(Modifier.fillMaxSize().padding(padding)) { Text("加载中…", Modifier.padding(16.dp)) }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = s.name, onValueChange = viewModel::onNameChange,
                label = { Text("名称 *") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.description, onValueChange = viewModel::onDescriptionChange,
                label = { Text("一句话描述") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.icon, onValueChange = viewModel::onIconChange,
                label = { Text("图标名（Material Icon，如 Star/Code/Lightbulb）") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            LabeledSection("分类") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillCategory.entries.forEach { c ->
                        FilterChip(
                            selected = s.category == c,
                            onClick = { viewModel.onCategoryChange(c) },
                            label = { Text(categoryLabel(c)) }
                        )
                    }
                }
            }

            LabeledSection("输出格式") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputFormat.entries.forEach { f ->
                        FilterChip(
                            selected = s.outputFormat == f,
                            onClick = { viewModel.onOutputFormatChange(f) },
                            label = { Text(f.name) }
                        )
                    }
                }
            }

            LabeledSection("可用工具（勾选后该 Skill 可调用对应工具）") {
                if (viewModel.availableTools.isEmpty()) {
                    Text("无可用工具", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        viewModel.availableTools.forEach { tool ->
                            FilterChip(
                                selected = tool.name in s.selectedTools,
                                onClick = { viewModel.toggleTool(tool.name) },
                                label = { Text(tool.name) }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = s.systemPrompt, onValueChange = viewModel::onSystemPromptChange,
                label = { Text("System Prompt *（角色设定 + 工作流程 + 约束）") },
                minLines = 6, maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = s.styleHints, onValueChange = viewModel::onStyleHintsChange,
                label = { Text("输出风格提示（可选）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = viewModel::save,
                    enabled = s.canSave,
                    modifier = Modifier.weight(1f)
                ) { Text(if (s.isEditing) "保存" else "创建") }
                if (s.isEditing) {
                    OutlinedButton(
                        onClick = viewModel::delete,
                        enabled = !s.saving
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("删除")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LabeledSection(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

private fun categoryLabel(category: SkillCategory): String = when (category) {
    SkillCategory.CODE_MODIFY -> "代码改动"
    SkillCategory.CODE_UNDERSTAND -> "理解产出"
    SkillCategory.CODE_GENERATE -> "新生成"
    SkillCategory.QA_ASSIST -> "问答辅助"
}
