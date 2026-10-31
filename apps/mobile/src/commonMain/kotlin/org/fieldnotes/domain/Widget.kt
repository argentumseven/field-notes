package org.fieldnotes.domain

fun widgetMayShowTitles(cache: Cache, deviceUnlocked: Boolean, authenticated: Boolean = true): Boolean = authenticated && deviceUnlocked && cache.preferences.widgetTitles && cache.token != null && cache.snapshot != null
