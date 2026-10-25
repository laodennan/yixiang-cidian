package com.example.yixiang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
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
    var category by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<LexiconEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val inSubScreen = detail != null || category != null

    BackHandler(enabled = detail != null) {
        detail = null
    }
    BackHandler(enabled = detail == null && category != null) {
        category = null
    }

    Scaffold(
        bottomBar = {
            if (!inSubScreen) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.DICT,
                        onClick = { tab = Tab.DICT },
                        icon = {},
                        label = { Text("词典") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.CATEGORY,
                        onClick = { tab = Tab.CATEGORY },
                        icon = {},
                        label = { Text("分类") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.REWRITE,
                        onClick = { tab = Tab.REWRITE },
                        icon = {},
                        label = { Text("AI 改写") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.FAVORITES,
                        onClick = { tab = Tab.FAVORITES },
                        icon = {},
                        label = { Text("收藏") }
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
                category != null -> {
                    CategoryEntryListScreen(category = category!!) { entry ->
                        detail = entry
                    }
                }
                else -> {
                    when (tab) {
                        Tab.DICT -> DictionaryScreen(
                            onEntryClick = { detail = it },
                            onShowSettings = { showSettings = true }
                        )
                        Tab.CATEGORY -> CategoryScreen { category = it }
                        Tab.REWRITE -> RewriteScreen(
                            onShowSettings = { showSettings = true }
                        )
                        Tab.FAVORITES -> FavoritesScreen { detail = it }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}
