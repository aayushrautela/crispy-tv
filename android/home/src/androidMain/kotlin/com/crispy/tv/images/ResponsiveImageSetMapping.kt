package com.crispy.tv.images

// Aliased: this module's own `ResponsiveImageSet` is in `commonMain` and the
// backend DTO carries the same name, so the wire type has to be named here.
import com.crispy.tv.backend.ResponsiveImageSet as BackendResponsiveImageSet

/**
 * Bridging between the backend's wire shape and this module's model.
 *
 * `androidMain` rather than `commonMain` because these are the only things in
 * `:android:home` that reach for `:android:backend`, and a domain source set has
 * no business depending on an HTTP adapter. The DTO stays a DTO: renaming its
 * `small`/`medium`/`large` fields to match `ImageQuality` would put a UI
 * preference in the wire contract.
 */
fun BackendResponsiveImageSet.toUiResponsiveImageSet(): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = small,
        medium = medium,
        high = large,
    )
}

internal fun BackendResponsiveImageSet.toDomainMap(): Map<String, String?> {
    return mapOf(
        "small" to small,
        "medium" to medium,
        "large" to large,
    )
}

internal fun responsiveImageSetFromDomainMap(values: Map<String, String?>): ResponsiveImageSet {
    return ResponsiveImageSet(
        low = values["small"],
        medium = values["medium"],
        high = values["large"],
    )
}
