package com.lagradost.cloudstream3

import android.app.Activity
import com.google.android.gms.cast.framework.CastSession

fun Activity?.getCastSession(): CastSession? {
    // Stub — Cast support handled by fork's own app
    return null
}
