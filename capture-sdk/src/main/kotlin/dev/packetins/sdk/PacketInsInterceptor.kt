package dev.packetins.sdk

import okhttp3.Interceptor
import okhttp3.Response

class PacketInsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
