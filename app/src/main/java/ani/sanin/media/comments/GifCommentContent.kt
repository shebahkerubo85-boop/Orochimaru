package ani.sanin.media.comments

/**
 * Matches `![alt](https://...)` gif markdown. The URL may contain balanced
 * parentheses (e.g. Tenor slugs like `hello-(1).gif`).
 */
private val GIF_IMAGE_REGEX =
    Regex("""!\[[^\]]*\]\((https?://(?:[^()\s]|\([^()\s]*\))*)\)""")

/** Image markdown whose alt text mentions "gif" but whose URL broke the strict match. */
private val GIF_ALT_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]\([^)]*\)""", RegexOption.IGNORE_CASE)

/** Image markdown with a `.gif` URL that broke the strict match. */
private val GIF_URL_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*\]\([^)]*\.gif[^)]*\)""", RegexOption.IGNORE_CASE)

/** Image markdown with a "gif" alt text and no destination at all. */
private val GIF_BARE_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]""", RegexOption.IGNORE_CASE)

/** Image markdown with a "gif" alt text and an unclosed destination paren. */
private val GIF_UNCLOSED_MARKDOWN_REGEX =
    Regex("""!\[[^\]]*gif[^\]]*\]\(https?://[^)]*""", RegexOption.IGNORE_CASE)

data class GifCommentContent(
    val text: String,
    val gifUrl: String?,
    val gifAbove: Boolean,
)

/**
 * Splits a comment into display text and an optional gif URL. Gif markdown that
 * cannot be matched (spaces, unbalanced parens, missing destination) is stripped
 * from the text so the alt text "gif" never renders as literal text.
 */
fun parseGifCommentContent(content: String): GifCommentContent {
    val match = GIF_IMAGE_REGEX.find(content)
    if (match != null) {
        val gifUrl = match.groupValues.getOrNull(1)
        val gifAbove = content.substring(0, match.range.first).isBlank()
        return GifCommentContent(
            text = stripGifMarkdown(content.replace(GIF_IMAGE_REGEX, "")),
            gifUrl = gifUrl,
            gifAbove = gifAbove,
        )
    }
    return GifCommentContent(
        text = stripGifMarkdown(content),
        gifUrl = null,
        gifAbove = false,
    )
}

/** Removes gif image markdown that commonmark or plain-text views would show verbatim. */
fun stripGifMarkdown(text: String): String = text
    .replace(GIF_ALT_MARKDOWN_REGEX, "")
    .replace(GIF_URL_MARKDOWN_REGEX, "")
    .replace(GIF_UNCLOSED_MARKDOWN_REGEX, "")
    .replace(GIF_BARE_MARKDOWN_REGEX, "")
    .trim()
