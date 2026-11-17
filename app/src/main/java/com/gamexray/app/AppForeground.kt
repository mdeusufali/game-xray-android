package com.gamexray.app

/**
 * True while this app's own Activity is in the foreground.
 *
 * MediaProjection captures the whole screen, including Game X-Ray's own dashboard and settings.
 * Those frames are not game frames: reading them would feed our own labels, odds, status text and
 * card-zone help text back into the parser and could create false rounds.
 */
object AppForeground {
    @Volatile
    var appActivityResumed: Boolean = false
}
