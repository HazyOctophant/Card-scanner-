package com.hazyoctophant.cardvault.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

private val usd = NumberFormat.getCurrencyInstance(Locale.US)
private val eur = NumberFormat.getCurrencyInstance(Locale.US).apply { currency = Currency.getInstance("EUR") }

fun formatUsd(value: Double): String = usd.format(value)

fun formatMoney(value: Double, currency: String): String = when (currency) {
    "USD" -> usd.format(value)
    "EUR" -> eur.format(value)
    "TIX" -> "%.2f tix".format(value)
    else -> "%.2f %s".format(value, currency)
}

/** "+12.5%" style change between two values, or null if there's nothing to compare. */
fun percentChange(now: Double, before: Double?): Double? =
    if (before == null || before <= 0.0) null else (now - before) / before * 100

fun formatPercent(p: Double): String = (if (p >= 0) "+" else "−") + "%.1f%%".format(abs(p))

fun formatSignedUsd(delta: Double): String = (if (delta >= 0) "+" else "−") + usd.format(abs(delta))

private val dateTime = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)

fun formatTimestamp(millis: Long): String =
    if (millis <= 0) "never" else dateTime.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
