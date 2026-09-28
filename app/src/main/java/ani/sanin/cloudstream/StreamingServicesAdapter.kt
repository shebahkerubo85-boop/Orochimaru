package ani.sanin.cloudstream

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import ani.sanin.R
import ani.sanin.connections.tmdb.Tmdb
import ani.sanin.connections.tmdb.TmdbProvider
import ani.sanin.databinding.ItemStreamingServiceBinding
import ani.sanin.getThemeColor
import ani.sanin.setSafeOnClickListener

/**
 * The streaming-services rail: a horizontal strip of service cards for the app's region.
 * Tapping one opens that service's catalogue. This browses; it plays nothing.
 *
 * Each card is a WIDE tile filled with the brand's own colour — read off the logo by
 * [StreamingLogoTint] — with the square logo drawn on top. Because the fill is the logo's
 * exact background, the two blend with no inner edge. A logo that cannot be read keeps a
 * neutral plate, which is the honest fallback rather than a wrong colour.
 *
 * Sizes match Zangetsu: 128x70 phone, 168x92 TV, the latter growing because TV sits further
 * from the eye.
 *
 * There is no "Streaming Services" header here, so the See All is the last CARD in the rail
 * (Zangetsu's TV treatment) rather than a text button beside a title — the D-pad simply
 * walks onto it.
 */
class StreamingServicesAdapter(
    private val isTv: Boolean,
    private val onServiceClick: (TmdbProvider) -> Unit,
    private val onSeeAll: () -> Unit,
    /**
     * The Explore rail ends in a See All card, because it is the way to the full list.
     * That list itself is nothing but cards, so it renders the same tiles with the
     * trailing card and the cap turned off.
     */
    private val showSeeAll: Boolean = true,
    /** The rail is capped so a long tail never pushes the majors out of reach. */
    private val limit: Int = MAX_SERVICES
) : RecyclerView.Adapter<StreamingServicesAdapter.ServiceViewHolder>() {

    private var providers: List<TmdbProvider> = emptyList()

    /**
     * Grid geometry, handed in by the host; zero width means this adapter is in a rail.
     *
     * The rail is a fixed 128x70 tile, but the full services screen is a grid, and that
     * fixed width is what made its tiles overlap. GridLayoutManager hands a child the
     * cell's width and then lays it out at that size without clamping, so a tile asking
     * for 128dp inside a 109dp cell simply spilled over its neighbour. Zangetsu cannot
     * hit that because Flutter's SliverGrid forces the child to the cell and it passes
     * the card double.infinity, which is why its grid is clean at any width. So in a
     * grid the tile is measured to the cell here too, rather than assuming a width.
     */
    private var gridTileWidthPx = 0
    private var gridTileHeightPx = 0
    private var gridCrossSpacingPx = 0
    private var gridMainSpacingPx = 0

    /**
     * Sizes the tiles to one grid cell. The host recomputes this on rotation, because
     * this screen is not recreated when the device turns.
     */
    fun setGridTileSize(widthPx: Int, heightPx: Int, crossSpacingPx: Int, mainSpacingPx: Int) {
        if (gridTileWidthPx == widthPx && gridTileHeightPx == heightPx) return
        gridTileWidthPx = widthPx
        gridTileHeightPx = heightPx
        gridCrossSpacingPx = crossSpacingPx
        gridMainSpacingPx = mainSpacingPx
        notifyDataSetChanged()
    }

    /** Enough to fill a phone rail twice over without waiting on a long tail nobody scrolls to. */
    fun submit(list: List<TmdbProvider>) {
        providers = list.take(limit)
        notifyDataSetChanged()
    }

    fun clear() {
        if (providers.isEmpty()) return
        providers = emptyList()
        notifyDataSetChanged()
    }

    // +1 for the trailing See All card, which the full-services screen does not draw.
    override fun getItemCount(): Int = providers.size + if (showSeeAll) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (showSeeAll && position == providers.size) TYPE_SEE_ALL else TYPE_SERVICE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServiceViewHolder {
        val binding = ItemStreamingServiceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ServiceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServiceViewHolder, position: Int) {
        if (holder.itemViewType == TYPE_SEE_ALL) {
            holder.bindSeeAll()
        } else {
            holder.bindService(providers[position])
        }
    }

    override fun onViewRecycled(holder: ServiceViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    inner class ServiceViewHolder(
        val binding: ItemStreamingServiceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            // Sizes are applied per bind: a service is a fixed tile, the See All arrow is
            // its natural size.
        }

        private fun sizeTile() {
            val density = binding.root.resources.displayMetrics.density
            val lp = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            if (gridTileWidthPx > 0) {
                // A grid cell, measured rather than assumed: see setGridTileSize.
                lp.width = gridTileWidthPx
                lp.height = gridTileHeightPx
                lp.marginEnd = gridCrossSpacingPx
                lp.marginBottom = gridMainSpacingPx
            } else {
                val w = if (isTv) 168 else 128
                val h = if (isTv) 92 else 70
                lp.width = (w * density).toInt()
                lp.height = (h * density).toInt()
                lp.marginEnd = (GAP_DP * density).toInt()
                lp.marginBottom = (RAIL_MARGIN_BOTTOM_DP * density).toInt()
            }
            binding.root.layoutParams = lp
        }

        private fun sizeArrow() {
            val density = binding.root.resources.displayMetrics.density
            val lp = (binding.root.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.marginEnd = (4 * density).toInt()
            binding.root.layoutParams = lp
        }

        fun bindService(provider: TmdbProvider) {
            val card = binding.streamingServiceCard
            val logo = binding.streamingServiceLogo
            sizeTile()
            card.contentDescription = provider.displayName
            binding.streamingServiceSeeAll.isVisible = false
            logo.isVisible = true
            logo.scaleX = 1f
            logo.scaleY = 1f
            card.setSafeOnClickListener { onServiceClick(provider) }

            val ctx = card.context
            // Neutral plate until a tint is known, so a recycled card never shows the
            // previous service's colour.
            applyPlate(card, ctx.getThemeColor(com.google.android.material.R.attr.colorSurfaceVariant))

            val logoUrl = Tmdb.imageUrl(provider.logoPath, LOGO_WIDTH) ?: return
            // A tint already read this session paints on the first frame.
            StreamingLogoTint.cached(logoUrl)?.let {
                applyTint(card, logo, it)
                return@let
            }
            if (!StreamingLogoTint.isKnown(logoUrl) && !StreamingLogoTint.beginLoad(logoUrl)) return

            Glide.with(logo)
                .asBitmap()
                .load(logoUrl)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .override(LOGO_SAMPLE_PX, LOGO_SAMPLE_PX)
                .into(object : CustomTarget<android.graphics.Bitmap>() {
                    override fun onResourceReady(
                        resource: android.graphics.Bitmap,
                        transition: Transition<in android.graphics.Bitmap>?
                    ) {
                        val tint = StreamingLogoTint.sample(resource)
                        StreamingLogoTint.put(logoUrl, tint)
                        if (tint != null) applyTint(card, logo, tint)
                        logo.setImageBitmap(resource)
                    }

                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        logo.setImageDrawable(null)
                    }

                    override fun onLoadFailed(errorDrawable: android.graphics.drawable.Drawable?) {
                        StreamingLogoTint.failLoad(logoUrl)
                    }
                })
        }

        fun bindSeeAll() {
            val card = binding.streamingServiceCard
            val logo = binding.streamingServiceLogo
            sizeArrow()
            card.contentDescription = "See all streaming services"
            binding.streamingServiceSeeAll.isVisible = true
            logo.isVisible = false
            card.setSafeOnClickListener { onSeeAll() }
            // Bare arrow, like the row-header More buttons — no brand tile behind it.
            card.background = null
        }

        fun clear() {
            binding.streamingServiceLogo.setImageDrawable(null)
        }

        /** Rounded plate in [colour]; a gradient fill replaces it once a tint is known. */
        private fun applyPlate(card: android.view.View, colour: Int) {
            card.background = GradientDrawable().apply {
                cornerRadius = CORNER_DP * card.resources.displayMetrics.density
                setColor(colour)
            }
        }

        private fun applyTint(card: android.view.View, logo: ImageView, tint: StreamingTint) {
            card.background = GradientDrawable(
                if (tint.vertical) GradientDrawable.Orientation.TOP_BOTTOM
                else GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(tint.start, tint.end)
            ).apply {
                cornerRadius = CORNER_DP * card.resources.displayMetrics.density
            }
            // Enlarging pushes the logo's own edges past the card, but those edges are the
            // logo's background — the same colour the card is filled with — so nothing
            // visible is cropped.
            logo.scaleX = tint.contentScale
            logo.scaleY = tint.contentScale
        }
    }

    private companion object {
        const val TYPE_SERVICE = 0
        const val TYPE_SEE_ALL = 1
        const val MAX_SERVICES = 12
        const val GAP_DP = 10

        /**
         * The rail's row spacing. It lives here rather than on the layout because the
         * grid overrides it, and a value set in two places would be one of them wrong.
         */
        const val RAIL_MARGIN_BOTTOM_DP = 8
        const val CORNER_DP = 14f

        /** Provider logos are 332x332 on TMDB; 185 is the size the CDN serves natively. */
        const val LOGO_WIDTH = 185

        /** Requested above native so the edge sampling and mark box stay crisp. */
        const val LOGO_SAMPLE_PX = 320
    }
}
