package com.deepseek.coder.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepseek.coder.ui.setup.SetupViewModel

@Composable
fun SetupScreen(
    onNavigateToChat: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var visible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("配置 DeepSeek API Key") }) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "使用 DeepCoder 前请先配置 API Key。",
                    style = MaterialTheme.typography.bodyLarge
                )

                OutlinedTextField(
                    value = ui.rawKey,
                    onValueChange = viewModel::onRawKeyChanged,
                    label = { Text("API Key（sk- 开头）") },
                    singleLine = true,
                    enabled = !ui.saving,
                    isError = ui.validationError != null,
                    supportingText = {
                        Text(
                            text = ui.validationError ?: "格式: sk-xxxxxxxxxxxxxxxx",
                            color = if (ui.validationError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "隐藏" else "显示"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveAndContinue(onNavigateToChat) },
                        enabled = !ui.saving && ui.rawKey.isNotBlank() && ui.validationError == null
                    ) {
                        if (ui.saving) {
                            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (ui.saved) "已保存，继续" else "保存并进入")
                    }

                    OutlinedButton(onClick = viewModel::clearKey, enabled = ui.saved && !ui.saving) {
                        Text("清除")
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 8.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("如何获取 API Key", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "1. 访问 https://platform.deepseek.com/ 并登录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "2. 进入「API Keys」创建新 Key",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "3. 密钥仅显示一次，请妥善保存；在此应用中使用 AndroidX EncryptedSharedPreferences 加密存储。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (ui.keyTail != null) {
                            Text(
                                "当前已保存 Key: sk-****${ui.keyTail}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF059669)
                            )
                        }
                    }
                }
            }
        }
    }
}
