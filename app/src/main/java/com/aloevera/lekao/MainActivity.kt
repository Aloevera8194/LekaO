package com.aloevera.lekao

import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup

class MainActivity : AppCompatActivity() {

    private lateinit var drawing: DrawingView
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnRedo: MaterialButton
    private lateinit var btnColor: MaterialButton

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

        drawing.loadState(filesDir)
        drawing.onHistoryChanged = { refreshHistoryButtons() }
        drawing.onColorPicked = { updateColorButton() }
        refreshHistoryButtons()
        updateColorButton()

        btnUndo.setOnClickListener { drawing.undo() }
        btnRedo.setOnClickListener { drawing.redo() }
        btnColor.setOnClickListener { showColorDialog() }
        findViewById<View>(R.id.btnLayers).setOnClickListener { showLayersDialog() }
        findViewById<View>(R.id.btnSave).setOnClickListener { savePng() }
        findViewById<View>(R.id.btnRotate).setOnClickListener { drawing.rotateView() }
        findViewById<View>(R.id.btnFlip).setOnClickListener { drawing.flipView() }
        findViewById<View>(R.id.btnFit).setOnClickListener { drawing.fitToScreen() }
        findViewById<View>(R.id.btnNew).setOnClickListener { confirmNew() }

        val toolMap = mapOf(
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
        findViewById<MaterialButtonToggleGroup>(R.id.toolGroup).apply {
            check(R.id.btnPen)
            addOnButtonCheckedListener { _, id, checked ->
                if (checked) toolMap[id]?.let { drawing.tool = it }
            }
        }

        val sizeLabel = findViewById<TextView>(R.id.sizeLabel)
        findViewById<SeekBar>(R.id.sizeBar).onChange { p ->
            val v = p + 1
            drawing.brushSize = v.toFloat()
            sizeLabel.text = "Tamanho: $v"
        }

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
        drawing.saveState(filesDir)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

    private fun confirmNew() {
        AlertDialog.Builder(this)
            .setTitle("Novo desenho")
            .setMessage("O desenho atual será apagado. Se quiser guardar, salve em PNG antes.")
            .setPositiveButton("Apagar e começar") { _, _ -> drawing.newDocument() }
            .setNegativeButton("Cancelar", null)
            .show()
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

        AlertDialog.Builder(this)
            .setTitle("Cor")
            .setView(root)
            .setPositiveButton("OK") { _, _ ->
                drawing.color = Color.HSVToColor(hsv)
                updateColorButton()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Camadas
    // ------------------------------------------------------------------

    private fun smallButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
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
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260))
        )
        val opLabel = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        val opBar = SeekBar(this).apply { max = 100 }
        root.addView(opLabel)
        root.addView(opBar)

        var updating = false
        var refreshAll: () -> Unit = {}

        fun syncOpacity() {
            val l = drawing.layers[drawing.activeIndex]
            updating = true
            opBar.progress = l.opacity
            updating = false
            opLabel.text = "Opacidade da camada: ${l.opacity}%"
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
            .setView(root)
            .setPositiveButton("Nova", null)
            .setNeutralButton("Excluir", null)
            .setNegativeButton("Fechar", null)
            .show()

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
    // Salvar PNG na galeria (Imagens/LekaO)
    // ------------------------------------------------------------------

    private fun savePng() {
        val bmp = drawing.exportBitmap()
        Thread {
            var ok = false
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "LekaO_${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LekaO")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use {
                        ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            } catch (_: Exception) {
            }
            bmp.recycle()
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Salvo em Imagens/LekaO" else "Não consegui salvar",
                    Toast.LENGTH_LONG
                ).show()
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
