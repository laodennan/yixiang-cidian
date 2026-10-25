package com.example.yixiang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lexicon.load(applicationContext)
        FavoritesStore.init(applicationContext)
        SentenceFavoritesStore.init(applicationContext)
        CustomLexiconStore.init(applicationContext)
        HistoryStore.init(applicationContext)
        DictHistoryStore.init(applicationContext)
        WebSearchCache.init(applicationContext)
        SynonymCache.init(applicationContext)
        MaterialStore.init(applicationContext)
        AppLog.init(applicationContext)
        SettingsStore.init(applicationContext)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

@Composable
fun App() {
    var tab by remember { mutableStateOf(Tab.DICT) }
    var detail by remember { mutableStateOf<LexiconEntry?>(null) }
    var dictDetail by remember { mutableStateOf<DictDetailRef?>(null) }
    var historyDetail by remember { mutableStateOf<HistoryEntry?>(null) }
    var showMyWords by remember { mutableStateOf(false) }
    var showMySentences by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showSceneList by remember { mutableStateOf(false) }
    var sceneDetail by remember { mutableStateOf<SceneEntry?>(null) }

    val inSubScreen = detail != null || dictDetail != null || historyDetail != null ||
        showMyWords || showMySentences || showLog || showSceneList || sceneDetail != null

    BackHandler(enabled = detail != null) {
        detail = null
    }
    BackHandler(enabled = detail == null && dictDetail != null) {
        dictDetail = null
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail != null) {
        historyDetail = null
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail == null && showMyWords) {
        showMyWords = false
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail == null && !showMyWords && showMySentences) {
        showMySentences = false
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail == null && !showMyWords && !showMySentences && showLog) {
        showLog = false
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail == null && !showMyWords && !showMySentences && !showLog && sceneDetail != null) {
        sceneDetail = null
    }
    BackHandler(enabled = detail == null && dictDetail == null && historyDetail == null && !showMyWords && !showMySentences && !showLog && sceneDetail == null && showSceneList) {
        showSceneList = false
    }

    Scaffold(
        bottomBar = {
            if (!inSubScreen) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.DICT,
                        onClick = { tab = Tab.DICT },
                        icon = { Icon(Icons.Filled.Search, contentDescription = "词典") },
                        label = { Text("词典") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.DICTIONARY,
                        onClick = { tab = Tab.DICTIONARY },
                        icon = { Icon(Icons.Filled.Book, contentDescription = "字典") },
                        label = { Text("字典") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.REWRITE,
                        onClick = { tab = Tab.REWRITE },
                        icon = { Icon(Icons.Filled.Edit, contentDescription = "AI 改写") },
                        label = { Text("AI 改写") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.HISTORY,
                        onClick = { tab = Tab.HISTORY },
                        icon = { Icon(Icons.Filled.History, contentDescription = "历史") },
                        label = { Text("历史") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.FAVORITES,
                        onClick = { tab = Tab.FAVORITES },
                        icon = { Icon(Icons.Filled.Star, contentDescription = "收藏") },
                        label = { Text("收藏") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.MATERIAL,
                        onClick = { tab = Tab.MATERIAL },
                        icon = { Icon(Icons.Filled.Folder, contentDescription = "素材本") },
                        label = { Text("素材本") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when {
                detail != null -> {
                    EntryDetailScreen(entry = detail!!)
                }
                dictDetail != null -> {
                    DictDetailScreen(ref = dictDetail!!)
                }
                historyDetail != null -> {
                    HistoryDetailScreen(entry = historyDetail!!)
                }
                showMyWords -> {
                    MyWordsScreen(onEntryClick = { detail = it })
                }
                showMySentences -> {
                    MySentencesScreen()
                }
                showLog -> {
                    LogScreen()
                }
                showSceneList -> {
                    SceneListScreen(
                        onSceneClick = {
                            sceneDetail = it
                            showSceneList = false
                        }
                    )
                }
                sceneDetail != null -> {
                    SceneDetailScreen(entry = sceneDetail!!)
                }
                else -> {
                    when (tab) {
                        Tab.DICT -> DictionaryScreen(
                            onEntryClick = { detail = it },
                            onDictDetailClick = { dictDetail = it },
                            onShowSettings = { showSettings = true },
                            onShowMyWords = { showMyWords = true },
                            onShowMySentences = { showMySentences = true },
                            onSceneListClick = { showSceneList = true },
                            onSceneDetailClick = { sceneDetail = it }
                        )
                        Tab.DICTIONARY -> DictionaryTabScreen(
                            onDictDetailClick = { dictDetail = it }
                        )
                        Tab.REWRITE -> RewriteScreen(
                            onShowSettings = { showSettings = true }
                        )
                        Tab.HISTORY -> HistoryScreen(
                            onHistoryClick = { historyDetail = it }
                        )
                        Tab.FAVORITES -> FavoritesScreen { detail = it }
                        Tab.MATERIAL -> MaterialScreen()
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onShowLog = {
                showSettings = false
                showLog = true
            }
        )
    }
}
