package com.dsi.ant.framers;

public interface IAntHciFramer
{
    short getCommandOpcode(byte [] message);
    byte [] packageCommand(byte [] message);
    byte [] getANTMessage(byte [] packet) throws InvalidAntPacketException;

    public class InvalidAntPacketException extends Exception
    {
        private static final long serialVersionUID = 2707955151464940223L;

        public InvalidAntPacketException () {}

        public InvalidAntPacketException(String message)
        {
            super(message);
        }
    }
}

