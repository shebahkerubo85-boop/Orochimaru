package ani.sanin.others.svg

import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

/**
 * Loads a CloudStream repo/plugin logo into an [ImageView]. Routes `.svg`
 * sources through [SvgDecoder] (PictureDrawable transcode chain) and everything
 * else through Glide's normal bitmap path.
 */
object SvgImageLoader {

    fun load(imageView: ImageView, url: String?, placeholder: Int, error: Int) {
        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            imageView.setImageResource(error)
            return
        }
        val context = imageView.context
        if (url.contains(".svg", ignoreCase = true)) {
            val requestOptions = RequestOptions()
            context.getDrawable(placeholder)?.let { requestOptions.placeholder(it) }
            context.getDrawable(error)?.let { requestOptions.error(it) }
            Glide.with(imageView)
                .as(PictureDrawable::class.java)
                .load(url)
                .apply(requestOptions)
                .into(imageView)
        } else {
            Glide.with(imageView)
                .load(url)
                .placeholder(placeholder)
                .error(error)
                .into(imageView)
        }
    }
}
