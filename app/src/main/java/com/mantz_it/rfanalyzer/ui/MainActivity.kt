// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
package com.mantz_it.rfanalyzer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.LogcatLogger
import com.mantz_it.rfanalyzer.analyzer.AnalyzerService
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.MainViewModel.UiAction
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.LocalSnackbarHostState
import com.mantz_it.rfanalyzer.ui.screens.LogFileScreen
import com.mantz_it.rfanalyzer.ui.screens.MainScreen
import com.mantz_it.rfanalyzer.ui.screens.RecordingsScreen
import com.mantz_it.rfanalyzer.ui.composable.ScreenOrientation
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.screens.AboutScreen
import com.mantz_it.rfanalyzer.ui.screens.ManualScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import android.provider.Settings

/**
 * <h1>RF Analyzer - Main Activity</h1>
 *
 * Module:      MainActivity.kt
 * Description: The main activity of the app.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


const val RECORDINGS_DIRECTORY = "recordings"

@AndroidEntryPoint
class MainActivity: ComponentActivity() {

    @Inject lateinit var appStateRepository: AppStateRepository
    private val mainViewModel: MainViewModel by viewModels()
    private var analyzerService: AnalyzerService? = null
    private var isBound = false
    private lateinit var analyzerSurface: AnalyzerSurface

    // Activity Launchers
    private lateinit var openIQFileLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "MainActivity"
    }

    // Callbacks for bind/unbindService
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected: Service connected.")
            val localBinder = binder as AnalyzerService.LocalBinder
            analyzerService = localBinder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected: Service disconnected.")
            isBound = false
            analyzerService = null
        }
    }

    

    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity created.")

        // Get version name:
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "<unknown version>"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "onCreate: Cannot read version name: " + e.message);
            "<unknown version>"
        }
        appStateRepository.appVersion.set(versionName)

        // Get build type (debug/release)
        val buildType = BuildConfig.BUILD_TYPE
        Log.i(TAG, "This is RF Decoder $versionName ($buildType), modified build by Logan Zarov; upstream RF Analyzer by Dennis Mantz.");
        appStateRepository.appBuildType.set(buildType)

        // Device Info
        val deviceName = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            Build.MODEL
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        Log.i(TAG, "onCreate: Android Device Info: $deviceName, Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")

        // Prepare the preferred recording directory. RecordingStorage will still
        // fall back automatically if removable/shared SD access is not available.
        RecordingStorage.preferredDirectory(this).mkdirs()

        // Android 7/8: ask once for legacy shared-storage access so a removable
        // SD card can use a visible /recordings directory. Recording continues
        // in app-specific storage if the permission is denied.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.M..Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            val legacyStoragePermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                Log.i(TAG, "Legacy storage permission granted=$granted")
            }
            lifecycleScope.launch {
                delay(500)
                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Check and request POST_NOTIFICATIONS permission if needed
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "POST_NOTIFICATIONS permission granted.")
            } else {
                Log.w(TAG, "POST_NOTIFICATIONS permission denied.")
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "onCreate: Check for permissions..")
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                lifecycleScope.launch {
                    delay(2000) // 2-second delay to make sure the AppStateRepository is initialized
                    if (!appStateRepository.dontAskForNotificationPermission.value) {
                        requestNotificationPermission(requestPermissionLauncher)
                    }
                }
            }
        }

        val maxHeap = Runtime.getRuntime().maxMemory() / 1024 / 1024
        Log.d(TAG, "onCreate: Max Heap size: ${maxHeap}MB")
        if (maxHeap < 512)
            Log.w(TAG, "onCreate: Max Heap is less than 512MB! This may cause issues with waterfall rendering!")

        // set initial screen orientation
        setScreenOrientation(appStateRepository.screenOrientation.value)

        // Create the AnalyzerSurface
        analyzerSurface = AnalyzerSurface(
            context = this,
            sourceName = appStateRepository.sourceName,
            sourceOptimalSampleRates = appStateRepository.sourceOptimalSampleRates,
            sourceFrequency = appStateRepository.sourceFrequency,
            sourceSampleRate = appStateRepository.sourceSampleRate,
            sourceSignalStartFrequency = appStateRepository.sourceSignalStartFrequency,
            sourceSignalEndFrequency = appStateRepository.sourceSignalEndFrequency,
            fftAverageLength = appStateRepository.fftAverageLength,
            fftPeakHold = appStateRepository.fftPeakHold,
            maxFrameRate = appStateRepository.maxFrameRate,
            waterfallSpeed = appStateRepository.waterfallSpeed,
            waterfallColorMap = appStateRepository.waterfallColorMap,
            fftDrawingType = appStateRepository.fftDrawingType,
            fftRelativeFrequency = appStateRepository.fftRelativeFrequency,
            fftWaterfallRatio = appStateRepository.fftWaterfallRatio,
            demodulationMode = appStateRepository.demodulationMode,
            demodulationEnabled = appStateRepository.demodulationEnabled,
            channelFrequency = appStateRepository.channelFrequency,
            channelWidth = appStateRepository.channelWidth,
            channelStartFrequency = appStateRepository.channelStartFrequency,
            channelEndFrequency = appStateRepository.channelEndFrequency,
            squelchEnabled = appStateRepository.squelchEnabled,
            squelch = appStateRepository.squelch,
            recordingRunning = appStateRepository.recordingRunning,
            recordOnlyWhenSquelchIsSatisfied = appStateRepository.recordOnlyWhenSquelchIsSatisfied,
            recordingCurrentFileSize = appStateRepository.recordingCurrentFileSize,
            fontSize = appStateRepository.fontSize,
            showDebugInformation = appStateRepository.showDebugInformation,
            viewportFrequency = appStateRepository.viewportFrequency,
            viewportSampleRate = appStateRepository.viewportSampleRate,
            viewportVerticalScaleMin = appStateRepository.viewportVerticalScaleMin,
            viewportVerticalScaleMax = appStateRepository.viewportVerticalScaleMax,
            viewportStartFrequency = appStateRepository.viewportStartFrequency,
            viewportEndFrequency = appStateRepository.viewportEndFrequency,
            averageSignalStrength = appStateRepository.averageSignalStrength,
            squelchSatisfied = appStateRepository.squelchSatisfied,
            fftProcessorData = appStateRepository.fftProcessorData,
            analyzerSurfaceActions = mainViewModel.analyzerSurfaceActions
        )


        // Setup the composable UI with NavController
        setContent {
            val colorTheme by appStateRepository.colorTheme.stateFlow.collectAsState()
            RFAnalyzerTheme(colorTheme = colorTheme) {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(Unit) {
                    mainViewModel.navigationEvent.collect { screen ->
                        navController.navigate(screen.route + screen.subUrl) {
                            // if we navigate to the main screen, make sure that the backstack is cleared:
                            if(screen == AppScreen.MainScreen) {
                                // Pop up to the start destination (or a specific destination) and clear the backstack
                                popUpTo(navController.graph.startDestinationId) {
                                    inclusive = true // Clear all destinations up to and including the start destination
                                }
                                launchSingleTop = true // Avoid re-creating the destination if it's already at the top
                            }
                        }
                    }
                }
                val focusManager = LocalFocusManager.current
                val isLoading by mainViewModel.isLoadingIndicatorVisible.collectAsState()
                val showHelp = { subUrl: String ->
                    val encodedUrl = Uri.encode(subUrl)
                    Log.d(TAG, "showHelp: got subUrl=$subUrl  encoded=$encodedUrl  enabled=${appStateRepository.longPressHelpEnabled.value}")
                    if(appStateRepository.longPressHelpEnabled.value)
                        navController.navigate("${AppScreen.ManualScreen().route}$encodedUrl")
                }
                CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                    CompositionLocalProvider(LocalShowHelp provides showHelp) {
                        Scaffold(
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            modifier = Modifier
                                .background(Color.Black)
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .pointerInput(Unit) {
                                detectTapGestures { focusManager.clearFocus() } // Clears focus when tapping anywhere
                            }
                        ) { innerPadding ->
                            LaunchedEffect(Unit) {
                                mainViewModel.snackbarEvent.collect { snackbarEvent ->
                                    val result = snackbarHostState.showSnackbar(
                                        message = snackbarEvent.message,
                                        actionLabel = snackbarEvent.buttonText,
                                        duration = SnackbarDuration.Short
                                    )
                                    snackbarEvent.callback?.let { it(result) }
                                }
                            }
                            NavHost(
                                navController = navController,
                                startDestination = AppScreen.MainScreen.route,
                                modifier = Modifier.padding(innerPadding)
                            ) {
                                composable(AppScreen.MainScreen.route) { MainScreen(analyzerSurface, mainViewModel, appStateRepository) }
                                composable(AppScreen.RecordingScreen.route) { RecordingsScreen(navController, mainViewModel.recordings, appStateRepository.displayOnlyFavoriteRecordings.stateFlow, mainViewModel.recordingsScreenActions) }
                                composable(AppScreen.LogFileScreen.route) { LogFileScreen(navController, mainViewModel.logContent) }
                                composable(AppScreen.AboutScreen.route) { AboutScreen(versionName, navController) }
                                composable(
                                    route = "${AppScreen.ManualScreen().route}{subUrl}",
                                    arguments = listOf(
                                        navArgument("subUrl") {
                                            type = NavType.StringType
                                            defaultValue = "index.html"
                                            nullable = true
                                        }
                                    )) { backStackEntry -> ManualScreen(navController, subUrl = Uri.decode(backStackEntry.arguments?.getString("subUrl") ?: "index.html")) }
                            }
                            // Loading Overlay (only visible when isLoading = true)
                            if (isLoading) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(enabled = true) { }   // Blocks all touch input
                                        .background(Color.Black.copy(alpha = 0.5f)), // Semi-transparent overlay
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        lifecycleScope.collectAppState(appStateRepository.analyzerRunning) { running ->
            if (running && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) enterSdrFocus()
            else leaveSdrFocus()
        }

        // Observe and handle the UI actions
        lifecycleScope.launch {
            mainViewModel.uiActions.collect { action ->
                when (action) {

                    is UiAction.OnStartClicked -> onStartClickedAction()
                    is UiAction.OnStopClicked  -> onStopClickedAction()
                    is UiAction.OnOpenIQFileClicked  -> onOpenIQFileClicked()
                    is UiAction.OnAutoscaleClicked -> analyzerSurface.autoscale()
                    is UiAction.ShowDialog -> AlertDialog.Builder(this@MainActivity)
                        .setTitle(action.title)
                        .setMessage(action.msg)
                        .setPositiveButton(action.positiveButton) { _, _ ->
                            action.action?.let { it() }
                        }
                        .setNegativeButton(action.negativeButton) { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                    is UiAction.OnShowLogFileClicked -> {
                        Log.i(TAG,"opening LogFileScreen")
                        mainViewModel.navigate(AppScreen.LogFileScreen)
                        Log.i(TAG,"loading logs..")
                        mainViewModel.loadLogs(File(filesDir, LogcatLogger.logfileName))
                        Log.i(TAG,"logs loaded!")
                    }
                    is UiAction.OnDeleteLogFileClicked -> {
                        Log.i(TAG, "UiAction.OnDeleteLogFileClicked: Deleting logfile ${LogcatLogger.logfileName}")
                        File(filesDir, LogcatLogger.logfileName).delete()
                    }
                    is UiAction.OnSaveLogToFileClicked -> saveFileToUserDirectory(this@MainActivity, action.destUri, File(filesDir, LogcatLogger.logfileName))
                    is UiAction.OnShareLogFileClicked -> shareFile(File(filesDir, LogcatLogger.logfileName), "text/plain", "Share log file via")
                    is UiAction.OnStartRecordingClicked -> if(isBound) analyzerService?.startRecording()
                    is UiAction.OnStopRecordingClicked -> if(isBound) analyzerService?.stopRecording()
                    is UiAction.OnDeleteRecordingClicked -> {
                        val file = File(action.filePath)
                        if(file.exists()) file.delete()
                        else Log.e(TAG, "UiAction.OnDeleteRecordingClicked: File ${file.absoluteFile} [i.e. ${action.filePath}] does not exist")
                    }
                    is UiAction.OnDeleteAllRecordingsClicked -> {
                        RecordingStorage.candidateDirectories(this@MainActivity).forEach { dir ->
                            dir.deleteRecursively()
                            dir.mkdirs()
                        }
                    }
                    is UiAction.RenameFile -> renameFile(action.file, action.newName)
                    is UiAction.OnSaveRecordingClicked -> saveFileToUserDirectory(this@MainActivity, action.destUri, File(action.filename))
                    is UiAction.OnShareRecordingClicked -> shareFile(File(action.filename), "application/octet-stream", "Share recording via")
                    null -> Log.e(TAG, "mainViewModel.uiActions.collect: action is NULL!")
                }
            }
        }

        // Observe App State
        lifecycleScope.collectAppState(appStateRepository.screenOrientation) {
            setScreenOrientation(it)
        }

        

        // Initialize the launcher for opening the IQ file
        openIQFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val uri: Uri? = result.data?.data
                Log.d(TAG, "openIQFileLauncher result: URI: $uri")
                if (uri != null)
                    setFileSourceFromContentUri(uri)
            }
        }

        // Handle incoming intent (when app was started by opening an .iq file with RF Analyzer
        if(intent != null) {
            Log.d(TAG, "onCreate: Incoming intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
            if("android.intent.action.VIEW" == intent.action && intent.data != null)
                handleIncomingFile(intent)
        }
    }

    private fun requestNotificationPermission(requestPermissionLauncher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

            val askedAlready = appStateRepository.notificationPermissionAskedAtLeastOnce.value
            // Show a dialog that explains why we need the permission
            AlertDialog.Builder(this)
                .setTitle("Background Notification")
                .setMessage("The app needs permission to display a notification while the analyzer service is running in the background.")
                .setPositiveButton(
                    if(askedAlready) "Go to Settings" else "OK"
                ) { dialog, whichButton ->
                    if(askedAlready) {
                        // go to settings:
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", this.packageName, null)
                        intent.data = uri
                        this.startActivity(intent)
                    } else {
                        // show permission dialog:
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        appStateRepository.notificationPermissionAskedAtLeastOnce.set(true)
                    }
                }
                .setNegativeButton(
                    "Don't ask again"
                ) { dialog, whichButton ->
                    appStateRepository.dontAskForNotificationPermission.set(true)
                    appStateRepository.notificationPermissionAskedAtLeastOnce.set(true)
                }
                .show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        Log.d(TAG, "onNewIntent: received intent: action=${intent.action} categories=${intent.categories} data=${intent.data}")
        super.onNewIntent(intent)
        handleIncomingFile(intent)
    }

    private fun handleIncomingFile(intent: Intent) {
        val uri = intent.data
        Log.d(TAG, "handleIncomingFile: Intent.data=$uri")
        if (uri != null) {
            if (appStateRepository.analyzerRunning.value) {
                lifecycleScope.launch {
                    Log.d(TAG, "handleIncomingFile: Analyzer is running. Show dialog to the user after 1 second delay")
                    delay(1000)
                    mainViewModel.showSnackbar(MainViewModel.SnackbarEvent(
                        message = "Load incoming file into File Source? (Analyzer will stop!)",
                        buttonText = "Load File",
                        callback = {
                            onStopClickedAction()
                            setFileSourceFromContentUri(uri)
                            mainViewModel.showSnackbar(MainViewModel.SnackbarEvent("File loaded. Press PLAY to start Analyzer!"))
                        }
                    ))
                }
            } else
                setFileSourceFromContentUri(uri)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart: Binding to service if running.")
        // Bind Service:
        Intent(this, AnalyzerService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop: Unbinding from service.")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private var previousSystemUi: Int? = null
    private fun enterSdrFocus() {
        if (appStateRepository.sourceType.value != SourceType.LGMODEM) return
        val controller = com.mantz_it.rfanalyzer.source.SdrFocusController.get(this)
        if (!controller.isEnabled) return
        if (previousSystemUi == null) previousSystemUi = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        controller.start()
    }
    private fun leaveSdrFocus() {
        com.mantz_it.rfanalyzer.source.SdrFocusController.get(this).stop()
        previousSystemUi?.let { window.decorView.systemUiVisibility = it }
        previousSystemUi = null
    }
    override fun onResume() {
        super.onResume()
        if (appStateRepository.analyzerRunning.value) enterSdrFocus()
    }
    override fun onPause() {
        leaveSdrFocus()
        super.onPause()
    }
    override fun onDestroy() {
        leaveSdrFocus()
        Log.d(TAG, "onDestroy: Activity destroyed.")
        super.onDestroy()
    }

    private fun onStartClickedAction() {
        Log.d(TAG, "onStartPressedAction")
        if (isBound) {
            analyzerService?.startAnalyzer()
        } else {
            Log.w(TAG, "onStartClickedAction: Service is not bound! Cannot start Analyzer")
        }
    }

    private fun onStopClickedAction() {
        leaveSdrFocus()
        Log.d(TAG, "onStopPressedAction: Stopping Service..")
        if(isBound) {
            analyzerService?.stopAnalyzer()
        } else {
            Log.w(TAG, "onStopClickedAction: Service is not bound! Cannot stop Analyzer")
        }
    }

    private fun onOpenIQFileClicked() {
        // Show system file chooser
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Allow any file type
        }
        openIQFileLauncher.launch(intent)
    }

    private fun setFileSourceFromContentUri(uri: Uri) {
        var filename: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) {
                cursor.moveToFirst()
                filename = cursor.getString(nameIndex)
            }
        }
        Log.d(TAG, "setFileSourceFromContentUri: URI: $uri (Original Filename: $filename)")
        mainViewModel.setFilesourceUri(uri.toString(), filename)
    }

    

    private fun setScreenOrientation(screenOrientation: ScreenOrientation) {
        when(screenOrientation) {
            ScreenOrientation.AUTO -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            ScreenOrientation.PORTRAIT -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            ScreenOrientation.LANDSCAPE -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            ScreenOrientation.REVERSE_PORTRAIT -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT)
            ScreenOrientation.REVERSE_LANDSCAPE -> setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
        }
    }

    private fun saveFileToUserDirectory(context: Context, destUri: Uri, srcFile: File) {
        try {
            val contentResolver = context.contentResolver
            if (!srcFile.exists()) {
                Log.w(TAG, "saveFileToUserDirectory: File ${srcFile.absolutePath} does not exist!")
                return
            }
            contentResolver.openOutputStream(destUri)?.use { outputStream ->
                srcFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream) // Copy file contents
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Failed to save file ($e)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File, mimeType: String, message: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        ShareCompat.IntentBuilder(this)
            .setType(mimeType)
            .setStream(uri)
            .setChooserTitle(message)
            .startChooser()
    }

    private fun renameFile(file: File, newName: String, overrideExisting: Boolean = true): Boolean {
        if (!file.exists()) {
            Log.e(TAG, "renameFile: Old file does not exist: ${file.absolutePath}")
            return false
        }
        val newFile = File(file.parentFile, newName)

        if (newFile.exists()) {
            Log.w(TAG, "renameFile: New file already exists: ${newFile.absolutePath}")
            if (overrideExisting) {
                if(!newFile.delete()) {
                    Log.e(TAG, "renameFile: Failed to delete existing destination file")
                    return false
                } else {
                    Log.i(TAG, "renameFile: Deleted ${newFile.absolutePath}")
                }
            } else {
                return false
            }
        }

        Log.i(TAG, "renameFile: Renaming file from ${file.absolutePath} to ${newFile.absolutePath}")
        val success = file.renameTo(newFile)
        if (!success) {
            Log.e(TAG, "renameFile: Failed to rename file from ${file.absolutePath} to ${newFile.absolutePath}")
        }
        return success
    }

}
