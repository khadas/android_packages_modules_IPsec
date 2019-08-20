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

package com.android.ike.eap.message.simaka;

import static com.android.ike.eap.EapAuthenticator.LOG;

import com.android.ike.eap.exceptions.simaka.EapSimAkaInvalidAtPaddingException;
import com.android.ike.eap.exceptions.simaka.EapSimAkaInvalidAttributeException;
import com.android.ike.eap.exceptions.simaka.EapSimInvalidAtRandException;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EapSimAkaAttribute represents a single EAP SIM/AKA Attribute.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4186">RFC 4186, Extensible Authentication
 * Protocol for Subscriber Identity Modules (EAP-SIM)</a>
 * @see <a href="https://tools.ietf.org/html/rfc4187">RFC 4187, Extensible Authentication
 * Protocol for Authentication and Key Agreement (EAP-AKA)</a>
 * @see <a href="https://www.iana.org/assignments/eap-numbers/eap-numbers.xhtml">EAP SIM/AKA
 * Attributes</a>
 */
public abstract class EapSimAkaAttribute {
    static final int LENGTH_SCALING = 4;

    private static final int MIN_ATTR_LENGTH = 4;

    public static final int SKIPPABLE_ATTRIBUTE_RANGE_START = 128;

    // EAP non-Skippable Attribute values defined by IANA
    // https://www.iana.org/assignments/eapsimaka-numbers/eapsimaka-numbers.xhtml
    public static final int EAP_AT_RAND = 1;
    public static final int EAP_AT_PADDING = 6;
    public static final int EAP_AT_NONCE_MT = 7;
    public static final int EAP_AT_PERMANENT_ID_REQ = 10;
    public static final int EAP_AT_MAC = 11;
    public static final int EAP_AT_NOTIFICATION = 12;
    public static final int EAP_AT_ANY_ID_REQ = 13;
    public static final int EAP_AT_IDENTITY = 14;
    public static final int EAP_AT_VERSION_LIST = 15;
    public static final int EAP_AT_SELECTED_VERSION = 16;
    public static final int EAP_AT_FULLAUTH_ID_REQ = 17;
    public static final int EAP_AT_COUNTER = 19;
    public static final int EAP_AT_COUNTER_TOO_SMALL = 20;
    public static final int EAP_AT_NONCE_S = 21;
    public static final int EAP_AT_CLIENT_ERROR_CODE = 22;

    // EAP Skippable Attribute values defined by IANA
    // https://www.iana.org/assignments/eapsimaka-numbers/eapsimaka-numbers.xhtml
    public static final int EAP_AT_IV = 129;
    public static final int EAP_AT_ENCR_DATA = 130;
    public static final int EAP_AT_NEXT_PSEUDONYM = 132;
    public static final int EAP_AT_NEXT_REAUTH_ID = 133;
    public static final int EAP_AT_RESULT_IND = 135;

    public static final Map<Integer, String> EAP_ATTRIBUTE_STRING = new HashMap<>();
    static {
        EAP_ATTRIBUTE_STRING.put(EAP_AT_RAND, "AT_RAND");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_PADDING, "AT_PADDING");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_NONCE_MT, "AT_NONCE_MT");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_PERMANENT_ID_REQ, "AT_PERMANENT_ID_REQ");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_MAC, "AT_MAC");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_NOTIFICATION, "AT_NOTIFICATION");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_ANY_ID_REQ, "AT_ANY_ID_REQ");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_IDENTITY, "AT_IDENTITY");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_VERSION_LIST, "AT_VERSION_LIST");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_SELECTED_VERSION, "AT_SELECTED_VERSION");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_FULLAUTH_ID_REQ, "AT_FULLAUTH_ID_REQ");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_COUNTER, "AT_COUNTER");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_COUNTER_TOO_SMALL, "AT_COUNTER_TOO_SMALL");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_NONCE_S, "AT_NONCE_S");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_CLIENT_ERROR_CODE, "AT_CLIENT_ERROR_CODE");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_IV, "AT_IV");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_ENCR_DATA, "AT_ENCR_DATA");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_NEXT_PSEUDONYM, "AT_NEXT_PSEUDONYM");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_NEXT_REAUTH_ID, "AT_NEXT_REAUTH_ID");
        EAP_ATTRIBUTE_STRING.put(EAP_AT_RESULT_IND, "AT_RESULT_IND");
    }

    public final int attributeType;
    public final int lengthInBytes;

    protected EapSimAkaAttribute(int attributeType, int lengthInBytes)
            throws EapSimAkaInvalidAttributeException {
        this.attributeType = attributeType;
        this.lengthInBytes = lengthInBytes;

        if (lengthInBytes % LENGTH_SCALING != 0) {
            throw new EapSimAkaInvalidAttributeException("Attribute length must be multiple of 4");
        }
    }

    /**
     * Encodes this EapSimAkaAttribute into the given ByteBuffer
     *
     * @param byteBuffer the ByteBuffer that this instance will be written to
     */
    public abstract void encode(ByteBuffer byteBuffer);

    protected void encodeAttributeHeader(ByteBuffer byteBuffer) {
        byteBuffer.put((byte) attributeType);
        byteBuffer.put((byte) (lengthInBytes / LENGTH_SCALING));
    }

    void consumePadding(int bytesUsed, ByteBuffer byteBuffer) {
        int paddingRemaining = lengthInBytes - bytesUsed;
        byteBuffer.get(new byte[paddingRemaining]);
    }

    void addPadding(int bytesUsed, ByteBuffer byteBuffer) {
        int paddingNeeded = lengthInBytes - bytesUsed;
        byteBuffer.put(new byte[paddingNeeded]);
    }

    /**
     * EapSimAkaUnsupportedAttribute represents any unsupported, skippable EAP-SIM attribute.
     */
    public static class EapSimAkaUnsupportedAttribute extends EapSimAkaAttribute {
        // Attribute Type (1B) + Attribute Length (1B) = 2B Header
        private static final int HEADER_BYTES = 2;

        public final byte[] data;

        public EapSimAkaUnsupportedAttribute(
                int attributeType,
                int lengthInBytes,
                ByteBuffer byteBuffer) throws EapSimAkaInvalidAttributeException {
            super(attributeType, lengthInBytes);

            // Attribute not supported, but remaining attribute still needs to be saved
            int remainingBytes = lengthInBytes - HEADER_BYTES;
            data = new byte[remainingBytes];
            byteBuffer.get(data);
        }

        @VisibleForTesting
        public EapSimAkaUnsupportedAttribute(int attributeType, int lengthInBytes, byte[] data)
                throws EapSimAkaInvalidAttributeException {
            super(attributeType, lengthInBytes);
            this.data = data;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(data);
        }
    }

    /**
     * AtVersionList represents the AT_VERSION_LIST attribute defined in RFC 4186#10.2
     */
    public static class AtVersionList extends EapSimAkaAttribute {
        private static final int BYTES_PER_VERSION = 2;

        public final List<Integer> versions = new ArrayList<>();

        public AtVersionList(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_VERSION_LIST, lengthInBytes);

            // number of bytes used to represent list (RFC 4186 Section 10.2)
            int bytesInList = Short.toUnsignedInt(byteBuffer.getShort());
            if (bytesInList % BYTES_PER_VERSION != 0) {
                throw new EapSimAkaInvalidAttributeException(
                        "Actual Version List Length must be multiple of 2");
            }

            int numVersions =  bytesInList / BYTES_PER_VERSION;
            for (int i = 0; i < numVersions; i++) {
                versions.add(Short.toUnsignedInt(byteBuffer.getShort()));
            }

            int bytesUsed = MIN_ATTR_LENGTH + (BYTES_PER_VERSION * versions.size());
            consumePadding(bytesUsed, byteBuffer);
        }

        @VisibleForTesting
        public AtVersionList(int lengthInBytes, int... versions)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_VERSION_LIST, lengthInBytes);
            for (int version : versions) {
                this.versions.add(version);
            }
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);

            byteBuffer.putShort((short) (versions.size() * BYTES_PER_VERSION));
            for (int i : versions) {
                byteBuffer.putShort((short) i);
            }

            int bytesUsed = MIN_ATTR_LENGTH + (BYTES_PER_VERSION * versions.size());
            addPadding(bytesUsed, byteBuffer);
        }
    }

    /**
     * AtSelectedVersion represents the AT_SELECTED_VERSION attribute defined in RFC 4186#10.3
     */
    public static class AtSelectedVersion extends EapSimAkaAttribute {
        private static final String TAG = AtSelectedVersion.class.getSimpleName();
        private static final int LENGTH = LENGTH_SCALING;

        public static final int SUPPORTED_VERSION = 1;

        public final int selectedVersion;

        public AtSelectedVersion(int lengthInBytes, int selectedVersion)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_SELECTED_VERSION, LENGTH);
            this.selectedVersion = selectedVersion;

            if (lengthInBytes != LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }
        }

        @VisibleForTesting
        public AtSelectedVersion(int selectedVersion) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_SELECTED_VERSION, LENGTH);
            this.selectedVersion = selectedVersion;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.putShort((short) selectedVersion);
        }

        /**
         * Constructs and returns an AtSelectedVersion for the only supported version of EAP-SIM
         *
         * @return an AtSelectedVersion for the supported version (1) of EAP-SIM
         */
        public static AtSelectedVersion getSelectedVersion() {
            try {
                return new AtSelectedVersion(LENGTH, SUPPORTED_VERSION);
            } catch (EapSimAkaInvalidAttributeException ex) {
                // this should never happen
                LOG.wtf(TAG,
                        "Error thrown while creating AtSelectedVersion with correct length", ex);
                throw new AssertionError("Impossible exception encountered", ex);
            }
        }
    }

    /**
     * AtNonceMt represents the AT_NONCE_MT attribute defined in RFC 4186#10.4
     */
    public static class AtNonceMt extends EapSimAkaAttribute {
        private static final int LENGTH = 5 * LENGTH_SCALING;
        private static final int RESERVED_BYTES = 2;

        public static final int NONCE_MT_LENGTH = 16;

        public final byte[] nonceMt = new byte[NONCE_MT_LENGTH];

        public AtNonceMt(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NONCE_MT, LENGTH);
            if (lengthInBytes != LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            // next two bytes are reserved (RFC 4186 Section 10.4)
            byteBuffer.get(new byte[RESERVED_BYTES]);
            byteBuffer.get(nonceMt);
        }

        @VisibleForTesting
        public AtNonceMt(byte[] nonceMt) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NONCE_MT, LENGTH);
            for (int i = 0; i < nonceMt.length; i++) {
                this.nonceMt[i] = nonceMt[i];
            }
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(new byte[RESERVED_BYTES]);
            byteBuffer.put(nonceMt);
        }
    }

    private abstract static class AtIdReq extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = LENGTH_SCALING;
        private static final int RESERVED_BYTES = 2;

        protected AtIdReq(int lengthInBytes, int attributeType, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(attributeType, ATTR_LENGTH);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            // next two bytes are reserved (RFC 4186 Section 10.5-10.7)
            byteBuffer.get(new byte[RESERVED_BYTES]);
        }

        @VisibleForTesting
        protected AtIdReq(int attributeType) throws EapSimAkaInvalidAttributeException {
            super(attributeType, ATTR_LENGTH);
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(new byte[RESERVED_BYTES]);
        }
    }

    /**
     * AtPermanentIdReq represents the AT_PERMANENT_ID_REQ attribute defined in RFC 4186#10.5 and
     * RFC 4187#10.2
     */
    public static class AtPermanentIdReq extends AtIdReq {
        public AtPermanentIdReq(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(lengthInBytes, EAP_AT_PERMANENT_ID_REQ, byteBuffer);
        }

        @VisibleForTesting
        public AtPermanentIdReq() throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_PERMANENT_ID_REQ);
        }
    }

    /**
     * AtAnyIdReq represents the AT_ANY_ID_REQ attribute defined in RFC 4186#10.6 and RFC 4187#10.3
     */
    public static class AtAnyIdReq extends AtIdReq {
        public AtAnyIdReq(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(lengthInBytes, EAP_AT_ANY_ID_REQ, byteBuffer);
        }

        @VisibleForTesting
        public AtAnyIdReq() throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_ANY_ID_REQ);
        }
    }

    /**
     * AtFullauthIdReq represents the AT_FULLAUTH_ID_REQ attribute defined in RFC 4186#10.7 and RFC
     * 4187#10.4
     */
    public static class AtFullauthIdReq extends AtIdReq {
        public AtFullauthIdReq(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(lengthInBytes, EAP_AT_FULLAUTH_ID_REQ, byteBuffer);
        }

        @VisibleForTesting
        public AtFullauthIdReq() throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_FULLAUTH_ID_REQ);
        }
    }

    /**
     * AtIdentity represents the AT_IDENTITY attribute defined in RFC 4186#10.8 and RFC 4187#10.5
     */
    public static class AtIdentity extends EapSimAkaAttribute {
        public final byte[] identity;

        public AtIdentity(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_IDENTITY, lengthInBytes);

            int identityLength = Short.toUnsignedInt(byteBuffer.getShort());
            identity = new byte[identityLength];
            byteBuffer.get(identity);

            int bytesUsed = MIN_ATTR_LENGTH + identityLength;
            consumePadding(bytesUsed, byteBuffer);
        }

        @VisibleForTesting
        public AtIdentity(int lengthInBytes, byte[] identity)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_IDENTITY, lengthInBytes);
            this.identity = identity;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.putShort((short) identity.length);
            byteBuffer.put(identity);

            int bytesUsed = MIN_ATTR_LENGTH + identity.length;
            addPadding(bytesUsed, byteBuffer);
        }

        /**
         * Creates and returns an AtIdentity instance for the given identity.
         *
         * @param identity byte-array representing the identity for the AtIdentity
         * @return AtIdentity instance for the given identity byte-array
         */
        public static AtIdentity getAtIdentity(byte[] identity)
                throws EapSimAkaInvalidAttributeException {
            int lengthInBytes = MIN_ATTR_LENGTH + identity.length;
            if (lengthInBytes % LENGTH_SCALING != 0) {
                lengthInBytes += LENGTH_SCALING - (lengthInBytes % LENGTH_SCALING);
            }

            return new AtIdentity(lengthInBytes, identity);
        }
    }

    /**
     * AtRandSim represents the AT_RAND attribute for EAP-SIM defined in RFC 4186#10.9
     */
    public static class AtRandSim extends EapSimAkaAttribute {
        private static final int RAND_LENGTH = 16;
        private static final int RESERVED_BYTES = 2;
        private static final int MIN_RANDS = 2;
        private static final int MAX_RANDS = 3;

        public final List<byte[]> rands = new ArrayList<>(MAX_RANDS);

        public AtRandSim(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_RAND, lengthInBytes);

            // next two bytes are reserved (RFC 4186 Section 10.9)
            byteBuffer.get(new byte[RESERVED_BYTES]);

            int numRands = (lengthInBytes - MIN_ATTR_LENGTH) / RAND_LENGTH;
            if (!isValidNumRands(numRands)) {
                throw new EapSimInvalidAtRandException("Unexpected number of rands: " + numRands);
            }

            for (int i = 0; i < numRands; i++) {
                byte[] rand = new byte[RAND_LENGTH];
                byteBuffer.get(rand);

                // check for rand being unique (RFC 4186 Section 10.9)
                for (int j = 0; j < i; j++) {
                    byte[] otherRand = rands.get(j);
                    if (Arrays.equals(rand, otherRand)) {
                        throw new EapSimAkaInvalidAttributeException("Received identical RANDs");
                    }
                }
                rands.add(rand);
            }
        }

        @VisibleForTesting
        public AtRandSim(int lengthInBytes, byte[]... rands)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_RAND, lengthInBytes);

            if (!isValidNumRands(rands.length)) {
                throw new EapSimInvalidAtRandException("Unexpected number of rands: "
                        + rands.length);
            }
            for (byte[] rand : rands) {
                this.rands.add(rand);
            }
        }

        private boolean isValidNumRands(int numRands) {
            // numRands is valid iff 2 <= numRands <= 3
            return MIN_RANDS <= numRands && numRands <= MAX_RANDS;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(new byte[RESERVED_BYTES]);

            for (byte[] rand : rands) {
                byteBuffer.put(rand);
            }
        }
    }

    /**
     * AtPadding represents the AT_PADDING attribute defined in RFC 4186#10.12 and RFC 4187#10.12
     */
    public static class AtPadding extends EapSimAkaAttribute {
        private static final int ATTR_HEADER = 2;

        public AtPadding(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_PADDING, lengthInBytes);

            int remainingBytes = lengthInBytes - ATTR_HEADER;
            for (int i = 0; i < remainingBytes; i++) {
                // Padding must be checked to all be 0x00 bytes (RFC 4186 Section 10.12)
                if (byteBuffer.get() != 0) {
                    throw new EapSimAkaInvalidAtPaddingException("Padding bytes must all be 0x00");
                }
            }
        }

        @VisibleForTesting
        public AtPadding(int lengthInBytes) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_PADDING, lengthInBytes);
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);

            addPadding(ATTR_HEADER, byteBuffer);
        }
    }

    /**
     * AtMac represents the AT_MAC attribute defined in RFC 4186#10.14 and RFC 4187#10.15
     */
    public static class AtMac extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = 5 * LENGTH_SCALING;
        private static final int RESERVED_BYTES = 2;

        public static final int MAC_LENGTH = 4 * LENGTH_SCALING;

        public final byte[] mac;

        public AtMac(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_MAC, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            // next two bytes are reserved (RFC 4186 Section 10.14)
            byteBuffer.get(new byte[RESERVED_BYTES]);

            mac = new byte[MAC_LENGTH];
            byteBuffer.get(mac);
        }

        // Used for calculating MACs. Per RFC 4186 Section 10.14, the MAC should be calculated over
        // the entire packet, with the value field of the MAC attribute set to zero.
        public AtMac() throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_MAC, ATTR_LENGTH);
            mac = new byte[MAC_LENGTH];
        }

        public AtMac(byte[] mac) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_MAC, ATTR_LENGTH);
            this.mac = mac;

            if (mac.length != MAC_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid length for MAC");
            }
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(new byte[RESERVED_BYTES]);
            byteBuffer.put(mac);
        }
    }

    /**
     * AtCounter represents the AT_COUNTER attribute defined in RFC 4186#10.15 and RFC 4187#10.16
     */
    public static class AtCounter extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = LENGTH_SCALING;

        public final int counter;

        public AtCounter(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_COUNTER, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            this.counter = Short.toUnsignedInt(byteBuffer.getShort());
        }

        @VisibleForTesting
        public AtCounter(int counter) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_COUNTER, ATTR_LENGTH);
            this.counter = counter;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.putShort((short) counter);
        }
    }


    /**
     * AtCounterTooSmall represents the AT_COUNTER_TOO_SMALL attribute defined in RFC 4186#10.16 and
     * RFC 4187#10.17
     */
    public static class AtCounterTooSmall extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = LENGTH_SCALING;
        private static final int ATTR_HEADER = 2;

        public AtCounterTooSmall(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_COUNTER_TOO_SMALL, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }
            consumePadding(ATTR_HEADER, byteBuffer);
        }

        public AtCounterTooSmall() throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_COUNTER_TOO_SMALL, ATTR_LENGTH);
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            addPadding(ATTR_HEADER, byteBuffer);
        }
    }

    /**
     * AtNonceS represents the AT_NONCE_S attribute defined in RFC 4186#10.17 and RFC 4187#10.18
     *
     * <p>This Nonce is generated by the server and used for fast re-authentication only.
     */
    public static class AtNonceS extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = 5 * LENGTH_SCALING;
        private static final int NONCE_S_LENGTH = 4 * LENGTH_SCALING;
        private static final int RESERVED_BYTES = 2;

        public final byte[] nonceS;

        public AtNonceS(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NONCE_S, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            // next two bytes are reserved (RFC 4186 Section 10.17)
            byteBuffer.get(new byte[RESERVED_BYTES]);

            nonceS = new byte[NONCE_S_LENGTH];
            byteBuffer.get(nonceS);
        }

        @VisibleForTesting
        public AtNonceS(byte[] nonceS) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NONCE_S, ATTR_LENGTH);
            this.nonceS = nonceS;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.put(new byte[RESERVED_BYTES]);
            byteBuffer.put(nonceS);
        }
    }

    /**
     * AtNotification represents the AT_NOTIFICATION attribute defined in RFC 4186#10.18 and RFC
     * 4187#10.19
     */
    public static class AtNotification extends EapSimAkaAttribute {
        private static final int ATTR_LENGTH = 4;
        private static final int SUCCESS_MASK = 0x8000;
        private static final int PRE_SUCCESSFUL_CHALLENGE_MASK = 0x4000;

        // Notification codes defined in RFC 4186 Section 10.18
        public static final int GENERAL_FAILURE_POST_CHALLENGE = 0;
        public static final int GENERAL_FAILURE_PRE_CHALLENGE = 16384; // 0x4000
        public static final int SUCCESS = 32768; // 0x8000
        public static final int DENIED_ACCESS_POST_CHALLENGE = 1026;
        public static final int USER_NOT_SUBSCRIBED_POST_CHALLENGE = 1031;

        private static final Map<Integer, String> CODE_DEFS = loadCodeDefs();

        public final boolean isSuccessCode;
        public final boolean isPreSuccessfulChallenge;
        public final int notificationCode;

        public AtNotification(int lengthInBytes, ByteBuffer byteBuffer)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NOTIFICATION, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            notificationCode = Short.toUnsignedInt(byteBuffer.getShort());

            // If Success bit == 0, failure is implied
            isSuccessCode = (notificationCode & SUCCESS_MASK) == SUCCESS_MASK;

            // if Phase bit == 0, notification code can only be used after a successful
            isPreSuccessfulChallenge =
                    (notificationCode & PRE_SUCCESSFUL_CHALLENGE_MASK)
                            == PRE_SUCCESSFUL_CHALLENGE_MASK;

            if (isSuccessCode && isPreSuccessfulChallenge) {
                throw new EapSimAkaInvalidAttributeException("Invalid state specified");
            }
        }

        @VisibleForTesting
        public AtNotification(int notificationCode) throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_NOTIFICATION, ATTR_LENGTH);
            this.notificationCode = notificationCode;

            // If Success bit == 0, failure is implied
            isSuccessCode = (notificationCode & SUCCESS_MASK) != 0;

            // if Phase bit == 0, notification code can only be used after a successful challenge
            isPreSuccessfulChallenge = (notificationCode & PRE_SUCCESSFUL_CHALLENGE_MASK) != 0;

            if (isSuccessCode && isPreSuccessfulChallenge) {
                throw new EapSimAkaInvalidAttributeException("Invalid state specified");
            }
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.putShort((short) notificationCode);
        }

        @Override
        public String toString() {
            String description = CODE_DEFS.getOrDefault(notificationCode, "Code not recognized");
            return "{Notification Code=" + notificationCode + ", descr=" + description + "}";
        }

        private static Map<Integer, String> loadCodeDefs() {
            Map<Integer, String> defs = new HashMap<>();
            defs.put(GENERAL_FAILURE_POST_CHALLENGE,
                    "General failure after authentication. (Implies failure, used after successful"
                    + " authentication.)");
            defs.put(GENERAL_FAILURE_PRE_CHALLENGE,
                    "General failure. (Implies failure, used before authentication.)");
            defs.put(SUCCESS,
                    "Success.  User has been successfully authenticated. (Does not imply failure,"
                    + " used after successful authentication).");
            defs.put(DENIED_ACCESS_POST_CHALLENGE,
                    "User has been temporarily denied access to the requested service. (Implies"
                    + " failure, used after successful authentication.)");
            defs.put(USER_NOT_SUBSCRIBED_POST_CHALLENGE,
                    "User has not subscribed to the requested service.  (Implies failure, used"
                    + " after successful authentication.)");
            return defs;
        }
    }

    /**
     * AtClientErrorCode represents the AT_CLIENT_ERROR_CODE attribute defined in RFC 4186#10.19 and
     * RFC 4187#10.20
     */
    public static class AtClientErrorCode extends EapSimAkaAttribute {
        private static final String TAG = AtClientErrorCode.class.getSimpleName();
        private static final int ATTR_LENGTH = 4;

        // Error codes defined in RFC 4186 Section 10.19
        public static final AtClientErrorCode UNABLE_TO_PROCESS = getClientErrorCode(0);
        public static final AtClientErrorCode UNSUPPORTED_VERSION = getClientErrorCode(1);
        public static final AtClientErrorCode INSUFFICIENT_CHALLENGES = getClientErrorCode(2);
        public static final AtClientErrorCode STALE_RANDS = getClientErrorCode(3);

        public final int errorCode;

        public AtClientErrorCode(int lengthInBytes, int errorCode)
                throws EapSimAkaInvalidAttributeException {
            super(EAP_AT_CLIENT_ERROR_CODE, lengthInBytes);

            if (lengthInBytes != ATTR_LENGTH) {
                throw new EapSimAkaInvalidAttributeException("Invalid Length specified");
            }

            this.errorCode = errorCode;
        }

        @Override
        public void encode(ByteBuffer byteBuffer) {
            encodeAttributeHeader(byteBuffer);
            byteBuffer.putShort((short) errorCode);
        }

        private static AtClientErrorCode getClientErrorCode(int errorCode) {
            try {
                return new AtClientErrorCode(ATTR_LENGTH, errorCode);
            } catch (EapSimAkaInvalidAttributeException exception) {
                LOG.wtf(TAG, "Exception thrown while making AtClientErrorCodeConstants");
                return null;
            }
        }
    }
}