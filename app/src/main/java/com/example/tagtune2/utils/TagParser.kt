package com.example.tagtune2.utils

/**
 * Мозг TagTune2.
 * Читает поле COMMENT и вытаскивает оттуда теги, игнорируя обычный текст.
 */
object TagParser {

    // Ищем всё, что начинается с #. Поддерживает русские и английские буквы.
    private val hashtagRegex = Regex("#([\\p{L}0-9_]+)")

    /**
     * Достает теги из текста комментария.
     * Пример: "Любимая песня \n #рок \n #дорога" -> ["рок", "дорога"]
     */
    fun extractTags(commentText: String?): List<String> {
        if (commentText.isNullOrBlank()) return emptyList()

        return hashtagRegex.findAll(commentText)
            .map { it.groupValues[1].lowercase() } // Убираем решетку, делаем маленькими
            .distinct() // Удаляем дубликаты
            .toList()
    }

    /**
     * Подготавливает тег к записи.
     */
    fun formatTag(tag: String): String {
        val clean = tag.replace("#", "").trim().lowercase()
        return if (clean.isNotEmpty()) "#$clean" else ""
    }

    /**
     * Добавляет тег к комментарию с новой строки, сохраняя старый текст.
     */
    fun addTagToComment(existingComment: String?, newTag: String): String {
        val formatted = formatTag(newTag)
        if (formatted.isEmpty()) return existingComment ?: ""

        val current = existingComment?.trim() ?: ""
        
        // Проверяем, нет ли уже такого тега
        val existingTags = extractTags(current)
        if (existingTags.contains(formatted.removePrefix("#"))) {
            return current // Тег уже есть, ничего не меняем
        }

        return if (current.isEmpty()) formatted else "$current\n$formatted"
    }
}
