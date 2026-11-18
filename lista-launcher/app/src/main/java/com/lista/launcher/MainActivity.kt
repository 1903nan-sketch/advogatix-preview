package com.lista.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
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
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
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
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var launcherApps: LauncherApps
    private lateinit var imm: InputMethodManager
    private lateinit var sheet: Sheet

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rawApps: List<RawApp> = emptyList()
    private var allApps: List<AppEntry> = emptyList()
    private var drawerApps: List<AppEntry> = emptyList()
    private var byKey: Map<String, AppEntry> = emptyMap()
    private var sections: List<Pair<Char, Int>> = emptyList()

    // Views
    private lateinit var root: FrameLayout
    private lateinit var homeLayer: FrameLayout
    private lateinit var home: LinearLayout
    private lateinit var homeEdge: View
    private lateinit var edgePill: View
    private lateinit var favoritesView: LinearLayout
    private lateinit var homeEmpty: TextView
    private lateinit var defaultBanner: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var listFrame: FrameLayout
    private lateinit var search: EditText
    private lateinit var clearSearch: ImageView
    private lateinit var appList: ListView
    private lateinit var webSearch: TextView
    private lateinit var scrubber: AlphabetScrubber
    private lateinit var letterBubble: TextView
    private lateinit var adapter: AppListAdapter
    private lateinit var rowsIn: LayoutAnimationController

    private var drawerOpen = false
    private var imeVisible = false
    private var backCallback: Any? = null
    private var edgeEnabled = true

    private lateinit var gestures: GestureDetector
    private var downListAtTop = false
    private var downOnScrubber = false
    /** Toque que começou na borda direita da tela inicial e está rolando a lista. */
    private var edgeScrubbing = false

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
        sheet = Sheet(this)
        edgeEnabled = prefs.edgeScroll

        root = findViewById(R.id.root)
        homeLayer = findViewById(R.id.homeLayer)
        home = findViewById(R.id.home)
        homeEdge = findViewById(R.id.homeEdge)
        edgePill = findViewById(R.id.edgePill)
        favoritesView = findViewById(R.id.favorites)
        homeEmpty = findViewById(R.id.homeEmpty)
        defaultBanner = findViewById(R.id.defaultBanner)
        drawer = findViewById(R.id.drawer)
        listFrame = findViewById(R.id.listFrame)
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

        // Com navegação por gestos, a borda direita também é o gesto de "voltar".
        // Reserva o trecho do meio para a barra de letras (o sistema aceita até 200dp).
        if (Build.VERSION.SDK_INT >= 29) {
            val exclude = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val h = v.height
                val band = min(h, dp(200))
                val zone = min(v.width, dp(EDGE_ZONE_DP))
                v.systemGestureExclusionRects = listOf(Rect(v.width - zone, (h - band) / 2, v.width, (h + band) / 2))
            }
            homeEdge.addOnLayoutChangeListener(exclude)
            scrubber.addOnLayoutChangeListener(exclude)
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
        findViewById<View>(R.id.openDrawer).setOnClickListener { openDrawer(fromEdge = false) }
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
        rowsIn = LayoutAnimationController(AnimationUtils.loadAnimation(this, R.anim.row_in), 0.06f)
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

        scrubber.onScrub = { index, fraction, y -> scrubTo(index, fraction, y) }
        scrubber.onRelease = {
            adapter.activeSection = null
            letterBubble.animate().cancel()
            letterBubble.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(180).withEndAction {
                letterBubble.visibility = View.GONE
            }.start()
        }
    }

    /**
     * Rola a lista acompanhando o dedo: a letra sob o dedo vai para o topo e,
     * dentro de cada letra, a posição do dedo escolhe o app (rolagem contínua).
     */
    private fun scrubTo(index: Int, fraction: Float, y: Float) {
        if (index !in sections.indices || drawerApps.isEmpty()) return
        val (letter, start) = sections[index]
        val end = if (index + 1 < sections.size) sections[index + 1].second else drawerApps.size
        val exact = start + fraction * (end - start)
        val item = exact.toInt().coerceIn(0, drawerApps.size - 1)
        val rowHeight = appList.getChildAt(0)?.height?.takeIf { it > 0 } ?: dp(56)
        appList.setSelectionFromTop(item, -((exact - item) * rowHeight).toInt())
        adapter.activeSection = letter

        if (imeVisible) hideKeyboard()
        val size = dp(68).toFloat()
        letterBubble.text = letter.toString()
        letterBubble.translationY = (y - size / 2f).coerceIn(0f, max(0f, listFrame.height - size))
        if (letterBubble.visibility != View.VISIBLE) {
            letterBubble.animate().cancel()
            letterBubble.alpha = 0f
            letterBubble.scaleX = 0.7f
            letterBubble.scaleY = 0.7f
            letterBubble.visibility = View.VISIBLE
            letterBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        }
    }

    private fun applyFilter() {
        val query = search.text.toString()
        val searching = query.isNotBlank()
        val results = if (searching) AppSearch.filter(drawerApps, query) else drawerApps
        adapter.submit(results, sections = !searching, markFirst = searching && results.isNotEmpty())
        clearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        val showRail = !searching && results.size > MIN_APPS_FOR_RAIL
        scrubber.visibility = if (showRail) View.VISIBLE else View.GONE
        if (searching && results.isEmpty()) {
            webSearch.text = getString(R.string.search_web, query.trim())
            webSearch.visibility = View.VISIBLE
        } else {
            webSearch.visibility = View.GONE
        }
    }

    private fun updateEdge() {
        val show = edgeEnabled && drawerApps.size > MIN_APPS_FOR_RAIL
        edgePill.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun launchTopResult() {
        if (adapter.count > 0) {
            launch(adapter.getItem(0), appList.getChildAt(0))
        } else if (search.text.isNotBlank()) {
            searchWeb(search.text.toString())
        }
    }

    private fun openDrawer(fromEdge: Boolean) {
        if (drawerOpen) return
        drawerOpen = true
        setBackCallback(true)
        drawer.animate().cancel()
        homeLayer.animate().cancel()
        drawer.visibility = View.VISIBLE
        drawer.alpha = 0f
        if (fromEdge) {
            // Aparece na hora: o dedo já está rolando a lista (sem animar as linhas).
            appList.layoutAnimation = null
            drawer.translationY = 0f
            drawer.animate().alpha(1f).setDuration(120).start()
            homeLayer.animate().alpha(0f).setDuration(100).withEndAction {
                if (drawerOpen) homeLayer.visibility = View.INVISIBLE
            }.start()
        } else {
            appList.setSelection(0)
            drawer.translationY = dp(40).toFloat()
            drawer.animate().alpha(1f).translationY(0f).setDuration(260)
                .setInterpolator(DecelerateInterpolator(2f)).start()
            appList.layoutAnimation = rowsIn
            appList.scheduleLayoutAnimation()
            homeLayer.animate().alpha(0f).translationY(-dp(24).toFloat()).setDuration(180).withEndAction {
                if (drawerOpen) homeLayer.visibility = View.INVISIBLE
            }.start()
            if (prefs.autoKeyboard) showKeyboard()
        }
    }

    private fun closeDrawer(animate: Boolean) {
        if (!drawerOpen) return
        drawerOpen = false
        edgeScrubbing = false
        setBackCallback(false)
        hideKeyboard()
        if (search.text.isNotEmpty()) search.setText("")
        adapter.activeSection = null
        letterBubble.visibility = View.GONE
        homeLayer.visibility = View.VISIBLE
        drawer.animate().cancel()
        homeLayer.animate().cancel()
        if (animate) {
            homeLayer.animate().alpha(1f).translationY(0f).setDuration(220).start()
            drawer.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(180).withEndAction {
                if (!drawerOpen) {
                    // INVISIBLE (e não GONE): a lista continua medida, pronta para a borda direita.
                    drawer.visibility = View.INVISIBLE
                    drawer.translationY = 0f
                }
            }.start()
        } else {
            homeLayer.alpha = 1f
            homeLayer.translationY = 0f
            drawer.alpha = 0f
            drawer.translationY = 0f
            drawer.visibility = View.INVISIBLE
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
            edgeScrubbing = !drawerOpen && inEdgeZone(ev)
            if (edgeScrubbing) openDrawer(fromEdge = true)
            downListAtTop = !appList.canScrollVertically(-1)
            downOnScrubber = scrubber.isShown && isInside(scrubber, ev)
        }
        if (edgeScrubbing) {
            // Repassa o arraste da borda da tela inicial direto para a barra de letras.
            forwardToScrubber(ev)
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) edgeScrubbing = false
            return true
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

    private fun inEdgeZone(ev: MotionEvent): Boolean {
        if (!edgeEnabled || scrubber.visibility != View.VISIBLE || scrubber.letters.isEmpty()) return false
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        if (ev.rawX < rootLoc[0] + root.width - dp(EDGE_ZONE_DP)) return false
        val loc = IntArray(2)
        scrubber.getLocationOnScreen(loc)
        return ev.rawY >= loc[1] && ev.rawY <= loc[1] + scrubber.height
    }

    private fun forwardToScrubber(ev: MotionEvent) {
        val loc = IntArray(2)
        scrubber.getLocationOnScreen(loc)
        val e = MotionEvent.obtain(ev)
        e.setLocation(ev.rawX - loc[0], ev.rawY - loc[1])
        scrubber.handleTouch(e, force = true)
        e.recycle()
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
                if (dy < 0) openDrawer(fromEdge = false) else expandNotifications()
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
        testApps?.let {
            onAppsLoaded(it)
            return
        }
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
        val list = ArrayList<Pair<Char, Int>>()
        val seen = HashSet<Char>()
        drawerApps.forEachIndexed { i, app -> if (seen.add(app.section)) list += app.section to i }
        sections = list
        scrubber.setLetters(list.map { it.first })

        renderFavorites()
        applyFilter()
        updateEdge()
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

    /** Atalhos do próprio app (ex.: conversas do WhatsApp), mostrados no topo do menu. */
    private fun shortcutItems(app: AppEntry): List<Sheet.Item> =
        shortcutsFor(app).map { s ->
            Sheet.Item(s.shortLabel!!, group = 0) {
                try {
                    launcherApps.startShortcut(s, null, null)
                } catch (_: Exception) {
                    toast(getString(R.string.toast_cant_open))
                }
            }
        }

    // ------------------------------------------------------------------ menus

    private fun showAppMenu(app: AppEntry) {
        val items = ArrayList(shortcutItems(app))
        items += if (app.key in prefs.favorites) {
            Sheet.Item(getString(R.string.menu_remove_home), group = 1) { removeFavorite(app) }
        } else {
            Sheet.Item(getString(R.string.menu_add_home), group = 1) { addFavorite(app) }
        }
        items += Sheet.Item(getString(R.string.menu_rename), group = 1) { showRename(app) }
        items += Sheet.Item(getString(R.string.menu_hide), group = 1) { hideApp(app) }
        items += Sheet.Item(getString(R.string.menu_info), group = 1) { openAppInfo(app) }
        if (!app.raw.isSystem && app.raw.isMainProfile) {
            items += Sheet.Item(getString(R.string.menu_uninstall), group = 1, danger = true) { uninstall(app) }
        }
        sheet.show(app.label, items)
    }

    private fun showFavoriteMenu(app: AppEntry) {
        val favs = prefs.favorites.filter { byKey.containsKey(it) }
        val index = favs.indexOf(app.key)
        val items = ArrayList(shortcutItems(app))
        if (index > 0) items += Sheet.Item(getString(R.string.menu_move_up), group = 1) { moveFavorite(app, -1) }
        if (index in 0 until favs.size - 1) {
            items += Sheet.Item(getString(R.string.menu_move_down), group = 1) { moveFavorite(app, +1) }
        }
        items += Sheet.Item(getString(R.string.menu_rename), group = 1) { showRename(app) }
        items += Sheet.Item(getString(R.string.menu_remove_home), group = 1) { removeFavorite(app) }
        items += Sheet.Item(getString(R.string.menu_info), group = 1) { openAppInfo(app) }
        sheet.show(app.label, items)
    }

    private fun showRename(app: AppEntry) {
        val renamed = app.label != app.raw.label
        sheet.showInput(
            title = getString(R.string.rename_title) + " · " + app.raw.label,
            initial = app.label,
            primary = getString(R.string.save),
            secondary = if (renamed) getString(R.string.restore) else getString(R.string.cancel),
            onPrimary = { text ->
                val name = text.trim()
                prefs.setCustomName(app.key, name.takeIf { it.isNotEmpty() && it != app.raw.label })
                rebuild()
            },
            onSecondary = {
                if (renamed) {
                    prefs.setCustomName(app.key, null)
                    rebuild()
                }
            },
        )
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    private fun showSettings() {
        val hiddenCount = prefs.hidden.count { byKey.containsKey(it) }
        val items = ArrayList<Sheet.Item>()
        items += Sheet.Item(getString(R.string.settings_edge), detail = yesNo(edgeEnabled), keepOpen = true) { detail ->
            edgeEnabled = !edgeEnabled
            prefs.edgeScroll = edgeEnabled
            detail.text = yesNo(edgeEnabled)
            updateEdge()
        }
        items += Sheet.Item(getString(R.string.settings_keyboard), detail = yesNo(prefs.autoKeyboard), keepOpen = true) { detail ->
            prefs.autoKeyboard = !prefs.autoKeyboard
            detail.text = yesNo(prefs.autoKeyboard)
        }
        items += Sheet.Item(getString(R.string.settings_hidden), detail = hiddenCount.toString()) { showHiddenApps() }
        items += Sheet.Item(getString(R.string.settings_wallpaper), group = 1) {
            safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), getString(R.string.settings_wallpaper)))
        }
        if (!isDefaultHome()) {
            items += Sheet.Item(getString(R.string.settings_default), group = 1) { requestDefaultHome() }
        }
        items += Sheet.Item(getString(R.string.settings_android), group = 1) { safeStart(Intent(Settings.ACTION_SETTINGS)) }
        sheet.show(getString(R.string.settings_title), items)
    }

    private fun showHiddenApps() {
        val hiddenApps = allApps.filter { it.key in prefs.hidden }
        if (hiddenApps.isEmpty()) {
            toast(getString(R.string.hidden_none))
            return
        }
        val items = hiddenApps.map { app ->
            Sheet.Item(app.label, detail = getString(R.string.unhide)) {
                prefs.hidden = prefs.hidden - app.key
                rebuild()
            }
        }
        sheet.show(getString(R.string.hidden_title), items)
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

    companion object {
        private const val MAX_FAVORITES = 8
        private const val REQ_HOME_ROLE = 42
        private const val MIN_APPS_FOR_RAIL = 12
        /** Largura da faixa na borda direita que começa a rolagem. */
        private const val EDGE_ZONE_DP = 36

        /** Só para testes: substitui a leitura dos apps instalados. */
        internal var testApps: List<RawApp>? = null
    }
}
