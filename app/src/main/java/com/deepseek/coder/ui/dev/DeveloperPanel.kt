package com.deepseek.coder.ui.dev

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.coder.BuildConfig
import com.deepseek.coder.data.dev.DiagnosticZipExporter
import com.deepseek.coder.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DeveloperPanelViewModel @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _qualityGates = MutableStateFlow(
        (1..10).associate { "Q$it" to true }
    )
    val qualityGates: StateFlow<Map<String, Boolean>> = _qualityGates.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    fun toggleGate(gate: String) {
        _qualityGates.value = _qualityGates.value.toMutableMap().apply {
            this[gate] = !(this[gate] ?: true)
        }
    }

    fun enableAll(enabled: Boolean) {
        _qualityGates.value = _qualityGates.value.keys.associateWith { enabled }
    }

    fun exportZip(out: Uri) {
        viewModelScope.launch {
            _exportStatus.value = "正在导出..."
            runCatching {
                DiagnosticZipExporter.export(context, out)
            }.onSuccess { count ->
                _exportStatus.value = "导出成功：共写入 $count 个文件到 ${out.lastPathSegment}"
            }.onFailure { t ->
                _exportStatus.value = "导出失败：${t.message}"
            }
        }
    }
}

@Composable
fun DeveloperPanel(
    viewModel: DeveloperPanelViewModel = hiltViewModel()
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val gates by viewModel.qualityGates.collectAsStateWithLifecycle()
    val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportZip(it) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "开发者面板 Developer Panel",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text("版本号 versionName: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("版本号 versionCode: ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodyMedium)
                Text("构建类型: ${BuildConfig.BUILD_TYPE}", style = MaterialTheme.typography.bodyMedium)
                Text("包名 applicationId: ${BuildConfig.APPLICATION_ID}", style = MaterialTheme.typography.bodyMedium)
                Text("Debuggable: ${BuildConfig.DEBUG}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "诊断包导出 Diagnostic Export",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            val ts = System.currentTimeMillis()
                            saveLauncher.launch("deepcoder-diagnostic-$ts.zip")
                        }
                    ) {
                        Text("导出 Zip")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "包含 8 类文件：settings / chat_snapshot / last_plans / app_logs / prompt_audit / fine_tune_bucket / usage_stats / device_info",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (exportStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    val ok = exportStatus?.startsWith("导出成功") == true
                    Text(
                        exportStatus ?: "",
                        color = if (ok) Color(0xFF059669) else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "质检开关 Q1~Q10 Quality Gates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    FilterChip(
                        selected = gates.values.all { it },
                        onClick = { viewModel.enableAll(true) },
                        label = { Text("全选") }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = gates.values.none { it },
                        onClick = { viewModel.enableAll(false) },
                        label = { Text("全不选") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                gates.entries.chunked(2).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        row.forEach { (gate, enabled) ->
                            Row(
                                Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    gate,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(56.dp)
                                )
                                Text(
                                    gateName(gate),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { viewModel.toggleGate(gate) }
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun gateName(g: String): String = when (g) {
    "Q1" -> "JSON Schema 合法"
    "Q2" -> "粒度区间步数"
    "Q3" -> "控制 Token 回显对齐"
    "Q4" -> "Acceptance Criteria 质量"
    "Q5" -> "Duration Pct 求和"
    "Q6" -> "Contrastive 三元组"
    "Q7" -> "粒度转换 Milestone 对齐"
    "Q8" -> "粒度分析评分合理"
    "Q9" -> "拒答 / 失败分类"
    "Q10" -> "双语 Pair 结构对齐"
    else -> ""
}
