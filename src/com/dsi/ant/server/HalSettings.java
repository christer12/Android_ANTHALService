package com.dsi.ant.server;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

import android.util.Log;

public class HalSettings
{
    public enum Transport
    {
        VFS,
        HCI
    }

    public static final String PROPS_FILE_LOCATION = "/etc/ant-wireless.conf";
    public static final String TAG = HalSettings.class.getSimpleName();

    public static final short HCI_ENABLE_OPCODE;
    public static final byte[] HCI_ENABLE_COMMAND;
    public static final boolean HCI_ENABLE;

    public static final Transport TRANSPORT;

    public static final String HCI_FORMATCLASS;
    public static final short HCI_OPCODE;
    public static final byte[] HCI_FILTER_MASK;
    public static final byte[] HCI_FILTER_VALUE;
    public static final int HCI_COMMAND_RETRIES;
    public static final int HCI_COMMAND_RETRY_SLEEP_MS;

    private static short parseShort(String src, short defaultValue)
    {
        if (src == null)
        {
            return defaultValue;
        }

        try
        {
            int val = Integer.decode(src);
            // Allow signed and unsigned input.
            if (val < Short.MIN_VALUE || val > 0xFFFF)
            {
                throw new NumberFormatException(src + " is out of range for shorts.");
            }
            return (short) val;
        }
        catch (NumberFormatException e)
        {
            Log.w(TAG, "Could not parse " + src + ": " + e.getLocalizedMessage());
            return defaultValue;
        }
    }

    private static int parseInt(String src, int defaultValue)
    {
        if (src == null)
        {
            return defaultValue;
        }

        try
        {
            return Integer.decode(src);
        }
        catch (NumberFormatException e)
        {
            Log.w(TAG, "Could not parse " + src + ": " + e.getLocalizedMessage());
            return defaultValue;
        }
    }

    /**
     * Parses a byte array form the configuration file. Parses as a series
     * of hex bytes separated by ' ' or ',' or '[' or ']'.
     * This allows for most common formats like 00 01 or 00,01 or [00][01]
     * @param src The string to parse as a byte array.
     * @return The byte array presentation, or null if it could not be parsed.
     */
    private static byte[] parseByteArray(String src)
    {
        if (src == null)
        {
            return null;
        }

        String[] vals = src.split("[ ,\\[\\]\t]+");
        ArrayList<Byte> result = new ArrayList<Byte>(vals.length);

        try
        {
            for (String val : vals)
            {
                if (val.length() == 0) continue;

                int parsed = Integer.decode(val);
                // Allow for signed and unsigned input.
                if (parsed < Byte.MIN_VALUE || parsed > 0xFF)
                {
                    throw new NumberFormatException(src + " is out of range for bytes.");
                }
                result.add((byte) parsed);
            }
        }
        catch (NumberFormatException e)
        {
            Log.e(TAG, "Unable to parse " + src + " as a byte array: " + e.getLocalizedMessage());
            return null;
        }

        if(!result.isEmpty())
        {
            byte[] realResult = new byte[result.size()];
            for (int i = 0; i < result.size(); i++)
            {
                realResult[i] = result.get(i);
            }
            return realResult;
        }

        return null;
    }

    private static <E extends Enum<E>> E parseEnum(String src, E defaultValue, Class<E> clazz)
    {
        E value = defaultValue;
        if(src != null)
        {
            try
            {
                value = Enum.valueOf(clazz, src.trim());
            }
            catch (IllegalArgumentException e)
            {
                Log.e(TAG, src + " is not a valid value.");
            }
        }

        return value;
    }

    static
    {
        Properties props = new Properties();
        try
        {
            props.load(new FileInputStream(PROPS_FILE_LOCATION));
        }
        catch (IOException e)
        {
            Log.w(TAG, "Unable to read HAL configuration, using defaults: " + e.getLocalizedMessage());
        }

        String prop = props.getProperty("hci.enable.opcode");
        HCI_ENABLE_OPCODE = parseShort(prop, (short) 0);

        prop = props.getProperty("hci.enable.command");
        if (prop != null)
        {
            HCI_ENABLE_COMMAND = parseByteArray(prop);
        }
        else
        {
            HCI_ENABLE_COMMAND = null;
        }

        // Only use if a valid command and response were defined.
        HCI_ENABLE = (HCI_ENABLE_COMMAND != null);

        prop = props.getProperty("transport");
        TRANSPORT = parseEnum(prop, Transport.VFS, Transport.class);

        if (TRANSPORT == Transport.HCI)
        {
            prop = props.getProperty("hci.formatclass");
            if (prop == null)
            {
                Log.w(TAG, "No HCI formatclass specified." +
                        " Expect a crash if ANT is used.");
            }
            HCI_FORMATCLASS = prop;

            prop = props.getProperty("hci.opcode");
            HCI_OPCODE = parseShort(prop, (short) 0);

            prop = props.getProperty("hci.filter.mask");
            HCI_FILTER_MASK = parseByteArray(prop);

            prop = props.getProperty("hci.filter.value");
            HCI_FILTER_VALUE = parseByteArray(prop);

            prop = props.getProperty("hci.command.retries");
            HCI_COMMAND_RETRIES = parseInt(prop, 100);

            prop = props.getProperty("hci.command.retry_sleep_ms");
            HCI_COMMAND_RETRY_SLEEP_MS = parseInt(prop, 100);
        }
        else
        {
            HCI_FORMATCLASS = null;
            HCI_OPCODE = 0;
            HCI_FILTER_MASK = null;
            HCI_FILTER_VALUE = null;
            HCI_COMMAND_RETRIES = 0;
            HCI_COMMAND_RETRY_SLEEP_MS = 0;
        }
    }
}
