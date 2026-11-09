package org.koitharu.kotatsu.parsers.site.ru

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.HttpStatusException
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import org.koitharu.kotatsu.parsers.util.json.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("WAMANGA", "WaManga", "ru", type = ContentType.MANGA)
internal class WaMangaParser(
	context: MangaLoaderContext,
) : PagedMangaParser(context, MangaParserSource.WAMANGA, pageSize = 24) {

	override val configKeyDomain = ConfigKey.Domain("wamanga.ru")

	// Server-side sorting: /api/v1/manga accepts sortKey in
	// createdAt | updatedAt | publicDate | views | likes | alphabetical | chapters (anything else is a 400).
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.UPDATED_ASC,
		SortOrder.POPULARITY,
		SortOrder.POPULARITY_ASC,
		SortOrder.RATING,
		SortOrder.RATING_ASC,
		SortOrder.NEWEST,
		SortOrder.NEWEST_ASC,
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isYearSupported = true,
			isYearRangeSupported = true,
		)

	// The API has no genres endpoint (GET /api/v1/genres -> 404) and the catalog input is free text,
	// so tags are the raw site strings. The site's tag data is messy (500+ variants with typos and
	// split words), so the filter offers the tags that occur on 10+ of the ~2400 titles; every entry
	// still keeps its verbatim tags, which is what the server matches by exact string.
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = GENRES.map { genreTag(it) }.toSet(),
	)

	private fun apiUrl() = "https://${domain}/api/v1"

	private fun getPublicUrl(jo: JSONObject) = "https://$domain/${jo.getString("type")}/${jo.getString("slug")}"

	private fun sortKey(order: SortOrder): Pair<String, Boolean> = when (order) {
		SortOrder.UPDATED, SortOrder.UPDATED_ASC -> "updatedAt" to (order == SortOrder.UPDATED)
		SortOrder.POPULARITY, SortOrder.POPULARITY_ASC -> "views" to (order == SortOrder.POPULARITY)
		SortOrder.RATING, SortOrder.RATING_ASC -> "likes" to (order == SortOrder.RATING)
		SortOrder.NEWEST, SortOrder.NEWEST_ASC -> "createdAt" to (order == SortOrder.NEWEST)
		SortOrder.ALPHABETICAL, SortOrder.ALPHABETICAL_DESC -> "alphabetical" to (order == SortOrder.ALPHABETICAL_DESC)
		else -> "updatedAt" to true
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val (key, descending) = sortKey(order)
		val yearFrom = filter.year.takeIf { it > 0 } ?: filter.yearFrom.takeIf { it > 0 } ?: 0
		val yearTo = filter.year.takeIf { it > 0 } ?: filter.yearTo.takeIf { it > 0 } ?: 0
		val url = buildString {
			append(apiUrl())
			append("/manga?limit=").append(pageSize)
			append("&offset=").append((page - 1) * pageSize)
			append("&sortKey=").append(key)
			append("&sortDescending=").append(descending)
			filter.query?.takeIf { it.isNotEmpty() }?.let {
				append("&query=").append(it.urlEncoded())
			}
			for (tag in filter.tags) {
				append("&genres=").append(tag.key.urlEncoded())
			}
			for (tag in filter.tagsExclude) {
				append("&excludedGenres=").append(tag.key.urlEncoded())
			}
			if (yearFrom > 0) {
				append("&releaseYearFrom=").append(yearFrom)
			}
			if (yearTo > 0) {
				append("&releaseYearTo=").append(yearTo)
			}
		}
		val list = webClient.httpGet(url).parseJsonArray().mapJSON { parseManga(it) }
		return resolveCovers(list)
	}

	private fun parseManga(jo: JSONObject): Manga {
		val id = jo.getString("id")
		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = getPublicUrl(jo),
			title = jo.getString("title"),
			altTitles = collectAltTitles(jo),
			coverUrl = jo.getStringOrNull("coverUrl")?.toAbsoluteUrl(domain),
			largeCoverUrl = jo.getStringOrNull("imageUrl")?.toAbsoluteUrl(domain),
			tags = parseTags(jo),
			state = parseState(jo.getStringOrNull("statusTitle")),
			authors = jo.optJSONArray("authors")?.asTypedList<String>()?.toSet().orEmpty(),
			rating = RATING_UNKNOWN,
			contentRating = if (jo.getBooleanOrDefault("isAdult", false)) {
				ContentRating.ADULT
			} else {
				ContentRating.SAFE
			},
			source = source,
		)
	}

	// The site's file storage has lost about a third of the uploads: cover.webp 404s
	// permanently, and banner.webp died with it in most cases. Probe which files exist
	// and keep whatever is available instead of URLs that only render 404 placeholders.
	private suspend fun resolveCovers(list: List<Manga>): List<Manga> = coroutineScope {
		val semaphore = Semaphore(COVER_PROBE_CONCURRENCY)
		list.map { manga ->
			async { semaphore.withPermit { manga.withAvailableCover() } }
		}.awaitAll()
	}

	private suspend fun Manga.withAvailableCover(): Manga {
		val cover = coverUrl
		if (cover != null && doesFileExist(cover)) {
			return this
		}
		val banner = largeCoverUrl?.takeIf { it != cover }
		if (banner != null && doesFileExist(banner)) {
			return copy(coverUrl = banner, largeCoverUrl = banner)
		}
		if (cover == null && banner == null) {
			return this
		}
		return copy(coverUrl = null, largeCoverUrl = null)
	}

	private suspend fun doesFileExist(url: String): Boolean = runCatching {
		webClient.httpHead(url).use { response -> response.code in 200..299 }
	}.recoverCatching { error ->
		when (error) {
			is NotFoundException -> false
			is HttpStatusException -> false
			else -> throw error
		}
	}.getOrDefault(false)

	override suspend fun getDetails(manga: Manga): Manga {
		val jo = webClient.httpGet("${apiUrl()}/manga/${manga.url}").parseJson()
		val chaptersJson = webClient.httpGet("${apiUrl()}/manga/${manga.url}/chapters").parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", sourceLocale)
		val scanlator = jo.optJSONArray("teams")
			?.mapJSON { it.getStringOrNull("name") }
			?.filterNotNull()
			?.joinToString()
			?.takeUnless { it.isEmpty() }
		val details = manga.copy(
			publicUrl = getPublicUrl(jo),
			altTitles = collectAltTitles(jo),
			description = jo.getStringOrNull("description"),
			tags = parseTags(jo),
			authors = jo.optJSONArray("authors")?.asTypedList<String>()?.toSet().orEmpty(),
			state = parseState(jo.getStringOrNull("statusTitle")),
			largeCoverUrl = jo.getStringOrNull("imageUrl")?.toAbsoluteUrl(domain) ?: manga.largeCoverUrl,
			chapters = chaptersJson.asTypedList<JSONObject>().mapChapters { _, it ->
				val chapterId = it.getString("id")
				val position = it.getFloatOrDefault("position", 0f)
				MangaChapter(
					id = generateUid(chapterId),
					url = chapterId,
					title = it.getStringOrNull("title")?.takeUnless { t -> t.isEmpty() },
					number = position,
					volume = 0,
					scanlator = scanlator,
					uploadDate = dateFormat.parseSafe(it.getStringOrNull("createdAt")),
					branch = null,
					source = source,
				)
			},
		)
		val resolved = details.withAvailableCover()
		val large = resolved.largeCoverUrl
		return if (large == null || large == resolved.coverUrl || doesFileExist(large)) {
			resolved
		} else {
			resolved.copy(largeCoverUrl = resolved.coverUrl)
		}
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		return webClient.httpGet("${apiUrl()}/chapters/${chapter.url}")
			.parseJson()
			.getJSONArray("files")
			.mapJSON { file ->
				val img = file.getString("diskFile").toAbsoluteUrl(domain)
				MangaPage(
					id = generateUid(img),
					url = img,
					preview = null,
					source = source,
				)
			}
	}

	private fun collectAltTitles(jo: JSONObject): Set<String> = buildSet {
		jo.getStringOrNull("titleEnglish")?.takeUnless { it.isEmpty() }?.let(::add)
		jo.optJSONArray("alternateTitles")?.asTypedList<String>()?.forEach { if (it.isNotEmpty()) add(it) }
	}

	private fun parseTags(jo: JSONObject): Set<MangaTag> {
		return jo.optJSONArray("genres")?.asTypedList<String>()?.mapNotNullToSet { name ->
			if (name.isEmpty()) {
				null
			} else {
				genreTag(name)
			}
		}.orEmpty()
	}

	private fun genreTag(name: String) = MangaTag(
		title = name.toTitleCase(sourceLocale),
		key = name,
		source = source,
	)

	// API statuses: ongoing, completed, hiatus, cancelled, unknown, abandoned, announced.
	private fun parseState(status: String?) = when (status?.lowercase(sourceLocale)) {
		"ongoing" -> MangaState.ONGOING
		"finished", "completed" -> MangaState.FINISHED
		"abandoned", "cancelled" -> MangaState.ABANDONED
		"paused", "frozen", "hiatus" -> MangaState.PAUSED
		"announced" -> MangaState.UPCOMING
		else -> null
	}

	private companion object {
		val GENRES: Set<String> = setOf(
			"16+", "18+", "Алхимия", "Антигерой",
			"Апокалипсис", "Аристократия", "Армия", "Артефакты",
			"Безумие", "Боги", "Боевик", "Боевые",
			"Боевые искусства", "Бои", "Бои на мечах", "Борьба",
			"Борьба за власть", "Брат и сестра", "Будущее", "Вампиры",
			"Веб", "Видеоигры", "Владыка демонов", "Военные",
			"Война", "Волшебники", "Волшебники / маги", "Волшебные",
			"Волшебные существа", "Воспоминания", "Воспоминания из другого мира", "Выживание",
			"ГГ", "ГГ женщина", "ГГ имба", "ГГ мужчина",
			"Гарем", "Гг женщина", "Героическое фэнтези", "Гильдии",
			"Гоблины", "Горничные", "Демоны", "Детектив",
			"Дзёсэй", "Драконы", "Драма", "Дружба",
			"Жестокий", "Жестокий мир", "Животные компаньоны", "Зверолюди",
			"Игра", "Игровые элементы", "Империи", "Исекай",
			"История", "Квесты", "Комедия", "Культивирование",
			"Легендарное оружие", "Магическая академия", "Магия", "Манга",
			"Мафия", "Месть", "Мистика", "Монстры",
			"Музыка", "Мурим", "Навыки", "Навыки / способности",
			"Насилие", "Насилие / жестокость", "Наёмники", "Нежить",
			"Огнестрельное оружие", "Отношения", "Офисные работники", "Повседневность",
			"Подземелья", "Политика", "Полиция", "Преступники / Криминал",
			"Преступники / криминал", "Призраки / Духи", "Приключения", "Психология",
			"Путешествие во времени", "Работа", "Рабы", "Разумные расы",
			"Ранги силы", "Регрессия", "Реинкарнация", "Романтика",
			"Рыцари", "Сверхъестественное", "Система", "Скрытие личности",
			"Спасение мира", "Спорт", "Спортивное", "Спортивное тело",
			"Средневековье", "Супер сила", "Сэйнэн", "Сёдзё",
			"Сёнэн", "Трагедия", "Триллер", "Ужасы",
			"Умный", "Умный ГГ", "Учитель", "Фантастика",
			"Философия", "Фэнтези", "Хентай", "Холодное оружие",
			"Шантаж", "Школа", "Экшен", "Элементы юмора",
			"Эльфы", "Эротика", "Этти", "Япония",
			"власть", "другого", "женщина", "жестокость",
			"за", "из", "искусства", "маги",
			"мечах", "мир", "мира", "мужчина",
			"на", "способности", "существа", "тело",
			"цвете",
		)

		private const val COVER_PROBE_CONCURRENCY = 4
	}
}
