package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.SourceConfigMock
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.toTitleCase

internal class WaMangaParserTest {

	@ParameterizedTest
	@CsvSource(
		"manga, https://wamanga.ru/manga/sample-title",
		"manhwa, https://wamanga.ru/manhwa/sample-title",
		"manhua, https://wamanga.ru/manhua/sample-title",
	)
	fun publicLinksFollowTheTitleType(type: String, expectedUrl: String) = runTest {
		val mangaJson = """
			{"id":"sample-id","slug":"sample-title","title":"Sample title","type":"$type"}
		""".trimIndent()
		val parser = WaMangaParser(TestContext(responder = { path ->
			when (path) {
				"/api/v1/manga" -> "[$mangaJson]"
				"/api/v1/manga/sample-id" -> mangaJson
				"/api/v1/manga/sample-id/chapters" -> "[]"
				else -> error("Unexpected request: $path")
			}
		}))

		val manga = parser.getListPage(1, SortOrder.ALPHABETICAL, MangaListFilter.EMPTY).single()
		assertEquals(expectedUrl, manga.publicUrl)

		val cachedManga = manga.copy(publicUrl = "https://wamanga.ru/manga/old-slug")
		assertEquals(expectedUrl, parser.getDetails(cachedManga).publicUrl)
	}

	@org.junit.jupiter.api.Test
	fun listPageSendsSortAndFilterParameters() = runTest {
		val requests = mutableListOf<okhttp3.HttpUrl>()
		val parser = WaMangaParser(TestContext({ path -> "[]" }) { requests.add(it) })
		val filter = MangaListFilter(
			query = "Механик",
			tags = setOf(MangaTag("Драма", "Драма", MangaParserSource.WAMANGA)),
			tagsExclude = setOf(MangaTag("Эротика", "Эротика", MangaParserSource.WAMANGA)),
			year = 2024,
		)

		parser.getListPage(3, SortOrder.POPULARITY_ASC, filter)

		val url = requests.single()
		assertEquals("/api/v1/manga", url.encodedPath)
		assertEquals("24", url.queryParameter("limit"))
		assertEquals("48", url.queryParameter("offset"))
		assertEquals("views", url.queryParameter("sortKey"))
		assertEquals("false", url.queryParameter("sortDescending"))
		assertEquals("Механик", url.queryParameter("query"))
		assertEquals(listOf("Драма"), url.queryParameterValues("genres"))
		assertEquals(listOf("Эротика"), url.queryParameterValues("excludedGenres"))
		assertEquals("2024", url.queryParameter("releaseYearFrom"))
		assertEquals("2024", url.queryParameter("releaseYearTo"))
	}

	@org.junit.jupiter.api.Test
	fun alphabeticalSortIsAscending() = runTest {
		val requests = mutableListOf<okhttp3.HttpUrl>()
		val parser = WaMangaParser(TestContext({ path -> "[]" }) { requests.add(it) })

		parser.getListPage(1, SortOrder.ALPHABETICAL, MangaListFilter.EMPTY)

		val url = requests.single()
		assertEquals("alphabetical", url.queryParameter("sortKey"))
		assertEquals("false", url.queryParameter("sortDescending"))
	}

	@org.junit.jupiter.api.Test
	fun filterOptionsMatchEntryTagDerivation() = runTest {
		val parser = WaMangaParser(TestContext({ path -> "[]" }))
		val options = parser.getFilterOptions()
		val option = options.availableTags.first { it.key == "Драма" }
		assertEquals("Драма".toTitleCase(), option.title)
	}

	// Each test uses its own upload id: probe results are cached per URL on the parser
	// class level (they are deterministic in production), so ids must not overlap.
	@org.junit.jupiter.api.Test
	fun deadCoverFallsBackToLiveBanner() = runTest {
		val parser = WaMangaParser(
			TestContext(
				{ path -> if (path == "/api/v1/manga") coverJson("probe-list-banner") else error("Unexpected request: $path") },
				headStatus = { url -> if (url.endsWith("cover.webp")) 404 else 200 },
			),
		)

		val manga = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()

		assertEquals("https://wamanga.ru/app/uploads/probe-list-banner/banner.webp", manga.coverUrl)
		assertEquals(manga.coverUrl, manga.largeCoverUrl)
	}

	@org.junit.jupiter.api.Test
	fun deadCoverWithoutLiveBannerIsNulls() = runTest {
		val parser = WaMangaParser(
			TestContext(
				{ path -> if (path == "/api/v1/manga") coverJson("probe-list-none") else error("Unexpected request: $path") },
				headStatus = { 404 },
			),
		)

		val manga = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()

		assertNull(manga.coverUrl)
		assertNull(manga.largeCoverUrl)
	}

	@org.junit.jupiter.api.Test
	fun deadBannerFallsBackToCoverInDetails() = runTest {
		val detailsJson = coverJson("probe-details").removePrefix("[").removeSuffix("]")
		val parser = WaMangaParser(
			TestContext(
				{ path ->
					when (path) {
						"/api/v1/manga" -> coverJson("probe-details")
						"/api/v1/manga/probe-details" -> detailsJson
						"/api/v1/manga/probe-details/chapters" -> "[]"
						else -> error("Unexpected request: $path")
					}
				},
				headStatus = { url -> if (url.endsWith("banner.webp")) 404 else 200 },
			),
		)
		val listed = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()

		val details = parser.getDetails(listed)

		assertEquals("https://wamanga.ru/app/uploads/probe-details/cover.webp", details.largeCoverUrl)
		assertEquals(details.coverUrl, details.largeCoverUrl)
	}

	@org.junit.jupiter.api.Test
	fun probesAreCachedForRepeatedLoads() = runTest {
		val heads = mutableListOf<String>()
		val parser = WaMangaParser(
			TestContext(
				{ path -> if (path == "/api/v1/manga") coverJson("probe-cache") else error("Unexpected request: $path") },
				headStatus = { url ->
					heads.add(url)
					if (url.endsWith("cover.webp")) 404 else 200
				},
			),
		)

		val first = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY).single()
		assertEquals("https://wamanga.ru/app/uploads/probe-cache/banner.webp", first.coverUrl)
		val headsAfterFirst = heads.size
		assertNotEquals(0, headsAfterFirst)

		parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)

		assertEquals(headsAfterFirst, heads.size, "repeated load must reuse cached probe results")
	}

	private fun coverJson(id: String) =
		"""[{"id":"$id","slug":"sample-title","title":"Sample title","type":"manga",""" +
			""""coverUrl":"/app/uploads/$id/cover.webp","imageUrl":"/app/uploads/$id/banner.webp"}]"""

	private class TestContext(
		private val responder: (path: String) -> String,
		private val headStatus: (url: String) -> Int = { 200 },
		private val recorder: (okhttp3.HttpUrl) -> Unit = {},
	) : MangaLoaderContext() {
		override val cookieJar: CookieJar = CookieJar.NO_COOKIES
		override val httpClient: OkHttpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				val request = chain.request()
				recorder(request.url)
				val isHead = request.method == "HEAD"
				val code = if (isHead) headStatus(request.url.toString()) else 200
				val body = if (isHead) {
					ByteArray(0).toResponseBody(null)
				} else {
					responder(request.url.encodedPath).toResponseBody("application/json".toMediaType())
				}
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(code)
					.message("OK")
					.body(body)
					.build()
			}
			.build()

		override fun getConfig(source: MangaSource) = SourceConfigMock()
		override fun getDefaultUserAgent(): String = "TestAgent/1.0"
		@Deprecated("Test")
		override suspend fun evaluateJs(script: String): String? = null
		override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? = null
		override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response = response
		override fun createBitmap(width: Int, height: Int): Bitmap = throw UnsupportedOperationException()
	}
}
