package com.crispy.tv.details

import android.content.Context
import android.text.format.DateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The locale-aware half of every date this app shows, built once so the two screens
 * that need it agree on what "the user's date format" means.
 *
 * These three renderings are deliberately *not* in `commonMain`, and the reason is
 * the same for all of them: `android.text.format.DateFormat` follows the device's
 * configured date and time patterns, and `DateTimeFormatter.ofPattern("MMMM yyyy",
 * Locale.getDefault())` follows the device's language. `formatIso8601LongDate` in
 * `:core-domain` is the portable formatter and it is *locale-invariant* — routing
 * either of these through it would print a different string than the user has
 * learned to read, which is a behaviour change dressed as a port.
 *
 * So the decision ("which arm of the copy is this", "which month is this relative
 * to") lives in `commonMain` where it can be tested, and only the rendering crosses
 * as a slot. This class is the composition root for those slots, which is why it
 * takes a `Context`: none of the three can be built without one.
 *
 * Returned as a bundle rather than three separate functions so a composable call
 * site can hold all of them in one `remember`.
 */
class LocaleDateFormatters(
    /** Renders an epoch-millis instant as a full date in the device's format. */
    val date: (Long) -> String,
    /** Renders an epoch-millis instant as a time of day in the device's format. */
    val time: (Long) -> String,
    /** Renders a `"yyyy-MM"` month key as a localised month name and year. */
    val monthName: (String) -> String,
)

/**
 * [LocaleDateFormatters] bound to [context]'s current configuration and locale.
 *
 * `DateFormat.getDateFormat` and `getTimeFormat` read the user's 12/24-hour and
 * date-order settings, and `getTimeZone` is deliberately *not* consulted: the
 * epoch-millis values these receive are already computed in the zone the caller
 * chose (see `civilMonthKey` in `:core-domain`, which takes an explicit UTC offset
 * for exactly this reason), and a formatter with its own zone would silently
 * shift them a second time.
 */
fun localeDateFormatters(context: Context): LocaleDateFormatters {
    val dateFormat = DateFormat.getDateFormat(context)
    val timeFormat = DateFormat.getTimeFormat(context)
    val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    return LocaleDateFormatters(
        date = { epochMillis -> dateFormat.format(java.util.Date(epochMillis)) },
        time = { epochMillis -> timeFormat.format(java.util.Date(epochMillis)) },
        monthName = { monthKey -> YearMonth.parse(monthKey).format(monthFormatter) },
    )
}

/**
 * Hands [text] to the platform share sheet as a `text/plain` payload.
 *
 * This is the androidMain half of the `shareText` slot: `android.content.Intent`
 * and `android.net.Uri` cannot be named in a `commonMain` signature at all, so
 * `DetailsHeader` builds the message and this builds the Intent. The chooser title
 * lives here rather than at the call site for the same reason -- it is part of the
 * platform behaviour, not of the message.
 *
 * `FLAG_ACTIVITY_NEW_TASK` is required because [context] is an application context
 * in the navigation graph, and starting an activity from a non-activity context
 * without it throws.
 */
fun shareOnCrispy(context: Context, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = android.content.Intent.createChooser(intent, "Share on Crispy")
    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
