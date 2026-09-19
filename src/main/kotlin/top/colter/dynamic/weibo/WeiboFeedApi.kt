package top.colter.dynamic.weibo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.colter.dynamic.core.plugin.PluginAdminApiRequest
import top.colter.dynamic.core.plugin.PluginAdminApiResponse
import top.colter.dynamic.core.plugin.PluginAdminApiStatus
import top.colter.dynamic.core.plugin.PublisherLoginStatus

/** Read-only acquisition: never publishes updates, changes subscriptions, or emits notifications. */
internal class WeiboFeedApi(
    private val timeoutMillis: Long = 45_000,
    private val gatewayProvider: () -> WeiboGateway,
) {
    private val mutex = Mutex()

    suspend fun handle(request: PluginAdminApiRequest): PluginAdminApiResponse {
        if (request.method != "GET" || request.path.trim('/') != "feed") {
            return PluginAdminApiResponse.notFound("接口不存在")
        }
        val uid = request.query["uid"]?.singleOrNull()
        val cursor = request.query["cursor"]?.singleOrNull()
        if (uid == null || !UID.matches(uid) ||
            (request.query.containsKey("cursor") && (cursor == null || !CURSOR.matches(cursor))) ||
            request.query.keys.any { it !in setOf("uid", "cursor") }
        ) {
            return PluginAdminApiResponse.badRequest("需要有效微博 UID 和可选分页游标")
        }
        return withTimeoutOrNull(timeoutMillis) {
            mutex.withLock {
                try {
                    read(uid, cursor)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: WeiboLoginException) {
                    unavailable("login_required", "微博登录已失效，请在 Dynamic Bot 更新登录")
                } catch (error: Exception) {
                    // Upstream exception messages can contain session/request details. Never export them.
                    unavailable("unavailable", "微博来源暂时不可用，请稍后重试")
                }
            }
        } ?: unavailable("unavailable", "微博来源请求超时，请稍后重试")
    }

    private suspend fun read(uid: String, cursor: String?): PluginAdminApiResponse {
        val gateway = gatewayProvider()
        if (gateway.checkLoginState().status != PublisherLoginStatus.SUCCESS) {
            return unavailable("login_required", "请先在 Dynamic Bot 登录微博")
        }
        val publisher = gateway.fetchPublisherSnapshot(uid)
            ?: return unavailable("unavailable", "暂时无法读取微博来源资料")
        when (publisher.following) {
            false -> return unavailable("not_following", "当前微博账号尚未关注此来源")
            null -> return unavailable("unavailable", "暂时无法确认微博来源的关注状态")
            true -> Unit
        }
        val page = gateway.fetchFollowTimelinePage(cursor)
        require(page.posts.size <= 25) { "Unexpected page size" }
        val ids = page.posts.map { post ->
            val id = post.numericId ?: post.postId
            require(NUMERIC_ID.matches(id) && id.toLongOrNull() != null) { "Missing numeric status ID" }
            id.toLong()
        }
        val next = page.nextCursor?.takeUnless { it.isBlank() || it == "0" }
        require(next == null || (CURSOR.matches(next) && next != cursor)) { "Invalid upstream cursor" }
        val posts = page.posts.filter { it.userId == uid }.distinctBy { it.numericId ?: it.postId }.map { post ->
            val observedText = post.feedText()
            val text = observedText.take(MAX_TEXT_LENGTH)
            buildJsonObject {
                put("id", post.numericId ?: post.postId)
                put("title", text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "微博动态")
                put("text", text)
                put("content_truncated", post.hasPartialText() || observedText.length > MAX_TEXT_LENGTH)
                put("url", post.url ?: "https://weibo.com/$uid/${post.postId}")
                put("published_at", post.createdAtEpochSeconds)
            }
        }
        return PluginAdminApiResponse.ok(buildJsonObject {
            put("status", "ready")
            put("scope", "following")
            put("publisher", buildJsonObject {
                put("uid", uid)
                put("name", publisher.screenName)
            })
            put("posts", JsonArray(posts))
            put("next_cursor", next?.let(::JsonPrimitive) ?: JsonNull)
            put("page_min_id", ids.minOrNull()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("page_max_id", ids.maxOrNull()?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        })
    }

    private fun unavailable(status: String, message: String): PluginAdminApiResponse =
        PluginAdminApiResponse(PluginAdminApiStatus.CONFLICT, buildJsonObject {
            put("status", status)
            put("message", message)
            put("scope", "following")
            put("posts", JsonArray(emptyList()))
            put("next_cursor", JsonNull)
            put("page_min_id", JsonNull)
            put("page_max_id", JsonNull)
        })

    private fun WeiboPostSnapshot.feedText(depth: Int = 0): String = buildString {
        append(text.trim())
        card?.let { card ->
            if (card.title.isNotBlank()) append("\n\n").append(card.title)
            if (card.description.isNotBlank()) append("\n").append(card.description)
        }
        if (depth < 3) reposted?.let { original ->
            append("\n\n转发 @").append(original.screenName ?: original.userId)
            append(": ").append(original.feedText(depth + 1))
        }
    }.trim()

    private fun WeiboPostSnapshot.hasPartialText(depth: Int = 0): Boolean =
        isLongText || text.length > MAX_TEXT_LENGTH ||
            (reposted != null && (depth >= 3 || reposted.hasPartialText(depth + 1)))

    private companion object {
        const val MAX_TEXT_LENGTH = 10_000
        val UID = Regex("[1-9][0-9]{0,19}")
        val NUMERIC_ID = Regex("[1-9][0-9]{0,18}")
        val CURSOR = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
