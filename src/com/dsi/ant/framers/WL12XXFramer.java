package com.dsi.ant.framers;

import java.lang.System;

public class WL12XXFramer implements IAntHciFramer
{
    private static final short OPCODE = (short) 0xFDD1;
    private static final short EVENT_CODE = (short) 0x0500;
    private static final int COMMAND_HEADER_SIZE = 2;
    private static final int EVENT_HEADER_SIZE = 4;

    public short getCommandOpcode(byte [] message)
    {
        return OPCODE;
    }

    public byte [] packageCommand(byte [] command)
    {
        byte [] packaged = new byte [COMMAND_HEADER_SIZE + command.length];

        packaged[0]  = (byte)(command.length);
        packaged[1]  = (byte)(command.length >> 8);
        System.arraycopy(command, 0, packaged, 2, command.length);

        return packaged;
    }

    public byte [] getANTMessage(byte [] packet) throws InvalidAntPacketException
    {
        if (packet.length < EVENT_HEADER_SIZE)
        {
            throw new InvalidAntPacketException("Not an ANT packet: Invalid packet length");
        }

        short opcode = (short)((packet[0] << 8) | (packet[1] & 0x00FF));

        if (opcode != EVENT_CODE)
        {
            throw new InvalidAntPacketException("Not an ANT packet: Invalid vendor specific event opcode");
        }

        short messageLength = (short)((packet[2] << 8) | ( packet[3] & 0x00FF));

        if(packet.length < EVENT_HEADER_SIZE + messageLength)
        {
            throw new InvalidAntPacketException("Not an ANT packet: Invalid packet length");
        }

        byte [] antMessage = new byte [packet.length - EVENT_HEADER_SIZE];
        System.arraycopy(packet, EVENT_HEADER_SIZE, antMessage, 0, antMessage.length);
        return antMessage;
    }
}
