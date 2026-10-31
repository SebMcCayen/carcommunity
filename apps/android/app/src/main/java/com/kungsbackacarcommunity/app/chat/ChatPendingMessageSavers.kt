package com.kungsbackacarcommunity.app.chat

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import com.kungsbackacarcommunity.app.chatchannels.ChannelDeliveryState
import com.kungsbackacarcommunity.app.chatchannels.ChannelMessage
import com.kungsbackacarcommunity.app.chatchannels.ChannelReplyTo
import com.kungsbackacarcommunity.app.chatchannels.ChannelSendError
import com.kungsbackacarcommunity.app.dm.DmDeliveryState
import com.kungsbackacarcommunity.app.dm.DmMessage
import com.kungsbackacarcommunity.app.dm.DmReplyTo
import com.kungsbackacarcommunity.app.dm.DmSendError

// Explicit Bundle-safe fields only: no model Serializable changes or serialization dependency.
val ChannelPendingMessageSaver: Saver<ChannelMessage, Any> = mapSaver(
    save = { message ->
        mapOf(
            "id" to message.id,
            "senderUid" to message.senderUid,
            "text" to message.text,
            "senderDisplayName" to message.senderDisplayName,
            "senderAvatarPath" to message.senderAvatarPath,
            "createdAtMillis" to message.createdAtMillis,
            "createdAtIso" to message.createdAtIso,
            "mentionedUids" to ArrayList(message.mentionedUids),
            "clientId" to message.clientId,
            "deliveryState" to message.deliveryState.name,
            "sendError" to message.sendError?.name,
            "replyId" to message.replyTo?.messageId,
            "replySenderUid" to message.replyTo?.senderUid,
            "replySenderDisplayName" to message.replyTo?.senderDisplayName,
            "replyTextPreview" to message.replyTo?.textPreview,
        )
    },
    restore = { fields ->
        ChannelMessage(
            id = fields["id"] as String,
            senderUid = fields["senderUid"] as String,
            text = fields["text"] as String,
            senderDisplayName = fields["senderDisplayName"] as String?,
            senderAvatarPath = fields["senderAvatarPath"] as String?,
            createdAtMillis = fields["createdAtMillis"] as Long?,
            createdAtIso = fields["createdAtIso"] as String?,
            mentionedUids = (fields["mentionedUids"] as List<*>).map { it as String },
            clientId = fields["clientId"] as String?,
            deliveryState = ChannelDeliveryState.valueOf(fields["deliveryState"] as String),
            sendError = (fields["sendError"] as String?)?.let { ChannelSendError.valueOf(it) },
            replyTo = (fields["replyId"] as String?)?.let { id ->
                ChannelReplyTo(id, fields["replySenderUid"] as String,
                    fields["replySenderDisplayName"] as String?, fields["replyTextPreview"] as String)
            },
        )
    },
)

val DmPendingMessageSaver: Saver<DmMessage, Any> = mapSaver(
    save = { message ->
        mapOf(
            "id" to message.id,
            "senderUid" to message.senderUid,
            "text" to message.text,
            "createdAtMillis" to message.createdAtMillis,
            "createdAtIso" to message.createdAtIso,
            "clientId" to message.clientId,
            "deliveryState" to message.deliveryState.name,
            "sendError" to message.sendError?.name,
            "replyId" to message.replyTo?.messageId,
            "replySenderUid" to message.replyTo?.senderUid,
            "replySenderDisplayName" to message.replyTo?.senderDisplayName,
            "replyTextPreview" to message.replyTo?.textPreview,
        )
    },
    restore = { fields ->
        DmMessage(
            id = fields["id"] as String,
            senderUid = fields["senderUid"] as String,
            text = fields["text"] as String,
            createdAtMillis = fields["createdAtMillis"] as Long?,
            createdAtIso = fields["createdAtIso"] as String?,
            clientId = fields["clientId"] as String?,
            deliveryState = DmDeliveryState.valueOf(fields["deliveryState"] as String),
            sendError = (fields["sendError"] as String?)?.let { DmSendError.valueOf(it) },
            replyTo = (fields["replyId"] as String?)?.let { id ->
                DmReplyTo(id, fields["replySenderUid"] as String,
                    fields["replySenderDisplayName"] as String?, fields["replyTextPreview"] as String)
            },
        )
    },
)
