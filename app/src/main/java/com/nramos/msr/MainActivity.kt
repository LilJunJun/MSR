package com.nramos.msr

import android.Manifest
import android.content.pm.PackageManager
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
import com.nramos.msr.ui.theme.CoralRed
import com.nramos.msr.ui.theme.MintGreen
import com.nramos.msr.ui.theme.RecordScreenTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        setContent {
            RecordScreenTheme {

            }
            var isServiceRunning by remember {
                mutableStateOf(false)
            }

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

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                hasNotificationPermission = isGranted
                if (hasNotificationPermission && !isServiceRunning) {
                    //todo: launch our media projection service
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
                                    //todo: stop service
                                } else {
                                    //todo: start service
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
