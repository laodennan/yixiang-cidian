package com.example.yixiang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab {
    DICT, CATEGORY, REWRITE, FAVORITES
}

// ---------- 复制按钮（点按后短暂显示已复制） ----------

@Composable
fun CopyTextButton(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    TextButton(
        onClick = {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("意象词典", text))
            copied = true
        }
    ) {
        Text(if (copied) "✓已复制" else "复制")
    }
}

// ---------- 词条行 ----------

@Composable
fun EntryRow(entry: LexiconEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = entry.word, fontSize = 18.sp, modifier = Modifier.weight(1f))
        Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

// ---------- 搜索页（含 AI 兜底：离线词库没有的词可调用用户配置的接口生成） ----------

@Composable
fun DictionaryScreen(onEntryClick: (LexiconEntry) -> Unit, onShowSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf("") }

    val trimmed = query.trim()
    val results = Lexicon.search(query)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "意象词典",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("输入意象，如月亮") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (results.isEmpty() && trimmed.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "离线词库里还没有「$trimmed」", fontSize = 17.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "可以让 AI 现场为这个词生成一则词条",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                if (isGenerating) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "正在生成…")
                } else {
                    Button(
                        onClick = {
                            if (!SettingsStore.hasKey()) {
                                aiError = "还没有配置 API Key，请先到设置里填写（「AI 改写」页的设置）"
                                return@Button
                            }
                            aiError = ""
                            isGenerating = true
                            AIClient.generateEntry(trimmed) { result ->
                                isGenerating = false
                                result.fold(
                                    onSuccess = { onEntryClick(it) },
                                    onFailure = { aiError = it.message ?: "生成失败，请重试" }
                                )
                            }
                        }
                    ) {
                        Text("用 AI 生成「$trimmed」的内容")
                    }
                    if (!SettingsStore.hasKey()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onShowSettings) {
                            Text("去设置 API Key")
                        }
                    }
                }
                if (aiError.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = aiError, color = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { entry ->
                    EntryRow(entry) { onEntryClick(entry) }
                }
            }
        }
    }
}

// ---------- 分类页 ----------

@Composable
fun CategoryScreen(onCategoryClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "分类",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(Lexicon.categories) { category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCategoryClick(category) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = category, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text(
                        text = "${Lexicon.entriesIn(category).size} 个",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun CategoryEntryListScreen(category: String, onEntryClick: (LexiconEntry) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(Lexicon.entriesIn(category)) { entry ->
            EntryRow(entry) { onEntryClick(entry) }
        }
    }
}

// ---------- 词条详情页 ----------

@Composable
fun EntryDetailScreen(entry: LexiconEntry) {
    // 订阅收藏变化，点星后立即刷新
    @Suppress("UNUSED_VARIABLE")
    val favoritesVersion = FavoritesStore.version

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.word,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!entry.isGenerated) {
                TextButton(
                    onClick = { FavoritesStore.toggle(entry.word) }
                ) {
                    Text(
                        text = if (FavoritesStore.isFavorite(entry.word)) "★" else "☆",
                        fontSize = 26.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = entry.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (entry.isGenerated) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "由 AI 现场生成，仅供参考；古诗句请以原文核对",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "古诗句", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        for (poem in entry.poems) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = poem.text, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${poem.author} · 《${poem.source}》",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CopyTextButton(text = poem.text)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "生动表达", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        for (line in entry.expressions) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = line, fontSize = 16.sp, modifier = Modifier.weight(1f))
                CopyTextButton(text = line)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- 收藏页 ----------

@Composable
fun FavoritesScreen(onEntryClick: (LexiconEntry) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val favoritesVersion = FavoritesStore.version
    val favorites = FavoritesStore.favoriteEntries()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "收藏",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (favorites.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(text = "还没有收藏", fontSize = 17.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "在词条详情里点亮星星，就会出现在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(favorites) { entry ->
                    EntryRow(entry) { onEntryClick(entry) }
                }
            }
        }
    }
}

// ---------- AI 改写页 ----------

@Composable
fun RewriteScreen(onShowSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 改写",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onShowSettings) {
                Text("设置")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "输入一句话，改写得更生动", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("输入你想改写的句子") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = hint,
            onValueChange = { hint = it },
            placeholder = { Text("意象提示（可选，如月亮）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            enabled = input.trim().isNotEmpty() && !isLoading,
            onClick = {
                val sentence = input.trim()
                if (sentence.isEmpty()) return@Button
                errorMessage = ""
                result = ""
                var system =
                    "你是一位擅长中文文学表达的助手。请把用户给的句子改写得更生动、更有意象，可适度化用古典意象，但不要改变原意。只返回改写后的句子，不要加任何解释。"
                val hintText = hint.trim()
                if (hintText.isNotEmpty()) {
                    system += "可围绕「$hintText」这一意象展开。"
                }
                isLoading = true
                AIClient.chat(system, sentence) { r ->
                    isLoading = false
                    r.fold(
                        onSuccess = { result = it },
                        onFailure = { errorMessage = it.message ?: "请求失败，请检查网络和接口配置" }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "正在改写…" else "改写")
        }
        if (isLoading) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }
        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = errorMessage, color = MaterialTheme.colorScheme.error)
        }
        if (result.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "改写结果", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = result, fontSize = 16.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CopyTextButton(text = result)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- 接口设置页（密钥只存本机） ----------

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var baseUrl by remember { mutableStateOf(SettingsStore.baseUrl) }
    var apiKey by remember { mutableStateOf(SettingsStore.apiKey) }
    var model by remember { mutableStateOf(SettingsStore.model) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "API Key 只保存在本机，不会上传到任何服务器（除你配置的接口外）。默认接口为 OpenAI，也可改为 DeepSeek、通义千问等任何 OpenAI 兼容接口，只需修改 Base URL 和模型名。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    SettingsStore.baseUrl = baseUrl.trim()
                    SettingsStore.apiKey = apiKey.trim()
                    SettingsStore.model = model.trim().ifEmpty { SettingsStore.DEFAULT_MODEL }
                    onDismiss()
                }
            ) {
                Text("完成")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    SettingsStore.resetDefaults()
                    baseUrl = SettingsStore.DEFAULT_BASE_URL
                    model = SettingsStore.DEFAULT_MODEL
                }
            ) {
                Text("恢复默认")
            }
        }
    )
}
