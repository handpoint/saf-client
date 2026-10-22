package com.handpoint.safclient.domain

import javax.inject.Qualifier

/**
 * Marks the app-wide `CoroutineScope`. Work launched in it lives as long as the app process,
 * not as long as a screen, so it finishes even when the merchant leaves the screen.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
