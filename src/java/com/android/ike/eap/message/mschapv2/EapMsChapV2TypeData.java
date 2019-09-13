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

package com.android.ike.eap.message.mschapv2;

import static com.android.ike.eap.EapAuthenticator.LOG;

import com.android.ike.eap.EapResult.EapError;
import com.android.ike.eap.exceptions.mschapv2.EapMsChapV2ParsingException;
import com.android.ike.eap.message.EapMessage;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * EapMsChapV2TypeData represents the Type Data for an {@link EapMessage} during an EAP MSCHAPv2
 * session.
 */
public class EapMsChapV2TypeData {
    // EAP MSCHAPv2 OpCode values (EAP MSCHAPv2#2)
    public static final int EAP_MSCHAP_V2_CHALLENGE = 1;
    public static final int EAP_MSCHAP_V2_RESPONSE = 2;
    public static final int EAP_MSCHAP_V2_SUCCESS = 3;
    public static final int EAP_MSCHAP_V2_FAILURE = 4;
    public static final int EAP_MSCHAP_V2_CHANGE_PASSWORD = 7;

    public static final Map<Integer, String> EAP_OP_CODE_STRING = new HashMap<>();
    static {
        EAP_OP_CODE_STRING.put(EAP_MSCHAP_V2_CHALLENGE, "Challenge");
        EAP_OP_CODE_STRING.put(EAP_MSCHAP_V2_RESPONSE, "Response");
        EAP_OP_CODE_STRING.put(EAP_MSCHAP_V2_SUCCESS, "Success");
        EAP_OP_CODE_STRING.put(EAP_MSCHAP_V2_FAILURE, "Failure");
        EAP_OP_CODE_STRING.put(EAP_MSCHAP_V2_CHANGE_PASSWORD, "Change-Password");
    }

    private static final Set<Integer> SUPPORTED_OP_CODES = new HashSet<>();
    static {
        SUPPORTED_OP_CODES.add(EAP_MSCHAP_V2_CHALLENGE);
        SUPPORTED_OP_CODES.add(EAP_MSCHAP_V2_RESPONSE);
        SUPPORTED_OP_CODES.add(EAP_MSCHAP_V2_SUCCESS);
        SUPPORTED_OP_CODES.add(EAP_MSCHAP_V2_FAILURE);
    }

    public final int opCode;

    EapMsChapV2TypeData(int opCode) throws EapMsChapV2ParsingException {
        this.opCode = opCode;

        if (!SUPPORTED_OP_CODES.contains(opCode)) {
            throw new EapMsChapV2ParsingException("Unsupported opCode provided: " + opCode);
        }
    }

    /**
     * Encodes this EapMsChapV2TypeData instance as a byte[].
     *
     * @return byte[] representing the encoded value of this EapMsChapV2TypeData instance.
     */
    public byte[] encode() {
        throw new UnsupportedOperationException(
                "encode() not supported by " + this.getClass().getSimpleName());
    }

    abstract static class EapMsChapV2VariableTypeData extends EapMsChapV2TypeData {
        public final int msChapV2Id;
        public final int msLength;

        EapMsChapV2VariableTypeData(int opCode, int msChapV2Id, int msLength)
                throws EapMsChapV2ParsingException {
            super(opCode);

            this.msChapV2Id = msChapV2Id;
            this.msLength = msLength;
        }
    }

    /**
     * EapMsChapV2ChallengeRequest represents the EAP MSCHAPv2 Challenge Packet (EAP MSCHAPv2#2.1).
     */
    public static class EapMsChapV2ChallengeRequest extends EapMsChapV2VariableTypeData {
        public static final int VALUE_SIZE = 16;
        public static final int TYPE_DATA_HEADER_SIZE = 5;

        public final byte[] challenge = new byte[VALUE_SIZE];
        public final byte[] name;

        EapMsChapV2ChallengeRequest(ByteBuffer buffer) throws EapMsChapV2ParsingException {
            super(
                    EAP_MSCHAP_V2_CHALLENGE,
                    Byte.toUnsignedInt(buffer.get()),
                    Short.toUnsignedInt(buffer.getShort()));

            int valueSize = Byte.toUnsignedInt(buffer.get());
            if (valueSize != VALUE_SIZE) {
                throw new EapMsChapV2ParsingException("Challenge Value-Size must be 16");
            }
            buffer.get(challenge);

            int nameLenBytes = msLength - VALUE_SIZE - TYPE_DATA_HEADER_SIZE;
            if (nameLenBytes < 0) {
                throw new EapMsChapV2ParsingException("Invalid MS-Length specified");
            }

            name = new byte[nameLenBytes];
            buffer.get(name);
        }

        @VisibleForTesting
        EapMsChapV2ChallengeRequest(int msChapV2Id, int msLength, byte[] challenge, byte[] name)
                throws EapMsChapV2ParsingException {
            super(EAP_MSCHAP_V2_CHALLENGE, msChapV2Id, msLength);

            if (challenge.length != VALUE_SIZE) {
                throw new EapMsChapV2ParsingException("Challenge length must be 16");
            }

            System.arraycopy(challenge, 0, this.challenge, 0, VALUE_SIZE);
            this.name = name;
        }
    }

    /**
     * EapMsChapV2ChallengeResponse represents the EAP MSCHAPv2 Response Packet (EAP MSCHAPv2#2.2).
     */
    public static class EapMsChapV2ChallengeResponse extends EapMsChapV2VariableTypeData {
        public static final int VALUE_SIZE = 49;
        public static final int PEER_CHALLENGE_SIZE = 16;
        public static final int RESERVED_BYTES = 8;
        public static final int NT_RESPONSE_SIZE = 24;
        public static final int TYPE_DATA_HEADER_SIZE = 5;

        public final byte[] peerChallenge = new byte[PEER_CHALLENGE_SIZE];
        public final byte[] ntResponse = new byte[NT_RESPONSE_SIZE];
        public final int flags;
        public final byte[] name;

        public EapMsChapV2ChallengeResponse(
                int msChapV2Id, byte[] peerChallenge, byte[] ntResponse, int flags, byte[] name)
                throws EapMsChapV2ParsingException {
            super(
                    EAP_MSCHAP_V2_RESPONSE,
                    msChapV2Id,
                    TYPE_DATA_HEADER_SIZE + VALUE_SIZE + name.length);

            if (peerChallenge.length != PEER_CHALLENGE_SIZE) {
                throw new EapMsChapV2ParsingException("Peer-Challenge must be 16B");
            } else if (ntResponse.length != NT_RESPONSE_SIZE) {
                throw new EapMsChapV2ParsingException("NT-Response must be 24B");
            } else if (flags != 0) {
                throw new EapMsChapV2ParsingException("Flags must be 0x00");
            }

            System.arraycopy(peerChallenge, 0, this.peerChallenge, 0, PEER_CHALLENGE_SIZE);
            System.arraycopy(ntResponse, 0, this.ntResponse, 0, NT_RESPONSE_SIZE);
            this.flags = flags;
            this.name = name;
        }

        @Override
        public byte[] encode() {
            ByteBuffer buffer = ByteBuffer.allocate(msLength);
            buffer.put((byte) EAP_MSCHAP_V2_RESPONSE);
            buffer.put((byte) msChapV2Id);
            buffer.putShort((short) msLength);
            buffer.put((byte) VALUE_SIZE);
            buffer.put(peerChallenge);
            buffer.put(new byte[RESERVED_BYTES]);
            buffer.put(ntResponse);
            buffer.put((byte) flags);
            buffer.put(name);

            return buffer.array();
        }
    }

    /** Class for decoding EAP MSCHAPv2 type data. */
    public static class EapMsChapV2TypeDataDecoder {
        /**
         * Decodes and returns an EapMsChapV2ChallengeRequest for the specified eapTypeData.
         *
         * @param tag String for logging tag
         * @param eapTypeData byte[] to be decoded as an EapMsChapV2ChallengeRequest instance
         * @return DecodeResult wrapping an EapMsChapV2ChallengeRequest instance for the given
         *     eapTypeData iff the eapTypeData is formatted correctly. Otherwise, the DecodeResult
         *     wraps the appropriate EapError.
         */
        public DecodeResult<EapMsChapV2ChallengeRequest> decodeChallengeRequest(
                String tag, byte[] eapTypeData) {
            try {
                ByteBuffer buffer = ByteBuffer.wrap(eapTypeData);
                int opCode = Byte.toUnsignedInt(buffer.get());

                if (opCode != EAP_MSCHAP_V2_CHALLENGE) {
                    return new DecodeResult<>(
                            new EapError(
                                    new EapMsChapV2ParsingException(
                                            "Received type data with invalid opCode: "
                                                    + EAP_OP_CODE_STRING.getOrDefault(
                                                            opCode, "Unknown"))));
                }

                return new DecodeResult<>(new EapMsChapV2ChallengeRequest(buffer));
            } catch (BufferUnderflowException | EapMsChapV2ParsingException ex) {
                LOG.e(tag, "Error parsing EAP MSCHAPv2 Challenge Request type data");
                return new DecodeResult<>(new EapError(ex));
            }
        }

        /**
         * DecodeResult represents the result from calling a decode method within
         * EapMsChapV2TypeDataDecoder. It will contain either an EapMsChapV2TypeData or an EapError.
         *
         * @param <T> The EapMsChapV2TypeData type that is wrapped in this DecodeResult
         */
        public static class DecodeResult<T extends EapMsChapV2TypeData> {
            public final T eapTypeData;
            public final EapError eapError;

            public DecodeResult(T eapTypeData) {
                this.eapTypeData = eapTypeData;
                this.eapError = null;
            }

            public DecodeResult(EapError eapError) {
                this.eapTypeData = null;
                this.eapError = eapError;
            }

            /**
             * Checks whether this instance represents a successful decode operation.
             *
             * @return true iff this DecodeResult represents a successfully decoded Type Data
             */
            public boolean isSuccessfulDecode() {
                return eapTypeData != null;
            }
        }
    }
}
