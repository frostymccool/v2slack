package com.frostymccool.voiceslack.actions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

sealed interface ActionResult {
    data object Success : ActionResult
    data class Failure(val message: String, val retryable: Boolean) : ActionResult
}

/**
 * One tap-able "send this text somewhere" action. Kept as an interface -- rather than the
 * record screen hardcoding "post to Slack" -- so a future general remote-control app can
 * register more actions (other webhooks, other apps) without touching the record/transcribe
 * flow at all. Today there are exactly two implementations, both dispatching to Slack by
 * different transports.
 */
interface RemoteAction {
    val id: String
    val label: String
    suspend fun execute(context: Context, text: String): ActionResult
}

/**
 * Primary output path: POSTs the transcribed text to a Slack incoming webhook. Only usable
 * once corporate IT approves a webhook for the target channel -- see [SlackDeepLinkAction]
 * for the fallback that works without that approval.
 */
class SlackWebhookAction(
    private val webhookUrlProvider: () -> String?,
    private val httpClient: OkHttpClient = defaultClient,
) : RemoteAction {
    override val id = "slack_webhook"
    override val label = "Post via Slack webhook"

    override suspend fun execute(context: Context, text: String): ActionResult {
        val rawUrl = webhookUrlProvider()
        if (rawUrl.isNullOrBlank()) {
            return ActionResult.Failure("No webhook URL configured", retryable = false)
        }
        val httpUrl = rawUrl.toHttpUrlOrNull()
            ?: return ActionResult.Failure("Webhook URL is invalid", retryable = false)

        val payload = JSONObject().put("text", text).toString()
        val body = payload.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(httpUrl).post(body).build()

        return try {
            suspendCancellableCoroutine { cont ->
                val call = httpClient.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resume(ActionResult.Failure("Network error: ${e.message}", retryable = true))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val result = if (it.isSuccessful) {
                                ActionResult.Success
                            } else {
                                ActionResult.Failure("Slack rejected the post (HTTP ${it.code})", retryable = it.code >= 500)
                            }
                            cont.resume(result)
                        }
                    }
                })
            }
        } catch (e: IOException) {
            ActionResult.Failure("Network error: ${e.message}", retryable = true)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val defaultClient = OkHttpClient()
    }
}

/**
 * Fallback output path: hands the text to the Slack app via a share [Intent] so the user
 * picks the channel and hits send themselves. Needed because incoming-webhook approval on a
 * locked-down corporate Slack is never guaranteed. Slack's share target can't be told which
 * channel to preselect through a plain ACTION_SEND, so a configured channel hint is prefixed
 * onto the message as a visible reminder for the user picking the destination.
 */
class SlackDeepLinkAction(
    private val channelHintProvider: () -> String?,
) : RemoteAction {
    override val id = "slack_deep_link"
    override val label = "Open in Slack (prefilled)"

    override suspend fun execute(context: Context, text: String): ActionResult {
        if (!isSlackInstalled(context.packageManager)) {
            return ActionResult.Failure("Slack app isn't installed on this device", retryable = false)
        }

        val hint = channelHintProvider()?.trim()?.takeIf { it.isNotEmpty() }
        val message = if (hint != null) "#$hint: $text" else text

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(SLACK_PACKAGE)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ActionResult.Success
        } catch (e: ActivityNotFoundException) {
            ActionResult.Failure("Slack couldn't handle the share", retryable = false)
        }
    }

    companion object {
        const val SLACK_PACKAGE = "com.Slack"

        fun isSlackInstalled(packageManager: PackageManager): Boolean = try {
            packageManager.getPackageInfo(SLACK_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
