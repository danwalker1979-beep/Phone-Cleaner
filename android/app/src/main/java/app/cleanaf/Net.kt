package app.cleanaf

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp client for calls to the Anthropic API. */
val claudeHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
}
