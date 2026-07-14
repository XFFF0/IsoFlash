package com.xfff0.isoflash

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.xfff0.isoflash.ui.IsoFlashTheme
import com.xfff0.isoflash.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BurnViewModel by viewModels()
    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.selectIso(it) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IsoFlashTheme { MainScreen(viewModel) { pickIso.launch(arrayOf("*/*")) } } }
    }
    override fun onResume() { super.onResume(); viewModel.refreshDrives() }
}
