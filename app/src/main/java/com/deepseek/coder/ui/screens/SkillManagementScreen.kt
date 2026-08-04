package com.deepseek.coder.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.data.skill.UserSkillDef
import com.deepseek.coder.domain.skill.Skill
import com.deepseek.coder.domain.skill.SkillCategory
import com.deepseek.coder.ui.chat.SkillManagementViewModel

/**
 * Skill 管理页（SPEC §6.1 / Phase 4）。
 *
 * 按分类分组展示所有 skill（内置 + 用户自定义合并），支持：
 *  - 启用/禁用切换
 *  - 新建/编辑/删除用户自定义 skill（编辑通过 [onNavigateToEditor] 跳转编辑器）
 *  - 导入/导出 JSON（自定义 skill 集合）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagementScreen(
    onBack: () -> Unit,
    onNavigateToEditor: (skillId: String) -> Unit,
    viewModel: SkillManagementViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 导出：先发起 SAF 创建文档，回调里取 JSON 写入
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.export { json ->
                if (json != null) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }.onSuccess { Toast.makeText(context, "已导出", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
                } else {
                    Toast.makeText(context, "导出失败：无自定义 Skill", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 导入：发起 SAF 选文档，回调里读取并导入
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(context, "导入失败：文件为空", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.import(text)
            }
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    val grouped = ui.skills.groupBy { it.category }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skill 管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToEditor("new") }) {
                        Icon(Icons.Default.Add, contentDescription = "新建 Skill")
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导入")
                    }
                    IconButton(onClick = { exportLauncher.launch("deepcoder_skills.json") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "导出")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "启用/禁用内置 Skill。禁用后该 Skill 不出现在对话页选择器中，" +
                        "但已用该 Skill 的历史消息仍可查看。\n" +
                        "自定义 Skill 可编辑/删除（点击右侧编辑按钮）；导出/导入为 JSON。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            SkillCategory.entries.forEach { category ->
                val skills = grouped[category] ?: return@forEach
                item {
                    Text(
                        categoryLabel(category),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor(category),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(skills, key = { it.id }) { skill ->
                    SkillRow(
                        skill = skill,
                        onToggle = { enabled -> viewModel.setEnabled(skill.id, enabled) },
                        onEdit = if (UserSkillDef.isUserSkill(skill.id)) {
                            { onNavigateToEditor(skill.id) }
                        } else null
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, onToggle: (Boolean) -> Unit, onEdit: (() -> Unit)?) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (skill.enabled) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    skill.name + if (!skill.builtIn) "（自定义）" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (skill.tools.isNotEmpty()) {
                        Icon(
                            Icons.Default.Build,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            skill.tools.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "无工具",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(18.dp))
                }
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = skill.id != "default_chat" // default_chat 不可禁用
            )
        }
    }
}

private fun categoryLabel(category: SkillCategory): String = when (category) {
    SkillCategory.CODE_MODIFY -> "代码改动"
    SkillCategory.CODE_UNDERSTAND -> "理解产出"
    SkillCategory.CODE_GENERATE -> "新生成"
    SkillCategory.QA_ASSIST -> "问答辅助"
}

private fun categoryColor(category: SkillCategory): Color = when (category) {
    SkillCategory.CODE_MODIFY -> Color(0xFFEF4444)
    SkillCategory.CODE_UNDERSTAND -> Color(0xFF3B82F6)
    SkillCategory.CODE_GENERATE -> Color(0xFF22C55E)
    SkillCategory.QA_ASSIST -> Color(0xFF8B5CF6)
}
