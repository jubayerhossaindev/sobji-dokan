package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.prefs.AppThemeMode
import com.example.ui.ShopViewModel
import com.example.ui.navigation.SobjiKhataAppShell
import com.example.ui.screens.OnboardingScreen
import com.example.ui.screens.PinLockScreen
import com.example.ui.theme.SobjiKhataTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { SobjiKhataRoot() }
  }
}

@Composable
internal fun SobjiKhataRoot(viewModel: ShopViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val pendingRestore by viewModel.pendingRestorePayload.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  val isDark =
    when (uiState.preferences.themeMode) {
      AppThemeMode.SYSTEM -> isSystemInDarkTheme()
      AppThemeMode.LIGHT -> false
      AppThemeMode.DARK -> true
    }
  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as? Activity)?.window
      if (window != null) {
        WindowCompat.getInsetsController(window, view).apply {
          isAppearanceLightStatusBars = !isDark
          isAppearanceLightNavigationBars = !isDark
        }
      }
    }
  }

  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> viewModel.onAppMovedToBackground()
        Lifecycle.Event.ON_START -> viewModel.onAppReturnedToForeground()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  SobjiKhataTheme(themeMode = uiState.preferences.themeMode, darkTheme = isDark) {
    when {
      !uiState.isLoaded -> LoadingSplashScreen()
      !uiState.preferences.isSetupComplete -> {
        OnboardingScreen(
          isSaving = uiState.isSaving,
          onCompleteSetup = viewModel::completeInitialSetup,
        )
      }
      !uiState.isUnlocked -> {
        PinLockScreen(
          shopName = uiState.preferences.shopName,
          lockoutUntilMs = uiState.pinLockoutUntilMs,
          onTryUnlock = viewModel::unlockWithPin,
        )
      }
      else -> {
        SobjiKhataAppShell(
          uiState = uiState,
          viewModel = viewModel,
          pendingRestore = pendingRestore,
        )
      }
    }
  }
}

@Composable
private fun LoadingSplashScreen() {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Column(
      modifier =
        Modifier.fillMaxSize().padding(24.dp).semantics(mergeDescendants = true) {
          contentDescription = "সবজি খাতা লোড হচ্ছে"
        },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Image(
        painter = painterResource(id = R.drawable.img_icon_art),
        contentDescription = null,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)),
      )
      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = "সবজি খাতা",
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = "হিসাব প্রস্তুত হচ্ছে...",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(18.dp))
      CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
    }
  }
}
