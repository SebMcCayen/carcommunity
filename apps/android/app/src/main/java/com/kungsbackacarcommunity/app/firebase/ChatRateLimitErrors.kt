package com.kungsbackacarcommunity.app.firebase

import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.chat.ChatRateLimit
import com.kungsbackacarcommunity.app.chat.ChatRateLimits

/** Only the explicitly tagged SEND quota is a chat pause. Auth and other quotas retain their mapping. */
fun Throwable.chatRateLimit(): ChatRateLimit? {
    val error = this as? FirebaseFunctionsException ?: return null
    return ChatRateLimits.parse(error.code.name, error.details)
}
