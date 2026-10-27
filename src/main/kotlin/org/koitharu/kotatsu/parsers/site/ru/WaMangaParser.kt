package org.koitharu.kotatsu.parsers.site.ru

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
) : PagedMangaParser(context, MangaParserSource.WAMANGA, pageSize = 20) {

	override val configKeyDomain = ConfigKey.Domain("wamanga.ru")

	// Swagger spec (https://wamanga.ru/api/docs): sortKey enum =
	// createdAt | updatedAt | publicDate | views | likes | alphabetical | chapters,
	// direction via boolean sortDescending. All verified against the live API.
	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.ALPHABETICAL_DESC,
		SortOrder.POPULARITY,		// views desc
		SortOrder.POPULARITY_ASC,	// views asc
		SortOrder.RATING,			// likes desc (closest to rating the API has)
		SortOrder.RATING_ASC,		// likes asc
		SortOrder.NEWEST,			// createdAt desc
		SortOrder.NEWEST_ASC,		// createdAt asc
		SortOrder.UPDATED,			// updatedAt desc
		SortOrder.UPDATED_ASC,		// updatedAt asc
		SortOrder.ADDED,			// publicDate desc (site's own publication date)
		SortOrder.ADDED_ASC,		// publicDate asc
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
			isMultipleTagsSupported = true,
		)

	// The API has no genres listing endpoint (GET /genres -> 404) and the catalog
	// tag data is messy (300+ typo/split variants like "Фэтези", "Боевые" + "искусства"),
	// so a curated list of canonical genres is used instead.
	// Every entry is verified to work with the `genres` query parameter
	// (server filters by exact string match, multiple genres are AND-combined).
	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = AVAILABLE_GENRES.mapNotNullToSet { name ->
			MangaTag(
				title = name,
				key = name,
				source = source,
			)
		},
	)

	private fun apiUrl() = "https://${domain}/api/v1"

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val offset = (page - 1) * pageSize
		val url = buildString {
			append(apiUrl())
			append("/manga?limit=")
			append(pageSize)
			append("&offset=")
			append(offset)
			filter.query?.takeIf { it.isNotEmpty() }?.let {
				append("&query=")
				append(it.urlEncoded())
			}
			if (filter.tags.isNotEmpty()) {
				append("&genres=")
				append(filter.tags.joinToString(separator = ",") { it.key }.urlEncoded())
			}
			// Server-side sorting (Swagger: sortKey + sortDescending).
			// Kotatsu convention: UPDATED/POPULARITY/RATING/NEWEST/ADDED = descending
			// (best/first items first), *_ASC = ascending, ALPHABETICAL = A→Z (ascending).
			data class SortSpec(val key: String, val desc: Boolean)
			val spec = when (order) {
				SortOrder.ALPHABETICAL -> SortSpec("alphabetical", desc = false)
				SortOrder.ALPHABETICAL_DESC -> SortSpec("alphabetical", desc = true)
				SortOrder.POPULARITY -> SortSpec("views", desc = true)
				SortOrder.POPULARITY_ASC -> SortSpec("views", desc = false)
				SortOrder.RATING -> SortSpec("likes", desc = true)
				SortOrder.RATING_ASC -> SortSpec("likes", desc = false)
				SortOrder.NEWEST -> SortSpec("createdAt", desc = true)
				SortOrder.NEWEST_ASC -> SortSpec("createdAt", desc = false)
				SortOrder.UPDATED -> SortSpec("updatedAt", desc = true)
				SortOrder.UPDATED_ASC -> SortSpec("updatedAt", desc = false)
				SortOrder.ADDED -> SortSpec("publicDate", desc = true)
				SortOrder.ADDED_ASC -> SortSpec("publicDate", desc = false)
				else -> SortSpec("alphabetical", desc = false)
			}
			append("&sortKey=")
			append(spec.key)
			append("&sortDescending=")
			append(spec.desc)
		}
		return webClient.httpGet(url).parseJsonArray().mapJSON { parseManga(it) }
	}

	private fun parseManga(jo: JSONObject): Manga {
		val id = jo.getString("id")
		val slug = jo.getString("slug")
		return Manga(
			id = generateUid(id),
			url = id,
			publicUrl = "https://${domain}/manga/$slug",
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

	override suspend fun getDetails(manga: Manga): Manga {
		val jo = webClient.httpGet("${apiUrl()}/manga/${manga.url}").parseJson()
		val chaptersJson = webClient.httpGet("${apiUrl()}/manga/${manga.url}/chapters").parseJsonArray()
		val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", sourceLocale)
		val scanlator = jo.optJSONArray("teams")
			?.mapJSON { it.getStringOrNull("name") }
			?.filterNotNull()
			?.joinToString()
			?.takeUnless { it.isEmpty() }
		return manga.copy(
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
				MangaTag(
					title = name.toTitleCase(sourceLocale),
					key = name,
					source = source,
				)
			}
		}.orEmpty()
	}

	private fun parseState(status: String?) = when (status?.lowercase(sourceLocale)) {
		"ongoing" -> MangaState.ONGOING
		"finished", "completed" -> MangaState.FINISHED
		"abandoned" -> MangaState.ABANDONED
		"paused", "frozen" -> MangaState.PAUSED
		else -> null
	}

	private companion object {
		private val AVAILABLE_GENRES = listOf(
			"16+", "18+", "Антигерой", "Апокалипсис", "Артефакты", "Боевик", "Боевые искусства",
			"Бои на мечах", "Боги", "Борьба за власть", "Брат и сестра", "Волшебные существа",
			"Волшебники / маги", "Воспоминания из другого мира", "ГГ женщина", "ГГ мужчина",
			"ГГ имба", "Гарем", "Горничные", "Демоны", "Драконы", "Драма", "Дружба", "Жестокий мир",
			"Зверолюди", "Игра", "Игровые элементы", "Империи", "История", "Исекай", "Комедия",
			"Магия", "Манга", "Месть", "Мистика", "Монстры", "Мурим", "Навыки / способности",
			"Наёмники", "Насилие", "Насилие / жестокость", "Отношения", "Психология",
			"Путешествие во времени", "Ранги силы", "Реинкарнация", "Романтика",
			"Сверхъестественное", "Сёдзё", "Сёнэн", "Сэйнэн", "Система", "Скрытие личности",
			"Спорт", "Спортивное тело", "Средневековье", "Трагедия", "Триллер", "Ужасы",
			"Умный ГГ", "Фантастика", "Фэнтези", "Хентай", "Холодное оружие", "Школа", "Эротика", "Этти",
		)
	}
}
