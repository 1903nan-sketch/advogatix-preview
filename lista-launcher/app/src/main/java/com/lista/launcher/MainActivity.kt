package com.lista.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.SearchManager
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var launcherApps: LauncherApps
    private lateinit var imm: InputMethodManager

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rawApps: List<RawApp> = emptyList()
    private var allApps: List<AppEntry> = emptyList()
    private var drawerApps: List<AppEntry> = emptyList()
    private var byKey: Map<String, AppEntry> = emptyMap()

    // Views
    private lateinit var root: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var favoritesView: LinearLayout
    private lateinit var homeEmpty: TextView
    private lateinit var defaultBanner: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var clearSearch: ImageView
    private lateinit var appList: ListView
    private lateinit var webSearch: TextView
    private lateinit var scrubber: AlphabetScrubber
    private lateinit var letterBubble: TextView
    private lateinit var adapter: AppListAdapter

    private var drawerOpen = false
    private var imeVisible = false
    private var backCallback: Any? = null

    private lateinit var gestures: GestureDetector
    private var downListAtTop = false
    private var downOnScrubber = false

    private val homePad = Rect()
    private val drawerPad = Rect()

    private val reloadRunnable = Runnable { loadAppsAsync() }

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            scheduleReload()

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            scheduleReload()
    }

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        repo = AppRepository(this)
        launcherApps = getSystemService(LauncherApps::class.java)!!
        imm = getSystemService(InputMethodManager::class.java)!!

        root = findViewById(R.id.root)
        home = findViewById(R.id.home)
        favoritesView = findViewById(R.id.favorites)
        homeEmpty = findViewById(R.id.homeEmpty)
        defaultBanner = findViewById(R.id.defaultBanner)
        drawer = findViewById(R.id.drawer)
        search = findViewById(R.id.search)
        clearSearch = findViewById(R.id.clearSearch)
        appList = findViewById(R.id.appList)
        webSearch = findViewById(R.id.webSearch)
        scrubber = findViewById(R.id.scrubber)
        letterBubble = findViewById(R.id.letterBubble)

        setupWindow()
        setupHome()
        setupDrawer()
        gestures = GestureDetector(this, SwipeListener())

        launcherApps.registerCallback(launcherCallback, mainHandler)
        loadAppsAsync()
    }

    override fun onResume() {
        super.onResume()
        defaultBanner.visibility = if (isDefaultHome()) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        // Ao abrir um app (ou apagar a tela), volta para a tela inicial limpa.
        closeDrawer(animate = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Botão "início" pressionado com o launcher já aberto.
        if (drawerOpen) closeDrawer(animate = true)
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(launcherCallback)
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Usado apenas no Android 12 ou anterior")
    override fun onBackPressed() {
        // Na tela inicial, "voltar" não faz nada (comportamento padrão de launcher).
        if (drawerOpen) closeDrawer(animate = true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME_ROLE && resultCode != RESULT_OK && !isDefaultHome()) {
            openHomeSettings()
        }
    }

    // ------------------------------------------------------------------ janela / insets

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        homePad.set(home.paddingLeft, home.paddingTop, home.paddingRight, home.paddingBottom)
        drawerPad.set(drawer.paddingLeft, drawer.paddingTop, drawer.paddingRight, drawer.paddingBottom)

        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            val ime: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                imeVisible = insets.isVisible(WindowInsets.Type.ime())
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.stableInsetBottom
                ime = insets.systemWindowInsetBottom
                imeVisible = ime > bottom + dp(80)
            }
            home.setPadding(homePad.left, homePad.top + top, homePad.right, homePad.bottom + bottom)
            drawer.setPadding(drawerPad.left, drawerPad.top + top, drawerPad.right, drawerPad.bottom + max(bottom, ime))
            insets
        }
    }

    // ------------------------------------------------------------------ tela inicial

    private fun setupHome() {
        val clock = findViewById<TextClock>(R.id.clock)
        val date = findViewById<TextClock>(R.id.date)
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
        date.format12Hour = pattern
        date.format24Hour = pattern

        clock.setOnClickListener { safeStart(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
        date.setOnClickListener {
            safeStart(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        }
        defaultBanner.setOnClickListener { requestDefaultHome() }
        findViewById<View>(R.id.openDrawer).setOnClickListener { openDrawer() }
        home.setOnLongClickListener { showSettings(); true }
    }

    private fun renderFavorites() {
        favoritesView.removeAllViews()
        val favs = prefs.favorites.mapNotNull { byKey[it] }
        homeEmpty.visibility = if (favs.isEmpty() && rawApps.isNotEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (app in favs) {
            val tv = inflater.inflate(R.layout.item_home_app, favoritesView, false) as TextView
            tv.text = app.label
            tv.setOnClickListener { launch(app, it) }
            tv.setOnLongClickListener { showFavoriteMenu(app); true }
            favoritesView.addView(tv)
        }
    }

    // ------------------------------------------------------------------ lista de apps

    private fun setupDrawer() {
        adapter = AppListAdapter(LayoutInflater.from(this))
        appList.adapter = adapter
        appList.setOnItemClickListener { _, view, pos, _ -> launch(adapter.getItem(pos), view) }
        appList.setOnItemLongClickListener { _, _, pos, _ -> showAppMenu(adapter.getItem(pos)); true }
        appList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
            }

            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = Unit
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyFilter()
                appList.setSelection(0)
            }
        })
        search.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                launchTopResult(); true
            } else {
                false
            }
        }
        clearSearch.setOnClickListener { search.setText("") }
        webSearch.setOnClickListener { searchWeb(search.text.toString()) }

        scrubber.onLetter = { letter, position ->
            appList.setSelection(position)
            letterBubble.animate().cancel()
            letterBubble.alpha = 1f
            letterBubble.text = letter.toString()
            letterBubble.visibility = View.VISIBLE
            hideKeyboard()
        }
        scrubber.onRelease = {
            letterBubble.animate().alpha(0f).setDuration(250).withEndAction {
                letterBubble.visibility = View.GONE
            }.start()
        }
    }

    private fun applyFilter() {
        val query = search.text.toString()
        val searching = query.isNotBlank()
        val results = if (searching) AppSearch.filter(drawerApps, query) else drawerApps
        adapter.submit(results)
        clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        scrubber.visibility = if (!searching && results.size > 12) View.VISIBLE else View.GONE
        if (searching && results.isEmpty()) {
            webSearch.text = getString(R.string.search_web, query.trim())
            webSearch.visibility = View.VISIBLE
        } else {
            webSearch.visibility = View.GONE
        }
    }

    private fun launchTopResult() {
        if (adapter.count > 0) {
            launch(adapter.getItem(0), appList.getChildAt(0))
        } else if (search.text.isNotBlank()) {
            searchWeb(search.text.toString())
        }
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        setBackCallback(true)
        appList.setSelection(0)
        drawer.visibility = View.VISIBLE
        drawer.animate().cancel()
        drawer.alpha = 0f
        drawer.translationY = dp(56).toFloat()
        drawer.animate().alpha(1f).translationY(0f).setDuration(220)
            .setInterpolator(DecelerateInterpolator()).start()
        home.animate().cancel()
        home.animate().alpha(0f).setDuration(160).withEndAction {
            if (drawerOpen) home.visibility = View.INVISIBLE
        }.start()
        if (prefs.autoKeyboard) showKeyboard()
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        setBackCallback(false)
        hideKeyboard()
        if (search.text.isNotEmpty()) search.setText("")
        home.visibility = View.VISIBLE
        drawer.animate().cancel()
        home.animate().cancel()
        if (animate) {
            home.animate().alpha(1f).setDuration(200).start()
            drawer.animate().alpha(0f).translationY(dp(56).toFloat()).setDuration(180).withEndAction {
                if (!drawerOpen) drawer.visibility = View.GONE
            }.start()
        } else {
            home.alpha = 1f
            drawer.visibility = View.GONE
        }
    }

    private fun setBackCallback(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        if (enabled && backCallback == null) {
            val cb = OnBackInvokedCallback { closeDrawer(animate = true) }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (!enabled && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    private fun showKeyboard() {
        search.requestFocus()
        search.postDelayed({ imm.showSoftInput(search, 0) }, 120)
    }

    private fun hideKeyboard() {
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // ------------------------------------------------------------------ gestos

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downListAtTop = !appList.canScrollVertically(-1)
            downOnScrubber = scrubber.isShown && isInside(scrubber, ev)
        }
        val handled = gestures.onTouchEvent(ev)
        if (handled && ev.actionMasked == MotionEvent.ACTION_UP) {
            // Gesto consumido: cancela o toque para não "clicar" num app sem querer.
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private inner class SwipeListener : GestureDetector.SimpleOnGestureListener() {
        private val minVelocity = ViewConfiguration.get(this@MainActivity).scaledMinimumFlingVelocity * 2

        override fun onDown(e: MotionEvent) = false

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dy = e2.y - start.y
            val dx = e2.x - start.x
            if (abs(dy) < dp(56) || abs(dy) < abs(dx) * 1.3f || abs(velocityY) < minVelocity) return false

            if (!drawerOpen) {
                if (dy < 0) openDrawer() else expandNotifications()
                return true
            }
            if (dy > 0 && downListAtTop && !downOnScrubber) {
                if (imeVisible) hideKeyboard() else closeDrawer(animate = true)
                return true
            }
            return false
        }
    }

    private fun isInside(view: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        try {
            val service = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(service)
        } catch (_: Throwable) {
            // Alguns aparelhos bloqueiam: apenas ignora.
        }
    }

    // ------------------------------------------------------------------ carregar apps

    private fun scheduleReload() {
        mainHandler.removeCallbacks(reloadRunnable)
        mainHandler.postDelayed(reloadRunnable, 300)
    }

    private fun loadAppsAsync() {
        executor.execute {
            val raw = try {
                repo.loadRaw()
            } catch (_: Exception) {
                null
            }
            if (raw != null) mainHandler.post { if (!isDestroyed) onAppsLoaded(raw) }
        }
    }

    private fun onAppsLoaded(raw: List<RawApp>) {
        rawApps = raw
        rebuild()
    }

    /** Recalcula a lista a partir de rawApps + preferências (renomear/ocultar não recarregam do sistema). */
    private fun rebuild() {
        allApps = repo.buildEntries(rawApps, prefs.customNames())
        byKey = allApps.associateBy { it.key }

        // Se a activity de um favorito mudou (atualização do app), reaproveita pelo pacote.
        if (rawApps.isNotEmpty()) {
            val fixed = prefs.favorites.map { key -> resolveKey(key)?.key ?: key }.distinct()
            if (fixed != prefs.favorites) prefs.favorites = fixed
            if (!prefs.initialized) {
                prefs.favorites = defaultFavorites()
                prefs.initialized = true
            }
        }

        val hidden = prefs.hidden
        drawerApps = allApps.filter { it.key !in hidden }
        val sections = ArrayList<Pair<Char, Int>>()
        val seen = HashSet<Char>()
        drawerApps.forEachIndexed { i, app -> if (seen.add(app.section)) sections += app.section to i }
        scrubber.setSections(sections)

        renderFavorites()
        applyFilter()
    }

    private fun resolveKey(key: String): AppEntry? {
        byKey[key]?.let { return it }
        val pkg = key.substringBefore('/')
        val serial = key.substringAfterLast('#')
        return allApps.firstOrNull { it.packageName == pkg && it.key.substringAfterLast('#') == serial }
    }

    /** Primeira execução: telefone, WhatsApp, mensagens, câmera e navegador, se existirem. */
    private fun defaultFavorites(): List<String> {
        val pm = packageManager
        fun handler(intent: Intent): String? = try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }

        val sms = try {
            Telephony.Sms.getDefaultSmsPackage(this)
        } catch (_: Exception) {
            null
        }
        val packages = listOf(
            handler(Intent(Intent.ACTION_DIAL)),
            "com.whatsapp",
            sms,
            handler(Intent(MediaStore.ACTION_IMAGE_CAPTURE)),
            handler(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))),
        )
        return packages.filterNotNull().filter { it != "android" }.distinct()
            .mapNotNull { pkg -> allApps.firstOrNull { it.packageName == pkg && it.raw.isMainProfile }?.key }
            .take(MAX_FAVORITES)
    }

    // ------------------------------------------------------------------ ações

    private fun launch(app: AppEntry, view: View?) {
        try {
            var bounds: Rect? = null
            var opts: Bundle? = null
            if (view != null && view.width > 0) {
                val loc = IntArray(2)
                view.getLocationOnScreen(loc)
                bounds = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
                opts = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
            }
            launcherApps.startMainActivity(app.raw.component, app.raw.user, bounds, opts)
        } catch (_: Exception) {
            toast(getString(R.string.toast_cant_open) + " " + app.label)
            scheduleReload()
        }
    }

    private fun searchWeb(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val intent = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            toast(getString(R.string.toast_cant_open))
        }
    }

    private fun addFavorite(app: AppEntry) {
        val favs = prefs.favorites.filter { byKey.containsKey(it) }.toMutableList()
        if (app.key in favs) return
        if (favs.size >= MAX_FAVORITES) {
            toast(getString(R.string.toast_max, MAX_FAVORITES))
            return
        }
        favs += app.key
        prefs.favorites = favs
        renderFavorites()
        toast(getString(R.string.toast_added))
    }

    private fun removeFavorite(app: AppEntry) {
        prefs.favorites = prefs.favorites - app.key
        renderFavorites()
    }

    private fun moveFavorite(app: AppEntry, delta: Int) {
        val favs = prefs.favorites.toMutableList()
        val i = favs.indexOf(app.key)
        val j = i + delta
        if (i < 0 || j !in favs.indices) return
        favs[i] = favs[j].also { favs[j] = favs[i] }
        prefs.favorites = favs
        renderFavorites()
    }

    private fun hideApp(app: AppEntry) {
        prefs.hidden = prefs.hidden + app.key
        rebuild()
        toast(getString(R.string.toast_hidden))
    }

    private fun openAppInfo(app: AppEntry) {
        try {
            launcherApps.startAppDetailsActivity(app.raw.component, app.raw.user, null, null)
        } catch (_: Exception) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)))
        }
    }

    private fun uninstall(app: AppEntry) {
        safeStart(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
    }

    private fun shortcutsFor(app: AppEntry): List<ShortcutInfo> = try {
        if (!launcherApps.hasShortcutHostPermission()) {
            emptyList()
        } else {
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.raw.component)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
                )
            launcherApps.getShortcuts(query, app.raw.user).orEmpty()
                .filter { it.isEnabled && !it.shortLabel.isNullOrBlank() }
                .sortedWith(compareBy({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
                .take(4)
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun shortcutActions(app: AppEntry): List<Pair<CharSequence, () -> Unit>> =
        shortcutsFor(app).map { s ->
            ("›  " + s.shortLabel) to {
                try {
                    launcherApps.startShortcut(s, null, null)
                } catch (_: Exception) {
                    toast(getString(R.string.toast_cant_open))
                }
            }
        }

    // ------------------------------------------------------------------ menus

    private fun showMenu(title: CharSequence, actions: List<Pair<CharSequence, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun showAppMenu(app: AppEntry) {
        val actions = ArrayList<Pair<CharSequence, () -> Unit>>()
        actions += shortcutActions(app)
        actions += if (app.key in prefs.favorites) {
            getString(R.string.menu_remove_home) to { removeFavorite(app) }
        } else {
            getString(R.string.menu_add_home) to { addFavorite(app) }
        }
        actions += getString(R.string.menu_rename) to { showRename(app) }
        actions += getString(R.string.menu_hide) to { hideApp(app) }
        actions += getString(R.string.menu_info) to { openAppInfo(app) }
        if (!app.raw.isSystem && app.raw.isMainProfile) {
            actions += getString(R.string.menu_uninstall) to { uninstall(app) }
        }
        showMenu(app.label, actions)
    }

    private fun showFavoriteMenu(app: AppEntry) {
        val favs = prefs.favorites.filter { byKey.containsKey(it) }
        val index = favs.indexOf(app.key)
        val actions = ArrayList<Pair<CharSequence, () -> Unit>>()
        actions += shortcutActions(app)
        if (index > 0) actions += getString(R.string.menu_move_up) to { moveFavorite(app, -1) }
        if (index in 0 until favs.size - 1) actions += getString(R.string.menu_move_down) to { moveFavorite(app, +1) }
        actions += getString(R.string.menu_rename) to { showRename(app) }
        actions += getString(R.string.menu_remove_home) to { removeFavorite(app) }
        actions += getString(R.string.menu_info) to { openAppInfo(app) }
        showMenu(app.label, actions)
    }

    private fun showRename(app: AppEntry) {
        val input = EditText(this).apply {
            setText(app.label)
            setSelection(text.length)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val save = {
            val name = input.text.toString().trim()
            prefs.setCustomName(app.key, name.takeIf { it.isNotEmpty() && it != app.raw.label })
            rebuild()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNeutralButton(R.string.restore) { _, _ -> prefs.setCustomName(app.key, null); rebuild() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, _, _ -> save(); dialog.dismiss(); true }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showSettings() {
        val actions = ArrayList<Pair<CharSequence, () -> Unit>>()
        actions += getString(R.string.settings_hidden, prefs.hidden.count { byKey.containsKey(it) }) to { showHiddenApps() }
        actions += getString(
            if (prefs.autoKeyboard) R.string.settings_keyboard_on else R.string.settings_keyboard_off,
        ) to { prefs.autoKeyboard = !prefs.autoKeyboard }
        actions += getString(R.string.settings_wallpaper) to {
            safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.settings_wallpaper)))
        }
        if (!isDefaultHome()) actions += getString(R.string.settings_default) to { requestDefaultHome() }
        actions += getString(R.string.settings_android) to { safeStart(Intent(Settings.ACTION_SETTINGS)) }
        showMenu(getString(R.string.settings_title), actions)
    }

    private fun showHiddenApps() {
        val hiddenApps = allApps.filter { it.key in prefs.hidden }
        if (hiddenApps.isEmpty()) {
            toast(getString(R.string.hidden_none))
            return
        }
        val checked = BooleanArray(hiddenApps.size) { true }
        AlertDialog.Builder(this)
            .setTitle(R.string.hidden_title)
            .setMultiChoiceItems(hiddenApps.map { it.label }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val unhide = hiddenApps.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet()
                prefs.hidden = prefs.hidden - unhide
                rebuild()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ launcher padrão

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE)
                    return
                } catch (_: Exception) {
                    // cai para as configurações abaixo
                }
            }
        }
        openHomeSettings()
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            safeStart(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // ------------------------------------------------------------------ utilitários

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_FAVORITES = 8
        const val REQ_HOME_ROLE = 42
    }
}
