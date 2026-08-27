package ani.sanin.others.svg

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.resource.SimpleResource
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import java.io.IOException
import java.io.InputStream

/**
 * Glide decoder that turns an SVG [InputStream] into a parsed [SVG] document.
 * Registered only for the CloudStream repo/plugin logos.
 */
class SvgDecoder : ResourceDecoder<InputStream, SVG> {

    override fun handles(source: InputStream, options: Options): Boolean = true

    @Throws(IOException::class)
    override fun decode(
        source: InputStream,
        width: Int,
        height: Int,
        options: Options
    ): Resource<SVG> {
        val svg = try {
            SVG.getFromInputStream(source)
        } catch (e: SVGParseException) {
            throw IOException("Cannot load SVG from stream", e)
        }
        // Glide passes SIZE_ORIGINAL (-1) when no override is set; only force
        // dimensions when a real target size is known.
        if (width > 0) svg.setDocumentWidth(width.toFloat())
        if (height > 0) svg.setDocumentHeight(height.toFloat())
        return SimpleResource(svg)
    }
}
