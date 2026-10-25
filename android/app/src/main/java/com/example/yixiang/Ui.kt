package com.example.yixiang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Slider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Tab {
    DICT, DICTIONARY, REWRITE, HISTORY, FAVORITES
}

// ---------- 小工具 ----------

/** 维基百科 / AI 自动搜索的门禁：必须含汉字，且不含 ASCII 字母（纯拼音如 hao、或中英混合如 灏ha 一律不自动查） */
fun isPureChineseForAuto(query: String): Boolean {
    if (query.none { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }) return false
    return query.none { it in 'a'..'z' || it in 'A'..'Z' }
}

fun formatDateTime(millis: Long): String {
    return SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(millis))
}

fun formatTime(millis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(millis))
}

fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("文栖", text))
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
    }
}

// ---------- 复制按钮（点按后短暂显示已复制） ----------

@Composable
fun CopyTextButton(text: String) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    TextButton(
        onClick = {
            copyToClipboard(context, text)
            copied = true
        }
    ) {
        Text(if (copied) "✓已复制" else "复制")
    }
}

// ---------- 句子收藏按钮（点亮即收藏、再点即取消） ----------

@Composable
fun SentenceStarButton(text: String, source: String) {
    @Suppress("UNUSED_VARIABLE")
    val version = SentenceFavoritesStore.version
    TextButton(
        onClick = { SentenceFavoritesStore.toggle(text, source) }
    ) {
        Text(
            text = if (SentenceFavoritesStore.isFavorite(text)) "★" else "☆",
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ---------- 加入词典按钮（把 AI 结果存进“我的词典”，点按后短暂显示对勾） ----------

@Composable
fun AddToLexiconButton(text: String, source: String) {
    @Suppress("UNUSED_VARIABLE")
    val version = CustomLexiconStore.version
    var added by remember { mutableStateOf(false) }
    if (added) {
        LaunchedEffect(Unit) {
            delay(1500)
            added = false
        }
    }
    val already = CustomLexiconStore.isSentenceAdded(text)
    TextButton(
        onClick = {
            CustomLexiconStore.addSentence(text, source)
            added = true
        }
    ) {
        Text(if (added || already) "✓" else "+")
    }
}

// ---------- 词条行 ----------

@Composable
fun EntryRow(entry: LexiconEntry, showMine: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = entry.word, fontSize = 18.sp, modifier = Modifier.weight(1f))
        if (showMine) {
            Text(
                text = "我的",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

// ---------- 词典页（含字典联查、维基百科自动搜索、AI 自动生成） ----------

@Composable
fun DictionaryScreen(
    onEntryClick: (LexiconEntry) -> Unit,
    onDictDetailClick: (DictDetailRef) -> Unit,
    onShowSettings: () -> Unit,
    onShowMyWords: () -> Unit,
    onShowMySentences: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val customVersion = CustomLexiconStore.version

    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 字典联查：防抖 + 后台 + 每类 50 条即停
    var debouncedDictQuery by remember { mutableStateOf("") }
    var dictMatches by remember { mutableStateOf(DictResults()) }
    LaunchedEffect(query) {
        delay(300)
        debouncedDictQuery = query
    }
    LaunchedEffect(debouncedDictQuery, DictionaryStore.loaded) {
        if (debouncedDictQuery.trim().isEmpty()) {
            dictMatches = DictResults()
            return@LaunchedEffect
        }
        if (!DictionaryStore.loaded) return@LaunchedEffect
        val q = debouncedDictQuery
        val start = System.currentTimeMillis()
        val found = withContext(Dispatchers.Default) {
            DictionaryStore.search(q, DictScope.ALL)
        }
        if (q != debouncedDictQuery) return@LaunchedEffect
        dictMatches = found
        val ms = System.currentTimeMillis() - start
        val tag = if (ms > 300) "慢搜索" else "联查"
        AppLog.log(
            tag,
            "词典联查「$q」耗时 ${ms}ms，成语 ${found.idioms.size}/词语 ${found.words.size}/汉字 ${found.chars.size}/歇后语 ${found.xiehouyu.size}"
        )
    }

    // 维基百科 + AI 自动搜索：停稳 800ms 后并行跑，只对纯中文词触发
    var webSummary by remember { mutableStateOf<WebSummary?>(null) }
    var webIsCached by remember { mutableStateOf(false) }
    var webLoading by remember { mutableStateOf(false) }
    var webError by remember { mutableStateOf("") }
    var aiEntry by remember { mutableStateOf<LexiconEntry?>(null) }
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf("") }
    var autoGen by remember { mutableStateOf(0) }

    val trimmed = query.trim()
    val baseResults = Lexicon.search(query)
    val results = if (trimmed.isEmpty()) {
        baseResults
    } else {
        val mine = CustomLexiconStore.words().filter { it.word.contains(trimmed) && !baseResults.contains(it) }
        baseResults + mine
    }

    LaunchedEffect(trimmed) {
        webSummary = null
        webIsCached = false
        webLoading = false
        webError = ""
        aiEntry = null
        aiLoading = false
        aiError = ""
        if (trimmed.isEmpty()) return@LaunchedEffect
        delay(800)
        // 门禁：含 ASCII 字母的查询（拼音半成品）不自动查联网和 AI
        if (!isPureChineseForAuto(trimmed)) return@LaunchedEffect
        autoGen += 1
        val gen = autoGen
        val word = trimmed
        // 维基百科：先查本机缓存，有缓存直接显示
        val cached = WebSearchCache.cached(word)
        if (cached != null) {
            webSummary = cached
            webIsCached = true
        } else {
            webLoading = true
            val webStart = System.currentTimeMillis()
            WebSearch.fetchSummary(word) { result ->
                // 被新查询取代的请求不更新界面、不记成失败
                if (gen != autoGen) return@fetchSummary
                webLoading = false
                result.fold(
                    onSuccess = { summary ->
                        WebSearchCache.save(summary)
                        webSummary = summary
                        webIsCached = false
                        HistoryStore.addWebSearch(word, summary.extract)
                        AppLog.log("联网", "维基百科「$word」联网获取成功，耗时 ${System.currentTimeMillis() - webStart}ms")
                    },
                    onFailure = { e ->
                        if (e is WebSearchNotFoundException) {
                            webError = "维基百科上没有「$word」的词条"
                            AppLog.log("联网", "维基百科「$word」无词条")
                        } else {
                            webError = "联网搜索失败，请检查网络后重试"
                            AppLog.log("联网失败", "维基百科「$word」${e.message ?: "未知错误"}")
                        }
                    }
                )
            }
        }
        // AI 自动生成：没配 Key 时只显示去设置提示，不调用接口
        if (SettingsStore.hasKey()) {
            aiLoading = true
            val aiStart = System.currentTimeMillis()
            AIClient.generateEntry(word) { result ->
                if (gen != autoGen) return@generateEntry
                aiLoading = false
                result.fold(
                    onSuccess = { entry ->
                        HistoryStore.addGenerated(entry.word, entry.poems, entry.expressions)
                        aiEntry = entry
                        AppLog.log("AI生成", "「$word」生成成功，耗时 ${System.currentTimeMillis() - aiStart}ms")
                    },
                    onFailure = { e ->
                        aiError = e.message ?: "生成失败，请重试"
                        AppLog.log("AI生成失败", "「$word」${e.message ?: "生成失败"}")
                    }
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        DictionaryStore.load(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "意象词典",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onShowSettings) {
                Text("设置")
            }
        }
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
        if (trimmed.isEmpty()) {
            // 首页：我的词条 / 我的词句入口 + 12 个分类折叠（默认收起）
            val myWords = CustomLexiconStore.words()
            val mySentences = CustomLexiconStore.sentences()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                if (myWords.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowMyWords() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "我的词条", fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "${myWords.size} 个",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
                if (mySentences.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowMySentences() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "我的词句", fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "${mySentences.size} 条",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
                for (category in Lexicon.categories) {
                    val isExpanded = expanded.contains(category)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expanded = if (isExpanded) expanded - category else expanded + category
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = category, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(
                            text = "${Lexicon.entriesIn(category).size} 个",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text(
                            text = if (isExpanded) "∧" else "›",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                    if (isExpanded) {
                        for (entry in Lexicon.entriesIn(category)) {
                            EntryRow(entry, showMine = CustomLexiconStore.isWordAdded(entry.word)) {
                                onEntryClick(entry)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else if (results.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(results) { entry ->
                    EntryRow(entry, showMine = CustomLexiconStore.isWordAdded(entry.word)) {
                        onEntryClick(entry)
                    }
                }
            }
        } else {
            // 空结果页：字典联查 + 维基百科自动搜索 + AI 自动生成
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(text = "意象词库里还没有「$trimmed」", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                // 字典库联查（离线）
                if (!DictionaryStore.loaded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(
                            text = "正在加载离线字典…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else if (!dictMatches.isEmpty()) {
                    Text(text = "字典里找到了", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (dictMatches.idioms.isNotEmpty()) {
                        Text(text = "成语", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (item in dictMatches.idioms.take(5)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDictDetailClick(DictDetailRef(DictScope.IDIOMS, item.word))
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Column {
                                    Text(text = item.word, fontSize = 16.sp)
                                    Text(
                                        text = item.explanation,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (dictMatches.words.isNotEmpty()) {
                        Text(text = "词语", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (item in dictMatches.words.take(5)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDictDetailClick(DictDetailRef(DictScope.WORDS, item.word))
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Column {
                                    Text(text = item.word, fontSize = 16.sp)
                                    Text(
                                        text = item.explanation,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (dictMatches.chars.isNotEmpty()) {
                        Text(text = "汉字", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (item in dictMatches.chars.take(3)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDictDetailClick(DictDetailRef(DictScope.CHARS, item.word))
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Column {
                                    Text(text = item.word, fontSize = 16.sp)
                                    Text(
                                        text = item.explanation,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (dictMatches.xiehouyu.isNotEmpty()) {
                        Text(text = "歇后语", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (item in dictMatches.xiehouyu.take(3)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDictDetailClick(DictDetailRef(DictScope.XIEHOUYU, item.riddle))
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Column {
                                    Text(text = item.riddle, fontSize = 16.sp)
                                    Text(
                                        text = item.answer,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 维基百科（自动显示，带来源小备注）
                Text(text = "维基百科", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "以下为维基百科的公开词条摘要，仅供参考",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val summary = webSummary
                if (summary != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = summary.title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = summary.extract, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (webIsCached) {
                                    Text(
                                        text = "本地缓存 · ${formatDateTime(summary.fetchedAt)}",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                AddToLexiconButton(text = summary.extract, source = "联网搜索")
                                CopyTextButton(text = summary.extract)
                            }
                            TextButton(onClick = { openUrl(context, summary.pageUrl) }) {
                                Text("来源：维基百科")
                            }
                            TextButton(
                                onClick = {
                                    webSummary = null
                                    webError = ""
                                    webLoading = true
                                    val refreshGen = autoGen
                                    val webStart = System.currentTimeMillis()
                                    WebSearch.fetchSummary(trimmed) { result ->
                                        if (refreshGen != autoGen) return@fetchSummary
                                        webLoading = false
                                        result.fold(
                                            onSuccess = { fresh ->
                                                WebSearchCache.save(fresh)
                                                webSummary = fresh
                                                webIsCached = false
                                                HistoryStore.addWebSearch(trimmed, fresh.extract)
                                                AppLog.log("联网", "维基百科「$trimmed」联网获取成功，耗时 ${System.currentTimeMillis() - webStart}ms")
                                            },
                                            onFailure = { e ->
                                                if (e is WebSearchNotFoundException) {
                                                    webError = "维基百科上没有「$trimmed」的词条"
                                                } else {
                                                    webError = "联网搜索失败，请检查网络后重试"
                                                }
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("重新获取")
                            }
                        }
                    }
                } else if (webLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(text = "正在维基百科搜索…")
                    }
                } else if (webError.isNotEmpty()) {
                    Text(text = webError, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            val encoded = try {
                                java.net.URLEncoder.encode(trimmed, "UTF-8")
                            } catch (e: Exception) {
                                trimmed
                            }
                            openUrl(context, "https://www.baidu.com/s?wd=$encoded")
                        }
                    ) {
                        Text("用浏览器搜索「$trimmed」")
                    }
                } else if (!isPureChineseForAuto(trimmed)) {
                    Text(
                        text = "正在输入拼音，联网搜索与 AI 生成将在输入完整中文词后自动开始",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(text = "正在搜索…")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // AI 生成（自动显示，带来源小备注）
                Text(text = "AI 生成", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "由 AI 现场生成，仅供参考；古诗句请以原文核对",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val generated = aiEntry
                if (generated != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = generated.word, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = generated.category,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = "古诗句", fontWeight = FontWeight.Bold)
                            for (poem in generated.poems) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = poem.text, fontSize = 16.sp)
                                        Text(
                                            text = "${poem.author} · 《${poem.source}》",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    SentenceStarButton(text = poem.text, source = "AI 生成")
                                    CopyTextButton(text = poem.text)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "生动表达", fontWeight = FontWeight.Bold)
                            for (line in generated.expressions) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = line,
                                        fontSize = 16.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SentenceStarButton(text = line, source = "AI 生成")
                                    CopyTextButton(text = line)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                enabled = !CustomLexiconStore.isWordAdded(generated.word),
                                onClick = { CustomLexiconStore.addWord(generated) }
                            ) {
                                Text(
                                    if (CustomLexiconStore.isWordAdded(generated.word)) "已加入我的词典" else "加入我的词典"
                                )
                            }
                        }
                    }
                } else if (aiLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(text = "AI 正在生成…")
                    }
                } else if (aiError.isNotEmpty()) {
                    Text(text = aiError, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            aiError = ""
                            aiLoading = true
                            val retryGen = autoGen
                            val aiStart = System.currentTimeMillis()
                            AIClient.generateEntry(trimmed) { result ->
                                if (retryGen != autoGen) return@generateEntry
                                aiLoading = false
                                result.fold(
                                    onSuccess = { entry ->
                                        HistoryStore.addGenerated(entry.word, entry.poems, entry.expressions)
                                        aiEntry = entry
                                        AppLog.log("AI生成", "「$trimmed」生成成功，耗时 ${System.currentTimeMillis() - aiStart}ms")
                                    },
                                    onFailure = { e ->
                                        aiError = e.message ?: "生成失败，请重试"
                                        AppLog.log("AI生成失败", "「$trimmed」${e.message ?: "生成失败"}")
                                    }
                                )
                            }
                        }
                    ) {
                        Text("重新生成")
                    }
                } else if (!SettingsStore.hasKey()) {
                    Text(
                        text = "AI 生成需要配置 API Key",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(onClick = onShowSettings) {
                        Text("去设置 API Key")
                    }
                } else if (!isPureChineseForAuto(trimmed)) {
                    Text(
                        text = "正在输入拼音，联网搜索与 AI 生成将在输入完整中文词后自动开始",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.padding(8.dp))
                        Text(text = "正在准备…")
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ---------- 字典页：离线汉字词典（查字 / 词语 / 成语 / 歇后语，断网可用） ----------

@Composable
fun DictionaryTabScreen(onDictDetailClick: (DictDetailRef) -> Unit) {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val dictHistoryVersion = DictHistoryStore.version

    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(DictScope.ALL) }
    var debouncedQuery by remember { mutableStateOf("") }
    var asyncResults by remember { mutableStateOf(DictResults()) }

    val trimmed = query.trim()
    val settled = debouncedQuery.trim() == trimmed

    LaunchedEffect(Unit) {
        DictionaryStore.load(context)
    }
    LaunchedEffect(query) {
        // 打字防抖：停稳 300ms 后再搜，避免每敲一字扫全库
        val q = query
        delay(300)
        debouncedQuery = q
    }
    LaunchedEffect(debouncedQuery, scope, DictionaryStore.loaded) {
        // 搜索放后台线程跑，主线程只负责显示结果
        if (debouncedQuery.trim().isEmpty()) {
            asyncResults = DictResults()
            return@LaunchedEffect
        }
        if (!DictionaryStore.loaded) return@LaunchedEffect
        val q = debouncedQuery
        val currentScope = scope
        val start = System.currentTimeMillis()
        val found = withContext(Dispatchers.Default) {
            DictionaryStore.search(q, currentScope)
        }
        // 旧结果不覆盖新查询
        if (q != debouncedQuery || currentScope != scope) return@LaunchedEffect
        asyncResults = found
        val ms = System.currentTimeMillis() - start
        val tag = if (ms > 300) "慢搜索" else "搜索"
        AppLog.log(
            tag,
            "字典「$q」${currentScope.label} 耗时 ${ms}ms，汉字 ${found.chars.size}/词语 ${found.words.size}/成语 ${found.idioms.size}/歇后语 ${found.xiehouyu.size}"
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "字典",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("输入汉字、词语、成语或拼音") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 范围选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            for (item in DictScope.values()) {
                TextButton(onClick = { scope = item }) {
                    Text(
                        text = item.label,
                        color = if (scope == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (scope == item) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (!DictionaryStore.loaded) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "正在加载离线字典…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (trimmed.isEmpty()) {
            // 空状态：说明 + 最近查过
            val historyItems = DictHistoryStore.items()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "离线汉字词典", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "输入汉字、词语、成语，或拼音，如：月亮 / yueliang / 风花雪月。数据已内置在 App 里，断网也能查。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (historyItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最近查过",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { DictHistoryStore.clear() }) {
                                Text("清除")
                            }
                        }
                        for (item in historyItems) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope = DictScope.fromLabel(item.scope)
                                        query = item.text
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = item.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                Text(
                                    text = item.scope,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else if (asyncResults.isEmpty() && settled) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(text = "没有找到「$trimmed」", fontSize = 17.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "换个写法试试，或到「AI 改写」页让 AI 帮你展开",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (asyncResults.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "正在搜索…")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (asyncResults.chars.isNotEmpty()) {
                    item {
                        Text(
                            text = "汉字（${asyncResults.chars.size}）",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(asyncResults.chars) { item ->
                        DictRow(
                            title = item.word,
                            subtitle = item.pinyin,
                            preview = item.explanation
                        ) {
                            onDictDetailClick(DictDetailRef(DictScope.CHARS, item.word))
                        }
                    }
                }
                if (asyncResults.words.isNotEmpty()) {
                    item {
                        Text(
                            text = "词语（${asyncResults.words.size}）",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(asyncResults.words) { item ->
                        DictRow(
                            title = item.word,
                            subtitle = item.pinyin,
                            preview = item.explanation
                        ) {
                            onDictDetailClick(DictDetailRef(DictScope.WORDS, item.word))
                        }
                    }
                }
                if (asyncResults.idioms.isNotEmpty()) {
                    item {
                        Text(
                            text = "成语（${asyncResults.idioms.size}）",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(asyncResults.idioms) { item ->
                        DictRow(
                            title = item.word,
                            subtitle = item.pinyin,
                            preview = item.explanation
                        ) {
                            onDictDetailClick(DictDetailRef(DictScope.IDIOMS, item.word))
                        }
                    }
                }
                if (asyncResults.xiehouyu.isNotEmpty()) {
                    item {
                        Text(
                            text = "歇后语（${asyncResults.xiehouyu.size}）",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(asyncResults.xiehouyu) { item ->
                        DictRow(
                            title = item.riddle,
                            subtitle = "歇后语",
                            preview = item.answer
                        ) {
                            onDictDetailClick(DictDetailRef(DictScope.XIEHOUYU, item.riddle))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DictRow(title: String, subtitle: String, preview: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(text = "›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

// ---------- 字典详情页（点开即记入“最近查过”） ----------

@Composable
fun DictDetailScreen(ref: DictDetailRef) {
    LaunchedEffect(Unit) {
        DictHistoryStore.record(ref.scope.label, ref.key)
    }
    when (ref.scope) {
        DictScope.CHARS -> {
            val item = DictionaryStore.findChar(ref.key)
            if (item != null) {
                DictCharDetail(item)
            } else {
                DetailNotFound()
            }
        }
        DictScope.WORDS -> {
            val item = DictionaryStore.findWord(ref.key)
            if (item != null) {
                DictWordDetail(item)
            } else {
                DetailNotFound()
            }
        }
        DictScope.IDIOMS -> {
            val item = DictionaryStore.findIdiom(ref.key)
            if (item != null) {
                DictIdiomDetail(item)
            } else {
                DetailNotFound()
            }
        }
        DictScope.XIEHOUYU -> {
            val item = DictionaryStore.findXiehouyu(ref.key)
            if (item != null) {
                DictXiehouyuDetail(item)
            } else {
                DetailNotFound()
            }
        }
        DictScope.ALL -> {
            DetailNotFound()
        }
    }
}

@Composable
fun DetailNotFound() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = "没有找到该词条")
    }
}

@Composable
fun DictCharDetail(item: DictChar) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.word,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            SentenceStarButton(text = item.explanation, source = "字典·${item.word}")
            CopyTextButton(text = item.explanation)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (item.pinyin.isNotEmpty()) {
            Text(text = "拼音：${item.pinyin}")
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row {
            if (item.radicals.isNotEmpty()) {
                Text(
                    text = "部首：${item.radicals}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            if (item.strokes.isNotEmpty()) {
                Text(
                    text = "笔画：${item.strokes}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            if (item.oldword.isNotEmpty() && item.oldword != item.word) {
                Text(
                    text = "繁体：${item.oldword}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = item.explanation, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DictWordDetail(item: DictWord) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.word,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            SentenceStarButton(text = item.explanation, source = "字典·${item.word}")
            CopyTextButton(text = item.explanation)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (item.pinyin.isNotEmpty()) {
            Text(
                text = "拼音：${item.pinyin}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(text = item.explanation, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DictIdiomDetail(item: DictIdiom) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.word,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            SentenceStarButton(text = item.explanation, source = "字典·${item.word}")
            CopyTextButton(text = item.explanation)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (item.pinyin.isNotEmpty()) {
            Text(
                text = "拼音：${item.pinyin}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text(text = item.explanation, fontSize = 16.sp)
        if (item.derivation.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "出处", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = item.derivation, fontSize = 16.sp)
        }
        if (item.example.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "例句", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = item.example, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DictXiehouyuDetail(item: DictXiehouyu) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.riddle,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            SentenceStarButton(text = "${item.riddle}——${item.answer}", source = "字典·歇后语")
            CopyTextButton(text = "${item.riddle}——${item.answer}")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "谜底", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = item.answer, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- 词条详情页（每句可星标收藏；AI 生成词条可整条加入我的词典） ----------

@Composable
fun EntryDetailScreen(entry: LexiconEntry) {
    @Suppress("UNUSED_VARIABLE")
    val favoritesVersion = FavoritesStore.version
    @Suppress("UNUSED_VARIABLE")
    val customVersion = CustomLexiconStore.version

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
            if (!entry.isGenerated && entry.category != "我的") {
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
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                enabled = !CustomLexiconStore.isWordAdded(entry.word),
                onClick = { CustomLexiconStore.addWord(entry) }
            ) {
                Text(
                    if (CustomLexiconStore.isWordAdded(entry.word)) "已加入我的词典" else "加入我的词典"
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "古诗句", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        val poemSource = if (entry.isGenerated) "AI 生成" else "出自「${entry.word}」· 古诗句"
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
                SentenceStarButton(text = poem.text, source = poemSource)
                CopyTextButton(text = poem.text)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "生动表达", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        val expressionSource = if (entry.isGenerated) "AI 生成" else "出自「${entry.word}」· 生动表达"
        for (line in entry.expressions) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = line, fontSize = 16.sp, modifier = Modifier.weight(1f))
                SentenceStarButton(text = line, source = expressionSource)
                CopyTextButton(text = line)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- 收藏页（词条 / 句子两栏） ----------

@Composable
fun FavoritesScreen(onEntryClick: (LexiconEntry) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val favoritesVersion = FavoritesStore.version
    @Suppress("UNUSED_VARIABLE")
    val sentenceVersion = SentenceFavoritesStore.version

    var showSentences by remember { mutableStateOf(false) }
    val favorites = FavoritesStore.favoriteEntries()
    val sentences = SentenceFavoritesStore.sentences()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "收藏",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = { showSentences = false }) {
                Text(
                    text = "词条",
                    fontWeight = if (!showSentences) FontWeight.Bold else FontWeight.Normal,
                    color = if (!showSentences) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showSentences = true }) {
                Text(
                    text = "句子",
                    fontWeight = if (showSentences) FontWeight.Bold else FontWeight.Normal,
                    color = if (showSentences) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (!showSentences) {
            if (favorites.isEmpty()) {
                EmptyHint("还没有收藏", "在词条详情里点亮星星，就会出现在这里")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(favorites) { entry ->
                        EntryRow(entry) { onEntryClick(entry) }
                    }
                }
            }
        } else {
            if (sentences.isEmpty()) {
                EmptyHint("还没有收藏的句子", "在任意句子旁点亮星星，就会出现在这里")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sentences) { favorite ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = favorite.text, fontSize = 16.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = favorite.source,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SentenceStarButton(text = favorite.text, source = favorite.source)
                            CopyTextButton(text = favorite.text)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Text(text = title, fontSize = 17.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- 我的词条 / 我的词句 ----------

@Composable
fun MyWordsScreen(onEntryClick: (LexiconEntry) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val customVersion = CustomLexiconStore.version
    val words = CustomLexiconStore.words()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "我的词条",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (words.isEmpty()) {
            EmptyHint("还没有加入的词条", "在 AI 生成的词条页点“加入我的词典”，就会出现在这里")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(words) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEntryClick(entry) }
                        ) {
                            Text(text = entry.word, fontSize = 18.sp)
                        }
                        TextButton(onClick = { CustomLexiconStore.removeWord(entry.word) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun MySentencesScreen() {
    val context = LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val customVersion = CustomLexiconStore.version
    val sentences = CustomLexiconStore.sentences()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "我的词句",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (sentences.isEmpty()) {
            EmptyHint("还没有加入的词句", "在 AI 改写结果或历史记录旁点加号，就会保存到这里")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sentences) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.text, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${item.source} · ${formatDateTime(item.date)}",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        CopyTextButton(text = item.text)
                        TextButton(onClick = { CustomLexiconStore.removeSentence(item.id) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

// ---------- 历史页：AI 改写、AI 生成词条、联网搜索自动留档 ----------

@Composable
fun HistoryScreen(onHistoryClick: (HistoryEntry) -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val historyVersion = HistoryStore.version
    val entries = HistoryStore.entries()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "历史",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("清空")
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyHint("还没有历史记录", "AI 改写、AI 生成的词条和联网搜索都会自动出现在这里")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHistoryClick(entry) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = entry.input,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = entry.kind,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.results.firstOrNull() ?: "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatDateTime(entry.date),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部历史？") },
            text = { Text("清空后不可恢复，收藏的内容不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        HistoryStore.clear()
                        showClearConfirm = false
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun HistoryDetailScreen(entry: HistoryEntry) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = entry.kind,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = formatDateTime(entry.date),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = entry.input, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = entry.detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "结果", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        for (line in entry.results) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = line, fontSize = 16.sp, modifier = Modifier.weight(1f))
                AddToLexiconButton(text = line, source = "历史·${entry.input}")
                SentenceStarButton(text = line, source = "历史·${entry.input}")
                CopyTextButton(text = line)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { copyToClipboard(context, entry.results.joinToString("\n")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("复制全部结果")
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- AI 改写页：口语进、美文出（风格三档、单句/成段） ----------

enum class RewriteStyle(val label: String, val styleSentence: String) {
    CLASSICAL(
        "古风",
        "风格要求：古风——表达方式用典雅的文言词汇与古典意象，句式错落有致，如古文小品。"
    ),
    MODERN(
        "现代抒情",
        "风格要求：现代抒情——表达方式用柔和的现代散文语言，抒情但不失具体细节。"
    ),
    PLAIN(
        "白描细腻",
        "风格要求：白描细腻——表达方式克制平实，少堆砌形容词，用具体动作和细节说话。"
    )
}

enum class RewriteMode(val label: String) {
    SINGLE("单句"),
    PARAGRAPH("成段")
}

@Composable
fun RewriteScreen(onShowSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf(RewriteStyle.MODERN) }
    var mode by remember { mutableStateOf(RewriteMode.SINGLE) }
    var targetWords by remember { mutableStateOf(300f) }
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }
    var paragraphText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    fun doRewrite() {
        val sentence = input.trim()
        if (sentence.isEmpty()) return
        errorMessage = ""
        versions = emptyList()
        paragraphText = ""
        // 核心任务：口语描述 → 文学形容。输入是通俗口语，输出必须精准、具体、有意境、优美。
        var system = "你是一位擅长中文文学表达的助手。用户输入的是通俗口语——他用平时说话的方式描述一个意思，不需要先想好文绉绉的句子。你的任务是把这段口语描述翻译成精准、具体、有意境、优美的文学形容：精准指贴合原意、不跑题；具体指必须写出可感知的细节，如神情、动作、环境、五感、恰当的比喻，必要时化用古典意象；有意境、优美指读起来像一句值得写进文章里的好句子。禁止只做同义词替换，禁止空洞抒情；意思不能改变，只能丰富细节。"
        system += selectedStyle.styleSentence
        val hintText = hint.trim()
        if (hintText.isNotEmpty()) {
            system += "可围绕「$hintText」这一意象展开。"
        }
        isLoading = true
        if (mode == RewriteMode.PARAGRAPH) {
            system += "请写成一段连贯的长文，可分 2–4 个自然段，约 ${targetWords.toInt()} 字，起承转合，前后承接，可直接用在文章里。只返回正文，不要任何解释，也不要加代码围栏。"
            AIClient.chat(system, sentence) { r ->
                isLoading = false
                r.fold(
                    onSuccess = { text ->
                        paragraphText = text
                        HistoryStore.addRewrite(
                            input = sentence,
                            detail = "${selectedStyle.label}·成段·约 ${targetWords.toInt()} 字",
                            results = listOf(text)
                        )
                    },
                    onFailure = { errorMessage = it.message ?: "请求失败，请检查网络和接口配置" }
                )
            }
        } else {
            system += "请恰好返回 3 个不同版本，以 JSON 字符串数组的形式输出，例如 [\"版本一\", \"版本二\", \"版本三\"]，不要加任何解释，也不要加代码围栏。"
            AIClient.chat(system, sentence) { r ->
                isLoading = false
                r.fold(
                    onSuccess = { raw ->
                        val parsed = AIClient.parseVariants(raw)
                        if (parsed.isEmpty()) {
                            errorMessage = "接口返回了空结果，请重试"
                        } else {
                            versions = parsed
                            HistoryStore.addRewrite(
                                input = sentence,
                                detail = "${selectedStyle.label}·单句·3 个版本",
                                results = parsed
                            )
                        }
                    },
                    onFailure = { errorMessage = it.message ?: "请求失败，请检查网络和接口配置" }
                )
            }
        }
    }

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
        Text(text = "用大白话描述，得到优美的形容", fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("用平时说话的方式描述，比如：他这个人冷冷的，不爱搭理人") },
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
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (style in RewriteStyle.values()) {
                TextButton(onClick = { selectedStyle = style }) {
                    Text(
                        text = style.label,
                        fontWeight = if (selectedStyle == style) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedStyle == style) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (item in RewriteMode.values()) {
                TextButton(onClick = { mode = item }) {
                    Text(
                        text = item.label,
                        fontWeight = if (mode == item) FontWeight.Bold else FontWeight.Normal,
                        color = if (mode == item) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (mode == RewriteMode.PARAGRAPH) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = "目标字数", modifier = Modifier.weight(1f))
                Text(
                    text = "约 ${targetWords.toInt()} 字",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = targetWords,
                onValueChange = { targetWords = it },
                valueRange = 100f..1000f,
                steps = 17,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "左右拖动，100–1000 字，长文分段写、保持连贯",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                enabled = input.trim().isNotEmpty() && !isLoading,
                onClick = { doRewrite() },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (isLoading) "正在改写…" else "改写")
            }
            Spacer(modifier = Modifier.padding(6.dp))
            Button(
                enabled = input.trim().isNotEmpty() && !isLoading &&
                    (if (mode == RewriteMode.PARAGRAPH) paragraphText.isNotEmpty() else versions.isNotEmpty()),
                onClick = { doRewrite() }
            ) {
                Text(if (mode == RewriteMode.PARAGRAPH) "换一段" else "换一批")
            }
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
        if (mode == RewriteMode.SINGLE && versions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "形容结果", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            for (version in versions) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = version, fontSize = 16.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            AddToLexiconButton(text = version, source = "AI 改写")
                            SentenceStarButton(text = version, source = "AI 改写")
                            CopyTextButton(text = version)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        if (mode == RewriteMode.PARAGRAPH && paragraphText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "成段结果（约 ${paragraphText.length} 字）",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = paragraphText, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AddToLexiconButton(text = paragraphText, source = "AI 改写·成段")
                        SentenceStarButton(text = paragraphText, source = "AI 改写·成段")
                        CopyTextButton(text = paragraphText)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------- 接口设置（密钥只存本机；默认接口为通义千问） ----------

@Composable
fun SettingsDialog(onDismiss: () -> Unit, onShowLog: () -> Unit) {
    var baseUrl by remember { mutableStateOf(SettingsStore.baseUrl) }
    var apiKey by remember { mutableStateOf(SettingsStore.apiKey) }
    var model by remember { mutableStateOf(SettingsStore.model) }
    var cacheCount by remember { mutableStateOf(WebSearchCache.count()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
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
                    text = "默认接口为通义千问（阿里云百炼），只需填写自己的 API Key；也可改为 OpenAI、DeepSeek 等任何 OpenAI 兼容接口。API Key 只保存在本机，不会上传到任何服务器（除你配置的接口外）。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "联网搜索缓存：$cacheCount 条",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        enabled = cacheCount > 0,
                        onClick = {
                            WebSearchCache.clear()
                            cacheCount = 0
                        }
                    ) {
                        Text("清除缓存")
                    }
                }
                TextButton(
                    onClick = {
                        onShowLog()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("查看日志")
                }
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

// ---------- 日志查看页（本机日志，供排查卡顿与失败） ----------

@Composable
fun LogScreen() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(AppLog.entries()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val logText = entries.joinToString("\n") { "${formatTime(it.date)} [${it.tag}] ${it.message}" }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "日志",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (entries.isNotEmpty()) {
                TextButton(
                    onClick = { copyToClipboard(context, logText) }
                ) {
                    Text("复制全部")
                }
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("清空")
                }
            }
        }
        if (entries.isEmpty()) {
            EmptyHint("还没有日志", "搜索、联网、AI 生成的耗时与失败都会记在这里")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    val isAlert = entry.tag.contains("失败") || entry.tag.contains("慢")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = formatTime(entry.date),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = entry.tag,
                                fontSize = 13.sp,
                                color = if (isAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = entry.message, fontSize = 15.sp)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空全部日志？") },
            text = { Text("清空后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        AppLog.clear()
                        entries = emptyList()
                        showClearConfirm = false
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}
