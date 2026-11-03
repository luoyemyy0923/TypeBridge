package cn.aclin.typebridge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import cn.aclin.typebridge.ui.screens.HomeScreen
import cn.aclin.typebridge.ui.theme.TypeBridgeTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.bootstrap()
        setContent {
            TypeBridgeTheme {
                HomeScreen(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }
}
