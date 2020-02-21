/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.ilink;

import org.agrona.LangUtil;
import org.agrona.sbe.MessageEncoderFlyweight;
import uk.co.real_logic.artio.messages.DisconnectReason;
import uk.co.real_logic.artio.protocol.GatewayPublication;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static uk.co.real_logic.artio.library.SessionConfiguration.AUTOMATIC_INITIAL_SEQUENCE_NUMBER;
import static uk.co.real_logic.artio.messages.DisconnectReason.LOGOUT;

// NB: This is an experimental API and is subject to change or potentially removal.
public class ILink3Session
{
    private static final long MICROS_IN_MILLIS = 1_000;
    private static final long NANOS_IN_MICROS = 1_000;
    private static final long NANOS_IN_MILLIS = MICROS_IN_MILLIS * NANOS_IN_MICROS;

    public enum State
    {
        /** TCP connection established, negotiate not sent.*/
        CONNECTED,
        /** Negotiate sent but no reply received */
        SENT_NEGOTIATE,

        NEGOTIATE_REJECTED,
        /** Negotiate accepted, Establish not sent */
        NEGOTIATED,
        /** Negotiate accepted, Establish sent */
        SENT_ESTABLISH,
        ESTABLISH_REJECTED,
        /** Establish accepted, messages can be exchanged */
        ESTABLISHED,
        UNBINDING,
        SENT_TERMINATE,
        UNBOUND
    }

    private final AbstractILink3Proxy proxy;
    private final ILink3SessionConfiguration configuration;
    private final long connectionId;
    private final Consumer<ILink3Session> onEstablished;
    private final GatewayPublication outboundPublication;
    private final int libraryId;
    private final ILink3SessionOwner owner;

    private final long uuid;
    private State state;
    private int nextSentSeqNo;

    ILink3Session(
        final AbstractILink3Proxy proxy,
        final ILink3SessionConfiguration configuration,
        final long connectionId,
        final Consumer<ILink3Session> onEstablished,
        final GatewayPublication outboundPublication,
        final int libraryId,
        final ILink3SessionOwner owner)
    {
        this.proxy = proxy;
        this.configuration = configuration;
        this.connectionId = connectionId;
        this.onEstablished = onEstablished;
        this.outboundPublication = outboundPublication;
        this.libraryId = libraryId;
        this.owner = owner;

        uuid = microSecondTimestamp();
        state = State.CONNECTED;
        nextSentSeqNo = calculateInitialSentSequenceNumber(configuration);

        sendNegotiate();
    }

    // PUBLIC API

    public long claimMessage(
        final MessageEncoderFlyweight message)
    {
        // TODO: set the sequence number appropriately message.sbeTemplateId();
        return proxy.claimILinkMessage(message.sbeBlockLength(), message);
    }

    public void commit()
    {
        proxy.commit();
    }

    public long terminate(final String reason, final int errorCodes)
    {
        validateCanSend();

        final long position = sendTerminate(reason, errorCodes);

        if (position > 0)
        {
            state = State.UNBINDING;
        }

        return position;
    }

    private long sendTerminate(final String reason, final int errorCodes)
    {
        final long requestTimestamp = requestTimestamp();
        return proxy.sendTerminate(
            reason,
            uuid,
            requestTimestamp,
            errorCodes);
    }

    private void validateCanSend()
    {
        if (state != State.ESTABLISHED)
        {
            throw new IllegalStateException("State should be ESTABLISHED in order to send but is " + state);
        }
    }

    public long requestDisconnect(final DisconnectReason reason)
    {
        return outboundPublication.saveRequestDisconnect(libraryId, connectionId, reason);
    }

    public long uuid()
    {
        return uuid;
    }

    public long connectionId()
    {
        return connectionId;
    }

    public State state()
    {
        return state;
    }

    // END PUBLIC API

    private int calculateInitialSentSequenceNumber(final ILink3SessionConfiguration configuration)
    {
        final int initialSentSequenceNumber = configuration.initialSentSequenceNumber();
        if (initialSentSequenceNumber == AUTOMATIC_INITIAL_SEQUENCE_NUMBER)
        {
            return 1; // TODO: persistent sequence numbers
        }
        return initialSentSequenceNumber;
    }

    private long microSecondTimestamp()
    {
        final long microseconds = (NANOS_IN_MICROS * System.nanoTime()) % MICROS_IN_MILLIS;
        return MILLISECONDS.toMicros(System.currentTimeMillis()) + microseconds;
    }

    private long requestTimestamp()
    {
        final long nanoseconds = System.nanoTime() % NANOS_IN_MILLIS;
        return System.currentTimeMillis() * NANOS_IN_MILLIS + nanoseconds;
    }

    private void sendNegotiate()
    {
        final long requestTimestamp = requestTimestamp();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId + '\n' + firmId;
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);

        final long position = proxy.sendNegotiate(
            hMACSignature, configuration.accessKeyId(), uuid, requestTimestamp, sessionId, firmId);

        if (position > 0)
        {
            state = State.SENT_NEGOTIATE;
        }
    }

    private void sendEstablish()
    {
        final long requestTimestamp = requestTimestamp();
        final String sessionId = configuration.sessionId();
        final String firmId = configuration.firmId();
        final String tradingSystemName = configuration.tradingSystemName();
        final String tradingSystemVersion = configuration.tradingSystemVersion();
        final String tradingSystemVendor = configuration.tradingSystemVendor();
        final int keepAliveInterval = configuration.requestedKeepAliveInterval();
        final String accessKeyId = configuration.accessKeyId();

        final String canonicalMsg = String.valueOf(requestTimestamp) + '\n' + uuid + '\n' + sessionId +
            '\n' + firmId + '\n' + tradingSystemName + '\n' + tradingSystemVersion + '\n' + tradingSystemVendor +
            '\n' + nextSentSeqNo + '\n' + keepAliveInterval;
        final byte[] hMACSignature = calculateHMAC(canonicalMsg);


        proxy.sendEstablish(hMACSignature, accessKeyId, tradingSystemName, tradingSystemVendor, tradingSystemVersion,
            uuid, requestTimestamp, nextSentSeqNo, sessionId, firmId, keepAliveInterval);
    }

    private byte[] calculateHMAC(final String canonicalRequest)
    {
        final String userKey = configuration.userKey();

        try
        {
            final Mac sha256HMAC = getHmac();

            // Decode the key first, since it is base64url encoded
            final byte[] decodedUserKey = Base64.getUrlDecoder().decode(userKey);
            final SecretKeySpec secretKey = new SecretKeySpec(decodedUserKey, "HmacSHA256");
            sha256HMAC.init(secretKey);

            // Calculate HMAC
            return sha256HMAC.doFinal(canonicalRequest.getBytes(UTF_8));
        }
        catch (final InvalidKeyException | IllegalStateException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    private Mac getHmac()
    {
        try
        {
            return Mac.getInstance("HmacSHA256");
        }
        catch (final NoSuchAlgorithmException e)
        {
            LangUtil.rethrowUnchecked(e);
            return null;
        }
    }

    int poll(final long timeInMs)
    {
        // TODO: sending retries for backpressure scenarios
        return 0;
    }

    // EVENT HANDLERS

    long onNegotiationResponse(
        final long uUID,
        final long requestTimestamp,
        final int secretKeySecureIDExpiration,
        final long previousSeqNo,
        final long previousUUID)
    {
        if (uUID != uuid())
        {
            // TODO: error
        }

        // TODO: validate request timestamp
        // TODO: calculate session expiration
        // TODO: check gap with previous sequence number and uuid

        state = State.NEGOTIATED;
        sendEstablish();

        return 1; // TODO: move to action
    }

    long onEstablishmentAck(
        final long uUID,
        final long requestTimestamp,
        final long nextSeqNo,
        final long previousSeqNo,
        final long previousUUID,
        final int keepAliveInterval,
        final int secretKeySecureIDExpiration)
    {
        if (uUID != uuid())
        {
            // TODO: error
        }

        // TODO: validate request timestamp
        // TODO: calculate session expiration
        // TODO: check gap with previous sequence number and uuid

        state = State.ESTABLISHED;
        onEstablished.accept(ILink3Session.this);

        return 1;
    }

    long onTerminate(final String reason, final long uUID, final long requestTimestamp, final int errorCodes)
    {
        if (uUID != uuid())
        {
            // TODO: error
        }

        // TODO: validate request timestamp

        // We initiated termination
        if (state == State.UNBINDING)
        {
            unbind();
        }
        // The exchange initiated termination
        else
        {
            // TODO: handle backpressure properly
            sendTerminate(reason, errorCodes);
            unbind();
        }

        return 1;
    }

    private void unbind()
    {
        // TODO: linger state = State.SENT_TERMINATE;
        state = State.UNBOUND;
        requestDisconnect(LOGOUT);
        owner.onUnbind(this);
    }

}
