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

import static android.telephony.TelephonyManager.APPTYPE_USIM;

import static com.android.ike.TestUtils.hexStringToByteArray;
import static com.android.ike.eap.message.EapData.EAP_TYPE_SIM;
import static com.android.ike.eap.message.EapMessage.EAP_CODE_REQUEST;
import static com.android.ike.eap.message.EapTestMessageDefinitions.EAP_SIM_CLIENT_ERROR_UNABLE_TO_PROCESS;
import static com.android.ike.eap.message.EapTestMessageDefinitions.EAP_SIM_NOTIFICATION_RESPONSE;
import static com.android.ike.eap.message.EapTestMessageDefinitions.ID_INT;
import static com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtNotification.GENERAL_FAILURE_PRE_CHALLENGE;
import static com.android.ike.eap.message.simaka.EapSimTypeData.EAP_SIM_NOTIFICATION;
import static com.android.ike.eap.message.simaka.EapSimTypeData.EAP_SIM_START;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.telephony.TelephonyManager;

import com.android.ike.eap.EapResult.EapError;
import com.android.ike.eap.EapResult.EapResponse;
import com.android.ike.eap.EapSessionConfig.EapSimConfig;
import com.android.ike.eap.exceptions.EapInvalidRequestException;
import com.android.ike.eap.message.EapData;
import com.android.ike.eap.message.EapMessage;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtNotification;
import com.android.ike.eap.message.simaka.EapSimAkaAttribute.AtVersionList;
import com.android.ike.eap.message.simaka.EapSimAkaTypeData;
import com.android.ike.eap.message.simaka.EapSimAkaTypeData.DecodeResult;
import com.android.ike.eap.message.simaka.EapSimTypeData;
import com.android.ike.eap.message.simaka.EapSimTypeData.EapSimTypeDataDecoder;
import com.android.ike.eap.statemachine.EapSimMethodStateMachine.CreatedState;

import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

public class EapSimMethodStateMachineTest {
    private static final int SUB_ID = 1;
    private static final byte[] DUMMY_EAP_TYPE_DATA = hexStringToByteArray("112233445566");

    private TelephonyManager mMockTelephonyManager;
    private EapSimTypeDataDecoder mMockEapSimTypeDataDecoder;

    private EapSimConfig mEapSimConfig = new EapSimConfig(SUB_ID, APPTYPE_USIM);
    private EapSimMethodStateMachine mEapSimMethodStateMachine;


    @Before
    public void setUp() {
        mMockTelephonyManager = mock(TelephonyManager.class);
        mMockEapSimTypeDataDecoder = mock(EapSimTypeDataDecoder.class);

        when(mMockTelephonyManager.createForSubscriptionId(SUB_ID))
                .thenReturn(mMockTelephonyManager);

        mEapSimMethodStateMachine =
                new EapSimMethodStateMachine(
                        mMockTelephonyManager,
                        mEapSimConfig,
                        new SecureRandom(),
                        mMockEapSimTypeDataDecoder);

        verify(mMockTelephonyManager).createForSubscriptionId(SUB_ID);
    }

    @Test
    public void testEapSimMethodStateMachineStartState() {
        assertTrue(mEapSimMethodStateMachine.getState() instanceof CreatedState);
    }

    @Test
    public void testGetMethod() {
        assertEquals(EAP_TYPE_SIM, mEapSimMethodStateMachine.getEapMethod());
    }

    @Test
    public void testEapSimFailsOnMultipleSimNotifications() throws Exception {
        EapData eapData = new EapData(EAP_TYPE_SIM, DUMMY_EAP_TYPE_DATA);
        EapMessage eapMessage = new EapMessage(EAP_CODE_REQUEST, ID_INT, eapData);

        // First EAP-SIM/Notification
        EapSimTypeData notificationTypeData =
                new EapSimTypeData(
                        EAP_SIM_NOTIFICATION,
                        Arrays.asList(new AtNotification(GENERAL_FAILURE_PRE_CHALLENGE)));
        DecodeResult<EapSimTypeData> decodeResult = new DecodeResult<>(notificationTypeData);
        when(mMockEapSimTypeDataDecoder.decode(eq(DUMMY_EAP_TYPE_DATA))).thenReturn(decodeResult);

        EapResponse eapResponse = (EapResponse) mEapSimMethodStateMachine.process(eapMessage);
        assertArrayEquals(EAP_SIM_NOTIFICATION_RESPONSE, eapResponse.packet);
        verify(mMockEapSimTypeDataDecoder).decode(DUMMY_EAP_TYPE_DATA);
        verifyNoMoreInteractions(mMockTelephonyManager, mMockEapSimTypeDataDecoder);

        // Transition to StartState
        decodeResult =
                new DecodeResult<>(
                        new EapSimTypeData(EAP_SIM_START, Arrays.asList(new AtVersionList(8, 1))));
        when(mMockEapSimTypeDataDecoder.decode(eq(DUMMY_EAP_TYPE_DATA))).thenReturn(decodeResult);

        eapResponse = (EapResponse) mEapSimMethodStateMachine.process(eapMessage);
        assertFalse(
                "EAP-Request/SIM-Start returned a Client-Error response",
                Arrays.equals(EAP_SIM_CLIENT_ERROR_UNABLE_TO_PROCESS, eapResponse.packet));

        // decoded in: previous 1 time + in CreatedState and StartState
        verify(mMockEapSimTypeDataDecoder, times(3)).decode(eq(DUMMY_EAP_TYPE_DATA));
        verifyNoMoreInteractions(mMockTelephonyManager, mMockEapSimTypeDataDecoder);

        // Second EAP-SIM/Notification
        decodeResult = new EapSimAkaTypeData.DecodeResult<>(notificationTypeData);
        when(mMockEapSimTypeDataDecoder.decode(eq(DUMMY_EAP_TYPE_DATA))).thenReturn(decodeResult);

        EapError eapError = (EapError) mEapSimMethodStateMachine.process(eapMessage);
        assertTrue(eapError.cause instanceof EapInvalidRequestException);

        // decoded previous 3 times + 1
        verify(mMockEapSimTypeDataDecoder, times(4)).decode(DUMMY_EAP_TYPE_DATA);
        verifyNoMoreInteractions(mMockTelephonyManager, mMockEapSimTypeDataDecoder);
    }
}
