package com.hazyoctophant.cardvault.data.remote

/** EUR→USD rate from the European Central Bank via frankfurter.app (no key needed). */
class ExchangeRates(private val http: Http) {
    suspend fun eurToUsd(): Double? =
        runCatching { http.get("https://api.frankfurter.app/latest?from=EUR&to=USD").obj()?.obj("rates")?.num("USD") }
            .getOrNull()
}
