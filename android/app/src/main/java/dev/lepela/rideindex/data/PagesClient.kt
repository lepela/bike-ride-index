package dev.lepela.rideindex.data

import dev.lepela.rideindex.model.Published
import dev.lepela.rideindex.model.parsePublished
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GitHub Pages 의 latest.json · rules.json 조회. 실패하면 null. */
class PagesClient(private val baseUrl: String) {
    suspend fun latest(): Published? = withContext(Dispatchers.IO) {
        runCatching { parsePublished(httpGet(urlFor(baseUrl, "latest.json"))) }.getOrNull()
    }

    suspend fun rules(): String? = withContext(Dispatchers.IO) {
        runCatching { httpGet(urlFor(baseUrl, "rules.json")) }.getOrNull()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://lepela.github.io/bike-ride-index/"

        fun urlFor(base: String, file: String) = base.trimEnd('/') + "/" + file
    }
}
