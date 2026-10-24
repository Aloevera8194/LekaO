package com.aloevera.lekao

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var drawing: DrawingView
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnRedo: MaterialButton
    private lateinit var btnColor: MaterialButton
    private var currentId = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importImage(uri)
    }

    private val palette = intArrayOf(
        Color.parseColor("#000000"), Color.parseColor("#FFFFFF"), Color.parseColor("#808080"),
        Color.parseColor("#E53935"), Color.parseColor("#FB8C00"), Color.parseColor("#FDD835"),
        Color.parseColor("#43A047"), Color.parseColor("#708238"), Color.parseColor("#00897B"),
        Color.parseColor("#1E88E5"), Color.parseColor("#8E24AA"), Color.parseColor("#EC407A"),
        Color.parseColor("#6D4C41"), Color.parseColor("#F1C27D")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val light = resources.getBoolean(R.bool.light_bars)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        drawing = findViewById(R.id.drawing)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        btnColor = findViewById(R.id.btnColor)

        // ---- desenho atual (galeria) ----
        var id = prefs().getString("current", null)
        if (id == null) {
            id = "d" + System.currentTimeMillis()
            val old = File(filesDir, "state.txt")
            if (old.exists()) {
                val dir = dirOf(id)
                dir.mkdirs()
                filesDir.listFiles()?.forEach { f ->
                    if (f.name == "state.txt" || (f.name.startsWith("layer_") && f.name.endsWith(".png"))) {
                        f.renameTo(File(dir, f.name))
                    }
                }
            }
            prefs().edit().putString("current", id).apply()
        }
        currentId = id
        drawing.loadState(dirOf(id))
        drawing.dirtyState = false

        drawing.onHistoryChanged = { refreshHistoryButtons() }
        drawing.onColorPicked = { updateColorButton() }
        drawing.onTextRequested = { x, y -> askText(x, y) }
        refreshHistoryButtons()
        updateColorButton()

        if (!prefs().getBoolean("onboarded", false)) showOnboarding()

        btnUndo.setOnClickListener { drawing.undo() }
        btnRedo.setOnClickListener { drawing.redo() }
        btnColor.setOnClickListener { showColorDialog() }
        findViewById<View>(R.id.btnLayers).setOnClickListener { showLayersDialog() }
        findViewById<View>(R.id.btnSave).setOnClickListener { savePng() }
        findViewById<View>(R.id.btnRotate).setOnClickListener { drawing.rotateView() }
        findViewById<View>(R.id.btnFlip).setOnClickListener { drawing.flipView() }
        findViewById<View>(R.id.btnFit).setOnClickListener { drawing.fitToScreen() }

        findViewById<MaterialButton>(R.id.btnFit).let { fit ->
            val parent = fit.parent as ViewGroup
            val sym = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            sym.text = "Simetria"
            sym.setOnClickListener {
                drawing.symmetry = !drawing.symmetry
                sym.text = if (drawing.symmetry) "Simetria ✓" else "Simetria"
            }
            parent.addView(sym, parent.indexOfChild(fit) + 1)
        }

        findViewById<View>(R.id.btnSave).let { save ->
            val parent = save.parent as ViewGroup
            val res = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            res.text = "Resolução"
            res.setOnClickListener {
                val sizes = intArrayOf(1024, 1600, 2048, 3000)
                val names = sizes.map { "$it x $it" + (if (it == drawing.docSize) "  (atual)" else "") }
                    .toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Resolução do desenho")
                    .setItems(names) { _, which ->
                        val n = sizes[which]
                        if (n != drawing.docSize) {
                            AlertDialog.Builder(this)
                                .setTitle("Mudar para $n x $n?")
                                .setMessage(
                                    "O desenho será redimensionado. Desfazer e refazer serão limpos. " +
                                        "Tamanhos grandes gastam mais memória."
                                )
                                .setPositiveButton("Mudar") { _, _ ->
                                    if (!drawing.resizeDocument(n)) {
                                        Toast.makeText(
                                            this, "Sem memória para esse tamanho", Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
            parent.addView(res, parent.indexOfChild(save))
        }

        findViewById<View>(R.id.btnSave).let { save ->
            val parent = save.parent as ViewGroup
            val flt = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            flt.text = "Filtros"
            flt.setOnClickListener {
                val nomes = arrayOf(
                    "Desfoque", "Mais brilho", "Menos brilho", "Mais contraste",
                    "Preto e branco", "Inverter cores", "Limpar camada"
                )
                AlertDialog.Builder(this)
                    .setTitle("Filtros (camada ativa)")
                    .setItems(nomes) { _, which ->
                        try {
                            drawing.applyFilter(which)
                        } catch (_: OutOfMemoryError) {
                            Toast.makeText(this, "Sem memória", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Fechar", null)
                    .show()
            }
            val grid = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            grid.text = "Grade"
            grid.setOnClickListener {
                drawing.showGrid = !drawing.showGrid
                grid.text = if (drawing.showGrid) "Grade ✓" else "Grade"
                drawing.invalidate()
            }
            parent.addView(flt, parent.indexOfChild(save))
            parent.addView(grid, parent.indexOfChild(save))
        }

        findViewById<MaterialButton>(R.id.btnNew).apply {
            text = "Galeria"
            setOnClickListener { showGalleryDialog() }
        }

        makeIconOnly(R.id.btnRotate, R.drawable.ic_rotate, "Girar 90 graus")
        makeIconOnly(R.id.btnFlip, R.drawable.ic_flip, "Espelhar")
        makeIconOnly(R.id.btnFit, R.drawable.ic_fit, "Ajustar à tela")

        val toolGroup = findViewById<MaterialButtonToggleGroup>(R.id.toolGroup)
        val toolMap = mutableMapOf(
            R.id.btnPencil to DrawingView.Tool.PENCIL,
            R.id.btnPen to DrawingView.Tool.PEN,
            R.id.btnMarker to DrawingView.Tool.MARKER,
            R.id.btnAirbrush to DrawingView.Tool.AIRBRUSH,
            R.id.btnEraser to DrawingView.Tool.ERASER,
            R.id.btnFill to DrawingView.Tool.FILL,
            R.id.btnPicker to DrawingView.Tool.PICKER,
            R.id.btnLine to DrawingView.Tool.LINE,
            R.id.btnRect to DrawingView.Tool.RECT,
            R.id.btnOval to DrawingView.Tool.OVAL
        )

        // ferramentas novas, criadas pelo código (a ordem importa: de trás para frente)
        fun addTool(label: String, t: DrawingView.Tool, index: Int) {
            val b = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            b.id = View.generateViewId()
            b.text = label
            toolMap[b.id] = t
            if (index < 0) toolGroup.addView(b) else toolGroup.addView(b, index)
        }
        addTool("Aquarela", DrawingView.Tool.WATERCOLOR, 4)
        addTool("Nanquim", DrawingView.Tool.INK, 2)
        addTool("Ret. cheio", DrawingView.Tool.RECT_FILL, -1)
        addTool("Elipse cheia", DrawingView.Tool.OVAL_FILL, -1)
        addTool("Texto", DrawingView.Tool.TEXT, -1)

        toolGroup.check(R.id.btnPen)
        toolGroup.addOnButtonCheckedListener { _, bid, checked ->
            if (checked) toolMap[bid]?.let { drawing.tool = it }
        }

        // ---- ferramentas agrupadas (toque abre as opções) ----
        val groups = listOf(
            "Pincéis" to listOf(
                "Lápis" to DrawingView.Tool.PENCIL, "Caneta" to DrawingView.Tool.PEN, "Nanquim" to DrawingView.Tool.INK,
                "Marcador" to DrawingView.Tool.MARKER, "Aerógrafo" to DrawingView.Tool.AIRBRUSH, "Aquarela" to DrawingView.Tool.WATERCOLOR
            ),
            "Borracha" to listOf("Borracha" to DrawingView.Tool.ERASER),
            "Cores" to listOf("Balde" to DrawingView.Tool.FILL, "Conta-gotas" to DrawingView.Tool.PICKER, "Degradê" to DrawingView.Tool.GRADIENT),
            "Formas" to listOf(
                "Linha" to DrawingView.Tool.LINE, "Retângulo" to DrawingView.Tool.RECT, "Elipse" to DrawingView.Tool.OVAL,
                "Ret. cheio" to DrawingView.Tool.RECT_FILL, "Elipse cheia" to DrawingView.Tool.OVAL_FILL
            ),
            "Texto" to listOf("Texto" to DrawingView.Tool.TEXT)
        )
        val groupBtns = mutableListOf<MaterialButton>()
        fun selectTool(gi: Int, label: String, t: DrawingView.Tool) {
            drawing.tool = t
            for (k in groups.indices) {
                val single = groups[k].second.size == 1
                val sel = k == gi
                groupBtns[k].text =
                    if (sel) (if (single) label else "$label ▾")
                    else (if (single) groups[k].first else groups[k].first + " ▾")
                groupBtns[k].strokeWidth = if (sel) dp(3) else dp(1)
            }
        }
        val groupBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((gi, g) in groups.withIndex()) {
            val b = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            )
            b.setOnClickListener {
                if (g.second.size == 1) {
                    selectTool(gi, g.second[0].first, g.second[0].second)
                } else {
                    val pm = android.widget.PopupMenu(this, b)
                    g.second.forEachIndexed { idx, item -> pm.menu.add(0, idx, idx, item.first) }
                    pm.setOnMenuItemClickListener { mi ->
                        val item = g.second[mi.itemId]
                        selectTool(gi, item.first, item.second)
                        true
                    }
                    pm.show()
                }
            }
            groupBtns.add(b)
            groupBar.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })
        }
        val oldScroll = toolGroup.parent as View
        val panel = oldScroll.parent as ViewGroup
        panel.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(groupBar)
            },
            panel.indexOfChild(oldScroll)
        )
        oldScroll.visibility = View.GONE
        selectTool(0, "Caneta", DrawingView.Tool.PEN)

        // tamanho: escala suave de 1 a 300 (fino nos tamanhos pequenos, grande no final)
        val sizeLabel = findViewById<TextView>(R.id.sizeLabel)
        val sizeBar = findViewById<SeekBar>(R.id.sizeBar)
        sizeBar.onChange { p ->
            val f = p / 100f
            val v = 1 + Math.round(299f * f * f)
            drawing.brushSize = v.toFloat()
            sizeLabel.text = "Tamanho: $v"
        }
        sizeBar.max = 100
        sizeBar.progress = 18

        val opacityLabel = findViewById<TextView>(R.id.opacityLabel)
        findViewById<SeekBar>(R.id.opacityBar).onChange { p ->
            val v = p + 1
            drawing.brushOpacity = v
            opacityLabel.text = "Opacidade: $v%"
        }

        val smoothLabel = findViewById<TextView>(R.id.smoothLabel)
        findViewById<SeekBar>(R.id.smoothBar).onChange { p ->
            drawing.smoothing = p / 100f
            smoothLabel.text = "Suavização: $p%"
        }

        val tolLabel = findViewById<TextView>(R.id.tolLabel)
        findViewById<SeekBar>(R.id.tolBar).onChange { p ->
            drawing.fillTolerance = p
            tolLabel.text = "Tolerância: $p"
        }
    }

    override fun onStop() {
        super.onStop()
        persistCurrent()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeIconOnly(id: Int, res: Int, desc: String) {
        val b = findViewById<MaterialButton>(id)
        b.text = ""
        b.setIconResource(res)
        b.iconPadding = 0
        b.iconSize = dp(24)
        b.contentDescription = desc
        b.setPadding(dp(10), b.paddingTop, dp(10), b.paddingBottom)
    }

    private fun refreshHistoryButtons() {
        btnUndo.isEnabled = drawing.canUndo
        btnRedo.isEnabled = drawing.canRedo
    }

    private fun updateColorButton() {
        btnColor.backgroundTintList = ColorStateList.valueOf(drawing.color)
        btnColor.setTextColor(
            if (ColorUtils.calculateLuminance(drawing.color) > 0.5) Color.BLACK else Color.WHITE
        )
    }

    // ------------------------------------------------------------------
    // Galeria de desenhos
    // ------------------------------------------------------------------

    private fun prefs() = getSharedPreferences("lekao", MODE_PRIVATE)

    private fun root(): File = File(filesDir, "drawings").also { it.mkdirs() }

    private fun dirOf(id: String): File = File(root(), id)

    private fun persistCurrent() {
        if (!drawing.dirtyState) return
        val dir = dirOf(currentId)
        dir.mkdirs()
        drawing.saveState(dir)
        try {
            val bmp = drawing.exportBitmap()
            val thumb = Bitmap.createScaledBitmap(bmp, 256, 256, true)
            FileOutputStream(File(dir, "thumb.png")).use { thumb.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (thumb !== bmp) thumb.recycle()
            bmp.recycle()
        } catch (_: Exception) {
        }
    }

    private fun switchTo(id: String) {
        persistCurrent()
        currentId = id
        prefs().edit().putString("current", id).apply()
        drawing.newDocument()
        drawing.loadState(dirOf(id))
        drawing.dirtyState = false
    }

    private fun showGalleryDialog() {
        persistCurrent()
        val pad = dp(12)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Galeria")
            .setView(ScrollView(this).apply { addView(list) })
            .setPositiveButton("Novo desenho", null)
            .setNeutralButton("Dicas", null)
            .setNegativeButton("Fechar", null)
            .create()

        fun rebuild() {
            list.removeAllViews()
            val dirs = root().listFiles()
                ?.filter { File(it, "state.txt").exists() }
                ?.sortedByDescending { File(it, "state.txt").lastModified() }
                ?: emptyList()
            if (dirs.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "Nenhum desenho salvo ainda."
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                })
            }
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
            for (d in dirs) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.WHITE)
                }
                val tf = File(d, "thumb.png")
                if (tf.exists()) img.setImageBitmap(BitmapFactory.decodeFile(tf.path))
                row.addView(img, LinearLayout.LayoutParams(dp(72), dp(72)))

                val stamp = fmt.format(java.util.Date(File(d, "state.txt").lastModified()))
                val label = TextView(this).apply {
                    text = stamp + (if (d.name == currentId) "\n(aberto agora)" else "")
                    textSize = 15f
                    setPadding(dp(12), 0, 0, 0)
                }
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                row.addView(smallButton("🗑", 18f) {
                    AlertDialog.Builder(this)
                        .setTitle("Apagar desenho")
                        .setMessage("Apagar o desenho de $stamp? Não dá para desfazer.")
                        .setPositiveButton("Apagar") { _, _ ->
                            dirOf(d.name).deleteRecursively()
                            if (d.name == currentId) {
                                drawing.dirtyState = false
                                switchTo("d" + System.currentTimeMillis())
                            }
                            rebuild()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                })

                row.setOnClickListener {
                    if (d.name != currentId) switchTo(d.name)
                    dialog.dismiss()
                }
                list.addView(row)
            }
        }

        rebuild()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            dialog.dismiss()
            showTips()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            switchTo("d" + System.currentTimeMillis())
            dialog.dismiss()
        }
    }

    // ------------------------------------------------------------------
    // Texto
    // ------------------------------------------------------------------

    private fun askText(x: Float, y: Float) {
        val input = EditText(this).apply {
            hint = "Digite o texto"
            minLines = 2
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("Texto")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> drawing.drawText(input.text.toString(), x, y) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Importar imagem (vira uma camada nova)
    // ------------------------------------------------------------------

    private fun importImage(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 3200 || bounds.outHeight / sample > 3200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap? = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp == null) {
                Toast.makeText(this, "Não consegui abrir a imagem", Toast.LENGTH_SHORT).show()
                return
            }
            val orientation = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val m = Matrix().apply { postRotate(deg) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            if (!drawing.importBitmap(bmp)) {
                Toast.makeText(this, "Máximo de ${DrawingView.MAX_LAYERS} camadas", Toast.LENGTH_SHORT).show()
            }
            bmp.recycle()
        } catch (_: Exception) {
            Toast.makeText(this, "Não consegui abrir a imagem", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Dicas
    // ------------------------------------------------------------------

    private fun showTips() {
        val msg = "• Conta-gotas: toque na tela com ele para pegar uma cor do desenho.\n\n" +
            "• Balde: ajuste a Tolerância para preencher áreas com cores parecidas.\n\n" +
            "• Camadas: use Bloquear transparência para pintar sem sair do traço.\n\n" +
            "• Modos de mesclagem: toque no botão de mesclagem na tela de Camadas.\n\n" +
            "• Importar imagem: ela entra como uma camada nova.\n\n" +
            "• Girar, espelhar e ajustar: ajudam a achar erros no desenho."
        AlertDialog.Builder(this)
            .setTitle("Dicas")
            .setMessage(msg)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Rever introdução") { _, _ -> showOnboarding() }
            .show()
    }

    // ------------------------------------------------------------------
    // Introdução (primeira vez que abre o app)
    // ------------------------------------------------------------------

    private fun showOnboarding() {
        val pages = listOf(
            "Desenhe" to "Escolha entre lápis, caneta, nanquim, aquarela e outras ferramentas. Ajuste tamanho, opacidade e suavização.",
            "Camadas" to "Separe esboço, cores e detalhes em camadas. Dá para mudar a ordem, esconder e travar a transparência.",
            "Cores" to "Misture a sua cor e reutilize as cores recentes que aparecem no seletor.",
            "Galeria" to "Seus desenhos ficam guardados na Galeria. Quando terminar, salve em PNG em Imagens/LekaO."
        )
        var i = 0
        val body = TextView(this).apply {
            textSize = 17f
            setPadding(dp(24), dp(20), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setView(body)
            .setCancelable(false)
            .setPositiveButton("Próximo", null)
            .setNegativeButton("Pular", null)
            .create()

        fun finish() {
            prefs().edit().putBoolean("onboarded", true).apply()
            dialog.dismiss()
        }

        fun refresh() {
            val dots = pages.indices.joinToString(" ") { if (it == i) "●" else "○" }
            dialog.setTitle(pages[i].first + "   " + dots)
            body.text = pages[i].second
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text =
                if (i == pages.size - 1) "Iniciar" else "Próximo"
        }

        dialog.show()
        refresh()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (i >= pages.size - 1) finish() else { i++; refresh() }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish() }
    }

    // ------------------------------------------------------------------
    // Cores recentes
    // ------------------------------------------------------------------

    private fun loadRecentColors(): MutableList<Int> =
        (prefs().getString("recent_colors", "") ?: "")
            .split(",").mapNotNull { it.toIntOrNull() }.toMutableList()

    private fun addRecentColor(c: Int) {
        val list = loadRecentColors()
        list.remove(c)
        list.add(0, c)
        while (list.size > 14) list.removeAt(list.size - 1)
        prefs().edit().putString("recent_colors", list.joinToString(",")).apply()
    }

    // ------------------------------------------------------------------
    // Cor
    // ------------------------------------------------------------------

    private fun showColorDialog() {
        val hsv = FloatArray(3)
        Color.colorToHSV(drawing.color, hsv)
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val preview = View(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        val bars = arrayOfNulls<SeekBar>(3)

        fun refresh() {
            preview.setBackgroundColor(Color.HSVToColor(hsv))
        }

        fun addBar(label: String, max: Int, idx: Int, scale: Float) {
            val tv = TextView(this).apply {
                text = label
                setPadding(0, dp(8), 0, 0)
            }
            val sb = SeekBar(this).apply {
                this.max = max
                progress = (hsv[idx] * scale).toInt()
            }
            sb.onChange { p ->
                hsv[idx] = p / scale
                refresh()
            }
            bars[idx] = sb
            root.addView(tv)
            root.addView(sb)
        }

        addBar("Matiz", 360, 0, 1f)
        addBar("Saturação", 100, 1, 100f)
        addBar("Brilho", 100, 2, 100f)
        refresh()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(8))
        }
        for (c in palette) {
            val sw = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1), Color.GRAY)
                }
                setOnClickListener {
                    Color.colorToHSV(c, hsv)
                    bars[0]?.progress = hsv[0].toInt()
                    bars[1]?.progress = (hsv[1] * 100).toInt()
                    bars[2]?.progress = (hsv[2] * 100).toInt()
                    refresh()
                }
            }
            row.addView(sw, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
        }
        root.addView(HorizontalScrollView(this).apply { addView(row) })

        val recents = loadRecentColors()
        if (recents.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "Recentes"
                setPadding(0, dp(4), 0, 0)
            })
            val rrow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            for (c in recents) {
                val sw = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c)
                        setStroke(dp(1), Color.GRAY)
                    }
                    setOnClickListener {
                        Color.colorToHSV(c, hsv)
                        bars[0]?.progress = hsv[0].toInt()
                        bars[1]?.progress = (hsv[1] * 100).toInt()
                        bars[2]?.progress = (hsv[2] * 100).toInt()
                        refresh()
                    }
                }
                rrow.addView(sw, LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) })
            }
            root.addView(HorizontalScrollView(this).apply { addView(rrow) })
        }

        AlertDialog.Builder(this)
            .setTitle("Cor")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                drawing.color = Color.HSVToColor(hsv)
                addRecentColor(drawing.color)
                updateColorButton()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Camadas
    // ------------------------------------------------------------------

    private fun smallButton(text: String, size: Float = 20f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onClick() }
        }

    private fun showLayersDialog() {
        val pad = dp(12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220))
        )

        var updating = false
        var refreshAll: () -> Unit = {}
        var dialogRef: AlertDialog? = null

        val blendView = smallButton("", 14f) {
            val l = drawing.layers[drawing.activeIndex]
            l.blend = (l.blend + 1) % DrawingView.BLEND_NAMES.size
            drawing.dirtyState = true
            drawing.invalidate()
            refreshAll()
        }
        val lockView = smallButton("", 14f) {
            val l = drawing.layers[drawing.activeIndex]
            l.lockAlpha = !l.lockAlpha
            drawing.dirtyState = true
            refreshAll()
        }
        val duplicateView = smallButton("Duplicar", 14f) {
            if (!drawing.duplicateLayer()) {
                Toast.makeText(this, "Máximo de ${DrawingView.MAX_LAYERS} camadas", Toast.LENGTH_SHORT).show()
            }
            refreshAll()
        }
        val mergeView = smallButton("Juntar com a de baixo", 14f) {
            if (!drawing.mergeDown()) {
                Toast.makeText(this, "Não há camada abaixo", Toast.LENGTH_SHORT).show()
            }
            refreshAll()
        }
        val importView = smallButton("Importar imagem", 14f) {
            dialogRef?.dismiss()
            pickImage.launch("image/*")
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(duplicateView)
        row1.addView(mergeView)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(blendView)
        row2.addView(lockView)
        root.addView(row1)
        root.addView(row2)
        root.addView(importView)

        val opLabel = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        val opBar = SeekBar(this).apply { max = 100 }
        root.addView(opLabel)
        root.addView(opBar)

        fun syncOpacity() {
            val l = drawing.layers[drawing.activeIndex]
            updating = true
            opBar.progress = l.opacity
            updating = false
            opLabel.text = "Opacidade da camada: ${l.opacity}%"
            blendView.text = "Modo: ${DrawingView.BLEND_NAMES[l.blend]}"
            lockView.text = "Bloquear transparência: " + (if (l.lockAlpha) "sim" else "não")
        }

        fun rebuild() {
            list.removeAllViews()
            for (i in drawing.layers.indices.reversed()) {
                val l = drawing.layers[i]
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(this).apply {
                    text = (if (i == drawing.activeIndex) "● " else "○ ") + l.name
                    textSize = 16f
                    setPadding(dp(8), dp(10), dp(8), dp(10))
                    setOnClickListener {
                        drawing.activeIndex = i
                        refreshAll()
                    }
                }
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(smallButton(if (l.clip) "✂" else "▫") {
                    l.clip = !l.clip
                    drawing.dirtyState = true
                    drawing.invalidate()
                    refreshAll()
                })
                row.addView(smallButton(if (l.visible) "👁" else "—") {
                    l.visible = !l.visible
                    drawing.dirtyState = true
                    drawing.invalidate()
                    refreshAll()
                })
                row.addView(smallButton("▲") {
                    drawing.moveLayer(i, 1)
                    refreshAll()
                })
                row.addView(smallButton("▼") {
                    drawing.moveLayer(i, -1)
                    refreshAll()
                })
                list.addView(row)
            }
        }

        refreshAll = {
            rebuild()
            syncOpacity()
        }

        opBar.onChange { p ->
            if (!updating) {
                drawing.layers[drawing.activeIndex].opacity = p
                opLabel.text = "Opacidade da camada: $p%"
                drawing.dirtyState = true
                drawing.invalidate()
            }
        }
        refreshAll()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Camadas")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Nova", null)
            .setNeutralButton("Excluir", null)
            .setNegativeButton("Fechar", null)
            .show()
        dialogRef = dialog

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (!drawing.addLayer()) {
                Toast.makeText(this, "Máximo de ${DrawingView.MAX_LAYERS} camadas", Toast.LENGTH_SHORT).show()
            }
            refreshAll()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (drawing.layers.size <= 1) {
                Toast.makeText(this, "Precisa de pelo menos uma camada", Toast.LENGTH_SHORT).show()
            } else {
                drawing.deleteLayer(drawing.activeIndex)
                refreshAll()
            }
        }
    }

    // ------------------------------------------------------------------
    // Salvar PNG na galeria do celular (Imagens/LekaO)
    // ------------------------------------------------------------------

    private fun savePng() {
        val bmp = drawing.exportBitmap()
        Thread {
            var ok = false
            var savedUri: Uri? = null
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "LekaO_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LekaO")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    savedUri = uri
                    contentResolver.openOutputStream(uri)?.use {
                        ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            } catch (_: Exception) {
            }
            bmp.recycle()
            val shareUri = savedUri
            runOnUiThread {
                if (ok && shareUri != null) {
                    AlertDialog.Builder(this)
                        .setTitle("Salvo em Imagens/LekaO")
                        .setPositiveButton("Compartilhar") { _, _ ->
                            val it = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "image/png"
                                putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(android.content.Intent.createChooser(it, "Compartilhar desenho"))
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                } else {
                    Toast.makeText(this, "Não consegui salvar", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}

private fun SeekBar.onChange(cb: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = cb(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    })
}
