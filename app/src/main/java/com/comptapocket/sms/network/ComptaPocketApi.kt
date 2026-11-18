package com.comptapocket.sms.network

import com.comptapocket.sms.data.local.ClientReminderEntity
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface ComptaPocketApi {

    @GET("api/reminders")
    suspend fun getReminders(): Response<Map<String, ClientReminderEntity>>

    @POST("api/reminders")
    suspend fun createReminder(@Body reminder: ClientReminderEntity): Response<ReminderResponse>

    @PUT("api/reminders/{id}")
    suspend fun updateReminder(
        @Path("id") id: String,
        @Body reminder: ClientReminderEntity
    ): Response<ClientReminderEntity>
}

data class ReminderResponse(
    val id: String,
    val data: ClientReminderEntity
)
