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

package android.net.ipsec.ike;

import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.util.Objects;

/**
 * IkeKeyIdIdentification represents an IKE entity identification based on a Key ID.
 *
 * <p>Key ID is an octet stream that may be used to pass vendor-specific information necessary to do
 * certain proprietary types of identification.
 *
 * @hide
 */
@SystemApi
public final class IkeKeyIdIdentification extends IkeIdentification {
    /** The KEY ID in octet stream. */
    @NonNull public final byte[] keyId;

    /**
     * Construct an instance of {@link IkeKeyIdIdentification} with a Key ID.
     *
     * @param keyId the Key ID in bytes.
     */
    public IkeKeyIdIdentification(@NonNull byte[] keyId) {
        super(ID_TYPE_KEY_ID);
        this.keyId = keyId;
    }

    /** @hide */
    @Override
    public int hashCode() {
        // idType is also hashed to prevent collisions with other IkeAuthentication subtypes
        return Objects.hash(idType, keyId);
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof IkeKeyIdIdentification)) return false;

        // idType already verified based on class type; no need to check again.
        return keyId.equals(((IkeKeyIdIdentification) o).keyId);
    }

    /**
     * Retrieve the byte-representation of the ID data.
     *
     * @return the byte-representation of the ID data.
     * @hide
     */
    @Override
    public byte[] getEncodedIdData() {
        return keyId;
    }
}
