package com.dsi.ant.server;

import android.bluetooth.BluetoothVS;
import android.bluetooth.BluetoothVS.BluetoothVSCallbacks;
import android.content.Context;
import android.os.Message;
import android.util.Log;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * This state machine handles the state of our Bluetooth Vendor Specific Interface
 * States:
 *      {@link IdleState} : Interface is down
 *      {@link WaitingForInterface} : Waiting for the interface to initialize
 *      {@link ReadyState} : Interface is ready to send Vendor Specific Commands
 *      {@link WaitingForCommandCompleteState} : Interface is waiting for a Command Complete
 *
 * Expected Behaviour of Vendor Specific State Machine
 *
 * AntService             StateMachine          BluetoothService
 *     |                       |                       |
 *                            IDLE                     |
 *     |                       |                       |
 *    ...                     ...                     ...
 *     |                       |                       |
 *     |---prepareInterface--->|---requestInterface--->|
 *     |              WAITING_FOR_INTERFACE            |
 *     |                       |                       |
 *     |                       |<---onInterfaceReady---|
 *     |<--onInterfaceReady--READY                     |
 *     |                       |                       |
 *    ...                     ...                     ...
 *     |                       |                       |
 *     |-----sendVSCommand---->|-----sendVSCommand---->|
 *     |          WAITING_FOR_COMMAND_COMPLETE         |
 *     |                       |                       |
 *     |                       |<----commandComplete---|
 *     |<--commandComplete---READY                     |
 *     |                       |                       |
 *    ...                     ...                     ...
 *     |                       |                       |
 *     |------release--------->|                       |
 *     |<---onInterfaceDown---IDLE                     |
 *     |                       |                       |
 *    ...                     ...                     ...
 *     |                       |<---interfaceDown------|
 *     |<---onInterfaceDown---IDLE                     |
 *     |                       |                       |
 */
public class VendorSpecificStateMachine extends StateMachine
{
//-------------------------------------------- Defines --------------------------------------------
    private static final String TAG = VendorSpecificStateMachine.class.getSimpleName();
    private static final boolean DEBUG = false;

//----------------------------------------- Message Whats -----------------------------------------
    // Commands from service
    private static final int PREPARE_INTERFACE = 1;
    private static final int RELEASE_INTERFACE = 2;
    private static final int SEND_COMMAND = 3;
    private static final int SET_EVENT_FILTER = 4;

    // Events from bt-interface
    private static final int INTERFACE_READY = 100;
    private static final int INTERFACE_DOWN = 101;
    private static final int COMMAND_COMPLETE = 102;
    private static final int EVENT_RECEIVED = 103;

    // Internal events
    private static final int PREPARE_INTERFACE_TIMEOUT = 200;
    private static final int COMMAND_COMPLETE_TIMEOUT = 201;

//--------------------------------------------- States --------------------------------------------
    private final IdleState mIdleState = new IdleState();
    private final WaitingForInterfaceState mWaitingForInterfaceState = new WaitingForInterfaceState();
    private final ReadyState mReadyState = new ReadyState();
    private final WaitingForCommandCompleteState mWaitingForCommandCompleteState = new WaitingForCommandCompleteState();

//--------------------------------------- Private Variables ---------------------------------------
    private final Context mContext;

//---------------------------------- Callbacks up to AntService -----------------------------------
    private final BTVSCallbacks mStateCallbacks;

//--------------------------------- Interface to BluetoothService ---------------------------------
    private BluetoothVS mBluetoothVS;
    private BTCallbacks mBTCallbacks;
    private static final AtomicInteger sCallbackInstanceCount = new AtomicInteger();
    private class BTCallbacks implements BluetoothVSCallbacks
    {
        public final int mInstanceNum = sCallbackInstanceCount.getAndIncrement();

        @Override
        public void onInterfaceReady()
        {
            Message msg = Message.obtain(getHandler(), INTERFACE_READY);
            msg.arg2 = mInstanceNum;
            sendMessage(msg);
        }

        @Override
        public void onCommandCompleteReceived(short opcode, byte[] parameters)
        {
            Message msg = Message.obtain(getHandler(), COMMAND_COMPLETE);
            msg.arg2 = mInstanceNum;
            msg.obj = parameters;
            msg.arg1 = opcode;
            sendMessage(msg);
        }

        @Override
        public void onInterfaceDown()
        {
            Message msg = Message.obtain(getHandler(), INTERFACE_DOWN);
            msg.arg2 = mInstanceNum;
            sendMessage(msg);
        }

        @Override
        public void onEventReceived(byte[] params)
        {
            Message msg = Message.obtain(getHandler(), EVENT_RECEIVED);
            msg.arg2 = mInstanceNum;
            msg.obj = params;
            sendMessage(msg);
        }
    };

//-------------------------------- Functions called by AntService ---------------------------------
    /**
     * Create an instance of the state machine.
     * @param context
     * @param callback
     * @return The one and only instance of the Vendor Specific State Machine
     */
    public static VendorSpecificStateMachine make(Context context, BTVSCallbacks callback)
    {
        VendorSpecificStateMachine machine;
        if (DEBUG) Log.d(TAG, "make");
        machine = new VendorSpecificStateMachine(context, callback);
        machine.start();
        return machine;
    }

    /**
     * Prepares the vendor specific interface if not already prepared
     */
    public void prepareVendorSpecificInterface()
    {
        sendMessage(PREPARE_INTERFACE);
    }

    /**
     * Sends the vendor specific command if interface is ready. If vendor specific command was not
     * send successfully, callback is notified
     * @param opcode
     * @param parameters
     * @param callback
     */
    public void sendVendorSpecificCommand(short opcode, byte[] parameters,
        CommandCompleteCallback callback)
    {
        Message msg = Message.obtain(getHandler(), SEND_COMMAND);
        msg.arg1 = opcode;
        CommandContext ctx = new CommandContext();
        ctx.callback = callback;
        ctx.params = parameters;
        msg.obj = ctx;
        sendMessage(msg);
    }

    /**
     * Release the vendor specific interface
     */
    public void releaseVendorSpecificInterface()
    {
        sendMessage(RELEASE_INTERFACE);
    }

    /**
     *
     */
    public void setVSEventFilter(byte [] mask, byte [] value)
    {
        FilterSpec spec = new FilterSpec();
        spec.mask = mask;
        spec.value = value;
        Message msg = Message.obtain(getHandler(), SET_EVENT_FILTER);
        msg.obj = spec;
        sendMessage(msg);
    }
//--------------------------------------- Internal Methods ----------------------------------------
    private VendorSpecificStateMachine(Context context, BTVSCallbacks callback)
    {
        super("VendorSpecificState");
        addState(mIdleState);
        addState(mWaitingForInterfaceState);
        addState(mReadyState);
        addState(mWaitingForCommandCompleteState);
        setInitialState(mIdleState);

        mContext = context;
        mStateCallbacks = callback;
    }
//-------------------------------------- Public Interfaces ----------------------------------------
    /**
     * Calls back to inform the state of the Vendor Specific Interface
     */
    public interface BTVSCallbacks
    {
        public void onInterfaceReady();
        public void onInterfaceDown();
        public void onEventReceived(byte[] params);
    }

    /**
     * Calls back to inform whether the command complete was successful or not
     */
    public interface CommandCompleteCallback
    {
        public void onCommandComplete(byte [] parameters);
    }

//------------------------------------------- States ----------------------------------------------
    /**
     * Interface is not ready for use
     */
    private class IdleState extends State
    {
        boolean mInitial = true;
        @Override
        public void enter()
        {
            if (DEBUG) Log.d(TAG, "enter Idle");

            mBluetoothVS = null;
            if(mInitial)
               mInitial = false;
            else
               mStateCallbacks.onInterfaceDown();
        }

        @Override
        public boolean processMessage(Message msg)
        {
            switch(msg.what)
            {
                case PREPARE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "PREPARE_INTERFACE");
                    transitionTo(mWaitingForInterfaceState);
                    mBTCallbacks = new BTCallbacks();
                    mBluetoothVS = new BluetoothVS(mContext, mBTCallbacks);
                    break;
                case RELEASE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "RELEASE_INTERFACE");
                    // there is no interface to release: IGNORE
                    break;
                case SEND_COMMAND:
                    if (DEBUG) Log.v(TAG, "SEND_COMMAND");
                    ((CommandContext)msg.obj).callback.onCommandComplete(null);
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Unexpected Message" + msg.what + " in state " + getName());
                    return false;
            }
            return true;
        }
    }

    /**
     * Waiting for interface to initialize. If in this state longer than 5s, assume initialization
     * failed and transition to idle state
     */
    private class WaitingForInterfaceState extends State
    {
        private static final int WAIT_FOR_INTERFACE_TIMEOUT_MS = 5000;

        @Override
        public void enter()
        {
            if (DEBUG) Log.d(TAG, "enter Waiting For Interface");
            sendMessageDelayed(PREPARE_INTERFACE_TIMEOUT, WAIT_FOR_INTERFACE_TIMEOUT_MS);
        }

        @Override
        public boolean processMessage(Message msg)
        {
            switch(msg.what)
            {
                case INTERFACE_READY:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "INTERFACE_READY");
                    transitionTo(mReadyState);
                    break;
                case INTERFACE_DOWN:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "INTERFACE_DOWN");
                    transitionTo(mIdleState);
                    break;
                case PREPARE_INTERFACE_TIMEOUT:
                   if (DEBUG) Log.v(TAG, "PREPARE_INTERFACE_TIMEOUT");
                    transitionTo(mIdleState);
                    break;
                case PREPARE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "PREPARE_INTERFACE");
                    // already preparing interface: IGNORE
                    break;
                case RELEASE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "RELEASE_INTERFACE");
                    transitionTo(mIdleState);
                    mBluetoothVS.release();
                    break;
                case SEND_COMMAND:
                    if (DEBUG) Log.v(TAG, "SEND_COMMAND");
                    ((CommandContext)msg.obj).callback.onCommandComplete(null);
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Unexpected Message" + msg.what + " in state " + getName());
                    return false;
            }
            return true;
        }

        @Override
        public void exit()
        {
            removeMessages(PREPARE_INTERFACE_TIMEOUT);
        }
    }

    /**
     * Interface is ready for use
     */
    private class ReadyState extends State
    {
        private boolean mTransitionFromCommandCompleteWait = false;

        public void setTransitioningFromCommandCompleteWait()
        {
            mTransitionFromCommandCompleteWait = true;
        }

        @Override
        public void enter()
        {
            if (DEBUG) Log.d(TAG, "enter Ready");
            if(!mTransitionFromCommandCompleteWait)
            {
                mStateCallbacks.onInterfaceReady();
            }
            else
            {
                mTransitionFromCommandCompleteWait = false;
            }
        }

        @Override
        public boolean processMessage(Message msg)
        {
            switch(msg.what)
            {
                case INTERFACE_DOWN:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "INTERFACE_DOWN");
                    transitionTo(mIdleState);
                    break;
                case EVENT_RECEIVED:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "EVENT_RECEIVED");
                    mStateCallbacks.onEventReceived((byte[])msg.obj);
                    break;
                case SEND_COMMAND:
                    if (DEBUG) Log.v(TAG, "SEND_COMMAND");
                    transitionTo(mWaitingForCommandCompleteState);
                    CommandContext ctx = (CommandContext)msg.obj;
                    mWaitingForCommandCompleteState.setCommand((short)msg.arg1, ctx.callback);
                    mBluetoothVS.sendVendorSpecificCommand((short)msg.arg1, ctx.params);
                    break;
                case PREPARE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "PREPARE_INTERFACE");
                    // interface already prepared: IGNORE
                    break;
                case RELEASE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "RELEASE_INTERFACE");
                    transitionTo(mIdleState);
                    mBluetoothVS.release();
                    break;
                case SET_EVENT_FILTER:
                    if (DEBUG) Log.v(TAG, "SET_EVENT_FILTER");
                    FilterSpec spec = (FilterSpec)msg.obj;
                    mBluetoothVS.setVendorSpecificEventFilter(spec.mask, spec.value);
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Unexpected Message" + msg.what + " in state " + getName());
                    return false;
            }
            return true;
        }

    }

    /**
     * Waiting for a command complete from BT Service after a send Vendor Specific Command
     */
    private class WaitingForCommandCompleteState extends State
    {
        private static final int WAIT_FOR_COMMAND_COMPLETE_TIMEOUT_MS = 5000;
        private short mOpcode;
        private CommandCompleteCallback mCallback;

        public void setCommand(short opcode, CommandCompleteCallback cb)
        {
            mOpcode = opcode;
            mCallback = cb;
        }

        @Override
        public void enter()
        {
            if (DEBUG) Log.d(TAG, "enter Waiting For Command Complete");
            sendMessageDelayed(COMMAND_COMPLETE_TIMEOUT, WAIT_FOR_COMMAND_COMPLETE_TIMEOUT_MS);
        }

        @Override
        public boolean processMessage(Message msg)
        {
            switch(msg.what)
            {
                case COMMAND_COMPLETE:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if((short)msg.arg1 != mOpcode)
                    {
                        if (DEBUG) Log.w(TAG, "Ignoring COMMAND_COMPLETE for wrong opcode.");
                        break;
                    }
                    if (DEBUG) Log.v(TAG, "COMMAND_COMPLETE");
                    mReadyState.setTransitioningFromCommandCompleteWait();
                    transitionTo(mReadyState);
                    mCallback.onCommandComplete((byte []) msg.obj);
                    break;
                case COMMAND_COMPLETE_TIMEOUT:
                    if (DEBUG) Log.v(TAG, "COMMAND_COMPLETE_TIMEOUT");
                    mReadyState.setTransitioningFromCommandCompleteWait();
                    transitionTo(mReadyState);
                    mCallback.onCommandComplete(null);
                    break;
                case INTERFACE_DOWN:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "INTERFACE_DOWN");
                    transitionTo(mIdleState);
                    mCallback.onCommandComplete(null);
                    break;
                case EVENT_RECEIVED:
                    if(mBTCallbacks.mInstanceNum != msg.arg2) break;
                    if (DEBUG) Log.v(TAG, "EVENT_RECEIVED");
                    mStateCallbacks.onEventReceived((byte[])msg.obj);
                    break;
                case PREPARE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "PREPARE_INTERFACE");
                    break;
                case RELEASE_INTERFACE:
                    if (DEBUG) Log.v(TAG, "RELEASE_INTERFACE, command in progress so release is deferred.");
                    deferMessage(msg);
                    break;
                case SEND_COMMAND:
                    if (DEBUG) Log.w(TAG, "SEND_COMMAND, command already in progress");
                    ((CommandContext)msg.obj).callback.onCommandComplete(null);
                    break;
                case SET_EVENT_FILTER:
                    if (DEBUG) Log.v(TAG, "SET_EVENT_FILTER");
                    FilterSpec spec = (FilterSpec)msg.obj;
                    mBluetoothVS.setVendorSpecificEventFilter(spec.mask, spec.value);
                    break;
                default:
                    if (DEBUG) Log.d(TAG, "Unexpected Message" + msg.what + " in state " + getName());
                    return false;
            }
            return true;
        }

        @Override
        public void exit()
        {
            removeMessages(COMMAND_COMPLETE_TIMEOUT);
        }
    }

    //---------------------------------- Internal classes -----------------------------------------
    private static final class CommandContext
    {
        private CommandCompleteCallback callback;
        private byte[] params;
    }

    private static final class FilterSpec
    {
        private byte[] mask;
        private byte[] value;
    }
}
