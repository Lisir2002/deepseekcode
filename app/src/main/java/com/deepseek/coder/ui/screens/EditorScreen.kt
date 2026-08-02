package com.deepseek.coder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.ui.editor.EditorViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("代码编辑器", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (ui.fimEnabled) "FIM 补全：已启用" else "FIM 补全：已禁用",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.fimEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = { expanded = true },
                        label = { Text(ui.language.uppercase()) }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        listOf(
                            "kotlin", "java", "python", "javascript", "typescript",
                            "cpp", "c", "rust", "go", "swift", "ruby", "php", "bash"
                        ).forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(lang) },
                                onClick = { viewModel.onLanguageChanged(lang); expanded = false }
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(ui.code)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "复制代码")
                    }
                    IconButton(onClick = viewModel::clear) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            )
        },
        bottomBar = {
            androidx.compose.material3.Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (ui.error != null) {
                        Text(
                            ui.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AssistChip(
                            onClick = viewModel::triggerFim,
                            leadingIcon = {
                                if (ui.fimLoading) {
                                    CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.AutoAwesome, null)
                                }
                            },
                            label = { Text(if (ui.fimLoading) "补全请求中…" else "请求 FIM 补全") },
                            enabled = ui.fimEnabled && !ui.fimLoading
                        )
                        Spacer(Modifier.width(8.dp))
                        if (ui.ghostText.isNotBlank()) {
                            AssistChip(
                                onClick = viewModel::acceptGhost,
                                leadingIcon = { Icon(Icons.Default.Check, null) },
                                label = { Text("接受补全") }
                            )
                            Spacer(Modifier.width(8.dp))
                            AssistChip(
                                onClick = viewModel::discardGhost,
                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                label = { Text("丢弃") }
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${ui.code.length} chars",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (ui.ghostText.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F9FF)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    "FIM 建议补全 (${ui.ghostText.lineSequence().count()} 行)：",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF0369A1)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    ui.ghostText,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    color = Color(0xFF0C4A6E)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
            ) {
                OutlinedTextField(
                    value = ui.code,
                    onValueChange = viewModel::onCodeChanged,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFFE2E8F0),
                        lineHeight = 18.sp
                    ),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF60A5FA)
                    ),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    minLines = 20,
                    maxLines = Int.MAX_VALUE
                )
            }
        }
    }
}
