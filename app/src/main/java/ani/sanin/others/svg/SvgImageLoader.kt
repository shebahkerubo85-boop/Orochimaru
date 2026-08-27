package ani.sanin.others.svg

import android.graphics.drawable.PictureDrawable
import android.widget.ImageView
import com.bumptech.glide.Glide

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
        if (url.contains(".svg", ignoreCase = true)) {
            val context = imageView.context
            val placeholderDrawable = context.getDrawable(placeholder)
            val errorDrawable = context.getDrawable(error)
            Glide.with(imageView)
                .as(PictureDrawable::class.java)
                .load(url)
                .apply {
                    placeholderDrawable?.let { placeholder(it) }
                    errorDrawable?.let { error(it) }
                }
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
