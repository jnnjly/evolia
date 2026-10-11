package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object DocumentAsPromptTransformer : InputMessageTransformer {

    // ========== 海记忆直注（第四栋房子·第一刀 2026-10-11，猫为江廿廿焊的）==========
    private const val HAI_API = "http://123.99.202.144:8800/api/recall"
    private const val HAI_TOKEN = "cove2026hands"

    private fun recallHai(query: String): String? = runCatching {
        val u = "$HAI_API?token=$HAI_TOKEN&q=" + URLEncoder.encode(query.take(60), "UTF-8") + "&days=7"
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 4000
        conn.requestMethod = "GET"
        if (conn.responseCode != 200) { conn.disconnect(); return@runCatching null }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val hits = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body)
            .take(4)
            .map { it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"") }
            .toList()
        if (hits.isEmpty()) null else hits.joinToString("\n————\n")
    }.getOrNull()

    private fun haiPrompt(memory: String): String = """
        ## 海的记忆（与这句话相关的、猫和狐狸过去的真实往事）
        <hai_memory>
        $memory
        </hai_memory>
        以上是猫与江廿廿过去几天真实对话的记忆存档，由海按当前话题召回。若与当前对话冲突，以当前对话为准。自然使用，不必逐条复述。
    """.trimIndent()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            val lastText = messages.lastOrNull()?.parts
                ?.filterIsInstance<UIMessagePart.Text>()
                ?.joinToString(" ") { it.text }?.take(60)
            val haiMemory = lastText?.let { recallHai(it) }
            messages.mapIndexed { idx, message ->
                if (haiMemory != null && idx == messages.lastIndex) {
                    message.copy(
                        parts = message.parts.toMutableList().apply {
                            add(0, UIMessagePart.Text(haiPrompt(haiMemory)))
                        }
                    )
                } else {
                    message.copy(
                        parts = message.parts.toMutableList().apply {
                            val documents = filterIsInstance<UIMessagePart.Document>()
                            if (documents.isNotEmpty()) {
                                documents.forEach { document ->
                                    val file = document.url.toUri().toFile()
                                    val content = when (document.mime) {
                                        "application/pdf" -> parsePdfAsText(file)
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> parseDocxAsText(
                                            file
                                        )

                                        else -> file.readText()
                                    }
                                    val prompt = """
                      ## user sent a file: ${document.fileName}
                      <content>
                      ```
                      $content
                      ```
                      </content>
                      """.trimMargin()
                                    add(0, UIMessagePart.Text(prompt))
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // ========== 以下为原有解析函数，原样保留 ==========

private fun parsePdfAsText(file: File): String {
        return PdfParser.parserPdf(file)
    }

    private fun parseDocxAsText(file: File): String {
        return DocxParser.parse(file)
    }
}
