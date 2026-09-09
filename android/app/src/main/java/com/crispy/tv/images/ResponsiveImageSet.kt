package com.crispy.tv.images

import com.crispy.tv.backend.CrispyBackendClient

internal fun CrispyBackendClient.ResponsiveImageSet.toUiResponsiveImageSet(): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = small,
        medium = medium,
        high = large,
    )
}
