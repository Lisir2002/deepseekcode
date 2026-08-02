package com.deepseek.coder.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.deepseek.coder.core.DispatcherProvider
import com.deepseek.coder.ui.navigation.DeepCoderNavGraph
import com.deepseek.coder.ui.navigation.RootViewModel
import com.deepseek.coder.ui.theme.DeepCoderTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var dispatchers: DispatcherProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeepCoderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val rootVm: RootViewModel = hiltViewModel()
                    val startDest by rootVm.startDestination.collectAsStateWithLifecycle()
                    DeepCoderNavGraph(
                        navController = navController,
                        startDestination = startDest
                    )
                }
            }
        }
    }
}
