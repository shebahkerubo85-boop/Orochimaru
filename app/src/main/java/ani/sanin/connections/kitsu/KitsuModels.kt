package ani.sanin.connections.kitsu

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A JSON:API document.
 *
 * `data` is deliberately untyped: Kitsu returns a single object for `/anime/:id` and
 * `/installments/:id` but an array for `/anime?filter[id]=...` and every relationship
 * route. Read it through [dataList] or [dataOne] instead of assuming a shape.
 */
@Serializable
data class KitsuDocument(
    @SerialName("data") val data: JsonElement? = null,
    @SerialName("included") val included: List<KitsuResource>? = null,
    @SerialName("meta") val meta: KitsuMeta? = null,
)

@Serializable
data class KitsuMeta(
    @SerialName("count") val count: Int = 0,
)

/**
 * `data` as a list, covering both the array form and a lone object.
 *
 * `null: []` is deliberately empty rather than a null resource: a `null` id and a `null`
 * entry mean the same thing to every caller here, and returning an empty list keeps the
 * `?:` chains at each call site readable.
 */
fun KitsuDocument.dataList(): List<KitsuResource> = when (val d = data) {
    is JsonArray -> d.mapNotNull { element ->
        element.jsonObjectOrNull?.let { kitsuJson.decodeFromJsonElement<KitsuResource>(it) }
    }
    is JsonObject -> listOfNotNull(
        kitsuJson.decodeFromJsonElement<KitsuResource>(d)
    )
    else -> emptyList()
}

/** `data` as a single resource, or null when absent or an array. */
fun KitsuDocument.dataOne(): KitsuResource? = dataList().firstOrNull()

/** This element as an object, or null when it is a primitive. */
private val JsonElement.jsonObjectOrNull: JsonObject?
    get() = this as? JsonObject

/**
 * One node of a JSON:API document. Kitsu spreads type, id, attributes and relationships
 * across separate keys, so a single class covers every resource shape this client reads.
 */
@Serializable
data class KitsuResource(
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    /**
     * Left untyped because a resource's attributes depend on its type, and the same client
     * reads both `anime` and `installment` documents. Decode per type with
     * [animeAttributes] and [installmentAttributes].
     */
    @SerialName("attributes") val attributes: JsonElement? = null,
    @SerialName("relationships") val relationships: Map<String, KitsuRelationship>? = null,
)

@Serializable
data class KitsuRelationship(
    @SerialName("links") val links: KitsuLinks? = null,
)

@Serializable
data class KitsuLinks(
    @SerialName("related") val related: String? = null,
)

/**
 * The anime attributes this client reads.
 *
 * Nulls are the norm here: Kitsu returns a null for anything it does not have, and
 * `idMal` in particular is null for many titles, which is why the MAL id cannot be used
 * to bridge AniList to Kitsu. See [Kitsu.franchiseCard].
 */
@Serializable
data class KitsuAnimeAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("startDate") val startDate: String? = null,
    @SerialName("seasonYear") val seasonYear: Int? = null,
    @SerialName("showType") val showType: String? = null,
    @SerialName("posterImage") val posterImage: KitsuPosterImage? = null,
    @SerialName("slug") val slug: String? = null,
)

@Serializable
data class KitsuTitles(
    @SerialName("en") val en: String? = null,
    @SerialName("en_jp") val enJp: String? = null,
    @SerialName("ja_jp") val jaJp: String? = null,
)

/**
 * The installment attributes, which is where Kitsu's curated `position` lives.
 *
 * Unreachable at the moment: every request that serializes an installment resource returns
 * HTTP 500, while the relationship routes return 200. See [Kitsu.curatedOrder].
 */
@Serializable
data class KitsuInstallmentAttributes(
    @SerialName("position") val position: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
    @SerialName("releaseDate") val releaseDate: String? = null,
)

/** Decodes this resource's attributes as an anime. Null when they are not. */
fun KitsuResource.animeAttributes(): KitsuAnimeAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuAnimeAttributes>(it) }

/** Decodes this resource's attributes as an installment. Null when they are not. */
fun KitsuResource.installmentAttributes(): KitsuInstallmentAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuInstallmentAttributes>(it) }

/**
 * A franchise's own title, which is the real franchise name.
 *
 * Preferred over a seed anime's title for [Franchise.name]: Naruto the franchise is called
 * "Naruto" even when the seed is "Naruto: Shippuden".
 */
@Serializable
data class KitsuFranchiseAttributes(
    @SerialName("canonicalTitle") val canonicalTitle: String? = null,
    @SerialName("titles") val titles: KitsuTitles? = null,
)

/** Decodes this resource's attributes as a franchise. Null when they are not. */
fun KitsuResource.franchiseAttributes(): KitsuFranchiseAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuFranchiseAttributes>(it) }

/**
 * A mapping row's AniList id.
 *
 * Separate from [KitsuAnimeAttributes] because a mapping is a third resource type, with an
 * `externalId` attribute instead of any anime field.
 */
@Serializable
data class KitsuMappingAttributes(
    @SerialName("externalSite") val externalSite: String? = null,
    @SerialName("externalId") val externalId: String? = null,
)

/** Decodes this resource's attributes as a mapping row. Null when they are not. */
fun KitsuResource.mappingAttributes(): KitsuMappingAttributes? =
    attributes?.let { kitsuJson.decodeFromJsonElement<KitsuMappingAttributes>(it) }

@Serializable
data class KitsuPosterImage(
    @SerialName("tiny") val tiny: String? = null,
    @SerialName("small") val small: String? = null,
    @SerialName("medium") val medium: String? = null,
    @SerialName("large") val large: String? = null,
    @SerialName("original") val original: String? = null,
)

internal val kitsuJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
