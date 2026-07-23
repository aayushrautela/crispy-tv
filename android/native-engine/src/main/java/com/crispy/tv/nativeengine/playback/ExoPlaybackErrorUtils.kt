package com.crispy.tv.nativeengine.playback

import androidx.media3.common.PlaybackException

internal fun PlaybackException.isDecoderFailure(): Boolean =
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
    )

internal fun PlaybackException.isSourceError(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
        errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
        cause?.toString()?.contains("UnrecognizedInputFormatException") == true

internal fun PlaybackException.isDrmError(): Boolean =
    errorCode in setOf(
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
    )
