package com.redeye.parentalmonitor.network

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
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
    @SerializedName("parse_mode") val parseMode: String? = "HTML",
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

data class EditMessageRequest(
    @SerializedName("chat_id") val chatId: String,
    @SerializedName("message_id") val messageId: Long,
    @SerializedName("text") val text: String,
    @SerializedName("parse_mode") val parseMode: String? = "HTML",
    @SerializedName("reply_markup") val replyMarkup: InlineKeyboardMarkup? = null
)

data class TelegramChat(
    @SerializedName("id") val id: Long
)

data class TelegramIncomingMessage(
    @SerializedName("chat") val chat: TelegramChat,
    @SerializedName("message_id") val messageId: Long = 0,
    @SerializedName("from") val from: TelegramUser? = null,
    @SerializedName("text") val text: String?,
    @SerializedName("caption") val caption: String? = null,
    @SerializedName("document") val document: TelegramDocument? = null,
    @SerializedName("date") val date: Long = 0,
    @SerializedName("edit_date") val editDate: Long = 0
)

data class TelegramDocument(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("file_size") val fileSize: Long = 0L,
    @SerializedName("mime_type") val mimeType: String? = null
)

data class TelegramFile(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_path") val filePath: String? = null,
    @SerializedName("file_size") val fileSize: Long = 0L
)

data class TelegramFileResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("result") val result: TelegramFile?
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    @SerializedName("message") val message: TelegramIncomingMessage?,
    @SerializedName("edited_message") val editedMessage: TelegramIncomingMessage? = null,
    @SerializedName("channel_post") val channelPost: TelegramIncomingMessage? = null,
    @SerializedName("edited_channel_post") val editedChannelPost: TelegramIncomingMessage? = null,
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

data class BotCommandScope(
    @SerializedName("type") val type: String,
    @SerializedName("chat_id") val chatId: Long? = null
)

data class SetMyCommandsRequest(
    @SerializedName("commands") val commands: List<BotCommand>,
    @SerializedName("scope") val scope: BotCommandScope? = null
)

data class DeleteCommandsRequest(
    @SerializedName("scope") val scope: BotCommandScope? = null
)

interface TelegramApi {
    @GET
    suspend fun getUpdates(
        @Url url: String
    ): Response<TelegramUpdatesResponse>

    @GET
    suspend fun getMe(
        @Url url: String
    ): Response<TelegramResponse>

    @GET
    suspend fun getChat(
        @Url url: String
    ): Response<TelegramResponse>

    @GET
    suspend fun getFile(
        @Url url: String
    ): Response<TelegramFileResponse>

    @GET
    suspend fun downloadFile(
        @Url url: String
    ): Response<okhttp3.ResponseBody>

    @POST
    suspend fun deleteMessage(
        @Url url: String,
        @Body body: Map<String, String>
    ): Response<TelegramResponse>

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
    suspend fun editMessageText(
        @Url url: String,
        @Body body: EditMessageRequest
    ): Response<TelegramResponse>

    @POST
    suspend fun setMyCommands(
        @Url url: String,
        @Body body: SetMyCommandsRequest
    ): Response<TelegramResponse>

    @POST
    suspend fun deleteMyCommands(
        @Url url: String,
        @Body body: DeleteCommandsRequest
    ): Response<TelegramResponse>

    @POST
    suspend fun deleteWebhook(
        @Url url: String,
        @Body body: Map<String, String>
    ): Response<TelegramResponse>

    @Multipart
    @POST
    suspend fun sendAudio(
        @Url url: String,
        @Part("chat_id") chatId: RequestBody,
        @Part("caption") caption: RequestBody?,
        @Part audio: MultipartBody.Part,
        @Part("reply_markup") replyMarkup: RequestBody?
    ): Response<TelegramResponse>

    @Multipart
    @POST
    suspend fun sendPhoto(
        @Url url: String,
        @Part("chat_id") chatId: RequestBody,
        @Part("caption") caption: RequestBody?,
        @Part photo: MultipartBody.Part,
        @Part("reply_markup") replyMarkup: RequestBody?
    ): Response<TelegramResponse>

    @Multipart
    @POST
    suspend fun sendVideo(
        @Url url: String,
        @Part("chat_id") chatId: RequestBody,
        @Part("caption") caption: RequestBody?,
        @Part video: MultipartBody.Part,
        @Part("reply_markup") replyMarkup: RequestBody?
    ): Response<TelegramResponse>
}

private object SharedNet {
    const val BASE_URL = "https://api.telegram.org/"
    val pool = ConnectionPool(4, 1, TimeUnit.MINUTES)
}

object TelegramClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .dispatcher(Dispatcher().apply {
            maxRequests = 6
            maxRequestsPerHost = 4
        })
        .connectionPool(SharedNet.pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(SharedNet.BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: TelegramApi = retrofit.create(TelegramApi::class.java)
}

object TelegramMediaClient {

    private val mediaOkHttp = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .dispatcher(Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 2
        })
        .connectionPool(SharedNet.pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    private val mediaRetrofit = Retrofit.Builder()
        .baseUrl(SharedNet.BASE_URL)
        .client(mediaOkHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: TelegramApi = mediaRetrofit.create(TelegramApi::class.java)
}
object TelegramPollClient {
    private val pollOkHttp = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
        .dispatcher(Dispatcher().apply {
            maxRequests = 2
            maxRequestsPerHost = 2
        })
        .connectionPool(SharedNet.pool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private val pollRetrofit = Retrofit.Builder()
        .baseUrl(SharedNet.BASE_URL)
        .client(pollOkHttp)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: TelegramApi = pollRetrofit.create(TelegramApi::class.java)
}
