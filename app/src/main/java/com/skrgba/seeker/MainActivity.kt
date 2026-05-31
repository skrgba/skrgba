package com.skrgba.seeker

import android.content.Intent
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.skrgba.seeker.databinding.ActivityMainBinding
import com.skrgba.seeker.emulator.EmulatorEngine
import com.skrgba.seeker.emulator.EmulatorGlView
import com.skrgba.seeker.emulator.GbaButton
import com.skrgba.seeker.solana.LicenseStorage
import com.skrgba.seeker.solana.SolanaConfig
import com.skrgba.seeker.solana.SolanaWalletManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var engine: EmulatorEngine
    private lateinit var walletManager: SolanaWalletManager
    private val licenseManager = com.skrgba.seeker.solana.SolanaLicenseManager()
    private var glView: EmulatorGlView? = null
    private var currentGameTitle: String? = null
    private var currentGameFileName: String? = null
    private var isEditMode = false
    private var isLicensed = false

    private val speedSteps = intArrayOf(1, 2, 4, 8)
    private var speedIndex = 0
    private val currentSpeed get() = speedSteps[speedIndex]

    @Suppress("DEPRECATION")
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                loadRomFromUri(uri)
            }
        }
    }

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            try {
                val r = com.skrgba.seeker.emulator.SaveBackup.exportTo(this, uri, engine.getPersistentSaveDir())
                runOnUiThread { toast(getString(R.string.backup_exported, r.files)) }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.backup_failed, e.message ?: "")) }
            }
        }.start()
    }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        Thread {
            try {
                val r = com.skrgba.seeker.emulator.SaveBackup.importFrom(this, uri, engine.getPersistentSaveDir())
                runOnUiThread { toast(getString(R.string.backup_imported, r.files)) }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.backup_failed, e.message ?: "")) }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        engine = EmulatorEngine(applicationContext)
        engine.init()
        walletManager = SolanaWalletManager(this)

        ensureAppFolders()

        setupUI()
        setupEmulatorView()
        wireControls()
        startLogoAnimation()
        observeWallet()

        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            loadRomFromUri(intent.data!!)
        }
    }

    private fun ensureAppFolders() {
        try {
            val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val romsDir = File(docs, "SkrGba/ROMs")
            val savesDir = File(docs, "SkrGba/saves")
            if (!romsDir.exists()) romsDir.mkdirs()
            if (!savesDir.exists()) savesDir.mkdirs()
            Log.d("MainActivity", "App folders ensured: ${romsDir.absolutePath}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to create app folders", e)
        }
    }

    private fun observeWallet() {
        lifecycleScope.launch {
            walletManager.walletState.collect { state ->
                when (state) {
                    is SolanaWalletManager.WalletState.Connected -> {
                        val shortKey = "${state.publicKey.take(6)}...${state.publicKey.takeLast(4)}"
                        b.solanaStatusText?.text = getString(R.string.wallet_connected, shortKey)
                        b.solanaStatusText?.alpha = 1.0f
                        b.btnConnectWallet?.visibility = View.GONE
                        updateCheatButton()
                        checkLicense(state.publicKey)
                    }
                    is SolanaWalletManager.WalletState.Error -> {
                        b.solanaStatusText?.text = getString(R.string.wallet_error, state.message)
                        toast(getString(R.string.wallet_msg, state.message))
                        b.btnConnectWallet?.visibility = View.VISIBLE
                        b.btnBuyLicense?.visibility = View.GONE
                    }
                    is SolanaWalletManager.WalletState.Disconnected -> {
                        b.solanaStatusText?.text = getString(R.string.optimized_solana_footer)
                        b.solanaStatusText?.alpha = 0.5f
                        b.btnConnectWallet?.visibility = View.VISIBLE
                        b.btnBuyLicense?.visibility = View.GONE
                        isLicensed = false
                    }
                    is SolanaWalletManager.WalletState.Connecting -> {
                        b.solanaStatusText?.text = getString(R.string.wallet_connecting)
                        b.btnConnectWallet?.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateCheatButton() {
        val walletKey = (walletManager.walletState.value as? SolanaWalletManager.WalletState.Connected)?.publicKey ?: ""
        val unlocked = (walletKey.isNotEmpty() && LicenseStorage.isCheatUnlocked(this, walletKey)) ||
            SolanaConfig.isWhitelisted(walletKey)
        b.btnCheat.text = getString(if (unlocked) R.string.cheat_button_unlocked else R.string.cheat_button_locked)
    }

    private fun cleanDisplayTitle(raw: String): String =
        raw.substringBefore("(").substringBefore("[").trim().ifEmpty { raw }

    private fun restoreLastGameTitle() {
        val raw = currentGameTitle
            ?: getSharedPreferences("app_prefs", MODE_PRIVATE).getString("last_game_title", null)
            ?: return
        b.gameTitle.text = cleanDisplayTitle(raw).uppercase()
        b.gameTitle.visibility = View.VISIBLE
    }

    private fun checkLicense(walletAddress: String, skipPolling: Boolean = true) {
        Log.d("MainActivity", "checkLicense starting for $walletAddress")
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                b.solanaStatusText?.text = getString(R.string.checking_license)
                b.btnBuyLicense?.visibility = View.GONE
            }

            // 1. Whitelist — Treasury/Developer-Wallet braucht keine Lizenz
            if (SolanaConfig.isWhitelisted(walletAddress)) {
                Log.d("MainActivity", "Wallet ist whitelisted: $walletAddress")
                withContext(Dispatchers.Main) { isLicensed = true; updateLicensedUI(walletAddress) }
                return@launch
            }

            // 2. Local cache - only valid if it matches the currently connected wallet
            if (LicenseStorage.isUnlocked(this@MainActivity, walletAddress)) {
                Log.d("MainActivity", "License found in local storage for $walletAddress")
                withContext(Dispatchers.Main) {
                    isLicensed = true
                    updateLicensedUI(walletAddress)
                    updateCheatButton()
                }
                return@launch
            }

            Log.d("MainActivity", "No local license for this wallet — scanning blockchain")

            // 3. Kein lokaler Eintrag für DIESE Wallet — Blockchain nach Zahlungen scannen
            val (licenseSig, cheatSig) = walletManager.restoreLicenses(walletAddress)

            if (licenseSig != null) {
                Log.i("MainActivity", "License restored from chain: $licenseSig")
                LicenseStorage.markUnlocked(this@MainActivity, licenseSig, walletAddress)
                if (cheatSig != null) {
                    Log.i("MainActivity", "Cheat license restored from chain: $cheatSig")
                    LicenseStorage.markCheatUnlocked(this@MainActivity, cheatSig, walletAddress)
                }
                withContext(Dispatchers.Main) {
                    isLicensed = true
                    updateLicensedUI(walletAddress)
                    updateCheatButton()
                }
                return@launch
            }

            if (cheatSig != null) {
                Log.i("MainActivity", "Cheat license restored from chain: $cheatSig")
                LicenseStorage.markCheatUnlocked(this@MainActivity, cheatSig, walletAddress)
                withContext(Dispatchers.Main) { updateCheatButton() }
            }

            withContext(Dispatchers.Main) {
                if (SolanaConfig.MOCK_MODE) {
                    isLicensed = true
                    updateLicensedUI(walletAddress)
                } else {
                    b.solanaStatusText?.text = getString(R.string.license_required_status)
                    b.btnBuyLicense?.visibility = View.VISIBLE
                    b.btnBuyLicense?.isEnabled = true
                }
            }
        }
    }

    private fun updateLicensedUI(walletAddress: String) {
        val shortKey = "${walletAddress.take(6)}...${walletAddress.takeLast(4)}"
        b.solanaStatusText?.text = getString(R.string.license_active_status, shortKey)
        b.solanaStatusText?.alpha = 1.0f
        b.btnBuyLicense?.visibility = View.GONE
        toast(getString(R.string.license_active))
    }

    private fun startLogoAnimation() {
        val logo = b.logoText ?: return
        val colors = intArrayOf(0xFF9945FF.toInt(), 0xFF14F195.toInt(), 0xFF00C2FF.toInt(), 0xFFFFE600.toInt(), 0xFF9945FF.toInt())
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 3000
        animator.repeatCount = android.animation.ValueAnimator.INFINITE
        animator.interpolator = android.view.animation.LinearInterpolator()
        animator.addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            val width = logo.paint.measureText(logo.text.toString())
            logo.paint.shader = android.graphics.LinearGradient(width * fraction, 0f, width * (fraction + 1), 0f, colors, null, android.graphics.Shader.TileMode.MIRROR)
            logo.invalidate()
        }
        animator.start()
    }

    private fun setupUI() {
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            hideSystemUI()
        }
        b.btnSaveLayout.setOnClickListener {
            saveLayout()
            isEditMode = false
            b.btnSaveLayout.visibility = View.GONE
            wireControls() 
            toast(getString(R.string.layout_saved))
        }
        loadLayout()
    }

    private fun saveLayout() {
        val prefs = getSharedPreferences("layout_v5", MODE_PRIVATE)
        val edit = prefs.edit()
        val isLand = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val prefix = if (isLand) "l_" else "p_"
        
        val controls = listOf(b.dpad, b.abCluster, b.btnSelect, b.btnStart, b.btnSettingsMain)
        val names = listOf("dpad", "abCluster", "select", "start", "settings")
        
        controls.forEachIndexed { i, v ->
            edit.putFloat("${prefix}${names[i]}_x", v.x)
            edit.putFloat("${prefix}${names[i]}_y", v.y)
        }
        edit.apply()
    }

    private fun loadLayout() {
        val prefs = getSharedPreferences("layout_v5", MODE_PRIVATE)
        val isLand = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val prefix = if (isLand) "l_" else "p_"
        
        val controls = listOf(b.dpad, b.abCluster, b.btnSelect, b.btnStart, b.btnSettingsMain)
        val names = listOf("dpad", "abCluster", "select", "start", "settings")
        
        controls.forEachIndexed { i, v ->
            val x = prefs.getFloat("${prefix}${names[i]}_x", -1f)
            val y = prefs.getFloat("${prefix}${names[i]}_y", -1f)
            if (x != -1f) {
                v.post { v.x = x; v.y = y }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun setupEmulatorView() {
        glView?.onPause()
        b.glHost.removeAllViews()
        glView = EmulatorGlView(this, engine).also { b.glHost.addView(it) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val wasRomLoaded = engine.romLoaded
        setupUI()
        setupEmulatorView()
        wireControls()
        
        // Wallet-Status nach Drehung wiederherstellen
        refreshWalletUI()
        
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) hideSystemUI()
        if (wasRomLoaded) {
            b.statusText.visibility = View.GONE
            b.introLayout.visibility = View.GONE
            b.glHost.visibility = View.VISIBLE
        }
        restoreLastGameTitle()
        updateCheatButton()
        glView?.onResume()
    }

    private fun refreshWalletUI() {
        val state = walletManager.walletState.value
        if (state is SolanaWalletManager.WalletState.Connected) {
            val shortKey = "${state.publicKey.take(6)}...${state.publicKey.takeLast(4)}"
            b.solanaStatusText?.text = getString(R.string.wallet_connected, shortKey)
            b.solanaStatusText?.alpha = 1.0f
            b.btnConnectWallet?.visibility = View.GONE
            
            // Wenn wir schon wissen, dass wir lizenziert sind, UI anpassen
            if (isLicensed) {
                b.btnBuyLicense?.visibility = View.GONE
            } else {
                // Falls Status unklar, kurz nachprüfen (optional, oder einfach alten Wert nehmen)
                b.btnBuyLicense?.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume()  { super.onResume(); glView?.onResume(); engine.resume() }
    override fun onPause() {
        engine.pause()
        glView?.onPause()
        
        // Automatisches Speichern bei Pause (App-Wechsel/Home)
        val title = currentGameTitle
        val fname = currentGameFileName
        if (title != null && fname != null) {
            // 1. Save State (Emulator-Snapshot)
            engine.saveState()?.let { state ->
                try {
                    val dir = engine.getPersistentSaveDir()
                    File(dir, "${title}.state").writeBytes(state)
                } catch (_: Exception) {}
            }
            // 2. Battery Save (Pokemon In-Game Save)
            engine.saveSram(fname)
        }
        
        super.onPause()
    }
    override fun onDestroy() { engine.unloadRom(); walletManager.destroy(); super.onDestroy() }

    private fun openRomPicker() {
        if (!isLicensed) {
            toast(getString(R.string.license_required_buy))
            return
        }

        val romDir = engine.getPublicRomDir()
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isFirstImport = prefs.getBoolean("first_import", true)

        if (isFirstImport) {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setTitle(getString(R.string.rom_folder_created_title))
                .setMessage(getString(R.string.rom_folder_created_message))
                .setPositiveButton(getString(R.string.ok_button)) { _, _ ->
                    prefs.edit().putBoolean("first_import", false).apply()
                    launchFilePicker()
                }
                .show()
        } else {
            launchFilePicker()
        }
    }

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Hint to open the newly created directory if possible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val romDir = engine.getPublicRomDir()
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.fromFile(romDir))
            }
        }
        pickRom.launch(intent)
    }

    private fun loadRomFromUri(uri: Uri) {
        if (!isLicensed) {
            toast(getString(R.string.license_required))
            return
        }
        val fileName = getFileName(uri) ?: "Unknown"
        val gameTitleText = fileName.substringBeforeLast(".")
        currentGameTitle = gameTitleText
        currentGameFileName = fileName
        val displayTitle = cleanDisplayTitle(gameTitleText)
        b.statusText.visibility = View.VISIBLE
        b.statusText.text = getString(R.string.loading_rom)
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    runOnUiThread { b.statusText.visibility = View.GONE; toast(getString(R.string.rom_not_readable)) }
                    return@Thread
                }
                if (engine.loadRom(bytes, fileName)) {
                    getSharedPreferences("app_prefs", MODE_PRIVATE)
                        .edit().putString("last_game_title", displayTitle).apply()
                    runOnUiThread {
                        b.statusText.visibility = View.GONE
                        b.introLayout.visibility = View.GONE
                        b.glHost.visibility = View.VISIBLE
                        b.gameTitle.text = displayTitle.uppercase()
                        b.gameTitle.visibility = View.VISIBLE
                        // Re-apply previously saved cheats for this ROM
                        reapplyCheatsForGame(gameTitleText)
                    }
                } else {
                    runOnUiThread { b.statusText.visibility = View.GONE; toast(getString(R.string.rom_load_failed)) }
                }
            } catch (e: Exception) {
                Log.e("SKR", "Load error", e)
                runOnUiThread { b.statusText.visibility = View.GONE; toast(getString(R.string.rom_load_error, e.message)) }
            }
        }.start()
    }

    private fun autoLoadState(gameTitle: String) {
        val dir = engine.getPersistentSaveDir()
        val file = File(dir, "${gameTitle}.state")
        if (file.exists()) {
            val state = file.readBytes()
            if (engine.loadState(state)) {
                toast(getString(R.string.save_auto_loaded))
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun wireControls() {
        isEditMode = false
        b.btnConnectWallet?.setOnClickListener { walletManager.connect() }
        b.btnBuyLicense?.setOnClickListener {
            Log.d("MainActivity", "Buy License clicked")
            b.btnBuyLicense?.isEnabled = false
            b.solanaStatusText?.text = getString(R.string.wallet_confirm_status)
            
            walletManager.buyLicense { success ->
                Log.d("MainActivity", "buyLicense callback: success=$success")
                if (success) {
                    handleSuccessfulPayment()
                } else {
                    b.btnBuyLicense?.isEnabled = true
                    b.solanaStatusText?.text = getString(R.string.purchase_failed_status)
                    toast(getString(R.string.purchase_failed))
                }
            }
        }
        b.btnSettingsMain.setOnClickListener { showSettings() }
        b.btnFastForward.setOnClickListener { 
            speedIndex = (speedIndex + 1) % speedSteps.size
            engine.setSpeed(currentSpeed)
            b.btnFastForward.text = getString(R.string.ff_format, currentSpeed)
            vibrateLight()
        }
        b.btnImport.setOnClickListener { openRomPicker() }
        b.btnSave.setOnClickListener {
            val title = currentGameTitle ?: return@setOnClickListener
            
            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setTitle(getString(R.string.save_dialog_title))
                .setMessage(getString(R.string.save_dialog_message))
                .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                    engine.saveState()?.let { state ->
                        try {
                            val dir = engine.getPersistentSaveDir()
                            val file = File(dir, "${title}.state")
                            FileOutputStream(file).use { it.write(state) }
                            toast(getString(R.string.game_saved))
                        } catch (e: Exception) {
                            toast(getString(R.string.save_failed))
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel_button), null)
                .show()
        }
        b.btnLoadState.setOnClickListener {
            val title = currentGameTitle ?: return@setOnClickListener
            val dir = engine.getPersistentSaveDir()
            val file = File(dir, "${title}.state")
            if (file.exists()) {
                val state = file.readBytes()
                if (engine.loadState(state)) {
                    toast(getString(R.string.game_loaded))
                } else {
                    toast(getString(R.string.load_failed))
                }
            } else {
                toast(getString(R.string.no_save_found))
            }
        }
        updateCheatButton()
        b.btnCheat.setOnClickListener { showCheatDialog() }

        setupDpadSlide()
        hold(b.btnA, GbaButton.A); hold(b.btnB, GbaButton.B)
        hold(b.btnSelect, GbaButton.SELECT); hold(b.btnStart, GbaButton.START)

        // D-pad children must NOT be clickable — parent intercepts all touches for slide detection
        b.dpad.let { group ->
            for (i in 0 until group.childCount) {
                group.getChildAt(i).isClickable = false
                group.getChildAt(i).isFocusable = false
            }
        }
        b.abCluster.let { group ->
            for (i in 0 until group.childCount) {
                group.getChildAt(i).isClickable = true
                group.getChildAt(i).isFocusable = true
            }
        }
    }

    private fun hold(v: View, btn: GbaButton) {
        v.setOnTouchListener { view, ev ->
            if (isEditMode) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { engine.setKey(btn, true); view.isPressed = true; vibrateLight(); true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { engine.setKey(btn, false); view.isPressed = false; true }
                else -> false
            }
        }
    }

    private fun setupDpadSlide() {
        val dpadButtons = listOf(
            b.btnUp    to GbaButton.UP,
            b.btnDown  to GbaButton.DOWN,
            b.btnLeft  to GbaButton.LEFT,
            b.btnRight to GbaButton.RIGHT
        )
        var activeBtn: GbaButton? = null

        b.dpad.setOnTouchListener { _, ev ->
            if (isEditMode) return@setOnTouchListener false
            val x = ev.x
            val y = ev.y

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    var hit: GbaButton? = null
                    for ((view, btn) in dpadButtons) {
                        if (x >= view.left && x <= view.right && y >= view.top && y <= view.bottom) {
                            hit = btn
                            break
                        }
                    }
                    if (hit != activeBtn) {
                        activeBtn?.let { engine.setKey(it, false) }
                        hit?.let { engine.setKey(it, true); vibrateLight() }
                        for ((view, btn) in dpadButtons) { view.isPressed = btn == hit }
                        activeBtn = hit
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeBtn?.let { engine.setKey(it, false) }
                    activeBtn = null
                    for ((view, _) in dpadButtons) { view.isPressed = false }
                    true
                }
                else -> false
            }
        }
    }

    private fun handleSuccessfulPayment() {
        b.solanaStatusText?.text = getString(R.string.transaction_success_status)

        val state = walletManager.walletState.value
        if (state is SolanaWalletManager.WalletState.Connected) {
            val walletAddress = state.publicKey
            isLicensed = true
            updateLicensedUI(walletAddress)
        }

        toast(getString(R.string.purchase_success))
    }

    private fun setupLayoutEditor() {
        // Deaktiviert: Layout-Editor wird nur über Settings gestartet (User-Wunsch)
    }

    private fun startEditMode() {
        isEditMode = true
        b.btnSaveLayout.visibility = View.VISIBLE
        val draggableViews = listOf(b.dpad, b.abCluster, b.btnSelect, b.btnStart, b.btnSettingsMain)
        draggableViews.forEach { enableDrag(it) }
        toast(getString(R.string.layout_editor_active))
    }

    private fun enableDrag(view: View) {
        view.setOnTouchListener { v, event ->
            if (!isEditMode) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.tag = floatArrayOf(v.x - event.rawX, v.y - event.rawY)
                    v.animate().alpha(0.5f).setDuration(100).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val offsets = v.tag as? FloatArray ?: return@setOnTouchListener false
                    v.x = event.rawX + offsets[0]
                    v.y = event.rawY + offsets[1]
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().alpha(1.0f).setDuration(100).start()
                }
            }
            true
        }
        
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                child.isClickable = false
                child.isFocusable = false
                child.setOnTouchListener(null)
            }
        }
    }

    private fun showSettings() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this, R.style.CustomBottomSheetDialog)
        
        val container = android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_bottom_sheet)
        }

        // Add Shooting Stars background
        val starsBackground = com.skrgba.seeker.ui.ShootingStarsView(this)
        container.addView(starsBackground, android.widget.FrameLayout.LayoutParams(-1, -1))

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 16, 0, 48)
        }
        container.addView(root)

        // Header Handle
        root.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(40 * resources.displayMetrics.density.toInt(), 4 * resources.displayMetrics.density.toInt()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 8, 0, 24)
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 100f
                setColor(0xFF333344.toInt())
            }
        })

        root.addView(android.widget.TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 14f
            letterSpacing = 0.2f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF888899.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        fun addItem(label: String, icon: String, onClick: () -> Unit) {
            val itemContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(32 * resources.displayMetrics.density.toInt(), 20 * resources.displayMetrics.density.toInt(), 32 * resources.displayMetrics.density.toInt(), 20 * resources.displayMetrics.density.toInt())
                isClickable = true
                isFocusable = true
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, R.drawable.selector_settings_item)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setOnClickListener { 
                    vibrateLight()
                    onClick()
                    dialog.dismiss() 
                }
            }

            val iconText = android.widget.TextView(this).apply {
                text = icon
                textSize = 22f
                setPadding(0, 0, 24 * resources.displayMetrics.density.toInt(), 0)
            }

            val labelText = android.widget.TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(0xFFEEEEFF.toInt())
                setTypeface(null, android.graphics.Typeface.NORMAL)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
            }

            val arrowText = android.widget.TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(0xFF555566.toInt())
            }

            itemContainer.addView(iconText)
            itemContainer.addView(labelText)
            itemContainer.addView(arrowText)
            root.addView(itemContainer)
            
            // Divider
            root.addView(android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1).apply {
                    setMargins(32 * resources.displayMetrics.density.toInt(), 0, 32 * resources.displayMetrics.density.toInt(), 0)
                }
                setBackgroundColor(0xFF1F1F2A.toInt())
            })
        }

        addItem(getString(R.string.edit_layout), "🎮") { startEditMode() }
        addItem(getString(R.string.reset_layout), "🔄") {
            getSharedPreferences("layout_v5", MODE_PRIVATE).edit().clear().apply()
            toast(getString(R.string.reset_toast))
        }
        addItem(getString(R.string.backup_saves), "💾") {
            val name = "SKRGBA-saves-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"
            exportBackup.launch(name)
        }
        addItem(getString(R.string.restore_saves), "📥") {
            importBackup.launch(arrayOf("application/zip","*/*"))
        }
        addItem(getString(R.string.website), "🌐") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://skrgba.xyz/"))) }
        addItem(getString(R.string.terms), "📜") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://skrgba.xyz/terms.html"))) }
        addItem(getString(R.string.privacy_policy), "🛡️") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://skrgba.xyz/privacy.html"))) }

        dialog.setContentView(container)
        dialog.show()
    }

    private fun showCheatDialog() {
        val walletKey = (walletManager.walletState.value as? SolanaWalletManager.WalletState.Connected)?.publicKey ?: ""
        if (!LicenseStorage.isCheatUnlocked(this, walletKey) && !SolanaConfig.isWhitelisted(walletKey)) {
            if (walletKey.isEmpty()) {
                toast(getString(R.string.connect_wallet))
                return
            }
            // Sofort Zahlung starten — kein Dialog dazwischen
            b.solanaStatusText?.text = getString(R.string.cheat_unlocking)
            walletManager.buyCheatLicense { success ->
                if (success) {
                    toast(getString(R.string.cheat_unlock_success))
                    b.solanaStatusText?.text = getString(R.string.license_active_status,
                        (walletManager.walletState.value as? SolanaWalletManager.WalletState.Connected)
                            ?.publicKey?.let { "${it.take(6)}...${it.takeLast(4)}" } ?: "")
                    updateCheatButton()
                    showCheatInput()
                } else {
                    toast(getString(R.string.cheat_unlock_failed))
                    b.solanaStatusText?.text = getString(R.string.purchase_failed_status)
                }
            }
            return
        }
        showCheatInput()
    }

    private fun showCheatInput() {
        val gameTitle = currentGameTitle
        if (gameTitle == null) {
            toast(getString(R.string.cheat_no_rom_loaded))
            return
        }
        showCheatManager(gameTitle)
    }

    private fun showCheatManager(gameTitle: String) {
        val px = resources.displayMetrics.density
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((20 * px).toInt(), (20 * px).toInt(), (20 * px).toInt(), (20 * px).toInt())
            setBackgroundColor(0xFF0D0D14.toInt())
        }

        val title = android.widget.TextView(this).apply {
            text = cleanDisplayTitle(gameTitle).uppercase()
            textSize = 12f
            letterSpacing = 0.2f
            setTextColor(0xFF888899.toInt())
            setPadding(0, 0, 0, (16 * px).toInt())
        }
        container.addView(title)

        val listContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        container.addView(listContainer)

        fun renderList() {
            listContainer.removeAllViews()
            val cheats = com.skrgba.seeker.emulator.CheatStorage.load(this, gameTitle)
            if (cheats.isEmpty()) {
                listContainer.addView(android.widget.TextView(this).apply {
                    text = getString(R.string.cheat_none_active)
                    textSize = 14f
                    setTextColor(0xFF555566.toInt())
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, (24 * px).toInt(), 0, (24 * px).toInt())
                })
            } else {
                cheats.forEach { cheat ->
                    val row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((12 * px).toInt(), (12 * px).toInt(), (12 * px).toInt(), (12 * px).toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 12 * px
                            setColor(0xFF1A1A26.toInt())
                        }
                        val lp = android.widget.LinearLayout.LayoutParams(-1, -2)
                        lp.setMargins(0, 0, 0, (8 * px).toInt())
                        layoutParams = lp
                    }

                    val text = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f)
                    }
                    text.addView(android.widget.TextView(this).apply {
                        this.text = cheat.label
                        textSize = 15f
                        setTextColor(0xFFEEEEFF.toInt())
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    text.addView(android.widget.TextView(this).apply {
                        this.text = cheat.code
                        textSize = 11f
                        setTextColor(0xFF888899.toInt())
                        typeface = android.graphics.Typeface.MONOSPACE
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                    row.addView(text)

                    val toggle = android.widget.Switch(this).apply {
                        isChecked = cheat.enabled
                        setOnCheckedChangeListener { _, on ->
                            com.skrgba.seeker.emulator.CheatStorage.toggle(this@MainActivity, gameTitle, cheat.slot, on)
                            engine.setCheat(cheat.slot, on, cheat.code)
                            vibrateLight()
                        }
                    }
                    row.addView(toggle)

                    val delete = android.widget.TextView(this).apply {
                        this.text = "✕"
                        textSize = 20f
                        setTextColor(0xFFFF5577.toInt())
                        setPadding((16 * px).toInt(), 0, (4 * px).toInt(), 0)
                        setOnClickListener {
                            engine.setCheat(cheat.slot, false, cheat.code)
                            com.skrgba.seeker.emulator.CheatStorage.remove(this@MainActivity, gameTitle, cheat.slot)
                            renderList()
                            vibrateLight()
                        }
                    }
                    row.addView(delete)

                    listContainer.addView(row)
                }
            }
        }
        renderList()

        // Add new cheat section
        container.addView(android.view.View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(-1, 1).apply {
                setMargins(0, (16 * px).toInt(), 0, (16 * px).toInt())
            }
            setBackgroundColor(0xFF1F1F2A.toInt())
        })

        val addLabel = android.widget.TextView(this).apply {
            text = getString(R.string.cheat_add_new)
            textSize = 11f
            letterSpacing = 0.18f
            setTextColor(0xFF888899.toInt())
            setPadding(0, 0, 0, (8 * px).toInt())
        }
        container.addView(addLabel)

        val nameInput = android.widget.EditText(this).apply {
            hint = getString(R.string.cheat_name_hint)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFF555566.toInt())
            textSize = 14f
        }
        container.addView(nameInput)

        val codeInput = android.widget.EditText(this).apply {
            hint = getString(R.string.cheat_code_hint)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFF555566.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            isSingleLine = false
            textSize = 13f
        }
        container.addView(codeInput)

        val scrollView = android.widget.ScrollView(this).apply { addView(container) }

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle(getString(R.string.cheat_manager_title))
            .setView(scrollView)
            .setPositiveButton(getString(R.string.cheat_add_button)) { _, _ ->
                val name = nameInput.text.toString().trim()
                val code = codeInput.text.toString().trim()
                if (code.isNotEmpty()) {
                    val newCheat = com.skrgba.seeker.emulator.CheatStorage.add(this, gameTitle, name, code)
                    engine.setCheat(newCheat.slot, true, newCheat.code)
                    toast(getString(R.string.cheat_activated))
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .create()
        dialog.show()
    }

    /**
     * Re-applies all enabled cheats for the given game title to the emulator
     * core. Called after a ROM is loaded.
     */
    private fun reapplyCheatsForGame(gameTitle: String) {
        val cheats = com.skrgba.seeker.emulator.CheatStorage.load(this, gameTitle)
        cheats.forEach { engine.setCheat(it.slot, it.enabled, it.code) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun vibrateLight() { try { vibrator.vibrate(VibrationEffect.createOneShot(30, 100)) } catch (_: Exception) {} }
}
