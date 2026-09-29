package com.crispy.tv.screenshot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Base64

/**
 * Pins the one fact [LandscapeCardScreenshotTest] depends on: under Robolectric
 * a `data:` image model decodes and a `file://` model does not.
 *
 * This exists because of a real failure, not a hypothetical one. The card
 * golden originally pointed `artworkUrl` at the committed fixture as a
 * `file://` URI — which Coil fetches fine on a device, and which Robolectric's
 * `ImageDecoder` shadow fails with
 * `ImageDecoder$DecodeException: Only supported on Android`. The card never
 * reported a load and the golden timed out. Isolating it took four
 * measurements, and the useful one is the contrast: the *same bytes* as a
 * `data:` URI decode to 240x135.
 *
 * The tempting wrong conclusion is "Robolectric cannot decode images", which
 * is false — `BitmapFactory.decodeFile` and
 * `ImageDecoder.createSource(ByteBuffer.wrap(bytes))` both read the fixture.
 * The distinction is the source shape Coil passes to the decoder, and the
 * narrowest useful claim is the one this test makes.
 *
 * Keeping it costs one file and makes the golden's choice self-explaining: a
 * future reader who swaps the `data:` URI back to a file path gets a failing
 * test at the point of the swap rather than a hanging golden.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotTestApplication::class, sdk = [35])
class CoilDataUriScreenshotTest {

    @Test
    fun aDataUriDecodesAndAFileUriDoesNot(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = ImageLoader.Builder(context).build()
        val fixture = artworkFixture()

        val fromDataUri = loader.execute(imageRequest(context, dataUriOf(fixture)))
        assertTrue(
            "a data: URI must decode under Robolectric; got $fromDataUri",
            fromDataUri is SuccessResult,
        )
        assertEquals(ARTWORK_WIDTH, (fromDataUri as SuccessResult).image.width)
        assertEquals(ARTWORK_HEIGHT, fromDataUri.image.height)

        val fromFileUri = loader.execute(imageRequest(context, fixture.toURI().toString()))
        assertTrue(
            "this test's whole point is that a file:// model fails here. If it " +
                "now succeeds, Robolectric's ImageDecoder shadow has been fixed " +
                "and the card golden's data: URI can become a file:// one again.",
            fromFileUri is ErrorResult,
        )
    }

    private fun imageRequest(context: Context, data: String) =
        ImageRequest.Builder(context)
            .data(data)
            .size(ARTWORK_WIDTH, ARTWORK_HEIGHT)
            .build()

    private fun dataUriOf(fixture: File) =
        "data:image/png;base64," + Base64.getEncoder().encodeToString(fixture.readBytes())

    private fun artworkFixture(): File = File(
        requireNotNull(javaClass.classLoader?.getResource(ARTWORK_RESOURCE)) {
            "the committed artwork fixture must be on the test classpath"
        }.toURI(),
    ).also {
        assertTrue("artwork fixture is not a file: $it", it.isFile)
    }

    private companion object {
        const val ARTWORK_RESOURCE = "screenshots/landscape_card_artwork.png"
        const val ARTWORK_WIDTH = 240
        const val ARTWORK_HEIGHT = 135
    }
}
