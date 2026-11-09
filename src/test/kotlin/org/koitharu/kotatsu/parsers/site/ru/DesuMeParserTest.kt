package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koitharu.kotatsu.parsers.MangaLoaderContextMock
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.test_util.mangaOf

internal class DesuMeParserTest {

    @Test
    fun migrateLegacyApiMangaUrl() = runTest {
        val parser = MangaLoaderContextMock.newParserInstance(MangaParserSource.DESUME)
        val legacyManga = mangaOf(MangaParserSource.DESUME, "https://desu.uno/manga/api/2602").copy(
            id = parser.generateUid(2602L),
        )

        val details = parser.getDetails(legacyManga)

        assertEquals(legacyManga.id, details.id)
        assertFalse("/manga/api/" in details.url)
        assertTrue(details.url.endsWith(".2602/"))
        assertTrue(details.publicUrl.endsWith(".2602/"))
        assertFalse(details.chapters.isNullOrEmpty())
    }

    @Test
    fun genresParamEncodesExclusions() {
        val parser = DesuMeParser(MangaLoaderContextMock)
        val include = setOf(MangaTag(title = "Т1", key = "90-Dementia", source = parser.source))
        val exclude = setOf(MangaTag(title = "Т2", key = "64-Vampire", source = parser.source))

        val param = parser.buildGenresParam(include, exclude)

        assertEquals("90-Dementia,64-!Vampire", param)
    }

    @Test
    fun listCatalogReturnsEntries() = runTest {
        val parser = DesuMeParser(MangaLoaderContextMock)

        val list = parser.getListPage(1, SortOrder.UPDATED, MangaListFilter.EMPTY)

        assertTrue(list.size >= 10, "Catalog page should not be empty")
        assertTrue(list.all { it.url.matches(Regex("""/manga/[a-z0-9-]+\.\d+/""")) })
    }
}
