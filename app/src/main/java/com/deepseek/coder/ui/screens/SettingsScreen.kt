package com.deepseek.coder.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.data.settings.AppSettings.DeepSeekModel
import com.deepseek.coder.data.settings.AppSettings.ReasoningEffort
import com.deepseek.coder.data.settings.AppSettings.ThemeMode
import com.deepseek.coder.ui.settings.SettingsViewModel
import java.text.DecimalFormat

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val s = ui.settings
    val fmt = remember { DecimalFormat("0.00") }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SectionCard("账户 / API Key") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (ui.apiKeyTail != null) "已保存：sk-****${ui.apiKeyTail}" else "尚未配置 API Key",
                        color = if (ui.apiKeyTail != null) Color(0xFF059669) else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::clearApiKey, enabled = ui.apiKeyTail != null) {
                        Icon(Icons.Default.DeleteForever, contentDescription = "清除 Key")
                    }
                }
            }

            SectionCard("模型与采样参数") {
                Labeled("模型") {
                    var expanded by remember { mutableStateOf(false) }
                    AssistChip(
                        onClick = { expanded = true },
                        label = { Text("${s.model.display} (${s.model.id})") }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DeepSeekModel.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.display) },
                                onClick = { viewModel.updateModel(m); expanded = false }
                            )
                        }
                    }
                }
                Labeled("Temperature: ${fmt.format(s.temperature)}") {
                    Slider(
                        value = s.temperature,
                        onValueChange = viewModel::updateTemperature,
                        valueRange = 0f..2f
                    )
                }
                Labeled("最大输出 Token: ${s.maxTokens}") {
                    Slider(
                        value = s.maxTokens.toFloat(),
                        onValueChange = { viewModel.updateMaxTokens(it.toInt()) },
                        valueRange = 256f..8192f,
                        steps = 30
                    )
                }
            }

            SectionCard("思考模式（Reasoning）") {
                Labeled("启用 reasoning_content") {
                    Switch(checked = s.thinkingEnabled, onCheckedChange = viewModel::updateThinkingEnabled)
                }
                Labeled("推理 effort") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReasoningEffort.entries.forEach { effort ->
                            FilterChip(
                                selected = s.reasoningEffort == effort,
                                onClick = { viewModel.updateEffort(effort) },
                                label = { Text(if (effort == ReasoningEffort.DISABLED) "关闭" else effort.value) }
                            )
                        }
                    }
                }
            }

            SectionCard("代码编辑器 / FIM 补全") {
                Labeled("字体大小 (sp): ${s.editorFontSizeSp.toInt()}") {
                    Slider(
                        value = s.editorFontSizeSp,
                        onValueChange = viewModel::updateEditorFontSize,
                        valueRange = 10f..28f,
                        steps = 17
                    )
                }
                Labeled("启用 FIM 自动补全") {
                    Switch(checked = s.fimEnabled, onCheckedChange = viewModel::updateFimEnabled)
                }
                Labeled("FIM 防抖 (ms): ${s.fimDebounceMs}") {
                    Slider(
                        value = s.fimDebounceMs.toFloat(),
                        onValueChange = { viewModel.updateFimDebounceMs(it.toLong()) },
                        valueRange = 200f..2000f,
                        steps = 17
                    )
                }
            }

            SectionCard("系统提示词 & 主题") {
                OutlinedTextField(
                    value = s.systemPrompt,
                    onValueChange = viewModel::updateSystemPrompt,
                    label = { Text("System Prompt") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Labeled("主题") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = s.themeMode == mode,
                                onClick = { viewModel.updateTheme(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "跟随系统"
                                            ThemeMode.LIGHT -> "浅色"
                                            ThemeMode.DARK -> "深色"
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            SectionCard("API 端点（高级）") {
                OutlinedTextField(
                    value = s.baseUrl,
                    onValueChange = viewModel::updateBaseUrl,
                    label = { Text("Chat Completions Base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = s.betaBaseUrl,
                    onValueChange = viewModel::updateBetaBaseUrl,
                    label = { Text("FIM / Beta Base URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionCard("工作流编排 Orchestrator") {
                Labeled("启用智能编排（意图分类 / 澄清 / 分步执行 / 自检）") {
                    Switch(
                        checked = s.orchestratorEnabled,
                        onCheckedChange = viewModel::updateOrchestratorEnabled
                    )
                }
                Labeled("自动追问澄清信息") {
                    Switch(
                        checked = s.clarificationsAutoAsk,
                        onCheckedChange = viewModel::updateClarificationsAutoAsk
                    )
                }
                Labeled("自检失败自动重试次数：${s.selfCheckMaxRetry}") {
                    Slider(
                        value = s.selfCheckMaxRetry.toFloat(),
                        onValueChange = { viewModel.updateSelfCheckMaxRetry(it.toInt()) },
                        valueRange = 0f..5f,
                        steps = 4
                    )
                }
            }

            SectionCard("LoRA 微调 / 自训练模型（v1.1）") {
                Labeled("自定义 Fine-tune 模型 ID") {
                    OutlinedTextField(
                        value = s.customFineTuneModelId.orEmpty(),
                        onValueChange = viewModel::updateCustomFineTuneModelId,
                        label = { Text("留空则使用上面选的默认模型。填 LoRA 训练完成后返回的 Model ID 即可切换。") },
                        placeholder = { Text("例如 deepseek-v4-flash-ft-xxxxxx") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Labeled("匿名收集对话以合成训练集（仅本地，不会上传）") {
                    Switch(
                        checked = s.fineTuneDataCollectionEnabled,
                        onCheckedChange = viewModel::updateFineTuneDataCollectionEnabled
                    )
                }
            }

            SectionCard("用量统计") {
                Labeled("累计 Token 用量") {
                    Text("${s.cumulativeTokens} tokens", fontWeight = FontWeight.SemiBold)
                }
            }

            Text(
                "DeepCoder v1.1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Labeled(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
