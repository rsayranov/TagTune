                if (track.format == "MP3") {
                    val mp3 = Mp3File(inF.absolutePath)
                    if (!mp3.hasId3v2Tag()) mp3.id3v2Tag = ID3v24Tag()
                    val tag = mp3.id3v2Tag
                    tag.title = draft.title
                    tag.artist = draft.artist
                    tag.album = draft.album
                    /* ID3v2.4: year — это String (TDRC frame) */
                    if (draft.year.isNotEmpty()) tag.year = draft.year
                    /* genreDescription — для текстового жанра (setGenre(int) не подходит) */
                    if (draft.genre.isNotEmpty()) tag.genreDescription = draft.genre
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
                        try {
                            tag.setAlbumImage(ByteArray(0), "image/jpeg")
                        } catch (_: Exception) { }
                    }
                    mp3.save(outF.absolutePath)
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                        outF.inputStream().use { it.copyTo(os) }
                    }
                } else {
