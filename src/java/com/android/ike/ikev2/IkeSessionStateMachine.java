/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.ike.ikev2;

import android.annotation.IntDef;
import android.net.IpSecManager;
import android.net.IpSecManager.ResourceUnavailableException;
import android.os.Looper;
import android.os.Message;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.SparseArray;

import com.android.ike.ikev2.IkeSessionOptions.IkeAuthConfig;
import com.android.ike.ikev2.SaRecord.IkeSaRecord;
import com.android.ike.ikev2.crypto.IkeCipher;
import com.android.ike.ikev2.crypto.IkeMacIntegrity;
import com.android.ike.ikev2.crypto.IkeMacPrf;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.ike.ikev2.message.IkeAuthPskPayload;
import com.android.ike.ikev2.message.IkeDeletePayload;
import com.android.ike.ikev2.message.IkeHeader;
import com.android.ike.ikev2.message.IkeIdPayload;
import com.android.ike.ikev2.message.IkeKePayload;
import com.android.ike.ikev2.message.IkeMessage;
import com.android.ike.ikev2.message.IkeNoncePayload;
import com.android.ike.ikev2.message.IkeNotifyPayload;
import com.android.ike.ikev2.message.IkePayload;
import com.android.ike.ikev2.message.IkeSaPayload;
import com.android.ike.ikev2.message.IkeSaPayload.DhGroupTransform;
import com.android.ike.ikev2.message.IkeTsPayload;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * IkeSessionStateMachine tracks states and manages exchanges of this IKE session.
 *
 * <p>IkeSessionStateMachine has two types of states. One type are states where there is no ongoing
 * procedure affecting IKE session (non-procedure state), including Initial, Closed, Idle and
 * Receiving. All other states are "procedure" states which are named as follows:
 *
 * <pre>
 * State Name = [Procedure Type] + [Exchange Initiator] + [Exchange Type].
 * - An IKE procedure consists of one or two IKE exchanges:
 *      Procedure Type = {CreateIke | DeleteIke | Info | RekeyIke | SimulRekeyIke}.
 * - Exchange Initiator indicates whether local or remote peer is the exchange initiator:
 *      Exchange Initiator = {Local | Remote}
 * - Exchange type defines the function of this exchange. To make it more descriptive, we separate
 *      Delete Exchange from generic Informational Exchange:
 *      Exchange Type = {IkeInit | IkeAuth | Create | Delete | Info}
 * </pre>
 */
public class IkeSessionStateMachine extends StateMachine {

    private static final String TAG = "IkeSessionStateMachine";

    // Package private IKE exchange subtypes describe the specific function of a IKE
    // request/response exchange. It helps IkeSessionStateMachine to do message validation according
    // to the subtype specific rules.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        IKE_EXCHANGE_SUBTYPE_IKE_INIT,
        IKE_EXCHANGE_SUBTYPE_IKE_AUTH,
        IKE_EXCHANGE_SUBTYPE_DELETE_IKE,
        IKE_EXCHANGE_SUBTYPE_DELETE_CHILD,
        IKE_EXCHANGE_SUBTYPE_REKEY_IKE,
        IKE_EXCHANGE_SUBTYPE_REKEY_CHILD,
        IKE_EXCHANGE_SUBTYPE_GENERIC_INFO
    })
    @interface IkeExchangeSubType {}

    static final int IKE_EXCHANGE_SUBTYPE_IKE_INIT = 1;
    static final int IKE_EXCHANGE_SUBTYPE_IKE_AUTH = 2;
    static final int IKE_EXCHANGE_SUBTYPE_CREATE_CHILD = 3;
    static final int IKE_EXCHANGE_SUBTYPE_DELETE_IKE = 4;
    static final int IKE_EXCHANGE_SUBTYPE_DELETE_CHILD = 5;
    static final int IKE_EXCHANGE_SUBTYPE_REKEY_IKE = 6;
    static final int IKE_EXCHANGE_SUBTYPE_REKEY_CHILD = 7;
    static final int IKE_EXCHANGE_SUBTYPE_GENERIC_INFO = 8;

    /** Package private signals accessible for testing code. */
    private static final int CMD_GENERAL_BASE = 0;
    /** Receive encoded IKE packet on IkeSessionStateMachine. */
    static final int CMD_RECEIVE_IKE_PACKET = CMD_GENERAL_BASE + 1;
    /** Receive locally built payloads from Child Session for building outbound IKE message. */
    static final int CMD_RECEIVE_OUTBOUND_CHILD_PAYLOADS = CMD_GENERAL_BASE + 2;
    /** Receive encoded IKE packet with unrecognized IKE SPI on IkeSessionStateMachine. */
    static final int CMD_RECEIVE_PACKET_INVALID_IKE_SPI = CMD_GENERAL_BASE + 3;
    // TODO: Add signal for retransmission.

    private static final int CMD_LOCAL_REQUEST_BASE = CMD_GENERAL_BASE + 100;
    static final int CMD_LOCAL_REQUEST_CREATE_IKE = CMD_LOCAL_REQUEST_BASE + 1;
    static final int CMD_LOCAL_REQUEST_DELETE_IKE = CMD_LOCAL_REQUEST_BASE + 2;
    static final int CMD_LOCAL_REQUEST_REKEY_IKE = CMD_LOCAL_REQUEST_BASE + 3;
    static final int CMD_LOCAL_REQUEST_INFO = CMD_LOCAL_REQUEST_BASE + 4;
    static final int CMD_LOCAL_REQUEST_CREATE_CHILD = CMD_LOCAL_REQUEST_BASE + 5;
    static final int CMD_LOCAL_REQUEST_DELETE_CHILD = CMD_LOCAL_REQUEST_BASE + 6;
    static final int CMD_LOCAL_REQUEST_REKEY_CHILD = CMD_LOCAL_REQUEST_BASE + 7;
    // TODO: Add signals for other procedure types and notificaitons.

    private final IkeSessionOptions mIkeSessionOptions;
    private final ChildSessionOptions mFirstChildSessionOptions;
    /** Map that stores all IkeSaRecords, keyed by remotely generated IKE SPI. */
    private final LongSparseArray<IkeSaRecord> mSpiToSaRecordMap;
    /**
     * Map that stores all ChildSessionStateMachines, keyed by remotely generated Child SPI for
     * sending IPsec packet. Different SPIs may point to the same ChildSessionStateMachine if this
     * Child Session is doing Rekey.
     */
    private final SparseArray<ChildSessionStateMachine> mSpiToChildSessionMap;

    private final IpSecManager mIpSecManager;

    /**
     * Package private socket that sends and receives encoded IKE message. Initialized in Initial
     * State.
     */
    @VisibleForTesting IkeSocket mIkeSocket;

    /** Local address assigned on device. Initialized in Initial State. */
    private InetAddress mLocalAddress;
    /** Remote address configured by users. Initialized in Initial State. */
    private InetAddress mRemoteAddress;

    /** Package private SaProposal that represents the negotiated IKE SA proposal. */
    @VisibleForTesting SaProposal mSaProposal;

    private IkeCipher mIkeCipher;
    private IkeMacIntegrity mIkeIntegrity;
    private IkeMacPrf mIkePrf;

    // FIXME: b/131265898 Pass these packets from CreateIkeLocalIkeInit to CreateIkeLocalIkeAuth
    // when Android StateMachine can support that.
    private byte[] mIkeInitRequestPacket;
    private byte[] mIkeInitResponsePacket;

    /** Package */
    @VisibleForTesting IkeSaRecord mCurrentIkeSaRecord;
    /** Package */
    @VisibleForTesting IkeSaRecord mLocalInitNewIkeSaRecord;
    /** Package */
    @VisibleForTesting IkeSaRecord mRemoteInitNewIkeSaRecord;

    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordSurviving;
    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordAwaitingLocalDel;
    /** Package */
    @VisibleForTesting IkeSaRecord mIkeSaRecordAwaitingRemoteDel;

    // States
    private final State mInitial = new Initial();
    private final State mClosed = new Closed();
    private final State mIdle = new Idle();
    private final State mReceiving = new Receiving();
    private final State mCreateIkeLocalIkeInit = new CreateIkeLocalIkeInit();
    private final State mCreateIkeLocalIkeAuth = new CreateIkeLocalIkeAuth();
    private final State mRekeyIkeLocalCreate = new RekeyIkeLocalCreate();
    private final State mSimulRekeyIkeLocalCreate = new SimulRekeyIkeLocalCreate();
    private final State mSimulRekeyIkeLocalDeleteRemoteDelete =
            new SimulRekeyIkeLocalDeleteRemoteDelete();
    private final State mSimulRekeyIkeLocalDelete = new SimulRekeyIkeLocalDelete();
    private final State mSimulRekeyIkeRemoteDelete = new SimulRekeyIkeRemoteDelete();
    private final State mRekeyIkeLocalDelete = new RekeyIkeLocalDelete();
    private final State mRekeyIkeRemoteDelete = new RekeyIkeRemoteDelete();
    // TODO: Add InfoLocal and DeleteIkeLocal.

    /** Package private constructor */
    IkeSessionStateMachine(
            String name,
            Looper looper,
            IpSecManager ipSecManager,
            IkeSessionOptions ikeOptions,
            ChildSessionOptions firstChildOptions) {
        super(name, looper);
        mIkeSessionOptions = ikeOptions;
        mFirstChildSessionOptions = firstChildOptions;
        // There are at most three IkeSaRecords co-existing during simultaneous rekeying.
        mSpiToSaRecordMap = new LongSparseArray<>(3);
        mSpiToChildSessionMap = new SparseArray<>();

        mIpSecManager = ipSecManager;

        addState(mInitial);
        addState(mClosed);
        addState(mCreateIkeLocalIkeInit);
        addState(mCreateIkeLocalIkeAuth);
        addState(mIdle);
        addState(mReceiving);
        addState(mRekeyIkeLocalCreate);
        addState(mSimulRekeyIkeLocalCreate, mRekeyIkeLocalCreate);
        addState(mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mSimulRekeyIkeLocalDelete, mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mSimulRekeyIkeRemoteDelete, mSimulRekeyIkeLocalDeleteRemoteDelete);
        addState(mRekeyIkeLocalDelete);
        addState(mRekeyIkeRemoteDelete);

        setInitialState(mInitial);
    }

    /**
     * This class represents a reserved IKE SPI.
     *
     * <p>This class is created to avoid assigning same SPI to the same address.
     *
     * <p>Objects of this type are used to track reserved IKE SPI to avoid SPI collision. They can
     * be obtained by calling {@link #allocateSecurityParameterIndex()} and must be released by
     * calling {@link #close()} when they are no longer needed.
     *
     * <p>This class follows the pattern of {@link IpSecManager.SecurityParameterIndex}.
     *
     * <p>TODO: Move this class to a central place, like IkeManager.
     */
    public static final class IkeSecurityParameterIndex implements AutoCloseable {
        // Remember assigned IKE SPIs to avoid SPI collision.
        private static final Set<Pair<InetAddress, Long>> sAssignedIkeSpis = new HashSet<>();
        private static final int MAX_ASSIGN_IKE_SPI_ATTEMPTS = 100;
        private static final SecureRandom IKE_SPI_RANDOM = new SecureRandom();

        private final InetAddress mSourceAddress;
        private final long mSpi;
        // TODO: Add CloseGuard

        private IkeSecurityParameterIndex(InetAddress sourceAddress, long spi) {
            mSourceAddress = sourceAddress;
            mSpi = spi;
        }

        /**
         * Get a new IKE SPI and maintain the reservation.
         *
         * @return an instance of IkeSecurityParameterIndex.
         */
        public static IkeSecurityParameterIndex allocateSecurityParameterIndex(
                InetAddress sourceAddress) throws IOException {
            // TODO: Create specific Exception for SPI assigning error.

            for (int i = 0; i < MAX_ASSIGN_IKE_SPI_ATTEMPTS; i++) {
                long spi = IKE_SPI_RANDOM.nextLong();
                // Zero value can only be used in the IKE responder SPI field of an IKE INIT
                // request.
                if (spi != 0L
                        && sAssignedIkeSpis.add(new Pair<InetAddress, Long>(sourceAddress, spi))) {
                    return new IkeSecurityParameterIndex(sourceAddress, spi);
                }
            }

            throw new IOException("Failed to generate IKE SPI.");
        }

        /**
         * Get a new IKE SPI and maintain the reservation.
         *
         * @return an instance of IkeSecurityParameterIndex.
         */
        public static IkeSecurityParameterIndex allocateSecurityParameterIndex(
                InetAddress sourceAddress, long requestedSpi) throws IOException {
            if (sAssignedIkeSpis.add(new Pair<InetAddress, Long>(sourceAddress, requestedSpi))) {
                return new IkeSecurityParameterIndex(sourceAddress, requestedSpi);
            }

            throw new IOException("Failed to generate IKE SPI.");
        }

        /**
         * Get the underlying SPI held by this object.
         *
         * @return the underlying IKE SPI.
         */
        public long getSpi() {
            return mSpi;
        }

        /** Release an SPI that was previously reserved. */
        @Override
        public void close() {
            sAssignedIkeSpis.remove(new Pair<InetAddress, Long>(mSourceAddress, mSpi));
        }
    }

    // TODO: b/131122444 Move these methods into States.
    private IkeMessage buildIkeDeleteReq(IkeSaRecord ikeSaRecord) {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeDeleteResp(IkeSaRecord ikeSaRecord) {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeRekeyReq() {
        // TODO: Implement it.
        return null;
    }

    private IkeMessage buildIkeRekeyResp(IkeMessage reqMsg) {
        // TODO: Implement it.
        return null;
    }

    private void validateIkeDeleteReq(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate ikeMessage.
    }

    private void validateIkeDeleteResp(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate ikeMessage.
    }

    private void validateIkeRekeyReq(IkeMessage ikeMessage) throws IkeException {
        // TODO: Validate it against mIkeSessionOptions.
    }

    private void validateIkeRekeyResp(IkeMessage reqMsg, IkeMessage respMsg) throws IkeException {
        // TODO: Validate ikeMessage against Rekey request.
    }

    // TODO: Add methods for building and validating general Informational packet.

    private void addIkeSaRecord(IkeSaRecord record) {
        mSpiToSaRecordMap.put(record.getRemoteSpi(), record);
    }

    private void removeIkeSaRecord(IkeSaRecord record) {
        mSpiToSaRecordMap.remove(record.getRemoteSpi());
    }

    /**
     * Receive IKE packet from remote server.
     *
     * <p>This method is called synchronously from IkeSocket. It proxies the synchronous call as an
     * asynchronous job to the IkeSessionStateMachine handler.
     *
     * @param ikeHeader the decoded IKE header.
     * @param ikePacketBytes the byte array of the entire received IKE packet.
     */
    public void receiveIkePacket(IkeHeader ikeHeader, byte[] ikePacketBytes) {
        sendMessage(CMD_RECEIVE_IKE_PACKET, new ReceivedIkePacket(ikeHeader, ikePacketBytes));
    }

    /**
     * ReceivedIkePacket is a package private data container consists of decoded IkeHeader and
     * encoded IKE packet in a byte array.
     */
    static class ReceivedIkePacket {
        /** Decoded IKE header */
        public final IkeHeader ikeHeader;
        /** Entire encoded IKE message including IKE header */
        public final byte[] ikePacketBytes;

        ReceivedIkePacket(IkeHeader ikeHeader, byte[] ikePacketBytes) {
            this.ikeHeader = ikeHeader;
            this.ikePacketBytes = ikePacketBytes;
        }
    }

    /**
     * Interface for ChildSessionStateMachine to notify IkeSessionStateMachine.
     *
     * <p>Package private so as to be injectable for testing.
     */
    interface IChildSessionCallback {
        /** Notify that new Child SA is created. */
        void onCreateChildSa(int remoteSpi, ChildSessionStateMachine childSession);
        /** Notify that the Child SA is deleted. */
        void onDeleteChildSa(int remoteSpi);
        // TODO: Add methods for handling errors and sending out locally built payloads.
    }

    /**
     * Callback for ChildSessionStateMachine to notify IkeSessionStateMachine.
     *
     * <p>Package private for being passed to only ChildSessionStateMachine.
     */
    class ChildSessionCallback implements IChildSessionCallback {
        public void onCreateChildSa(int remoteSpi, ChildSessionStateMachine childSession) {
            mSpiToChildSessionMap.put(remoteSpi, childSession);
        }

        public void onDeleteChildSa(int remoteSpi) {
            mSpiToChildSessionMap.remove(remoteSpi);
        }
    }

    /** Initial state of IkeSessionStateMachine. */
    class Initial extends State {
        @Override
        public void enter() {
            try {
                mRemoteAddress = mIkeSessionOptions.getServerAddress();

                boolean isIpv4 = mRemoteAddress instanceof Inet4Address;
                FileDescriptor sock =
                        Os.socket(
                                isIpv4 ? OsConstants.AF_INET : OsConstants.AF_INET6,
                                OsConstants.SOCK_DGRAM,
                                OsConstants.IPPROTO_UDP);
                Os.connect(sock, mRemoteAddress, IkeSocket.IKE_SERVER_PORT);
                mLocalAddress = ((InetSocketAddress) Os.getsockname(sock)).getAddress();
                Os.close(sock);

                mIkeSocket = IkeSocket.getIkeSocket(mIkeSessionOptions.getUdpEncapsulationSocket());
            } catch (ErrnoException | SocketException e) {
                // TODO: handle exception and close IkeSession.
            }
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_LOCAL_REQUEST_CREATE_IKE:
                    transitionTo(mCreateIkeLocalIkeInit);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }
    }

    /**
     * Closed represents the state when this IkeSessionStateMachine is closed, and no further
     * actions can be performed on it.
     */
    class Closed extends State {
        // TODO:Implement it.
    }

    /**
     * Idle represents a state when there is no ongoing IKE exchange affecting established IKE SA.
     */
    class Idle extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    deferMessage(message);
                    transitionTo(mReceiving);
                    return HANDLED;
                case CMD_LOCAL_REQUEST_REKEY_IKE:
                    transitionTo(mRekeyIkeLocalCreate);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
                    // TODO: Add more cases for supporting local request.
            }
        }
    }

    /**
     * Gets IKE exchange subtype of a inbound IKE request message.
     *
     * <p>Knowing IKE exchange subtype of a inbound IKE request message helps IkeSessionStateMachine
     * to validate this request using the specific rule.
     *
     * <p>It is not allowed to obtain exchange subtype from a inbound response message for two
     * reasons. Firstly, the exchange subtype of a response message is the same with its
     * corresponding request message. Secondly, trying to get the exchange subtype from a response
     * message will easily fail when the response message contains only error notification payloads.
     *
     * @param ikeMessage inbound request IKE message to check.
     * @return IKE exchange subtype.
     */
    @IkeExchangeSubType
    private static int getIkeExchangeSubType(IkeMessage ikeMessage) {
        IkeHeader ikeHeader = ikeMessage.ikeHeader;
        if (ikeHeader.isResponseMsg) {
            // STOPSHIP: b/130190639 Notify user the error and close IKE session.
            throw new UnsupportedOperationException(
                    "Do not support getting IKE exchange subtype from a response message.");
        }

        switch (ikeHeader.exchangeType) {
            case IkeHeader.EXCHANGE_TYPE_IKE_SA_INIT:
                return IKE_EXCHANGE_SUBTYPE_IKE_INIT;
            case IkeHeader.EXCHANGE_TYPE_IKE_AUTH:
                return IKE_EXCHANGE_SUBTYPE_IKE_AUTH;
            case IkeHeader.EXCHANGE_TYPE_CREATE_CHILD_SA:
                List<IkeNotifyPayload> notifyPayloads =
                        ikeMessage.getPayloadListForType(
                                IkePayload.PAYLOAD_TYPE_NOTIFY, IkeNotifyPayload.class);

                // It is checked during decoding that there is only one Rekey notification
                // payload. For Rekey IKE notification, the protocolId is unset. For Rekey Child
                // notification, the protocolId is set to ESP.
                for (IkeNotifyPayload notifyPayload : notifyPayloads) {
                    if (notifyPayload.notifyType == IkeNotifyPayload.NOTIFY_TYPE_REKEY_SA) {
                        return notifyPayload.protocolId == IkePayload.PROTOCOL_ID_UNSET
                                ? IKE_EXCHANGE_SUBTYPE_REKEY_IKE
                                : IKE_EXCHANGE_SUBTYPE_REKEY_CHILD;
                    }
                }

                // If no Rekey notification payload found, this request is for creating new
                // Child SA.
                return IKE_EXCHANGE_SUBTYPE_CREATE_CHILD;
            case IkeHeader.EXCHANGE_TYPE_INFORMATIONAL:
                List<IkeDeletePayload> deletePayloads =
                        ikeMessage.getPayloadListForType(
                                IkePayload.PAYLOAD_TYPE_DELETE, IkeDeletePayload.class);

                // If no Delete payload was found, this request is a generic informational request.
                if (deletePayloads.isEmpty()) return IKE_EXCHANGE_SUBTYPE_GENERIC_INFO;

                // IKEv2 protocol does not clearly disallow to have both a Delete IKE payload and a
                // Delete Child payload in one IKE message. In this case, IKE library will only
                // respond to the Delete IKE payload.
                for (IkeDeletePayload deletePayload : deletePayloads) {
                    if (deletePayload.protocolId == IkePayload.PROTOCOL_ID_IKE) {
                        return IKE_EXCHANGE_SUBTYPE_DELETE_IKE;
                    }
                }
                return IKE_EXCHANGE_SUBTYPE_DELETE_CHILD;
            default:
                // STOPSHIP: b/130190639 Notify user the error and close IKE session.
                throw new IllegalArgumentException(
                        "Unrecognized exchange type: " + ikeHeader.exchangeType);
        }
    }

    /** Base state defines common behaviours when receiving an IKE packet. */
    private abstract class BaseState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    handleReceivedIkePacket(message);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        protected void handleReceivedIkePacket(Message message) {
            ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
            IkeHeader ikeHeader = receivedIkePacket.ikeHeader;
            byte[] ikePacketBytes = receivedIkePacket.ikePacketBytes;
            IkeSaRecord ikeSaRecord = getIkeSaRecordForPacket(ikeHeader);
            try {
                if (ikeHeader.isResponseMsg) {
                    // TODO: Verify message ID against outstanding request.
                    IkeMessage ikeMessage =
                            IkeMessage.decode(
                                    mIkeIntegrity,
                                    mIkeCipher,
                                    ikeSaRecord,
                                    ikeHeader,
                                    ikePacketBytes);
                    handleResponseIkeMessage(ikeMessage);
                } else {
                    // TODO: Verify message ID.
                    IkeMessage ikeMessage =
                            IkeMessage.decode(
                                    mIkeIntegrity,
                                    mIkeCipher,
                                    ikeSaRecord,
                                    ikeHeader,
                                    ikePacketBytes);
                    handleRequestIkeMessage(ikeMessage, getIkeExchangeSubType(ikeMessage), message);
                }

                // TODO: Handle fatal error notifications.
            } catch (IkeException e) {
                // TODO: Handle decoding exceptions. Reply with error notifications if received IKE
                // message is an encrypted and authenticated request with a valid message ID.
            } catch (GeneralSecurityException e) {
                // IKE library failed on intergity checksum validation or on message decryption.
                // TODO: Handle decrypting exception
            }
        }

        // Default handler for decode errors in encrypted request.
        protected void handleDecodingErrorInEncryptedRequest(
                IkeException exception, IkeSaRecord ikeSaRecord) {
            switch (exception.errorCode) {
                case IkeNotifyPayload.NOTIFY_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD:
                    // TODO: Send encrypted error notification.
                    return;
                case IkeNotifyPayload.NOTIFY_TYPE_INVALID_MAJOR_VERSION:
                    // TODO: Send unencrypted error notification.
                    return;
                case IkeNotifyPayload.NOTIFY_TYPE_INVALID_SYNTAX:
                    // TODO: Send encrypted error notification and close IKE session if Message ID
                    // and cryptogtaphic checksum were invalid.
                    return;
                default:
                    Log.wtf(TAG, "Unknown error decoding IKE Message.");
            }
        }

        // Default handler for decode errors in encrypted responses.
        // NOTE: The DeleteIkeLocal state MUST override this state to avoid the possibility of an
        // infinite loop.
        protected void handleDecodingErrorInEncryptedResponse(
                IkeException exception, IkeSaRecord ikeSaRecord) {
            // All errors in parsing or processing reponse packets should cause the IKE library to
            // initiate a Delete IKE Exchange.

            // TODO: Initiate Delete IKE Exchange
        }

        protected IkeSaRecord getIkeSaRecordForPacket(IkeHeader ikeHeader) {
            if (ikeHeader.fromIkeInitiator) {
                return mSpiToSaRecordMap.get(ikeHeader.ikeInitiatorSpi);
            } else {
                return mSpiToSaRecordMap.get(ikeHeader.ikeResponderSpi);
            }
        }

        protected abstract void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message);

        protected abstract void handleResponseIkeMessage(IkeMessage ikeMessage);
    }

    /**
     * Receiving represents a state when idle IkeSessionStateMachine receives an incoming packet.
     */
    class Receiving extends BaseState {
        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            switch (ikeExchangeSubType) {
                case IKE_EXCHANGE_SUBTYPE_REKEY_IKE:
                    try {
                        validateIkeRekeyReq(ikeMessage);
                        // Reply
                        IkeMessage responseIkeMessage = buildIkeRekeyResp(ikeMessage);
                        // TODO: Encode and send out responseIkeMessage

                        mRemoteInitNewIkeSaRecord =
                                IkeSaRecord.makeNewIkeSaRecord(
                                        mCurrentIkeSaRecord, ikeMessage, responseIkeMessage);
                        addIkeSaRecord(mRemoteInitNewIkeSaRecord);
                        transitionTo(mRekeyIkeRemoteDelete);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                    // TODO: Add more cases for supporting other types of request.
                default:
            }
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            // TODO: Extract payloads and re-direct to awaiting ChildSessionStateMachines.
        }
    }

    /**
     * LocalNewExchangeBase represents the common behaviours when IKE library initiates a new
     * exchange.
     */
    private abstract class LocalNewExchangeBase extends BaseState {
        protected IkeMessage mRequestMsg;
        protected byte[] mRequestPacket;

        @Override
        public void enter() {
            mRequestMsg = buildRequest();
            mRequestPacket = encodeRequest();
            mIkeSocket.sendIkePacket(mRequestPacket, mRemoteAddress);
            // TODO: Send out packet and start retransmission timer.
        }

        @Override
        public void exit() {
            // TODO: Stop retransmission
            mRequestMsg = null;
            mRequestPacket = null;
        }

        protected abstract IkeMessage buildRequest();

        // CreateIkeLocalInit should override encodeRequest() to encode unencrypted packet
        protected byte[] encodeRequest() {
            return mRequestMsg.encryptAndEncode(mIkeIntegrity, mIkeCipher, mCurrentIkeSaRecord);
        }
    }

    /** CreateIkeLocalIkeInit represents state when IKE library initiates IKE_INIT exchange. */
    class CreateIkeLocalIkeInit extends LocalNewExchangeBase {
        private IkeSecurityParameterIndex mLocalIkeSpiResource;
        private IkeSecurityParameterIndex mRemoteIkeSpiResource;

        @Override
        public void enter() {
            super.enter();
            mIkeSocket.registerIke(
                    mRequestMsg.ikeHeader.ikeInitiatorSpi, IkeSessionStateMachine.this);
        }

        @Override
        protected IkeMessage buildRequest() {
            try {
                return buildIkeInitReq();
            } catch (IOException e) {
                // TODO: Handle SPI assigning failure.
                throw new UnsupportedOperationException(
                        "Do not support handling SPI assigning failure.");
            }
        }

        @Override
        protected byte[] encodeRequest() {
            mIkeInitRequestPacket = mRequestMsg.encode();
            return mIkeInitRequestPacket;
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    handleReceivedIkePacket(message);
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        protected void handleReceivedIkePacket(Message message) {
            ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
            IkeHeader ikeHeader = receivedIkePacket.ikeHeader;
            byte[] ikePacketBytes = receivedIkePacket.ikePacketBytes;
            try {
                if (ikeHeader.isResponseMsg) {
                    // TODO: Verify message ID is zero.
                    IkeMessage ikeMessage = IkeMessage.decode(ikeHeader, ikePacketBytes);
                    handleResponseIkeMessage(ikeMessage);
                    mIkeInitResponsePacket = ikePacketBytes;
                } else {
                    // TODO: Drop unexpected request.
                }
                // TODO: Handle fatal error notifications.
            } catch (IkeException e) {
                // TODO:Since IKE_INIT is not protected, log and ignore this message.
            }
        }

        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            Log.wtf(
                    TAG,
                    "State: "
                            + getCurrentState().getName()
                            + " received an unsupported request message with IKE exchange subtype: "
                            + ikeExchangeSubType);
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            boolean ikeInitSuccess = false;
            try {
                validateIkeInitResp(mRequestMsg, ikeMessage);

                // TODO: Pass mLocalIkeSpiResource and mRemoteIkeSpiResource to
                // makeFirstIkeSaRecord()
                mCurrentIkeSaRecord =
                        IkeSaRecord.makeFirstIkeSaRecord(
                                mRequestMsg,
                                ikeMessage,
                                mIkePrf,
                                mIkeIntegrity == null ? 0 : mIkeIntegrity.getKeyLength(),
                                mIkeCipher.getKeyLength());
                mCurrentIkeSaRecord.incrementMessageId();
                addIkeSaRecord(mCurrentIkeSaRecord);
                ikeInitSuccess = true;

                transitionTo(mCreateIkeLocalIkeAuth);
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            } catch (GeneralSecurityException e) {
                // TODO: Handle DH key exchange failure.
            } catch (IOException e) {
                // TODO: Handle the error case when the remote IKE SPI has been reserved with the
                // remote address.
            } finally {
                if (!ikeInitSuccess) {
                    if (mLocalIkeSpiResource != null) {
                        mLocalIkeSpiResource.close();
                        mLocalIkeSpiResource = null;
                    }
                    if (mRemoteIkeSpiResource != null) {
                        mRemoteIkeSpiResource.close();
                        mRemoteIkeSpiResource = null;
                    }
                }
            }
        }

        private IkeMessage buildIkeInitReq() throws IOException {
            List<IkePayload> payloadList = new LinkedList<>();

            // Generate IKE SPI
            mLocalIkeSpiResource =
                    IkeSecurityParameterIndex.allocateSecurityParameterIndex(mLocalAddress);
            long initSpi = mLocalIkeSpiResource.getSpi();
            long respSpi = 0;

            // It is validated in IkeSessionOptions.Builder to ensure IkeSessionOptions has at least
            // one SaProposal and all SaProposals are valid for IKE SA negotiation.
            SaProposal[] saProposals = mIkeSessionOptions.getSaProposals();

            // Build SA Payload
            IkeSaPayload saPayload = IkeSaPayload.createInitialIkeSaPayload(saProposals);
            payloadList.add(saPayload);

            // Build KE Payload using the first DH group number in the first SaProposal.
            DhGroupTransform dhGroupTransform = saProposals[0].getDhGroupTransforms()[0];
            IkeKePayload kePayload = new IkeKePayload(dhGroupTransform.id);
            payloadList.add(kePayload);

            // Build Nonce Payload
            IkeNoncePayload noncePayload = new IkeNoncePayload();
            payloadList.add(noncePayload);

            // TODO: Add Notification Payloads according to user configurations.

            // Build IKE header
            IkeHeader ikeHeader =
                    new IkeHeader(
                            initSpi,
                            respSpi,
                            IkePayload.PAYLOAD_TYPE_SA,
                            IkeHeader.EXCHANGE_TYPE_IKE_SA_INIT,
                            false /*isResponseMsg*/,
                            true /*fromIkeInitiator*/,
                            0 /*messageId*/);

            return new IkeMessage(ikeHeader, payloadList);
        }

        private void validateIkeInitResp(IkeMessage reqMsg, IkeMessage respMsg)
                throws IkeException, IOException {
            IkeHeader respIkeHeader = respMsg.ikeHeader;
            mRemoteIkeSpiResource =
                    IkeSecurityParameterIndex.allocateSecurityParameterIndex(
                            mIkeSessionOptions.getServerAddress(), respIkeHeader.ikeResponderSpi);

            int exchangeType = respIkeHeader.exchangeType;
            if (exchangeType != IkeHeader.EXCHANGE_TYPE_IKE_SA_INIT) {
                throw new InvalidSyntaxException(
                        "Expected EXCHANGE_TYPE_IKE_SA_INIT but received: " + exchangeType);
            }

            IkeSaPayload respSaPayload = null;
            IkeKePayload respKePayload = null;

            boolean hasNoncePayload = false;

            for (IkePayload payload : respMsg.ikePayloadList) {
                switch (payload.payloadType) {
                    case IkePayload.PAYLOAD_TYPE_SA:
                        respSaPayload = (IkeSaPayload) payload;
                        break;
                    case IkePayload.PAYLOAD_TYPE_KE:
                        respKePayload = (IkeKePayload) payload;
                        break;
                    case IkePayload.PAYLOAD_TYPE_CERT_REQUEST:
                        throw new UnsupportedOperationException(
                                "Do not support handling Cert Request Payload.");
                        // TODO: Handle it when using certificate based authentication. Otherwise,
                        // ignore it.
                    case IkePayload.PAYLOAD_TYPE_NONCE:
                        hasNoncePayload = true;
                        break;
                    case IkePayload.PAYLOAD_TYPE_VENDOR:
                        // Do not support any vendor defined protocol extensions. Ignore
                        // all Vendor ID Payloads.
                        break;
                    case IkePayload.PAYLOAD_TYPE_NOTIFY:
                        IkeNotifyPayload notifyPayload = (IkeNotifyPayload) payload;
                        if (notifyPayload.isErrorNotify()) {
                            // TODO: Throw IkeExceptions according to error types.
                            throw new UnsupportedOperationException(
                                    "Do not support handle error notifications in response.");
                        }

                        // TODO: handle status notifications.

                        break;
                    default:
                        throw new InvalidSyntaxException(
                                "Received unexpected payload in IKE INIT response. Payload type: "
                                        + payload.payloadType);
                }
            }

            if (respSaPayload == null || respKePayload == null || !hasNoncePayload) {
                throw new InvalidSyntaxException("SA, KE or Nonce payload missing.");
            }

            IkeSaPayload reqSaPayload =
                    reqMsg.getPayloadForType(IkePayload.PAYLOAD_TYPE_SA, IkeSaPayload.class);
            mSaProposal =
                    respSaPayload.getVerifiedNegotiatedIkeProposalPair(reqSaPayload, mRemoteAddress)
                            .second
                            .saProposal;

            // Build IKE crypto tools using mSaProposal. It is ensured that mSaProposal is valid and
            // has exactly one Transform for each Transform type. Only exception is when
            // combined-mode cipher is used, there will be either no integrity algorithm or an
            // INTEGRITY_ALGORITHM_NONE type algorithm.
            Provider provider = IkeMessage.getSecurityProvider();
            mIkeCipher = IkeCipher.create(mSaProposal.getEncryptionTransforms()[0], provider);
            if (!mIkeCipher.isAead()) {
                mIkeIntegrity =
                        IkeMacIntegrity.create(mSaProposal.getIntegrityTransforms()[0], provider);
            }
            mIkePrf = IkeMacPrf.create(mSaProposal.getPrfTransforms()[0], provider);

            IkeKePayload reqKePayload =
                    reqMsg.getPayloadForType(IkePayload.PAYLOAD_TYPE_KE, IkeKePayload.class);
            if (reqKePayload.dhGroup != respKePayload.dhGroup
                    && respKePayload.dhGroup != mSaProposal.getDhGroupTransforms()[0].id) {
                throw new InvalidSyntaxException("Received KE payload with mismatched DH group.");
            }
        }

        @Override
        public void exit() {
            super.exit();
            // TODO: Store IKE_INIT request and response in mIkeSessionOptions for IKE_AUTH
        }
    }

    /** CreateIkeLocalIkeAuth represents state when IKE library initiates IKE_AUTH exchange. */
    class CreateIkeLocalIkeAuth extends LocalNewExchangeBase {
        @Override
        protected IkeMessage buildRequest() {
            try {
                return buildIkeAuthReq();
            } catch (ResourceUnavailableException e) {
                // TODO:Handle IPsec SPI assigning failure.
                throw new UnsupportedOperationException(
                        "Do not support handling IPsec SPI assigning failure.");
            }
        }

        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            Log.wtf(
                    TAG,
                    "State: "
                            + getCurrentState().getName()
                            + "received an unsupported request message with IKE exchange subtype: "
                            + ikeExchangeSubType);
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            // TODO: Handle EAP request in IKE_AUTH response
            try {
                validateIkeAuthResp(mRequestMsg, ikeMessage);

                ChildSessionStateMachine firstChild =
                        ChildSessionStateMachineFactory.makeChildSessionStateMachine(
                                "ChildSessionStateMachine",
                                getHandler().getLooper(),
                                mFirstChildSessionOptions,
                                mLocalAddress,
                                mRemoteAddress);
                // TODO: Replace null input params to payload lists in IKE_AUTH request and
                // IKE_AUTH response for negotiating Child SA.
                firstChild.handleFirstChildExchange(null, null, new ChildSessionCallback());

                transitionTo(mIdle);
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            }
        }

        private IkeMessage buildIkeAuthReq() throws ResourceUnavailableException {
            List<IkePayload> payloadList = new LinkedList<>();

            // Build Identification payloads
            IkeIdPayload initIdPayload =
                    new IkeIdPayload(
                            true /*isInitiator*/, mIkeSessionOptions.getLocalIdentification());
            IkeIdPayload respIdPayload =
                    new IkeIdPayload(
                            false /*isInitiator*/, mIkeSessionOptions.getRemoteIdentification());
            payloadList.add(initIdPayload);
            payloadList.add(respIdPayload);

            // Build Authentication payload
            IkeAuthConfig authConfig = mIkeSessionOptions.getLocalAuthConfig();
            switch (authConfig.mAuthMethod) {
                case IkeSessionOptions.IKE_AUTH_METHOD_PSK:
                    IkeAuthPskPayload pskPayload =
                            new IkeAuthPskPayload(
                                    authConfig.mPsk,
                                    mIkeInitRequestPacket,
                                    mCurrentIkeSaRecord.nonceResponder,
                                    initIdPayload.getEncodedPayloadBody(),
                                    mIkePrf,
                                    mCurrentIkeSaRecord.getSkPi());
                    payloadList.add(pskPayload);
                    break;
                case IkeSessionOptions.IKE_AUTH_METHOD_PUB_KEY_SIGNATURE:
                    // TODO: Support authentication based on public key signature.
                    throw new UnsupportedOperationException(
                            "Do not support public-key based authentication.");
                case IkeSessionOptions.IKE_AUTH_METHOD_EAP:
                    // TODO: Support EAP.
                    throw new UnsupportedOperationException("Do not support EAP.");
                default:
                    throw new IllegalArgumentException(
                            "Unrecognized authentication method: " + authConfig.mAuthMethod);
            }

            // Build SA Payload
            IkeSaPayload childSaPayload =
                    IkeSaPayload.createChildSaPayload(
                            false /*isResp*/,
                            mFirstChildSessionOptions.getSaProposals(),
                            mIpSecManager,
                            mRemoteAddress);
            payloadList.add(childSaPayload);

            // Build TS payloads
            IkeTsPayload initTsPayload =
                    new IkeTsPayload(
                            true /*isInitiator*/,
                            mFirstChildSessionOptions.getLocalTrafficSelectors());
            IkeTsPayload respTsPayload =
                    new IkeTsPayload(
                            false /*isInitiator*/,
                            mFirstChildSessionOptions.getRemoteTrafficSelectors());
            payloadList.add(initTsPayload);
            payloadList.add(respTsPayload);

            // Build IKE header
            IkeHeader ikeHeader =
                    new IkeHeader(
                            mCurrentIkeSaRecord.initiatorSpi,
                            mCurrentIkeSaRecord.responderSpi,
                            IkePayload.PAYLOAD_TYPE_SK,
                            IkeHeader.EXCHANGE_TYPE_IKE_AUTH,
                            false /*isResponseMsg*/,
                            true /*fromIkeInitiator*/,
                            mCurrentIkeSaRecord.getMessageId());

            return new IkeMessage(ikeHeader, payloadList);
        }

        private void validateIkeAuthResp(IkeMessage reqMsg, IkeMessage respMsg)
                throws IkeException {
            // TODO: Validate ikeMessage against IKE_AUTH request and mIkeSessionOptions.
        }
    }

    /** RekeyIkeLocalCreate represents state when IKE library initiates Rekey IKE exchange. */
    class RekeyIkeLocalCreate extends LocalNewExchangeBase {
        @Override
        public IkeMessage buildRequest() {
            return buildIkeRekeyReq();
        }

        @Override
        protected byte[] encodeRequest() {
            // TODO: Remove this overriding method to directly call the super method when
            // buildIkeRekeyReq is implemented.
            return new byte[0];
        }

        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            switch (ikeExchangeSubType) {
                case IKE_EXCHANGE_SUBTYPE_REKEY_IKE:
                    try {
                        validateIkeRekeyReq(ikeMessage);
                        // Reply
                        IkeMessage responseIkeMessage = buildIkeRekeyResp(ikeMessage);
                        mRemoteInitNewIkeSaRecord =
                                IkeSaRecord.makeNewIkeSaRecord(
                                        mCurrentIkeSaRecord, ikeMessage, responseIkeMessage);
                        addIkeSaRecord(mRemoteInitNewIkeSaRecord);
                        // TODO: Encode and send responseIkeMessage.

                        transitionTo(mSimulRekeyIkeLocalCreate);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other types of request.
            }
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            try {
                handleRekeyResp(ikeMessage);
                transitionTo(mRekeyIkeLocalDelete);
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            }
        }

        // Is also called by SimulRekeyIkeLocalCreate to handle incoming rekey response.
        protected void handleRekeyResp(IkeMessage ikeMessage) throws IkeException {
            validateIkeRekeyResp(mRequestMsg, ikeMessage);
            mLocalInitNewIkeSaRecord =
                    IkeSaRecord.makeNewIkeSaRecord(mCurrentIkeSaRecord, mRequestMsg, ikeMessage);
            addIkeSaRecord(mLocalInitNewIkeSaRecord);
            // TODO: Stop retransmission
        }
    }

    /**
     * SimulRekeyIkeLocalCreate represents the state where IKE library has replied to rekey request
     * sent from the remote and is waiting for a rekey response for a locally initiated rekey
     * request.
     *
     * <p>SimulRekeyIkeLocalCreate extends RekeyIkeLocalCreate so that it can call super class to
     * validate incoming rekey response against locally initiated rekey request.
     */
    class SimulRekeyIkeLocalCreate extends RekeyIkeLocalCreate {
        @Override
        public void enter() {
            // Do not send request.
        }

        @Override
        public IkeMessage buildRequest() {
            throw new UnsupportedOperationException(
                    "Do not support sending request in " + getCurrentState().getName());
        }

        @Override
        public void exit() {
            // Do nothing.
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
                    IkeHeader ikeHeader = receivedIkePacket.ikeHeader;

                    if (mRemoteInitNewIkeSaRecord == getIkeSaRecordForPacket(ikeHeader)) {
                        deferMessage(message);
                    } else {
                        handleReceivedIkePacket(message);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
            }
        }

        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            switch (ikeExchangeSubType) {
                case IKE_EXCHANGE_SUBTYPE_DELETE_IKE:
                    deferMessage(message);
                    return;
                default:
                    // TODO: Add more cases for other types of request.
            }
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            try {
                handleRekeyResp(ikeMessage);
                transitionTo(mSimulRekeyIkeLocalDeleteRemoteDelete);
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            }
        }
    }

    /** RekeyIkeDeleteBase represents common behaviours of deleting stage during rekeying IKE SA. */
    private abstract class RekeyIkeDeleteBase extends BaseState {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CMD_RECEIVE_IKE_PACKET:
                    ReceivedIkePacket receivedIkePacket = (ReceivedIkePacket) message.obj;
                    IkeHeader ikeHeader = receivedIkePacket.ikeHeader;

                    // Request received on the new/surviving SA; treat it as acknowledgement that
                    // remote has successfully rekeyed.
                    if (mIkeSaRecordSurviving == getIkeSaRecordForPacket(ikeHeader)) {
                        deferMessage(message);
                        // TODO: Locally close old (and losing) IKE SAs.
                        finishRekey();
                    } else {
                        handleReceivedIkePacket(message);
                    }
                    return HANDLED;
                default:
                    return NOT_HANDLED;
                    // TODO: Add more cases for other packet types.
            }
        }

        protected void finishRekey() {
            mCurrentIkeSaRecord = mIkeSaRecordSurviving;
            mLocalInitNewIkeSaRecord = null;
            mRemoteInitNewIkeSaRecord = null;

            mIkeSaRecordSurviving = null;
            mIkeSaRecordAwaitingLocalDel = null;
            mIkeSaRecordAwaitingRemoteDel = null;
        }
    }

    /**
     * SimulRekeyIkeLocalDeleteRemoteDelete represents the deleting stage during simultaneous
     * rekeying when IKE library is waiting for both a Delete request and a Delete response.
     */
    class SimulRekeyIkeLocalDeleteRemoteDelete extends RekeyIkeDeleteBase {
        @Override
        public void enter() {
            // Detemine surviving IKE SA. According to RFC 7296: "The new IKE SA containing the
            // lowest nonce SHOULD be deleted by the node that created it, and the other surviving
            // new IKE SA MUST inherit all the Child SAs."
            if (mLocalInitNewIkeSaRecord.compareTo(mRemoteInitNewIkeSaRecord) > 0) {
                mIkeSaRecordSurviving = mLocalInitNewIkeSaRecord;
                mIkeSaRecordAwaitingLocalDel = mCurrentIkeSaRecord;
                mIkeSaRecordAwaitingRemoteDel = mRemoteInitNewIkeSaRecord;
            } else {
                mIkeSaRecordSurviving = mRemoteInitNewIkeSaRecord;
                mIkeSaRecordAwaitingLocalDel = mLocalInitNewIkeSaRecord;
                mIkeSaRecordAwaitingRemoteDel = mCurrentIkeSaRecord;
            }
            IkeMessage ikeMessage = buildIkeDeleteReq(mIkeSaRecordAwaitingLocalDel);
            // TODO: Encode and send out delete request and start retransmission timer.
            // TODO: Set timer awaiting for delete request.
        }

        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            IkeSaRecord ikeSaRecordForPacket = getIkeSaRecordForPacket(ikeMessage.ikeHeader);
            switch (ikeExchangeSubType) {
                case IKE_EXCHANGE_SUBTYPE_DELETE_IKE:
                    if (ikeSaRecordForPacket == mIkeSaRecordAwaitingRemoteDel) {
                        try {
                            validateIkeDeleteReq(ikeMessage);
                            IkeMessage respMsg = buildIkeDeleteResp(mIkeSaRecordAwaitingRemoteDel);
                            removeIkeSaRecord(mIkeSaRecordAwaitingRemoteDel);
                            // TODO: Encode and send response and close
                            // mIkeSaRecordAwaitingRemoteDel.
                            // TODO: Stop timer awating delete request.
                            transitionTo(mSimulRekeyIkeLocalDelete);
                        } catch (IkeException e) {
                            // TODO: Handle processing errors.
                        }
                    } else {
                        // TODO: The other side deletes wrong IKE SA and we should close whole IKE
                        // session.
                    }
                    return;
                default:
                    // TODO: Add more cases for other types of request.
            }
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            try {
                validateIkeDeleteResp(ikeMessage);
                transitionTo(mSimulRekeyIkeRemoteDelete);
                removeIkeSaRecord(mIkeSaRecordAwaitingLocalDel);
                // TODO: Close mIkeSaRecordAwaitingLocalDel
                // TODO: Stop retransmission timer
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            }
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop retransmission timer and awaiting delete request timer.
        }
    }

    /**
     * SimulRekeyIkeLocalDelete represents the state when IKE library is waiting for a Delete
     * response during simultaneous rekeying.
     */
    class SimulRekeyIkeLocalDelete extends RekeyIkeDeleteBase {
        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            // TODO: Handle remote requests.
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            try {
                validateIkeDeleteResp(ikeMessage);
                removeIkeSaRecord(mIkeSaRecordAwaitingLocalDel);
                // TODO: Close mIkeSaRecordAwaitingLocalDel.
                transitionTo(mIdle);
            } catch (IkeException e) {
                // TODO: Handle processing errors.
            }
        }
    }

    /**
     * SimulRekeyIkeRemoteDelete represents the state that waiting for a Delete request during
     * simultaneous rekeying.
     */
    class SimulRekeyIkeRemoteDelete extends RekeyIkeDeleteBase {
        @Override
        protected void handleRequestIkeMessage(
                IkeMessage ikeMessage, int ikeExchangeSubType, Message message) {
            switch (ikeExchangeSubType) {
                case IKE_EXCHANGE_SUBTYPE_DELETE_IKE:
                    try {
                        validateIkeDeleteReq(ikeMessage);
                        IkeMessage respMsg = buildIkeDeleteResp(mIkeSaRecordAwaitingRemoteDel);
                        // TODO: Encode and send response and close mIkeSaRecordAwaitingRemoteDel
                        removeIkeSaRecord(mIkeSaRecordAwaitingRemoteDel);
                        transitionTo(mIdle);
                    } catch (IkeException e) {
                        // TODO: Handle processing errors.
                    }
                    return;
                default:
                    // TODO: Add more cases for other types of request.
            }
        }

        @Override
        protected void handleResponseIkeMessage(IkeMessage ikeMessage) {
            Log.wtf(
                    TAG,
                    "State: "
                            + getCurrentState().getName()
                            + "received an unsupported response message.");
        }
    }

    /**
     * RekeyIkeLocalDelete represents the deleting stage when IKE library is initiating a Rekey
     * procedure.
     *
     * <p>RekeyIkeLocalDelete and SimulRekeyIkeLocalDelete have same behaviours in processMessage().
     * While RekeyIkeLocalDelete overrides enter() and exit() methods for initiating and finishing
     * the deleting stage for IKE rekeying.
     */
    class RekeyIkeLocalDelete extends SimulRekeyIkeLocalDelete {
        @Override
        public void enter() {
            mIkeSaRecordSurviving = mLocalInitNewIkeSaRecord;
            mIkeSaRecordAwaitingLocalDel = mCurrentIkeSaRecord;
            IkeMessage ikeMessage = buildIkeDeleteReq(mIkeSaRecordAwaitingLocalDel);
            // TODO: Encode ikeMessage, send out packet and start retransmission timer.
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop retransmission.
        }
    }

    /**
     * RekeyIkeRemoteDelete represents the deleting stage when responding to a Rekey procedure.
     *
     * <p>RekeyIkeRemoteDelete and SimulRekeyIkeRemoteDelete have same behaviours in
     * processMessage(). While RekeyIkeLocalDelete overrides enter() and exit() methods for waiting
     * incoming delete request and for finishing the deleting stage for IKE rekeying.
     */
    class RekeyIkeRemoteDelete extends SimulRekeyIkeRemoteDelete {
        @Override
        public void enter() {
            mIkeSaRecordSurviving = mRemoteInitNewIkeSaRecord;
            mIkeSaRecordAwaitingRemoteDel = mCurrentIkeSaRecord;
            // TODO: Set timer awaiting delete request.
        }

        @Override
        public void exit() {
            finishRekey();
            // TODO: Stop timer awaiting delete request.
        }
    }
}
