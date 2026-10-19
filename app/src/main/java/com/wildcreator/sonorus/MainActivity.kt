package com.wildcreator.sonorus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.wildcreator.sonorus.ui.changelog.ChangelogHost
import com.wildcreator.sonorus.ui.changelog.ChangelogViewModel
import com.wildcreator.sonorus.ui.navigation.WildNavGraph
import com.wildcreator.sonorus.ui.theme.RootViewModel
import com.wildcreator.sonorus.ui.theme.SonorusTheme
import com.wildcreator.sonorus.ui.update.AppUpdateHost
import com.wildcreator.sonorus.ui.update.UpdateViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val rootViewModel: RootViewModel by viewModels()

    /**
     * Activity 作用域的更新状态：启动时的静默检查与设置页的手动检查
     * 必须共用同一个实例，否则会各查各的、弹两次窗。
     */
    private val updateViewModel: UpdateViewModel by viewModels()

    /** 同理：更新日志的"这次启动弹不弹"也只能判一次 */
    private val changelogViewModel: ChangelogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by rootViewModel.themeMode.collectAsState()
            val fontScale by rootViewModel.fontScale.collectAsState()
            SonorusTheme(
                themeMode = themeMode,
                fontScale = fontScale
            ) {
                AppUpdateHost(viewModel = updateViewModel) {
                    ChangelogHost(viewModel = changelogViewModel) {
                        WildNavGraph()
                    }
                }
            }
        }
    }
}
