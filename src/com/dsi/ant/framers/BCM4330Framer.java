package com.dsi.ant.framers;

import java.lang.System;

public class BCM4330Framer implements IAntHciFramer
{
    private static final byte COMMAND_HEADER = (byte) 0xFF;
    private static final short OPCODE = (short) 0xFCEC;
    private static final byte EVENT_CODE = (byte) 0x2D;
    private static final int COMMAND_HEADER_SIZE = 1;
    private static final int EVENT_HEADER_SIZE = 2;

    public short getCommandOpcode(byte [] message)
    {
        return OPCODE;
    }

    public byte [] packageCommand(byte [] command)
    {
        byte [] packaged = new byte [COMMAND_HEADER_SIZE + command.length];

        packaged[0] = COMMAND_HEADER;
        System.arraycopy(command, 0, packaged, 1, command.length);

        return packaged;
    }

    public byte [] getANTMessage(byte [] packet) throws InvalidAntPacketException
    {
        if (packet.length <= EVENT_HEADER_SIZE)
        {
            throw new InvalidAntPacketException("Not an ANT packet: Invalid packet length");
        }
        if (packet[0] != EVENT_CODE)
        {
            throw new InvalidAntPacketException("Not an ANT packet: Invalid vendor specific event opcode");
        }

        byte [] antMessage = new byte[packet.length - EVENT_HEADER_SIZE];
        System.arraycopy(packet, EVENT_HEADER_SIZE, antMessage, 0, antMessage.length);
        return antMessage;
    }
}
