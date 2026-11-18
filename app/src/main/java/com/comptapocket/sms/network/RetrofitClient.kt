package com.comptapocket.sms.network

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private var retrofit: Retrofit? = null
    private var currentIp: String? = null

    fun getApi(context: Context): ComptaPocketApi? {
        val prefs = context.getSharedPreferences("comptapocket_prefs", Context.MODE_PRIVATE)
        val ip = prefs.getString("server_ip", "")?.trim()

        if (ip.isNullOrEmpty()) {
            return null
        }

        val baseUrl = if (ip.startsWith("http://") || ip.startsWith("https://")) {
            if (ip.endsWith("/")) ip else "$ip/"
        } else {
            "http://$ip/"
        }

        if (retrofit == null || currentIp != baseUrl) {
            currentIp = baseUrl
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        return retrofit?.create(ComptaPocketApi::class.java)
    }
}
