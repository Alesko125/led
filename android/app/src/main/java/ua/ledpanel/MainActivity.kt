package ua.ledpanel

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Поки екран відкритий, фонові перевірки не лізуть до контролера. */
        @Volatile var isVisible = false
    }

    private lateinit var controller: LedController
    private var light by mutableStateOf(Light())
    private var templates by mutableStateOf(emptyList<Light>())
    private var hasPermissions by mutableStateOf(false)
    private var askedPermissions = false

    private val requiredPermissions =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onAccessChanged() }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onAccessChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Saved.light(this)?.let { light = it }
        templates = Saved.templates(this)
        controller = LedController(applicationContext, lifecycleScope)
        lifecycleScope.launch {
            controller.connectedLight.filterNotNull().collect { light = it }
        }

        setContent {
            val status by controller.status.collectAsState()
            PanelScreen(
                status = status,
                light = light,
                hasPermissions = hasPermissions,
                onChange = { new ->
                    light = new
                    Saved.save(this, new)
                    controller.set(new)
                },
                templates = templates,
                onTemplatesChange = { list ->
                    templates = list
                    Saved.saveTemplates(this, list)
                },
                onGrant = ::requestAccess,
                onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        onAccessChanged()
        if (!hasPermissions && !askedPermissions) requestAccess()
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
        controller.stop() // звільняємо контролер для фонових перевірок
    }

    private fun requestAccess() {
        if (askedPermissions) {
            // Користувач уже відмовив — Android більше не покаже запит, тож відкриваємо налаштування застосунку.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            askedPermissions = true
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun onAccessChanged() {
        hasPermissions = requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (hasPermissions && isVisible) {
            controller.start()
            AutoRestore.register(applicationContext)
        }
    }
}
