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
package android.net.ipsec.ike.exceptions;

/**
 * IkeException is a generic IKE library exception class that provides type safety for all the
 * IKE-library-related exception classes that extend from it.
 *
 * @hide
 */
public abstract class IkeException extends Exception {
    /** @hide */
    protected IkeException() {
        super();
    }

    /** @hide */
    protected IkeException(String message) {
        super(message);
    }

    /** @hide */
    protected IkeException(Throwable cause) {
        super(cause);
    }

    /** @hide */
    protected IkeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns if this exception is caused by an IKE protocol error.
     *
     * @return true if this exception is caused by an IKE protocol error, false otherwise.
     * @hide
     */
    public boolean isProtocolException() {
        return this instanceof IkeProtocolException;
    }
}
