package com.crispy.tv.player

/**
 * Thrown when a torrent link is played on a build that ships no torrent engine.
 *
 * A distinct type so the UI can explain why, rather than reporting a generic
 * stream failure.
 */
class TorrentSupportUnavailableException :
    UnsupportedOperationException("Torrent playback is not available in this build")
