package dev.lepela.rideindex.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.lepela.rideindex.data.SettingsStore
import dev.lepela.rideindex.work.RefreshScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RefreshScheduler.scheduleAll(this)
        val store = SettingsStore(applicationContext)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                                icon = {}, label = { Text("지수") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                                icon = {}, label = { Text("설정") })
                        }
                    },
                ) { inner ->
                    val m = Modifier.padding(inner)
                    if (tab == 0) MainScreen(store, m) else SettingsScreen(store, m)
                }
            }
        }
    }
}
