package com.example.tagtune2

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mpatric.mp3agic.ID3v24Tag
import com.mpatric.mp3agic.Mp3File
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object TagEditorDialog {

    const val REQ_COVER = 303

    data class Draft(
        var title: String, var artist: String, var album: String,
        var year: String, var genre: String, var tags: MutableList<String>,
        var coverPath: String?
    )

    @Volatile var _pendingCoverView: ImageView? = null
    @Volatile var _pendingDraft: Draft? = null

    fun show(
        ctx: Context,
        track: MainActivity.Track,
        onSave: (Draft) -> Unit
    ) {
        val draft = Draft(
            track.title, track.artist, track.album,
            track.year, track.genre, track.tags.toMutableList(),
            track.coverPath
        )

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), 0)

        val fields = LinearLayout(ctx); fields.orientation = LinearLayout.VERTICAL
        val fTitle = field(ctx, "Название", draft.title); fields.addView(fTitle)
        val fArtist = field(ctx, "Исполнитель", draft.artist); fields.addView(fArtist)
        val fAlbum = field(ctx, "Альбом", draft.album); fields.addView(fAlbum)
        val fYear = field(ctx, "Год", draft.year); fields.addView(fYear)
        val fGenre = field(ctx, "Жанр", draft.genre); fields.addView(fGenre)
        box.addView(fields)

        val coverRow = LinearLayout(ctx)
        coverRow.orientation = LinearLayout.HORIZONTAL
        coverRow.gravity = Gravity.CENTER_VERTICAL
        coverRow.setPadding(0, dp(ctx, 10), 0, dp(ctx, 6))
        val coverView = ImageView(ctx)
        coverView.scaleType = ImageView.ScaleType.CENTER_CROP
        coverView.setBackgroundColor(Color.parseColor("#2A2A3A"))
        coverView.clipToOutline = true
        coverView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(ctx, 10).toFloat())
            }
        }
        loadCover(ctx, draft.coverPath, coverView)
        coverRow.addView(coverView, LinearLayout.LayoutParams(dp(ctx, 72), dp(ctx, 72)))

        val coverBtns = LinearLayout(ctx); coverBtns.orientation = LinearLayout.VERTICAL
        coverBtns.setPadding(dp(ctx, 10), 0, 0, 0)
        val btnReplace = miniBtn(ctx, "Заменить") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "image/*"
            (ctx as android.app.Activity).startActivityForResult(i, REQ_COVER)
            _pendingCoverView = coverView
            _pendingDraft = draft
        }
        val btnRemove = miniBtn(ctx, "Удалить") {
            draft.coverPath = null
            coverView.setImageResource(0)
        }
        coverBtns.addView(btnReplace)
        coverBtns.addView(btnRemove)
        coverRow.addView(coverBtns)
        box.addView(coverRow)

        val tagsLabel = TextView(ctx)
        tagsLabel.text = "Хештеги"
        tagsLabel.setTextColor(Color.parseColor("#BBBBCC"))
        tagsLabel.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        box.addView(tagsLabel)

        val tagsFlow = FlowLayout(ctx)
        tagsFlow.centerRows = true
        box.addView(tagsFlow)

        val tagInput = EditText(ctx)
        tagInput.hint = "#новый_тег"
        tagInput.setSingleLine(true)
        tagInput.setTextColor(Color.WHITE)
        tagInput.setHintTextColor(Color.GRAY)
        tagInput.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E2A"))
            cornerRadius = dp(ctx, 10).toFloat()
        }
        tagInput.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
        box.addView(tagInput)

        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("${track.artist} - ${track.title}")
            .setView(box)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.parseColor("#16121C"))
            cornerRadius = dp(ctx, 16).toFloat()
        })
        dialog.window?.setLayout(
            ctx.resources.displayMetrics.widthPixels - dp(ctx, 32),
            WindowManager.LayoutParams.WRAP_CONTENT
        )

        fun refreshTags() {
            tagsFlow.removeAllViews()
            if (draft.tags.isEmpty()) {
                val none = TextView(ctx); none.text = "тегов нет"; none.setTextColor(Color.GRAY)
                tagsFlow.addView(none)
                return
            }
            for (g in draft.tags) {
                val chip = TextView(ctx)
                chip.text = "#$g ✕"
                chip.setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                chip.background = GradientDrawable().apply {
                    setColor(Color.parseColor("#3A2A5A"))
                    cornerRadius = dp(ctx, 99).toFloat()
                }
                chip.setTextColor(Color.WHITE)
                val lp = ViewGroup.MarginLayoutParams(
                    ViewGroup.MarginLayoutParams.WRAP_CONTENT,
                    ViewGroup.MarginLayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, dp(ctx, 6), dp(ctx, 6))
                chip.layoutParams = lp
                chip.setOnClickListener {
                    draft.tags = draft.tags.filter { it != g }.toMutableList()
                    refreshTags()
                }
                tagsFlow.addView(chip)
            }
        }

        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.title)?.setTextColor(Color.WHITE)

            val saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn.isAllCaps = false
            saveBtn.setTextColor(Color.WHITE)
            saveBtn.background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF4D6D"))
                cornerRadius = dp(ctx, 99).toFloat()
            }
            saveBtn.setPadding(dp(ctx, 24), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))

            saveBtn.setOnClickListener {
                draft.title = fTitle.text.toString().trim().ifEmpty { track.title }
                draft.artist = fArtist.text.toString().trim().ifEmpty { track.artist }
                draft.album = fAlbum.text.toString().trim().ifEmpty { track.album }
                draft.year = fYear.text.toString().trim()
                draft.genre = fGenre.text.toString().trim()
                val extra = tagInput.text.toString()
                    .split(Regex("[\\s,]+"))
                    .map { it.trim().removePrefix("#").lowercase() }
                    .filter { it.isNotEmpty() }
                draft.tags = (draft.tags + extra).distinct().toMutableList()
                onSave(draft)
                dialog.dismiss()
            }

            tagInput.setOnEditorActionListener { _, _, _ ->
                val t = tagInput.text.toString().trim().removePrefix("#").lowercase()
                if (t.isNotEmpty() && !draft.tags.contains(t)) {
                    draft.tags = (draft.tags + t).distinct().toMutableList()
                    tagInput.setText("")
                    refreshTags()
                }
                true
            }

            refreshTags()
        }

        dialog.show()
    }

    fun applyCoverFromUri(ctx: Context, uri: Uri) {
        val view = _pendingCoverView ?: return
        val draft = _pendingDraft ?: return
        Thread {
            val tmp = File(ctx.cacheDir, "cover_tmp_" + System.currentTimeMillis() + ".jpg")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { out -> ins.copyTo(out) }
            }
            draft.coverPath = tmp.absolutePath
            (ctx as android.app.Activity).runOnUiThread {
                loadCover(ctx, tmp.absolutePath, view)
            }
        }.start()
    }

    private fun loadCover(ctx: Context, path: String?, iv: ImageView) {
        if (path == null) { iv.setImageResource(0); return }
        val f = File(path)
        if (!f.exists()) { iv.setImageResource(0); return }
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, opts)
        var s = 1
        while (opts.outWidth / (s * 2) >= 192) s *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = s
        iv.setImageBitmap(BitmapFactory.decodeFile(path, opts))
    }

    private fun field(ctx: Context, hint: String, value: String): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        e.setTextColor(Color.WHITE)
        e.setHintTextColor(Color.GRAY)
        e.background = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E2A"))
            cornerRadius = dp(ctx, 10).toFloat()
        }
        e.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(ctx, 4), 0, dp(ctx, 4))
        e.layoutParams = lp
        return e
    }

    private fun miniBtn(ctx: Context, text: String, click: () -> Unit): Button {
        val b = Button(ctx)
        b.text = text
        b.isAllCaps = false
        b.textSize = 12f
        b.setTextColor(Color.WHITE)
        b.background = GradientDrawable().apply {
            setColor(Color.parseColor("#2A2A3A"))
            cornerRadius = dp(ctx, 99).toFloat()
        }
        b.setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(ctx, 4))
        b.layoutParams = lp
        b.setOnClickListener { click() }
        return b
    }

    private fun dp(ctx: Context, n: Int) = (ctx.resources.displayMetrics.density * n).toInt()

    fun write(ctx: Context, track: MainActivity.Track, draft: Draft, onDone: (Boolean, String) -> Unit) {
        Thread {
            var ok = false; var msg = "ок"
            try {
                val uri = Uri.parse(track.uri)
                val ext = (uri.lastPathSegment?.substringAfterLast('.', "") ?: "mp3").lowercase()
                val inF = File(ctx.cacheDir, "edit_in." + ext)
                val outF = File(ctx.cacheDir, "edit_out." + ext)
                ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                    FileOutputStream(inF).use { out -> ins.copyTo(out) }
                }

                if (track.format == "MP3") {
                    val mp3 = Mp3File(inF.absolutePath)
                    if (!mp3.hasId3v2Tag()) mp3.id3v2Tag = ID3v24Tag()
                    val tag = mp3.id3v2Tag
                    tag.title = draft.title
                    tag.artist = draft.artist
                    tag.album = draft.album
                    /* в mp3agic year — Int, приводим через toIntOrNull */
                    if (draft.year.isNotEmpty()) {
                        val y = draft.year.toIntOrNull()
                        if (y != null && y > 0) tag.year = y
                    }
                    if (draft.genre.isNotEmpty()) tag.genre = draft.genre
                    val tagsLine = draft.tags.joinToString("\n") { "#$it" }
                    val old = tag.comment ?: ""
                    val nonTag = old.split("\n").map { it.trimEnd() }
                        .filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                    tag.comment = (nonTag + tagsLine).joinToString("\n")
                    draft.coverPath?.let { p ->
                        val bmp = BitmapFactory.decodeFile(p)
                        if (bmp != null) {
                            val baos = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                            tag.setAlbumImage(baos.toByteArray(), "image/jpeg")
                        }
                    } ?: run {
                        /* mp3agic 0.9.1 не имеет removeAlbumImage();
                           очищаем через setAlbumImage с пустым массивом */
                        try {
                            tag.setAlbumImage(ByteArray(0), "image/jpeg")
                        } catch (_: Exception) {
                            /* если упало — просто не трогаем существующую обложку */
                        }
                    }
                    mp3.save(outF.absolutePath)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        outF.inputStream().use { it.copyTo(os) }
                    }
                } else {
                    val af = AudioFileIO.read(inF)
                    val tag = af.tagOrCreateAndSetDefault
                    tag.setField(FieldKey.TITLE, draft.title)
                    tag.setField(FieldKey.ARTIST, draft.artist)
                    tag.setField(FieldKey.ALBUM, draft.album)
                    if (draft.year.isNotEmpty()) tag.setField(FieldKey.YEAR, draft.year)
                    if (draft.genre.isNotEmpty()) tag.setField(FieldKey.GENRE, draft.genre)
                    val tagsLine = draft.tags.joinToString("\n") { "#$it" }
                    val old = tag.getFirst(FieldKey.COMMENT) ?: ""
                    val nonTag = old.split("\n").map { it.trimEnd() }
                        .filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
                    tag.setField(FieldKey.COMMENT, (nonTag + tagsLine).joinToString("\n"))
                    draft.coverPath?.let { p ->
                        val art = ArtworkFactory.createArtworkFromFile(File(p))
                        tag.deleteArtworkField()
                        tag.setField(art)
                    } ?: run {
                        try { tag.deleteArtworkField() } catch (_: Exception) { }
                    }
                    af.commit()
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        inF.inputStream().use { it.copyTo(os) }
                    }
                }
                inF.delete(); outF.delete(); ok = true
            } catch (e: Exception) {
                msg = "Не записалось: ${e.javaClass.simpleName}: ${e.message}"
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(ok, msg) }
        }.start()
    }
}
