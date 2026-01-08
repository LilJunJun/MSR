package com.nramos.msr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nramos.msr.RecordService.Companion.KEY_RECORDING_CONFIG
import com.nramos.msr.RecordService.Companion.START_RECORDING
import com.nramos.msr.RecordService.Companion.STOP_RECORDING
import com.nramos.msr.ui.theme.CoralRed
import com.nramos.msr.ui.theme.MintGreen
import com.nramos.msr.ui.theme.RecordScreenTheme

class MainActivity : AppCompatActivity() {
    //reference to our mediaProjectionManager
    private val mediaProjectionManager by lazy {
        getSystemService<MediaProjectionManager>()!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setContent {
            RecordScreenTheme {

            }
            //state that we listen to directly from our service's companion object
            val isServiceRunning by RecordService.isServiceRunning.collectAsStateWithLifecycle()

            //local state to see if we have permission on certain api levels
            var hasNotificationPermission by remember {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU){
                    //request notification permissions
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                } else mutableStateOf(true)
            }

            val screenRecordLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result -> //called when we confirm or decline the dialog for screen recording
                val intent = result.data ?: return@rememberLauncherForActivityResult //get the result data from dialog
                val config = ScreenRecordConfig(
                    resultCode = result.resultCode,
                    data = intent
                )

                val serviceIntent = Intent(
                    applicationContext,
                    RecordService::class.java
                ).apply {
                    action = START_RECORDING
                    putExtra(KEY_RECORDING_CONFIG, config)
                }

                startForegroundService(serviceIntent) //sends our intent and starts our service
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasNotificationPermission = isGranted
                if (hasNotificationPermission && !isServiceRunning) {
                    //launch our media projection service with an intent
                    screenRecordLauncher.launch(
                        //this creates our intent that will launch our initial dialog where user can pick what kind of screen they want to record (single app or entire screen)
                        mediaProjectionManager.createScreenCaptureIntent()
                    )

                }
            }
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Button(
                        onClick = {
                            if(!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                //request permissions if we do not have permissions
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                if(isServiceRunning) {
                                    //declare intent that we want to stop the service
                                    Intent(applicationContext, RecordService::class.java).also {
                                        it.action = STOP_RECORDING
                                        startForegroundService(it) //deliver this intent here to the service so we can stop it
                                    }
                                } else {
                                    //launch our media projection service with an intent
                                    screenRecordLauncher.launch(
                                        //this creates our intent that will launch our initial dialog where user can pick what kind of screen they want to record (single app or entire screen)
                                        mediaProjectionManager.createScreenCaptureIntent()
                                    )
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(isServiceRunning){
                                CoralRed
                            } else MintGreen
                        )
                    ) {
                        Text(
                            text = if(isServiceRunning) {
                                "Stop recording"
                            } else "Start recording",
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}
