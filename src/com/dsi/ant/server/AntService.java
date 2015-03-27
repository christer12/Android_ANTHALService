/*
 * ANT Stack
 *
 * Copyright 2009 Dynastream Innovations
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dsi.ant.server;

import java.lang.reflect.Constructor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityManagerNative;
import android.app.Service;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import com.dsi.ant.core.*;

import com.dsi.ant.server.AntHalDefine;
import com.dsi.ant.server.HalSettings.Transport;
import com.dsi.ant.server.IAntHal;
import com.dsi.ant.server.IAntHalCallback;
import com.dsi.ant.server.VendorSpecificStateMachine.CommandCompleteCallback;
import com.dsi.ant.server.Version;
import com.dsi.ant.framers.IAntHciFramer;
import com.dsi.ant.framers.IAntHciFramer.InvalidAntPacketException;

import java.util.HashMap;

public class AntService extends Service
{
    private static final String TAG = "AntHalService";

    private static final boolean DEBUG = false;

    private static final boolean HAS_MULTI_USER_API =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;

    /**
     * This flag determines if background users are allowed to use the ANT radio or not. Note that
     * even if this flag is set, the active foreground user always has priority for using the
     * ANT radio.
     */
    private static final boolean ALLOW_BACKGROUND_USAGE = true;

    public static final String ANT_SERVICE = "AntService";

    /**
     * Allows the application to directly configure the ANT radio through the
     * proxy service. Malicious applications may prevent other ANT applications
     * from connecting to ANT devices
     */
    public static final String ANT_ADMIN_PERMISSION = "com.dsi.ant.permission.ANT_ADMIN";

    private JAntJava mJAnt = null;
    private IAntHciFramer mFramer = null;

    private boolean mInitialized = false;

    private Object mChangeAntPowerState_LOCK = new Object();
    private static Object sAntHalServiceDestroy_LOCK = new Object();

    /** Callback object for sending events to the upper layers */
    private volatile IAntHalCallback mCallback;
    /**
     * Used for synchronizing changes to {@link #mCallback}, {@link #mCallbackMap}, and
     * {@link #mCurrentUser}. Does not need to be used where a one-time read of the
     * {@link #mCallback} value is being done, however ALL WRITE ACCESSES must use this lock.
     */
    private final Object mUserCallback_LOCK = new Object();

    /**
     * The user handle associated with the current active user of the ANT HAL service.
     */
    private volatile UserHandle mCurrentUser;

    /**
     * Map containing the callback set for each current user.
     */
    private final HashMap<UserHandle, IAntHalCallback> mCallbackMap =
            new HashMap<UserHandle, IAntHalCallback>();

    private final VendorSpecificStateMachine.BTVSCallbacks mVSStateCallbacks =
        new VendorSpecificStateMachine.BTVSCallbacks()
        {
            @Override
            public void onInterfaceReady()
            {
                synchronized(mVSState_LOCK)
                {
                    mVSInterfaceUp = true;
                    mWaitingForVSState = false;
                    mVSState_LOCK.notifyAll();

                    if(HalSettings.TRANSPORT == Transport.HCI)
                    {
                        mVSState.setVSEventFilter(HalSettings.HCI_FILTER_MASK,
                            HalSettings.HCI_FILTER_VALUE);
                    }
                }
            }

            @Override
            public void onInterfaceDown()
            {
                synchronized(mVSState_LOCK)
                {
                    mVSInterfaceUp = false;
                    mWaitingForVSState = false;
                    mVSState_LOCK.notifyAll();
                }
            }

            @Override
            public void onEventReceived(byte [] params)
            {
                if (mFramer != null)
                {
                    try
                    {
                        byte[] antMessage = mFramer.getANTMessage(params);
                        receiveMessage(antMessage);
                    } catch (InvalidAntPacketException e)
                    {
                        // Wasn't a valid ant message, drop the packet.
                        return;
                    }
                }
            }
        };


    private final CommandCompleteCallback mVSEnableCallback = new CommandCompleteCallback()
    {
        @Override
        public void onCommandComplete(byte [] parameters)
        {
            synchronized (mVSState_LOCK)
            {
                if (parameters == null || parameters.length < 1 || parameters[0] != 0)
                {
                    mVSEnableResult = false;
                }
                else
                {
                    mVSEnableResult = true;
                }

                mVSState_LOCK.notifyAll();
            }
        }
    };

    private final CommandCompleteCallback mCommandCompleteCallback = new CommandCompleteCallback()
    {
        @Override
        public void onCommandComplete(byte [] parameters)
        {
            synchronized (mVSState_LOCK)
            {
                if (parameters == null || parameters.length < 1 || parameters[0] != 0)
                {
                    mCommandCompleteResult = false;
                }
                else
                {
                    mCommandCompleteResult = true;
                }
                mVSState_LOCK.notify();
            }
        }
    };

    private boolean mVSInterfaceUp = false;
    private boolean mWaitingForVSState = false;
    private Boolean mVSEnableResult = false;
    private Boolean mCommandCompleteResult = null;

    private VendorSpecificStateMachine mVSState;
    private final Object mVSState_LOCK = new Object();

    /**
     * Receives {@link Intent#ACTION_USER_SWITCHED} when we are not allowing background users
     * in order to clear the current user at the appropriate time.
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if(!ALLOW_BACKGROUND_USAGE)
            {
                if(HAS_MULTI_USER_API &&
                        Intent.ACTION_USER_SWITCHED.equals(action))
                {
                    clearCurrentUser();
                }
            }
        }
    };

    public static boolean startService(Context context)
    {
        return ( null != context.startService(new Intent(IAntHal.class.getName())) );
    }

    /**
     * Calls back the registered callback with the change to the new state
     * @param state the {@link AntHalDefine} state
     */
    private void setState(int state)
    {
        synchronized(mChangeAntPowerState_LOCK) {
            if(DEBUG) Log.i(TAG, "Setting ANT State = "+ state +" / "+ AntHalDefine.getAntHalStateString(state));

            // Use caching instead of synchronization so that we do not have to hold a lock during a callback.
            // It is safe to not hold the lock because we are not doing any write accesses.
            IAntHalCallback callback = mCallback;
            if (callback != null)
            {
                try
                {
                    if(DEBUG) Log.d(TAG, "Calling status changed callback "+ callback.toString());

                    callback.antHalStateChanged(state);
                }
                catch (RemoteException e)
                {
                    // Don't do anything as this is a problem in the application

                    if(DEBUG) Log.e(TAG, "ANT HAL State Changed callback failure in application", e);
                }
            }
            else
            {
                if(DEBUG) Log.d(TAG, "Calling status changed callback is null");
            }
        }
    }

    /**
     * Clear the current user, telling the associated ARS instance that the chip is disabled.
     */
    private void clearCurrentUser()
    {
        if (DEBUG) Log.i(TAG, "Clearing active user");
        synchronized (mUserCallback_LOCK)
        {
            setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
            mCurrentUser = null;
            mCallback = null;
            doSetAntState(AntHalDefine.ANT_HAL_STATE_DISABLED);
        }
    }

    /**
     * Attempt to change the current user to the calling user.
     * @return True if the calling user is now the current user (even if they were before the call
     *         was made), False otherwise.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private boolean trySwitchToCallingUser()
    {
        // Lock held here to avoid ordering issues if it is needed within the function.
        synchronized (mChangeAntPowerState_LOCK)
        {
            synchronized (mUserCallback_LOCK)
            {
                UserHandle callingUser = Binder.getCallingUserHandle();
                if(DEBUG) Log.d(TAG, "Trying to make user: " + callingUser + " the current user.");
                boolean isActiveUser = false;
                boolean shouldSwitch = false;
                long id = 0;

                // Always allow if they already are the current user.
                if(callingUser.equals(mCurrentUser))
                {
                    shouldSwitch = true;
                }

                try
                {
                    // Check foreground user using ANT HAL Service permissions.
                    id = Binder.clearCallingIdentity();
                    UserHandle activeUser =
                            ActivityManagerNative.getDefault().getCurrentUser().getUserHandle();
                    isActiveUser = activeUser.equals(callingUser);
                } catch (RemoteException e)
                {
                    if(DEBUG) Log.w(TAG, "Could not determine the foreground user.");
                    // don't know who the current user is, assume they are not the active user and
                    // continue.
                } finally
                {
                    // always restore our identity.
                    Binder.restoreCallingIdentity(id);
                }

                if(isActiveUser)
                {
                    // Always allow the active user to become the current user.
                    shouldSwitch = true;
                }

                if(ALLOW_BACKGROUND_USAGE)
                {
                    // Allow anyone to become the current user if there is no current user.
                    if(mCurrentUser == null)
                    {
                        shouldSwitch = true;
                    }
                }

                if(shouldSwitch)
                {
                    // Only actually do the switch if the users are different.
                    if(!callingUser.equals(mCurrentUser))
                    {
                        if (DEBUG) Log.i(TAG, "Making " + callingUser + " the current user.");
                        // Need to send state updates as the current user switches.
                        // The mChangeAntPowerState_LOCK needs to be held across these calls to
                        // prevent state updates during the user switch. It is held for this entire
                        // function to prevent lock ordering issues.
                        setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
                        mCurrentUser = callingUser;
                        mCallback = mCallbackMap.get(callingUser);
                        setState(doGetAntState(true));
                    } else
                    {
                        if (DEBUG) Log.d(TAG, callingUser + " is already the current user.");
                    }
                } else
                {
                    if (DEBUG) Log.d(TAG, callingUser + " is not allowed to become the current user.");
                }

                return shouldSwitch;
            }
        }
    }

    /**
     * Requests to change the state
     * @param state The desired state to change to
     * @return An {@link AntHalDefine} result
     */
    @SuppressLint("NewApi")
    private int doSetAntState(int state)
    {
        synchronized(mChangeAntPowerState_LOCK) {
            int result = AntHalDefine.ANT_HAL_RESULT_FAIL_INVALID_REQUEST;

            switch(state)
            {
                case AntHalDefine.ANT_HAL_STATE_ENABLED:
                {
                    // On platforms with multiple users the enable call is where we try to switch
                    // the current user.
                    if(HAS_MULTI_USER_API)
                    {
                        if(!trySwitchToCallingUser())
                        {
                            // If we cannot become the current user, fail the enable call.
                            result = AntHalDefine.ANT_HAL_RESULT_FAIL_NOT_ENABLED;
                            break;
                        }
                    }

                    result = asyncSetAntPowerState(true);
                    break;
                }
                case AntHalDefine.ANT_HAL_STATE_DISABLED:
                {
                    if(HAS_MULTI_USER_API)
                    {
                        UserHandle user = Binder.getCallingUserHandle();
                        if(!user.equals(mCurrentUser))
                        {
                            // All disables succeed for non current users.
                            result = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                            break;
                        }

                        result = asyncSetAntPowerState(false);

                        if(result == AntHalDefine.ANT_HAL_RESULT_SUCCESS &&
                                user.equals(mCurrentUser))
                        {
                            // To match setting the current user in enable.
                            clearCurrentUser();
                        }
                    } else
                    {
                        result = asyncSetAntPowerState(false);
                    }
                    break;
                }
                case AntHalDefine.ANT_HAL_STATE_RESET:
                {
                    result = doHardReset();
                    break;
                }
            }

            return result;
        }
    }

    /**
     * Queries the lower level code for state
     * @return An {@link AntHalDefine} state
     */
    @SuppressLint("NewApi")
    private int doGetAntState(boolean internalCall)
    {
        if(DEBUG) Log.v(TAG, "doGetAntState start");

        int retState = AntHalDefine.ANT_HAL_STATE_DISABLED;
        switch (HalSettings.TRANSPORT)
        {
            case VFS:
                // If there is no multi-user api we don't have to fake a disabled state.
                if(HAS_MULTI_USER_API &&
                    !internalCall &&
                    !Binder.getCallingUserHandle().equals(mCurrentUser))
                {
                    // State is disabled for users that are not the current user of the interface.
                    break;
                }
                retState = mJAnt.getRadioEnabledStatus(); // ANT state is native state

                // When using an HCI enable command we may need to override the result.
                if(HalSettings.HCI_ENABLE)
                {
                    synchronized (mVSState_LOCK)
                    {
                        if(mWaitingForVSState || mVSInterfaceUp) // If the interface is anything other than idle.
                        {
                            retState = AntHalDefine.ANT_HAL_STATE_ENABLING;
                        }
                    }
                }
                break;
            case HCI:
                synchronized (mVSState_LOCK)
                {
                    if (mVSEnableResult == null)
                    {
                        // Looks like we are enabled, but not sure yet.
                        retState = AntHalDefine.ANT_HAL_STATE_ENABLING;
                    }
                    else if (mWaitingForVSState)
                    {
                        retState = mVSInterfaceUp ?
                            AntHalDefine.ANT_HAL_STATE_DISABLING
                            : AntHalDefine.ANT_HAL_STATE_ENABLING;
                    }
                    else
                    {
                        retState = mVSInterfaceUp ?
                            AntHalDefine.ANT_HAL_STATE_ENABLED
                            : AntHalDefine.ANT_HAL_STATE_DISABLED;
                    }
                }
                break;
        }

        if(DEBUG) Log.i(TAG, "Get ANT State = "+ retState +" / "+ AntHalDefine.getAntHalStateString(retState));

        return retState;
    }

    /**
     * Perform a power change if required.
     * @param state true for enable, false for disable
     * @return {@link AntHalDefine#ANT_HAL_RESULT_SUCCESS} when the request has
     * been posted, false otherwise
     */
    private int asyncSetAntPowerState(final boolean state)
    {
        int result = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;

        synchronized (mChangeAntPowerState_LOCK) {
            // Check we are not already in/transitioning to the state we want
            int currentState = doGetAntState(true);

            if (state) {
                if ((AntHalDefine.ANT_HAL_STATE_ENABLED == currentState)
                        || (AntHalDefine.ANT_HAL_STATE_ENABLING == currentState)) {
                    if (DEBUG) {
                        Log.d(TAG, "Enable request ignored as already enabled/enabling");
                    }

                    return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                } else if (AntHalDefine.ANT_HAL_STATE_DISABLING == currentState) {
                    Log.w(TAG, "Enable request ignored as already disabling");

                    return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                }
            } else {
                if ((AntHalDefine.ANT_HAL_STATE_DISABLED == currentState)
                        || (AntHalDefine.ANT_HAL_STATE_DISABLING == currentState)) {
                    if (DEBUG) {
                        Log.d(TAG, "Disable request ignored as already disabled/disabling");
                    }

                    return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                } else if (AntHalDefine.ANT_HAL_STATE_ENABLING == currentState) {
                    Log.w(TAG, "Disable request ignored as already enabling");

                    return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                }
            }

            if (state) {
                result = enableBackground();
            } else {
                result = disableBackground();
            }
        }

        return result;
    }

    private boolean bringUpVSBlocking()
    {
        synchronized (mVSState_LOCK)
        {
            if(mVSInterfaceUp) return true;
            mVSState.prepareVendorSpecificInterface();

            mWaitingForVSState = true;
            while(mWaitingForVSState || !mVSInterfaceUp)
            {
                try
                {
                    mVSState_LOCK.wait();
                } catch (InterruptedException e)
                {
                    break;
                }
            }
            return mVSInterfaceUp;
        }
    }

    private void bringDownVSBlocking()
    {
        synchronized(mVSState_LOCK)
        {
            if(!mVSInterfaceUp) return;
            mVSState.releaseVendorSpecificInterface();
            mWaitingForVSState = true;

            while(mWaitingForVSState || mVSInterfaceUp)
            {
                try
                {
                    mVSState_LOCK.wait();
                } catch (InterruptedException e)
                {
                    break;
                }
            }

            if (mVSInterfaceUp)
            {
                Log.w(TAG, "Could not bring down VS interface.");
            }
        }
    }

    private boolean sendHCIEnableCommandBlocking()
    {
        synchronized(mVSState_LOCK)
        {
            mVSEnableResult = null;
            mVSState.sendVendorSpecificCommand(
                    HalSettings.HCI_ENABLE_OPCODE,
                    HalSettings.HCI_ENABLE_COMMAND,
                    mVSEnableCallback);
            while(mVSEnableResult == null)
            {
                try
                {
                    mVSState_LOCK.wait();
                } catch (InterruptedException e)
                {
                    return false;
                }
            }

            return mVSEnableResult;
        }
    }

    /**
     * Calls enable on the lower level code
     *
     * This code follows the following steps as needed
     * 1. Bring up the HCI VS interface. This is needed for chips that use HCI as their transport
     *    as well as chips that require an enable-ant command before use.
     * 2. Send an HCI enable command if needed.
     * 3. Enable the VFS layer if the chip uses the VFS transport.
     * 4. Clean up the HCI interface if the chip uses VFS as its transport.
     *
     * @return {@link AntHalDefine#ANT_HAL_RESULT_SUCCESS} when successful, or
     * {@link AntHalDefine#ANT_HAL_RESULT_FAIL_UNKNOWN} if unsuccessful
     */
    private int enableBlocking()
    {
        synchronized(sAntHalServiceDestroy_LOCK)
        {
            // 1. Bring up the HCI VS interface. This is needed for chips that use HCI as their
            //    transport as well as chips that require an enable-ant command before use.
            if (HalSettings.HCI_ENABLE || HalSettings.TRANSPORT == Transport.HCI)
            {
                synchronized(mVSState_LOCK)
                {
                    setState(AntHalDefine.ANT_HAL_STATE_ENABLING);
                    // This is needed so that there is no hole where we have brought up the HCI interface but not sent the enable command yet.
                    if(HalSettings.HCI_ENABLE) mVSEnableResult = null;

                    if(!bringUpVSBlocking())
                    {
                        setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
                        mVSEnableResult = false;
                        return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                    }

                    // 2. Send an HCI enable command if needed.
                    if(HalSettings.HCI_ENABLE)
                    {
                        if(!sendHCIEnableCommandBlocking())
                        {
                            bringDownVSBlocking();
                            setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
                            return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                        }
                    }
                }
            }

            // 3. Enable the VFS layer if the chip uses the VFS transport.
            if(HalSettings.TRANSPORT == Transport.VFS)
            {
                if (mJAnt != null && JAntStatus.SUCCESS != mJAnt.enable())
                {
                    if(DEBUG) Log.v(TAG, "Enable call: Failure");
                    if(HalSettings.HCI_ENABLE)
                    {
                        bringDownVSBlocking();
                        // since the lower layer callback would have been filtered.
                        setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
                    }
                    return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                }
                if(DEBUG) Log.v(TAG, "Enable call: Success");

                // 4. Clean up the HCI interface if the chip uses VFS as its transport.
                if(HalSettings.HCI_ENABLE) bringDownVSBlocking();
            }

            if(HalSettings.HCI_ENABLE || HalSettings.TRANSPORT == Transport.HCI)
            {
                setState(AntHalDefine.ANT_HAL_STATE_ENABLED);
            }
            return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
        }
    }

    /**
     * Calls disable on the lower level code
     * @return {@link AntHalDefine#ANT_HAL_RESULT_SUCCESS} when successful, or
     * {@link AntHalDefine#ANT_HAL_RESULT_FAIL_UNKNOWN} if unsuccessful
     */
    private int disableBlocking()
    {
        int ret = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
        synchronized(sAntHalServiceDestroy_LOCK)
        {
            switch (HalSettings.TRANSPORT)
            {
                case VFS:
                    if (mJAnt != null)
                    {
                        if(JAntStatus.SUCCESS == mJAnt.disable())
                        {
                            if(DEBUG) Log.v(TAG, "Disable callback end: Success");
                            ret = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                        }
                        else
                        {
                            if (DEBUG) Log.v(TAG, "Disable callback end: Failure");
                        }
                    }

                    if (HalSettings.HCI_ENABLE) bringDownVSBlocking();
                    break;
                case HCI:
                    synchronized (mVSState_LOCK)
                    {
                        setState(AntHalDefine.ANT_HAL_STATE_DISABLING);
                        bringDownVSBlocking();
                        setState(AntHalDefine.ANT_HAL_STATE_DISABLED);
                    }
                    break;
            }
        }
        return ret;
    }

    /**
     * Post an enable runnable.
     */
    private int enableBackground()
    {
        if(DEBUG) Log.v(TAG, "Enable start");

        if (DEBUG) Log.d(TAG, "Enable: enabling the radio");

        // TODO use handler to post runnable rather than creating a new thread.
        new Thread(new Runnable() {
            public void run() {
                enableBlocking();
            }
        }).start();

        if(DEBUG) Log.v(TAG, "Enable call end: Successfully called");
        return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
    }

    /**
     * Post a disable runnable.
     */
    private int disableBackground()
    {
        if(DEBUG) Log.v(TAG, "Disable start");

        // TODO use handler to post runnable rather than creating a new thread.
        new Thread(new Runnable() {
            public void run() {
                disableBlocking();
            }
        }).start();

        if(DEBUG) Log.v(TAG, "Disable call end: Success");
        return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
    }

    private int doANTTxMessage(byte[] message)
    {
        if(DEBUG) Log.v(TAG, "ANT Tx Message start");

        if(message == null)
        {
            Log.e(TAG, "ANTTxMessage invalid message: message is null");
            return AntHalDefine.ANT_HAL_RESULT_FAIL_INVALID_REQUEST;
        }

        int result = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;

        switch (HalSettings.TRANSPORT)
        {
            case VFS:
                result = TxMessageVFS(message);
                break;
            case HCI:
                result = TxMessageHCI(message);
                break;
        }

        if (DEBUG) Log.v(TAG, "ANTTxMessage: Result = "+ result);

        if(DEBUG) Log.v(TAG, "ANT Tx Message end");

        return result;
    }

    private int TxMessageVFS(byte[] message)
    {
        JAntStatus status = mJAnt.ANTTxMessage(message);

        int result = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
        if(JAntStatus.SUCCESS == status)
        {
            if (DEBUG) Log.d (TAG, "mJAnt.ANTTxMessage returned status: " + status.toString());

            result = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
        }
        else
        {
            if (DEBUG) Log.w( TAG, "mJAnt.ANTTxMessage returned status: " + status.toString() );

            if(JAntStatus.FAILED_BT_NOT_INITIALIZED == status)
            {
                result = AntHalDefine.ANT_HAL_RESULT_FAIL_NOT_ENABLED;
            }
            else if(JAntStatus.NOT_SUPPORTED == status)
            {
                result = AntHalDefine.ANT_HAL_RESULT_FAIL_NOT_SUPPORTED;
            }
            else if(JAntStatus.INVALID_PARM == status)
            {
                result = AntHalDefine.ANT_HAL_RESULT_FAIL_INVALID_REQUEST;
            }
        }

        return result;
    }

    private int TxMessageHCI(byte[] message)
    {
        int result = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
        if (mFramer != null)
        {
            short opcode = mFramer.getCommandOpcode(message);
            byte[] hcimessage = mFramer.packageCommand(message);
            long attemptsLeft = HalSettings.HCI_COMMAND_RETRIES;

            while (attemptsLeft > 0)
            {
                synchronized(mVSState_LOCK)
                {
                    mCommandCompleteResult = null; // reset
                    mVSState.sendVendorSpecificCommand(opcode, hcimessage, mCommandCompleteCallback);
                    while (mCommandCompleteResult == null)
                    {
                        try
                        {
                            mVSState_LOCK.wait();
                        }
                        catch (InterruptedException e)
                        {
                            Log.e(TAG, "HCI command transmit interrupted.");
                            return AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
                        }
                    }

                    if (mCommandCompleteResult)
                    {
                        result = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                        attemptsLeft = 0;
                    }
                    else
                    {
                        SystemClock.sleep(HalSettings.HCI_COMMAND_RETRY_SLEEP_MS);
                        attemptsLeft--;
                    }
                }
            }
        }
        return result;
    }

    @SuppressLint("NewApi")
    private int doRegisterAntHalCallback(IAntHalCallback callback)
    {
        synchronized (mUserCallback_LOCK)
        {
            if(HAS_MULTI_USER_API)
            {
                UserHandle user = Binder.getCallingUserHandle();
                if(DEBUG) Log.i(TAG, "Registering callback: "+ callback + " for user: " + user);
                mCallbackMap.put(user, callback);
                if(user.equals(mCurrentUser))
                {
                    mCallback = callback;
                }
            } else
            {
                if(DEBUG) Log.i(TAG, "Registering callback: "+ callback);
                mCallback = callback;
            }
        }

        return AntHalDefine.ANT_HAL_RESULT_SUCCESS;
    }

    @SuppressLint("NewApi")
    private int doUnregisterAntHalCallback(IAntHalCallback callback)
    {
        int result = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;

        if(HAS_MULTI_USER_API)
        {
            UserHandle user = Binder.getCallingUserHandle();
            if(DEBUG) Log.i(TAG, "Unregistering callback: "+ callback.toString() + " for user: " +
                    user);
            synchronized(mUserCallback_LOCK)
            {
                IAntHalCallback currentCallback = mCallbackMap.get(user);
                if(callback != null && currentCallback != null &&
                        callback.asBinder().equals(currentCallback.asBinder()))
                {
                    mCallbackMap.remove(user);
                    result = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                }
                // Regardless of state, if the current user is leaving we need to allow others to
                // take over.
                if(user.equals(mCurrentUser))
                {
                    clearCurrentUser();
                }
            }
        } else
        {
            if(DEBUG) Log.i(TAG, "Unregistering callback: "+ callback.toString());
            synchronized(mUserCallback_LOCK)
            {
                if(callback != null && mCallback != null &&
                        callback.asBinder().equals(mCallback.asBinder()))
                {
                    mCallback = null;
                    result = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                }
            }
        }
        return result;
    }

    private int doGetServiceLibraryVersionCode()
    {
        return Version.ANT_HAL_LIBRARY_VERSION_CODE;
    }

    private String doGetServiceLibraryVersionName()
    {
        return Version.ANT_HAL_LIBRARY_VERSION_NAME;
    }

    private int doHardReset()
    {
        int ret = AntHalDefine.ANT_HAL_RESULT_FAIL_UNKNOWN;
        switch (HalSettings.TRANSPORT)
        {
            case VFS:
                synchronized(sAntHalServiceDestroy_LOCK)
                {
                    if (mJAnt != null)
                    {
                        if(JAntStatus.SUCCESS == mJAnt.hardReset())
                        {
                            if(DEBUG) Log.v(TAG, "Hard Reset end: Success");
                            ret = AntHalDefine.ANT_HAL_RESULT_SUCCESS;
                        }
                        else
                        {
                            if (DEBUG) Log.v(TAG, "Hard Reset end: Failure");
                        }
                    }
                }
                break;
            case HCI:
                ret = AntHalDefine.ANT_HAL_RESULT_FAIL_NOT_SUPPORTED;
                break;
        }
        return ret;
    }

    private void receiveMessage(byte[] message)
    {
        // Use caching instead of synchronization so that we do not have to hold a lock during a callback.
        // It is safe to not hold the lock because we are not doing any write accesses.
        IAntHalCallback callback = mCallback;
        if(null != callback)
        {
            try
            {
                callback.antHalRxMessage(message);
            }
            catch (RemoteException e)
            {
                // Don't do anything as this is a problem in the application
                if(DEBUG) Log.e(TAG, "ANT HAL Rx Message callback failure in application", e);
            }
        }
        else
        {
            Log.w(TAG, "ANT message received after service has been destroyed");
        }
    }

    // ----------------------------------------------------------------------------------------- IAntHal

    private final IAntHal.Stub mHalBinder = new IAntHal.Stub()
    {
        public int setAntState(int state)
        {
            return doSetAntState(state);
        }

        public int getAntState()
        {
            return doGetAntState(false);
        }

        public int ANTTxMessage(byte[] message)
        {
            return doANTTxMessage(message);
        }

        // Call these in onServiceConnected and when unbinding
        public int registerAntHalCallback(IAntHalCallback callback)
        {
            return doRegisterAntHalCallback(callback);
        }

        public int unregisterAntHalCallback(IAntHalCallback callback)
        {
            return doUnregisterAntHalCallback(callback);
        }

        public int getServiceLibraryVersionCode()
        {
            return doGetServiceLibraryVersionCode();
        }

        public String getServiceLibraryVersionName()
        {
            return doGetServiceLibraryVersionName();
        }
    }; // new IAntHal.Stub()

    // -------------------------------------------------------------------------------------- Service

    @Override
    public void onCreate()
    {
        if (DEBUG) Log.d(TAG, "onCreate() entered");

        super.onCreate();

        mVSState = VendorSpecificStateMachine.make(this, mVSStateCallbacks);

        switch(HalSettings.TRANSPORT)
        {
            case VFS:
                if(null != mJAnt)
                {
                    // This somehow happens when quickly starting/stopping an application.
                    if (DEBUG) Log.e(TAG, "LAST JAnt HCI Interface object not destroyed");
                }
                // create a single new JAnt HCI Interface instance
                mJAnt = new JAntJava();
                JAntStatus createResult = mJAnt.create(mJAntCallback);

                if (createResult == JAntStatus.SUCCESS)
                {
                    mInitialized = true;

                    if (DEBUG) Log.d(TAG, "JAntJava create success");
                }
                else
                {
                    mInitialized = false;

                    if (DEBUG) Log.e(TAG, "JAntJava create failed: " + createResult);
                }
                break;
            case HCI:
                try
                {
                    Class<?> clazz = Class.forName(HalSettings.HCI_FORMATCLASS);
                    // Get's the default no-arg constructor.
                    Constructor<?> constructor = clazz.getConstructor();
                    mFramer = (IAntHciFramer) constructor.newInstance();
                } catch (Exception e) {
                    // Don't want to crash no matter what. We are running in the system server !!
                    Log.e(TAG, "Could not load HCI framer class", e);
                }

                if (mFramer != null)
                {
                    mInitialized = true;
                } else
                {
                    mInitialized = false;
                }
        }

        IntentFilter filter = new IntentFilter();

        if(HAS_MULTI_USER_API)
        {
            if(!ALLOW_BACKGROUND_USAGE)
            {
                // If we don't allow background users, we need to monitor user switches to clear the
                // active user.
                filter.addAction(Intent.ACTION_USER_SWITCHED);
            }
        }
        registerReceiver(mReceiver, filter);

    }

    @Override
    public void onDestroy()
    {
        if (DEBUG) Log.d(TAG, "onDestroy() entered");

        try
        {
            synchronized(sAntHalServiceDestroy_LOCK)
            {
                int result = disableBlocking();
                if (DEBUG)
                {
                    Log.d(TAG, "onDestroy: disable result is: "
                        + AntHalDefine.getAntHalResultString(result));
                }

                if (null != mJAnt)
                {
                    mJAnt.destroy();
                    mJAnt = null;
                }

                if (null != mFramer)
                {
                    mFramer = null;
                }
            }

            synchronized(mUserCallback_LOCK)
            {
                mCallbackMap.clear();
                mCallback = null;
            }
        }
        finally
        {
            super.onDestroy();
        }
        unregisterReceiver(mReceiver);
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        if (DEBUG) Log.d(TAG, "onBind() entered");

        IBinder binder = null;

        if (mInitialized)
        {
            if(intent.getAction().equals(IAntHal.class.getName()))
            {
                if (DEBUG) Log.i(TAG, "Bind: IAntHal");

                binder = mHalBinder;
            }
        }

        // As someone has started using us, make sure we run "forever" like we
        // are a system service.
        startService(this);

        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        if (DEBUG) Log.d(TAG, "onUnbind() entered");

        synchronized(mUserCallback_LOCK)
        {
            mCallback = null;
            mCallbackMap.clear();
        }

        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (DEBUG) Log.d(TAG, "onStartCommand() entered");

        if (!mInitialized)
        {
            if (DEBUG) Log.e(TAG, "not initialized, stopping self");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    // ----------------------------------------------------------------------------------------- JAnt Callbacks

    private JAntJava.ICallback mJAntCallback = new JAntJava.ICallback()
    {
        public synchronized void ANTRxMessage( byte[] message)
        {
            receiveMessage(message);
        }

        public synchronized void ANTStateChange(int NewState)
        {
            if (DEBUG) Log.i(TAG, "ANTStateChange callback to " + NewState);

            synchronized(mVSState_LOCK)
            {
                // Filter out state callback while hci is not idle, as state does not directly
                // correspond in that case.
                if(mWaitingForVSState || mVSInterfaceUp) return;
            }
            setState(NewState);
        }
    };
}
