package com.example.rstm


import AccelerometerScreen
import BLEScreen
import GyroscopeScreen
import ImplementRepository
import ImplementScreen
import LightScreenComp
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import com.example.rstm.Constants.LABELS_PATH
import com.example.rstm.Constants.MODEL_PATH
import com.example.rstm.model.SensorData
import com.example.rstm.ui.screens.Activated
import com.example.rstm.ui.screens.CameraPreviewScreen
import com.example.rstm.ui.screens.CameraScreen
import com.example.rstm.ui.screens.HomeScreen
import com.example.rstm.ui.screens.LocationScreen
import com.example.rstm.ui.screens.MagFieldScreen
import com.example.rstm.ui.theme.RSTMTheme
import com.example.rstm.viewModels.ImplementVM
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.example.rstm.roomImplementation.AppDatabase
import com.example.rstm.ui.screens.YoloDetectionRoute
import java.sql.Timestamp


class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var bleManager: BLEManager

    companion object{
        lateinit var appDatabase: AppDatabase
        const val REQUEST_CODE_STORAGE_PERMISSION = 101
    }

    // Permissions required for the app
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val permissionArray = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
    )

    private val requestPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionsList ->
            permissionsList.entries.forEach { isGranted ->
                if (isGranted.value) {
                    Toast.makeText(this, "Permission Granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Permission is required to access Sensors and Files, Enable it in device settings",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkPermission() {
        val permissionsToRequest = permissionArray.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_DENIED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        } else {
            checkExternalStoragePermission()
        }
    }
    // Request external storage permission
    private fun checkExternalStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestStoragePermission()
            } else {
                Toast.makeText(this, "Manage external storage permission granted", Toast.LENGTH_SHORT).show()
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_STORAGE_PERMISSION)
            } else {
                Toast.makeText(this, "Write external storage permission granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Request permission to manage all files (Android 11+)
    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestStoragePermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    // Initialize sensor Data class
    val sensorData = SensorData()

    fun changeGyroData(x: Float, y: Float, z: Float) {
        sensorData.gyroscopeData = Triple(x, y, z)
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }

    fun changeAccData(x: Float, y: Float, z: Float) {
        sensorData.accelerometerData = Triple(x, y, z)
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }

    fun changeLightData(light: Float) {
        sensorData.lightData = light
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }

    fun changeMagData(x: Float, y: Float, z: Float) {
        sensorData.magneticData = Triple(x, y, z)
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }

    fun changeLocationData(location: android.location.Location) {
        sensorData.locationData = location
        sensorData.timestamp = Timestamp(System.currentTimeMillis())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request permissions
        checkPermission()

        bleManager = BLEManager(this)
        fun isBluetoothEnabled(context: Context): Boolean {  // when user have ble disabled, app won't crash now
            val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

            // Check if Bluetooth is supported on the device
            if (bluetoothAdapter == null) {
                Toast.makeText(context, "Bluetooth not supported on this device", Toast.LENGTH_SHORT).show()
                return false
            }

            // Check if Bluetooth is enabled
            return bluetoothAdapter.isEnabled
        }
        // Start scanning for BLE devices
        if( isBluetoothEnabled(this) ) {
            bleManager.startScanning { device ->
                // Replace with a specific device name/address check if needed
                if (device.name == "ESP32_BLE_Sensor") {
                    Log.d("BLE", "Connecting to ${device.name}")
                    bleManager.connectToDevice(device) { data ->
                        // Handle received data here
                        Log.d("BLE", "Received from ESP: $data")
                    }
                }
            }
        }

        appDatabase = Room.databaseBuilder(applicationContext, AppDatabase::class.java, AppDatabase.NAME).build()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize sensorManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        checkPermission()

        val implementVM = ImplementVM(sensorManager, fusedLocationClient, ImplementRepository())

        enableEdgeToEdge()


        setContent {
            val navController = rememberNavController()
            RSTMTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(navController = navController, startDestination = "home") {
                        composable("yolo") {
                            // Replace `YoloFragmentScreen` with `YoloDetectionRoute`
                            YoloDetectionRoute(
                                modelPath = MODEL_PATH, // Replace with your actual model path
                                labelsPath = LABELS_PATH, // Replace with your actual labels path
                                onPermissionDenied = {
                                    // Handle permission denial
                                    navController.navigate("home") // Navigate back to the home screen or show a message
                                }
                            )
                        }
                        composable("home") {
                            HomeScreen(Modifier.padding(innerPadding), navController)
                        }
                        composable("BLE") {
                            BLEScreen()
                        }
                        composable("accelerometer") {
                            AccelerometerScreen(Modifier.padding(innerPadding), sensorManager, ::changeAccData)
                        }
                        composable("gyro") {
                            GyroscopeScreen(modifier = Modifier.padding(innerPadding), sensorManager, ::changeGyroData)
                        }
                        composable("magField") {
                            MagFieldScreen(modifier = Modifier.padding(innerPadding), sensorManager, ::changeMagData)
                        }
                        composable("lightScreen") {
                            LightScreenComp(modifier = Modifier.padding(innerPadding), sensorManager, :: changeLightData)
                        }
                        composable("locationScreen") {
                            LocationScreen(modifier = Modifier.padding(innerPadding), fusedLocationClient, ::changeLocationData)
                        }
                        composable("cameraScreen") {
                            CameraScreen(Modifier, this@MainActivity, applicationContext)
                        }
                        composable("Detection & Collection Activated") {
                            Activated(Modifier.padding(innerPadding), sensorManager, this@MainActivity, applicationContext, fusedLocationClient)
                        }
                        composable("Hidden View") {
                            CameraPreviewScreen(Modifier = Modifier.padding(innerPadding), sensorManager = sensorManager, fusedLocationClient = fusedLocationClient)
                        }
                        composable("Implement Screen"){
                            ImplementScreen(viewModel = implementVM, modifier = Modifier.padding(innerPadding))
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun DefaultPreview() {
    RSTMTheme {

    }
}