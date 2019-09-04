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

package com.android.ike.eap.statemachine;

import static com.android.ike.eap.message.EapData.EAP_IDENTITY;
import static com.android.ike.eap.message.EapData.EAP_TYPE_SIM;
import static com.android.ike.eap.message.EapMessage.EAP_CODE_FAILURE;
import static com.android.ike.eap.message.EapMessage.EAP_CODE_REQUEST;
import static com.android.ike.eap.message.EapMessage.EAP_CODE_SUCCESS;
import static com.android.ike.eap.message.EapTestMessageDefinitions.EAP_SIM_IDENTITY;
import static com.android.ike.eap.message.EapTestMessageDefinitions.ID_INT;
import static com.android.ike.eap.message.EapTestMessageDefinitions.IMSI;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_ANY_ID_REQ;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_ENCR_DATA;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_IV;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_MAC;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_PERMANENT_ID_REQ;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.EAP_AT_VERSION_LIST;
import static com.android.ike.eap.message.simaka.EapSimTypeData.EAP_SIM_START;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.android.ike.eap.EapResult;
import com.android.ike.eap.EapResult.EapError;
import com.android.ike.eap.EapResult.EapFailure;
import com.android.ike.eap.exceptions.EapInvalidRequestException;
import com.android.ike.eap.exceptions.simaka.EapSimAkaIdentityUnavailableException;
import com.android.ike.eap.message.EapData;
import com.android.ike.eap.message.EapMessage;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtAnyIdReq;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtIdentity;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtMac;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtPermanentIdReq;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtVersionList;
import com.android.ike.eap.message.simaka.EapSimAkaTypeData.DecodeResult;
import com.android.ike.eap.message.simaka.EapSimTypeData;
import com.android.ike.eap.statemachine.EapMethodStateMachine.FinalState;
import com.android.ike.eap.statemachine.EapSimMethodStateMachine.StartState;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

public class EapSimStartStateTest extends EapSimStateTest {

    private StartState mStartState;
    private LinkedHashMap<Integer, EapSimAkaAttribute> mAttributes;

    @Before
    public void setUp() {
        super.setUp();
        mStartState = mEapSimMethodStateMachine.new StartState(null);
        mEapSimMethodStateMachine.transitionTo(mStartState);

        mAttributes = new LinkedHashMap<>();
    }

    @Test
    public void testProcessSuccess() throws Exception {
        EapMessage input = new EapMessage(EAP_CODE_SUCCESS, ID_INT, null);
        EapResult result = mStartState.process(input);

        EapError eapError = (EapError) result;
        assertTrue(eapError.cause instanceof EapInvalidRequestException);
    }

    @Test
    public void testProcessFailure() throws Exception {
        EapMessage input = new EapMessage(EAP_CODE_FAILURE, ID_INT, null);
        EapResult result = mStartState.process(input);
        assertTrue(mEapSimMethodStateMachine.getState() instanceof FinalState);

        assertTrue(result instanceof EapFailure);
    }

    @Test
    public void testProcessIncorrectEapMethodType() throws Exception {
        EapData eapData = new EapData(EAP_IDENTITY, DUMMY_EAP_TYPE_DATA);
        EapMessage eapMessage = new EapMessage(EAP_CODE_REQUEST, ID_INT, eapData);

        EapResult result = mStartState.process(eapMessage);
        EapError eapError = (EapError) result;
        assertTrue(eapError.cause instanceof EapInvalidRequestException);
    }

    @Test
    public void testIsValidStartAttributes() throws Exception {
        mAttributes.put(EAP_AT_VERSION_LIST, new AtVersionList(8, 1));
        mAttributes.put(EAP_AT_PERMANENT_ID_REQ, new AtPermanentIdReq());
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertTrue(mStartState.isValidStartAttributes(eapSimTypeData));
    }

    @Test
    public void testIsValidStartAttributesMissingVersionList() throws Exception {
        mAttributes.put(EAP_AT_PERMANENT_ID_REQ, new AtPermanentIdReq());
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertFalse(mStartState.isValidStartAttributes(eapSimTypeData));
    }

    @Test
    public void testIsValidStartAttributesMultipleIdRequests() throws Exception {
        mAttributes.put(EAP_AT_VERSION_LIST, new AtVersionList(8, 1));
        mAttributes.put(EAP_AT_PERMANENT_ID_REQ, new AtPermanentIdReq());
        mAttributes.put(EAP_AT_ANY_ID_REQ, new AtAnyIdReq());
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertFalse(mStartState.isValidStartAttributes(eapSimTypeData));
    }

    @Test
    public void testIsValidStartAttributesInvalidAttributes() throws Exception {
        mAttributes.put(EAP_AT_VERSION_LIST, new AtVersionList(8, 1));
        mAttributes.put(EAP_AT_PERMANENT_ID_REQ, new AtPermanentIdReq());
        mAttributes.put(EAP_AT_MAC, new AtMac());
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertFalse(mStartState.isValidStartAttributes(eapSimTypeData));

        mAttributes.remove(EAP_AT_MAC);
        mAttributes.put(EAP_AT_IV, null); // just need <K, V> pair in the map
        eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertFalse(mStartState.isValidStartAttributes(eapSimTypeData));

        mAttributes.remove(EAP_AT_IV);
        mAttributes.put(EAP_AT_ENCR_DATA, null); // just need <K, V> pair in the map
        eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        assertFalse(mStartState.isValidStartAttributes(eapSimTypeData));
    }

    @Test
    public void testAddIdentityAttributeToResponse() throws Exception {
        EapSimTypeData eapSimTypeData = new EapSimTypeData(
                EAP_SIM_START, Arrays.asList(new AtPermanentIdReq()));

        when(mMockTelephonyManager.getSubscriberId()).thenReturn(IMSI);

        AtIdentity atIdentity = mStartState.getIdentityResponse(eapSimTypeData);
        assertArrayEquals(EAP_SIM_IDENTITY.getBytes(), mStartState.mIdentity);
        verify(mMockTelephonyManager).getSubscriberId();
        assertArrayEquals(EAP_SIM_IDENTITY.getBytes(), atIdentity.identity);
        verifyNoMoreInteractions(mMockTelephonyManager);
    }

    @Test
    public void testAddIdentityAttributeToResponseImsiUnavailable() throws Exception {
        EapMessage eapMessage = new EapMessage(
                EAP_CODE_REQUEST,
                ID_INT,
                new EapData(EAP_TYPE_SIM, DUMMY_EAP_TYPE_DATA));
        mAttributes.put(EAP_AT_VERSION_LIST, new AtVersionList(8, 1));
        mAttributes.put(EAP_AT_PERMANENT_ID_REQ, new AtPermanentIdReq());
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, mAttributes);
        DecodeResult decodeResult = new DecodeResult(eapSimTypeData);

        when(mMockEapSimTypeDataDecoder.decode(DUMMY_EAP_TYPE_DATA)).thenReturn(decodeResult);
        when(mMockTelephonyManager.getSubscriberId()).thenReturn(null);

        EapResult result = mStartState.process(eapMessage);
        EapError eapError = (EapError) result;
        assertTrue(eapError.cause instanceof EapSimAkaIdentityUnavailableException);

        verify(mMockTelephonyManager).getSubscriberId();
        verifyNoMoreInteractions(mMockTelephonyManager);
    }

    @Test
    public void testAddIdentityAttributeToResponseNoIdRequest() throws Exception {
        EapSimTypeData eapSimTypeData = new EapSimTypeData(EAP_SIM_START, Arrays.asList());

        AtIdentity atIdentity = mStartState.getIdentityResponse(eapSimTypeData);
        assertNull(atIdentity);
        verifyNoMoreInteractions(mMockTelephonyManager);
    }
}
