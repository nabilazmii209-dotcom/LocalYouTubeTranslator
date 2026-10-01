package com.nabil.localtranslator

import android.content.Context
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class LocalTranslator(private val context: Context) {
    private val idClient = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()

    suspend fun toArabic(text: String): String {
        if (text.isBlank()) return ""

        val tag = try {
            idClient.identifyLanguage(text).await()
        } catch (_: Throwable) {
            "und"
        }

        if (tag == "und" || tag == "ar") return text

        val source = TranslateLanguage.fromLanguageTag(tag) ?: return text
        val target = TranslateLanguage.ARABIC
        val key = "$source>$target"

        val translator = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
            )
        }

        translator.downloadModelIfNeeded().await()
        return translator.translate(text).await()
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        idClient.close()
    }
}
