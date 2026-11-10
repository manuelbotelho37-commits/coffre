package fr.manu.applis

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceResponse
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import androidx.core.content.FileProvider
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * La page de l'appli tourne dans cette appli, pas dans Chrome : ses données
 * (fiches, dépenses, clients) sont rangées dans l'appli elle-même. Un nettoyage
 * de Chrome ne peut pas les effacer ; seule une désinstallation le peut.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var banner: View
    private val bundled = BuildConfig.BUNDLED
    private val startUri = Uri.parse(BuildConfig.START_URL)
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0c0c0e"))
        banner = updateBanner().apply { visibility = View.GONE }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0c0c0e"))
            addView(banner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        if (bundled) {
            // Les requêtes passées par l'ancien « service worker » sont servies, elles aussi, depuis l'appli.
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = local(request.url)
                })
            } catch (_: Exception) {}
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }

        web.addJavascriptInterface(Saver(), "AndroidSave")
        if (bundled) web.addJavascriptInterface(Backup(), "AndroidBackup")
        // Google interdit de se connecter à Gmail dans une appli comme celle-ci :
        // la connexion se fait dans Chrome, puis Chrome renvoie l'autorisation ici.
        if (BuildConfig.AUTH_SCHEME != "frmanuapp") web.addJavascriptInterface(Auth(), "AndroidAuth")
        // Les boutons « Sauvegarder » de la page créent un fichier dans le navigateur :
        // ici on le range dans Téléchargements, comme le faisait Chrome.
        val saveHook = """
            (function(){
              if (window.__androidSaveHook) return; window.__androidSaveHook = true;
              const orig = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                const href = this.href || '';
                if (this.hasAttribute('download') && (href.startsWith('blob:') || href.startsWith('data:'))) {
                  const name = this.getAttribute('download') || 'fichier';
                  fetch(href).then(r => r.blob()).then(b => new Promise((ok, ko) => {
                    const fr = new FileReader(); fr.onload = () => ok([b.type, String(fr.result).split(',')[1] || '']); fr.onerror = ko; fr.readAsDataURL(b);
                  })).then(([type, b64]) => AndroidSave.save(name, type || 'application/octet-stream', b64));
                  return;
                }
                return orig.call(this);
              };
            })();
        """.trimIndent()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, saveHook, setOf("*"))
            if (bundled) WebViewCompat.addDocumentStartJavaScript(web, backupHook, setOf("https://" + startUri.host))
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url
                if (url.host == Uri.parse(BuildConfig.START_URL).host) return false
                // Les autres liens (Claude, Google…) s'ouvrent dans le navigateur.
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(saveHook, null)
                    // (sauvegardes auto seulement si le script peut passer avant la page)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (bundled) local(request.url) else null
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    val intent = params.createIntent()
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    pickFiles.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(BuildConfig.START_URL)
        intent?.let { handleAuth(it) }
    }

    override fun onResume() {
        super.onResume()
        if (bundled) checkUpdate()
    }

    /* ---------- Appli autonome : la page est rangée dans l'appli, plus rien ne vient d'internet ---------- */

    /**
     * L'adresse reste la même qu'avant (même « origine »), donc la page retrouve
     * exactement ses données rangées dans l'appli : rien n'est déplacé.
     */
    private fun local(url: Uri): WebResourceResponse? {
        // Bibliothèques venant de cdnjs (ex. Excel dans Vente) : copie rangée dans l'appli si elle existe.
        if (url.host == "cdnjs.cloudflare.com") {
            return try {
                val p = (url.path ?: "").trimStart('/')
                WebResourceResponse(mime(p), "utf-8", assets.open("www/cdn/$p")).apply { noCache() }
            } catch (e: Exception) { null }
        }
        if (url.host != startUri.host) return null
        val base = startUri.path ?: "/"
        val path = url.path ?: return null
        if (!path.startsWith(base)) return null
        var rest = path.removePrefix(base)
        if (rest.isEmpty() || rest.endsWith("/")) rest += "index.html"
        if (rest == "sw.js") {
            // L'ancien service worker n'est plus utile : il se retire tout seul.
            val js = "self.addEventListener('install',()=>self.skipWaiting());" +
                "self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.map(n=>caches.delete(n)))).then(()=>self.registration.unregister())));"
            return WebResourceResponse("text/javascript", "utf-8", js.byteInputStream()).apply { noCache() }
        }
        return try {
            val stream = assets.open("www/$rest")
            WebResourceResponse(mime(rest), if (mime(rest).startsWith("text/") || rest.endsWith(".js")) "utf-8" else null, stream).apply { noCache() }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), "".byteInputStream())
        }
    }

    private fun WebResourceResponse.noCache() {
        responseHeaders = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "json", "webmanifest" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "mjs" -> "text/javascript"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /* ---------- Sauvegardes automatiques ---------- */

    // Après chaque changement des données de la page, l'appli en reçoit une copie complète.
    private val backupHook = """
        (function(){
          if (window.__androidBackupHook || !window.AndroidBackup) return; window.__androidBackupHook = true;
          // L'ancienne version de la page (gardée par son « service worker ») ne doit plus jamais
          // s'afficher : on retire ce service worker et ses copies, puis on recharge une fois.
          try {
            if (navigator.serviceWorker) {
              // La page est déjà dans l'appli : plus besoin de service worker.
              navigator.serviceWorker.register = () => new Promise(() => {});
              navigator.serviceWorker.getRegistrations().then(rs => {
              if (!rs.length) return;
              if (sessionStorage.getItem('__swNettoye')) return; // une seule fois, jamais en boucle
              sessionStorage.setItem('__swNettoye', '1');
              Promise.all(rs.map(r => r.unregister()))
                .then(() => window.caches ? caches.keys().then(ks => Promise.all(ks.map(k => caches.delete(k)))) : null)
                .then(() => location.reload());
              });
            }
          } catch (e) {}
          // Appli vide à l'ouverture (par exemple après une réinstallation) : on propose de récupérer
          // la sauvegarde, et on n'écrase surtout pas les sauvegardes existantes avec du vide.
          const wasEmpty = localStorage.length === 0;
          let t = null;
          const dump = () => { const o = {}; for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); o[k] = localStorage.getItem(k); } return o; };
          // Si l'appli sait faire sa propre sauvegarde complète (ex. Vente, avec les factures), on prend celle-là.
          const go = async () => {
            try {
              if (typeof buildBackupFile === 'function') { const f = await buildBackupFile(); AndroidBackup.changedPage(await f.text()); }
              else AndroidBackup.changed(JSON.stringify(dump()));
            } catch (e) {}
          };
          const send = () => { if (wasEmpty) return; clearTimeout(t); t = setTimeout(go, 1500); };
          // Une fois par jour, sauvegarde complète avec les fichiers joints, si l'appli sait la faire (ex. Coffre).
          const full = async () => {
            try {
              if (typeof buildFullBlob !== 'function') return;
              const day = new Date().toISOString().slice(0, 10);
              if (localStorage.getItem('__autoComplete') === day) return;
              const { blob } = await buildFullBlob();
              const b64 = await new Promise((ok, ko) => { const fr = new FileReader(); fr.onload = () => ok(String(fr.result).split(',')[1] || ''); fr.onerror = ko; fr.readAsDataURL(blob); });
              if (AndroidBackup.fullFile(b64)) set.call(localStorage, '__autoComplete', day);
            } catch (e) {}
          };
          document.addEventListener('visibilitychange', () => { if (document.hidden && !wasEmpty) { clearTimeout(t); go(); full(); } });
          window.__androidRestore = (txt) => {
            const o = JSON.parse(txt);
            if (o && o.donnees) { localStorage.clear(); for (const k in o.donnees) localStorage.setItem(k, o.donnees[k]); setTimeout(() => location.reload(), 300); return 'ok'; }
            if (o && Array.isArray(o.clients) && typeof confirmRestore === 'function') { __pendingRestore = o; confirmRestore(); return 'ok'; }
            return 'non';
          };
          const S = Storage.prototype, set = S.setItem, rem = S.removeItem, clr = S.clear;
          S.setItem = function(){ const r = set.apply(this, arguments); if (this === localStorage) send(); return r; };
          S.removeItem = function(){ const r = rem.apply(this, arguments); if (this === localStorage) send(); return r; };
          S.clear = function(){ const r = clr.apply(this, arguments); if (this === localStorage) send(); return r; };
          window.addEventListener('load', () => {
            if (wasEmpty) { try { AndroidBackup.empty(); } catch (e) {} } else send();
          });
        })();
    """.trimIndent()

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val data = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.takeIf { AutoBackup.looksValid(it) }
        } catch (e: Exception) { null }
        if (data == null) {
            Toast.makeText(this, "Ce fichier n'est pas une sauvegarde de ${BuildConfig.APK_NAME}", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        web.evaluateJavascript("window.__androidRestore(" + JSONObject.quote(data) + ")") { res ->
            val ok = res?.contains("ok") == true
            Toast.makeText(this, if (ok) "Sauvegarde récupérée" else "Ce fichier n'est pas une sauvegarde de ${BuildConfig.APK_NAME}", Toast.LENGTH_LONG).show()
        }
    }

    inner class Backup {
        @JavascriptInterface
        fun changed(json: String) = AutoBackup.onChange(this@MainActivity, json)

        @JavascriptInterface
        fun changedPage(text: String) = AutoBackup.onChangePage(this@MainActivity, text)

        @JavascriptInterface
        fun fullFile(b64: String): Boolean = AutoBackup.onFullFile(this@MainActivity, b64)

        @JavascriptInterface
        fun empty() {
            val prefs = getSharedPreferences("sauvegardes", Context.MODE_PRIVATE)
            if (prefs.getBoolean("ne-plus-proposer", false)) return
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Récupérer une sauvegarde ?")
                    .setMessage("${BuildConfig.APK_NAME} est vide. Si tu avais des données avant, tu peux les récupérer : choisis le fichier « ${BuildConfig.APK_NAME}-sauvegarde-derniere » dans Téléchargements, dossier ${BuildConfig.APK_NAME}.")
                    .setPositiveButton("Récupérer") { _, _ -> try { pickBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) } catch (_: Exception) {} }
                    .setNegativeButton("Non merci") { _, _ -> prefs.edit().putBoolean("ne-plus-proposer", true).apply() }
                    .show()
            }
        }
    }

    /* ---------- Mise à jour intégrée (comme Tâche) ---------- */
    private var updateReady = false
    private var updateBusy = false
    private var lastUpdateCheck = 0L

    private fun checkUpdate() {
        val now = System.currentTimeMillis()
        if (updateBusy || updateReady || now - lastUpdateCheck < 10 * 60_000L) return
        lastUpdateCheck = now
        val mine = Updater.installedVersion(this)
        Thread {
            val latest = Updater.latestVersion()
            runOnUiThread {
                if (latest != null && latest > mine) {
                    updateReady = true
                    banner.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun startUpdate() {
        if (updateBusy) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Une autorisation, une seule fois")
                .setMessage("Pour se mettre à jour toute seule, ${BuildConfig.APK_NAME} a besoin d'installer ses nouvelles versions. Sur l'écran suivant, active « Autoriser », puis reviens et appuie à nouveau sur « Mettre à jour ».")
                .setPositiveButton("Continuer") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
                }
                .setNegativeButton("Plus tard", null)
                .show()
            return
        }
        updateBusy = true
        Toast.makeText(this, "Téléchargement de la nouvelle version…", Toast.LENGTH_LONG).show()
        Thread {
            val f = Updater.download(this)
            runOnUiThread {
                updateBusy = false
                if (f == null) {
                    Toast.makeText(this, "Téléchargement impossible, vérifie Internet", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                    startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "Installation impossible", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun updateBanner(): View {
        val gold = Color.parseColor("#D4A94E")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D1A14")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), gold)
            }
            setPadding(dp(14), dp(12), dp(10), dp(12))
        }
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(TextView(this).apply {
            text = "Nouvelle version disponible"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#F3D98F"))
        })
        txt.addView(TextView(this).apply {
            text = "Tes données sont gardées."
            textSize = 12f
            setTextColor(Color.parseColor("#CDBF9C"))
        })
        box.addView(txt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(TextView(this).apply {
            text = "Mettre à jour"
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#111113"))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#F7E3A1"), Color.parseColor("#D4A94E"), Color.parseColor("#A87B2C"), Color.parseColor("#ECD08A"))
            ).apply { cornerRadius = dp(10).toFloat() }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { startUpdate() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        return LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(4))
            addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuth(intent)
    }

    private fun handleAuth(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != BuildConfig.AUTH_SCHEME) return
        val t = uri.getQueryParameter("t") ?: return
        val e = (uri.getQueryParameter("e") ?: "3600").toIntOrNull() ?: 3600
        val js = "window.__pendingTok=[${JSONObject.quote(t)},$e,Date.now()];window.__gTok&&window.__gTok(${JSONObject.quote(t)},$e);"
        web.post { web.evaluateJavascript(js, null) }
    }

    inner class Auth {
        @JavascriptInterface
        fun scheme(): String = BuildConfig.AUTH_SCHEME

        @JavascriptInterface
        fun start(url: String) {
            val uri = Uri.parse(url)
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(Intent(i).setPackage("com.android.chrome")) } catch (_: Exception) {
                try { startActivity(i) } catch (_: Exception) {}
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    inner class Saver {
        @JavascriptInterface
        fun save(name: String, mime: String, b64: String) {
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw Exception()
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                runOnUiThread { Toast.makeText(this@MainActivity, "Enregistré dans Téléchargements", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Impossible d'enregistrer le fichier", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
