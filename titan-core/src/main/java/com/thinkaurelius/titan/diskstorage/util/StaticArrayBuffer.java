package com.thinkaurelius.titan.diskstorage.util;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.diskstorage.ReadBuffer;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;

import java.nio.ByteBuffer;

/**
 * Implementation of {@link StaticBuffer} against byte array.
 *
 * The byte to primitive conversion code was copied from / is inspired by Kryo's Input class:
 * {@linktourl https://code.google.com/p/kryo/source/browse/trunk/src/com/esotericsoftware/kryo/io/Input.java}
 *
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class StaticArrayBuffer implements StaticBuffer {

    private final byte[] array;
    private final int offset;
    private final int limit;

    public StaticArrayBuffer(byte[] array, int offset, int limit) {
        Preconditions.checkNotNull(array);
        // offset == limit implies a zero-length array
        Preconditions.checkArgument(offset>=0 && offset<=limit);
        Preconditions.checkArgument(limit<=array.length);
        this.array = array;
        this.offset = offset;
        this.limit = limit;
    }

    public StaticArrayBuffer(byte[] array, int limit) {
        this(array,0,limit);
    }

    public StaticArrayBuffer(byte[] array) {
        this(array,0,array.length);
    }

    public StaticArrayBuffer(StaticArrayBuffer buffer) {
        this(buffer.array,buffer.offset,buffer.limit);
    }


    private int require(int position, int size) {
        int base = position+offset;
        Preconditions.checkArgument(base+size<=limit,"BufferOverflow: Position %s - Limit %s",base+size,limit);
        return base;
    }

    private final byte getByteDirect(int position) {
        return array[position];
    }

    @Override
    public int length() {
        return limit-offset;
    }

    @Override
    public StaticBuffer subrange(int position, int length) {
        Preconditions.checkArgument(position>=0);
        Preconditions.checkArgument(length>=0);
        Preconditions.checkArgument(offset+position+length<=limit);
        return new StaticArrayBuffer(array,offset+position,offset+position+length);
    }

    @Override
    public ReadBuffer asReadBuffer() {
        return new ReadArrayBuffer(this);
    }

    @Override
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(array,offset,limit-offset);
    }

    @Override
    public<T> T as(Factory<T> factory) {
        return factory.get(array,offset,limit);
    }

    /*
    ############## IDENTICAL CODE ################
     */

    @Override
    public byte getByte(int position) {
        return getByteDirect(require(position,1));
    }

    @Override
    public short getShort(int position) {
        int base = require(position,2);
        return (short)(((getByteDirect(base++) & 0xFF) << 8) | (getByteDirect(base++) & 0xFF));
    }

    @Override
    public int getInt(int position) {
        int base = require(position,4);
        return (getByteDirect(base) & 0xFF) << 24 //
                | (getByteDirect(base + 1) & 0xFF) << 16 //
                | (getByteDirect(base + 2) & 0xFF) << 8 //
                | getByteDirect(base + 3) & 0xFF;
    }

    @Override
    public long getLong(int position) {
        int base = require(position,8);
        return (long)getByteDirect(base++) << 56 //
                | (long)(getByteDirect(base++) & 0xFF) << 48 //
                | (long)(getByteDirect(base++) & 0xFF) << 40 //
                | (long)(getByteDirect(base++) & 0xFF) << 32 //
                | (long)(getByteDirect(base++) & 0xFF) << 24 //
                | (getByteDirect(base++) & 0xFF) << 16 //
                | (getByteDirect(base++) & 0xFF) << 8 //
                | getByteDirect(base++) & 0xFF;
    }

    @Override
    public char getChar(int position) {
        int base = require(position,2);
        return (char)(((getByteDirect(base++) & 0xFF) << 8) | (getByteDirect(base++) & 0xFF));
    }

    @Override
    public float getFloat(int position) {
        return Float.intBitsToFloat(getInt(position));
    }

    @Override
    public double getDouble(int position) {
        return Double.longBitsToDouble(getLong(position));
    }

    /*
    ############## IDENTICAL CODE ################
     */

    @Override
    public boolean equals(Object o) {
        if (this==o) return true;
        else if (o==null) return false;
        else if (!(o instanceof StaticBuffer)) return false;
        return ByteBufferUtil.equals(this,(StaticBuffer)o);
    }

    @Override
    public int hashCode() {
        return ByteBufferUtil.hashcode(this);
    }

    @Override
    public String toString() {
        return ByteBufferUtil.toString(this,"-");
    }

    @Override
    public int compareTo(StaticBuffer other) {
        return ByteBufferUtil.compare(this,other);
    }

}
