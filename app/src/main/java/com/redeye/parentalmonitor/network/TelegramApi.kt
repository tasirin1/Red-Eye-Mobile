package com.redeye.parentalmonitor.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

data class TelegramMessage(
    @SerializedName("chat_id") val chatId: String,
    @SerializedName("text") val text: String,
    @SerializedName("parse_mode") val parseMode: String = "HTML",
    @SerializedName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null
)

data class InlineButton(
    @SerializedName("text") val text: String,
    @SerializedName("callback_data") val callbackData: String
)

data class InlineKeyboardMarkup(
    @SerializedName("inline_keyboard") val inlineKeyboard: List<List<InlineButton>>
)

data class TelegramResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("result") val result: JsonElement?
)

data class TelegramChat(
    @SerializedName("id") val id: Long
)

data class TelegramIncomingMessage(
    @SerializedName("message_id") val messageId: Long,
    @SerializedName("chat") val chat: TelegramChat,
    @SerializedName("text") val text: String?,
    @SerializedName("date") val date: Long = 0
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    @SerializedName("message") val message: TelegramIncomingMessage?,
    @SerializedName("callback_query") val callbackQuery: TelegramCallbackQuery? = null
)

data class TelegramUser(
    @SerializedName("id") val id: Long
)

data class TelegramCallbackQuery(
    @SerializedName("id") val id: String,
    @SerializedName("from") val from: TelegramUser?,
    @SerializedName("data") val data: String?,
    @SerializedName("message") val message: TelegramIncomingMessage?
)

data class TelegramUpdatesResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("result") val result: List<TelegramUpdate>?
)

data class BotCommand(
    @SerializedName("command") val command: String,
    @SerializedName("description") val description: String
)

data class SetMyCommandsRequest(
    @SerializedName("commands") val commands: List<BotCommand>
)

interface TelegramApi {
    @GET
    suspend fun getUpdates(
        @Url url: String
    ): Response<TelegramUpdatesResponse>

    @POST
    suspend fun sendMessage(
        @Url url: String,
        @Body message: TelegramMessage
    ): Response<TelegramResponse>

    @POST
    suspend fun answerCallbackQuery(
        @Url url: String,
        @Body body: Map<String, String>
    ): Response<TelegramResponse>

    @POST
    suspend fun setMyCommands(
        @Url url: String,
        @Body body: SetMyCommandsRequest
    ): Response<TelegramResponse>
    
    @Multipart
    @POST
    suspend fun sendAudio(
        @Url url: String,
        @Part("chat_id") chatId: RequestBody,
        @Part("caption") caption: RequestBody?,
        @Part audio: MultipartBody.Part
    ): Response<TelegramResponse>

    @Multipart
    @POST
    suspend fun sendPhoto(
        @Url url: String,
        @Part("chat_id") chatId: RequestBody,
        @Part("caption") caption: RequestBody?,
        @Part photo: MultipartBody.Part
    ): Response<TelegramResponse>
}

object TelegramClient {
    private const val BASE_URL = "https://api.telegram.org/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: TelegramApi = retrofit.create(TelegramApi::class.java)
}
