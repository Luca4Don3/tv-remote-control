package dev.lucasdone.tvremote.controller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.lucasdone.tvremote.controller.ui.ControllerApp
import dev.lucasdone.tvremote.controller.ui.ControllerViewModel
import dev.lucasdone.tvremote.controller.ui.theme.TvrcTheme

/** 薄壳 Activity：仅承载 Compose 与生命周期转发，状态与网络在 [ControllerViewModel]。 */
class MainActivity : ComponentActivity() {
    private val viewModel: ControllerViewModel by viewModels { ControllerViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ 强制 edge-to-edge；显式开启并在内容层处理 insets
        enableEdgeToEdge()
        setContent {
            TvrcTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControllerApp(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onStop() {
        // 进入后台关闭会话、心跳与连接（幂等）
        viewModel.onBackground()
        super.onStop()
    }
}
