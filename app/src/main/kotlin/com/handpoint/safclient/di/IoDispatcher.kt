package com.handpoint.safclient.di

import javax.inject.Qualifier

/**
 * Marks the dispatcher for blocking work, such as the first `connect()` that creates the SDK.
 * Injected so tests can run that work on a test dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
