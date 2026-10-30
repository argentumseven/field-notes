package org.fieldnotes.domain

fun widgetMayShowTitles(cache: Cache, deviceUnlocked: Boolean): Boolean = deviceUnlocked && cache.preferences.widgetTitles && cache.token != null && cache.snapshot != null
