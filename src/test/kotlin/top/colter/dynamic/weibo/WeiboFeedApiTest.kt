package top.colter.dynamic.weibo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.core.plugin.FollowState
import top.colter.dynamic.core.plugin.PluginAdminApiRequest
import top.colter.dynamic.core.plugin.PluginAdminApiStatus
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WeiboFeedApiTest {
    @Test
    fun `reads one page without enrichment publishing or following`() = runBlocking {
        val gateway = Gateway()
        gateway.page = WeiboTimelinePage(listOf(post("110", "42").copy(
            reposted = post("100", "77").copy(screenName = "原作者", text = "原文"),
        ), post("109", "99")), "older")
        val response = WeiboFeedApi { gateway }.handle(request())
        assertEquals(PluginAdminApiStatus.OK, response.status)
        val body = response.body.jsonObject
        assertEquals("ready", body.getValue("status").jsonPrimitive.content)
        assertEquals("following", body.getValue("scope").jsonPrimitive.content)
        assertEquals("110", body.getValue("page_max_id").jsonPrimitive.content)
        assertEquals("109", body.getValue("page_min_id").jsonPrimitive.content)
        assertEquals("older", body.getValue("next_cursor").jsonPrimitive.content)
        val post = body.getValue("posts").jsonArray.single().jsonObject
        assertEquals("110", post.getValue("id").jsonPrimitive.content)
        assertEquals("短文\n\n转发 @原作者: 原文", post.getValue("text").jsonPrimitive.content)
        assertEquals("https://weibo.com/42/bid110", post.getValue("url").jsonPrimitive.content)
        assertEquals(listOf("login", "publisher:42", "page:null"), gateway.calls)
    }

    @Test
    fun `empty matching page preserves whole-page bounds and pagination`() = runBlocking {
        val gateway = Gateway()
        gateway.page = WeiboTimelinePage(listOf(post("109", "99")), "older2")
        val body = WeiboFeedApi { gateway }.handle(request("older1")).body.jsonObject
        assertEquals(0, body.getValue("posts").jsonArray.size)
        assertEquals("109", body.getValue("page_max_id").jsonPrimitive.content)
        assertEquals("109", body.getValue("page_min_id").jsonPrimitive.content)
        assertEquals("older2", body.getValue("next_cursor").jsonPrimitive.content)
        assertEquals("page:older1", gateway.calls.last())
    }

    @Test
    fun `empty upstream page terminates with null bounds`() = runBlocking {
        val body = WeiboFeedApi { Gateway() }.handle(request()).body.jsonObject
        assertEquals(JsonNull, body.getValue("page_min_id"))
        assertEquals(JsonNull, body.getValue("page_max_id"))
        assertEquals(JsonNull, body.getValue("next_cursor"))
    }

    @Test
    fun `login and follow coverage failures never request content`() = runBlocking {
        val loggedOut = Gateway().apply { login = PublisherLoginStatus.FAILED }
        val loginResult = WeiboFeedApi { loggedOut }.handle(request())
        assertEquals(PluginAdminApiStatus.CONFLICT, loginResult.status)
        assertEquals("login_required", loginResult.body.jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("login"), loggedOut.calls)
        for ((follow, expected) in listOf(FollowState.NOT_FOLLOWING to "not_following", FollowState.UNSUPPORTED to "unavailable")) {
            val gateway = Gateway().apply { this.follow = follow }
            val response = WeiboFeedApi { gateway }.handle(request())
            assertEquals(expected, response.body.jsonObject.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("login", "publisher:42"), gateway.calls)
        }
    }

    @Test
    fun `request validation rejects mutations unknown queries and malformed cursor before gateway access`() = runBlocking {
        val gateway = Gateway()
        val api = WeiboFeedApi { gateway }
        assertEquals(PluginAdminApiStatus.NOT_FOUND, api.handle(request().copy(method = "POST")).status)
        for (query in listOf(
            emptyMap(), mapOf("uid" to listOf("42", "43")), mapOf("uid" to listOf("bad")),
            mapOf("uid" to listOf("42"), "cursor" to listOf("../secret")),
            mapOf("uid" to listOf("42"), "cookie" to listOf("secret")),
        )) assertEquals(PluginAdminApiStatus.BAD_REQUEST, api.handle(request().copy(query = query)).status)
        assertEquals(emptyList(), gateway.calls)
    }

    @Test
    fun `errors are sanitized and cancellation propagates`() = runBlocking {
        val gateway = Gateway().apply { error = WeiboLoginException("SUB=secret-cookie") }
        val api = WeiboFeedApi { gateway }
        val result = api.handle(request())
        assertEquals("login_required", result.body.jsonObject.getValue("status").jsonPrimitive.content)
        assertFalse(result.body.toString().contains("secret"))
        gateway.error = IllegalStateException("SUB=secret-cookie")
        assertFalse(api.handle(request()).body.toString().contains("secret"))
        gateway.error = CancellationException("cancel")
        assertFailsWith<CancellationException> { api.handle(request()) }
    }

    @Test
    fun `missing numeric IDs overflow IDs and repeating cursors fail without skipping page`() = runBlocking {
        for (id in listOf("notDecimal", "9223372036854775808")) {
            val gateway = Gateway().apply { page = WeiboTimelinePage(listOf(post(id, "99"))) }
            assertEquals(PluginAdminApiStatus.CONFLICT, WeiboFeedApi { gateway }.handle(request()).status)
        }
        val gateway = Gateway().apply { page = WeiboTimelinePage(listOf(post("109", "99")), "same") }
        assertEquals(PluginAdminApiStatus.CONFLICT, WeiboFeedApi { gateway }.handle(request("same")).status)
    }

    @Test
    fun `full page exports bounded observed content and marks partial text`() = runBlocking {
        val gateway = Gateway().apply {
            page = WeiboTimelinePage((100..124).map { id ->
                post(id.toString(), "42").copy(text = "文".repeat(20_000))
            })
        }
        val posts = WeiboFeedApi { gateway }.handle(request()).body.jsonObject.getValue("posts").jsonArray
        assertEquals(25, posts.size)
        for (post in posts) {
            assertEquals(10_000, post.jsonObject.getValue("text").jsonPrimitive.content.length)
            assertEquals("true", post.jsonObject.getValue("content_truncated").jsonPrimitive.content)
        }
        gateway.page = WeiboTimelinePage(listOf(post("125", "42").copy(
            reposted = post("90", "77").copy(isLongText = true),
        )))
        val partial = WeiboFeedApi { gateway }.handle(request()).body.jsonObject.getValue("posts").jsonArray.single()
        assertEquals("true", partial.jsonObject.getValue("content_truncated").jsonPrimitive.content)
    }

    @Test
    fun `deadline returns unavailable and releases feed lock for retry`() = runBlocking {
        val gateway = Gateway().apply { pageDelay = 10_000 }
        val api = WeiboFeedApi(timeoutMillis = 30) { gateway }
        val response = api.handle(request())
        assertEquals(PluginAdminApiStatus.CONFLICT, response.status)
        assertEquals("unavailable", response.body.jsonObject.getValue("status").jsonPrimitive.content)
        gateway.pageDelay = 0
        assertEquals(PluginAdminApiStatus.OK, api.handle(request()).status)
    }

    @Test
    fun `five second request spacing completes within feed deadline without extra profile lookup`() = runBlocking {
        val config = WeiboPublisherConfig(requestIntervalSeconds = 5.0)
        val gateway = Gateway().apply {
            requestIntervalMillis = (config.requestIntervalSeconds * 1_000).toLong()
            page = WeiboTimelinePage(listOf(post("125", "42")))
        }
        val response = WeiboFeedApi { gateway }.handle(request())
        assertEquals(PluginAdminApiStatus.OK, response.status)
        assertEquals(listOf("login", "publisher:42", "page:null"), gateway.calls)
    }

    private fun request(cursor: String? = null) = PluginAdminApiRequest(
        method = "GET", path = "feed", query = buildMap {
            put("uid", listOf("42"))
            cursor?.let { put("cursor", listOf(it)) }
        },
    )

    private fun post(id: String, uid: String) = WeiboPostSnapshot(
        postId = "bid$id", numericId = id, userId = uid, text = "短文", createdAtEpochSeconds = 123,
        url = "https://weibo.com/$uid/bid$id",
    )

    private class Gateway : WeiboGateway {
        val calls = mutableListOf<String>()
        var page = WeiboTimelinePage()
        var login = PublisherLoginStatus.SUCCESS
        var follow = FollowState.FOLLOWING
        var error: Exception? = null
        var pageDelay: Long = 0
        var requestIntervalMillis: Long = 0
        override suspend fun checkLoginState(): PublisherLoginResult {
            calls += "login"
            delay(requestIntervalMillis)
            error?.let { throw it }
            return PublisherLoginResult(login, "private login details")
        }
        override suspend fun queryFollowState(userId: String): FollowState {
            calls += "follow:$userId"
            return follow
        }
        override suspend fun fetchPublisherSnapshot(userId: String): WeiboPublisherSnapshot {
            calls += "publisher:$userId"
            delay(requestIntervalMillis)
            return WeiboPublisherSnapshot(userId, "测试作者", following = when (follow) {
                FollowState.FOLLOWING -> true
                FollowState.NOT_FOLLOWING -> false
                FollowState.UNSUPPORTED -> null
            })
        }
        override suspend fun fetchFollowTimelinePage(cursor: String?): WeiboTimelinePage {
            calls += "page:$cursor"
            delay(pageDelay + requestIntervalMillis)
            return page
        }
        override suspend fun enrichPost(post: WeiboPostSnapshot): WeiboPostSnapshot =
            error("Feed must not enrich posts")
    }
}
