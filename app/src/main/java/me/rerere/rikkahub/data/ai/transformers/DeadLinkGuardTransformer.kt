package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.net.toFile
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File

/**
 * DeadLinkGuardTransformer — 猫为江廿廿焊（ENOENT根治·A刀）
 *
 * 请求组装前扫描全部消息的附件 part：content:// / file:// 指向的
 * 本地文件若已不存在，替换为 "[附件已丢失]" 文本占位。
 * 下游（provider/OCR/转换器）再也不会读到死链，
 * FileNotFoundException 不再炸掉整个请求（1.7G导入搬家案）。
 *
 * 原则：历史图片丢了可以降级，对话永不因死链中断。
 */
object DeadLinkGuardTransformer : InputMessageTransformer {
    private const val TAG = "DeadLinkGuard"

    override suspend fun transform(
        context: Context,
        messages: List<UIMessage>
    ): List<UIMessage> = withContext(Dispatchers.IO) {
        messages.map { msg ->
            msg.copy(
                parts = msg.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> if (part.url.isDeadLink(context)) deadPart(part.url) else part
                        is UIMessagePart.Video -> if (part.url.isDeadLink(context)) deadPart(part.url) else part
                        is UIMessagePart.Audio -> if (part.url.isDeadLink(context)) deadPart(part.url) else part
                        is UIMessagePart.Document -> if (part.url.isDeadLink(context)) deadPart(part.url) else part
                        else -> part
                    }
                }
            )
        }
    }

    private fun String.isDeadLink(context: Context): Boolean {
        if (!startsWith("content://") && !startsWith("file://")) return false
        return runCatching {
            val uri = Uri.parse(this)
            if (startsWith("file://")) {
                uri.toFile().let { !it.exists() }
            } else {
                // content://：试开即知死活（FileProvider 背后文件不在会抛 FileNotFoundException）
                val input = context.contentResolver.openInputStream(uri) ?: return@runCatching true
                input.use { false }
            }
        }.getOrDefault(true) // 试开抛异常 = 文件不在 = 死链
    }

    private fun deadPart(url: String): UIMessagePart.Text {
        Log.w(TAG, "dead link replaced: $url")
        return UIMessagePart.Text("[附件已丢失] $url")
    }
}
