package com.mux.video.upload.internal

import java.nio.ByteBuffer

class Resample {
    val MAX_HWORD:Int = 32767
    val MIN_HWORD:Int = -32768
    val FP_DIGITS:Int = 15
    val FP_FACTOR:Int = 1 shl FP_DIGITS
    val FP_MASK:Int = FP_FACTOR - 1

    private var factor:Double = 1.0

    fun create(inputRate:Int, outputRate:Int, bufferSize:Int, channels:Int) {

    }

    fun getFactor():Double {
        return factor;
    }

    fun resample(inputBuffer:ByteBuffer, outputBuffer:ByteBuffer, byteLen:Int): Int {
        return -1;
    }

    fun resampleEx(inputBuffer:ByteBuffer, outputBuffer:ByteBuffer, byteLen:Int): Int {
        return -1;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //////////// Helpers ///////////////////////////////////////////////////////////////////////////

    private fun WordToHword(v1:Int, scl:Int):Short {
        var v = v1
        val out: Short
        val llsb = 1 shl scl - 1
        v += llsb /* round */
        v = v shr scl
        if (v > MAX_HWORD) {
            v = MIN_HWORD
        }
        out = v.toShort()
        return out
    }

    private fun SrcLinear(X:ShortArray, Y:ShortArray, fct:Double, time:UInt, Nx:UShort ):Int {
        var factor:Double = 1.0 / fct
        var iconst:Short
        var Xp:Int = 0
        var Ystart:Int = 0
        var v:Int; var X1:Int; var X2:Int
        var dt:UInt; var endTime:UInt;
        dt = (factor * FP_FACTOR + 0.5).toUInt()
        endTime = time + (FP_FACTOR * Nx.toInt()).toUInt()
        while(time < endTime) {
            iconst = (*Time) & FP_MASK;	/* mask off lower 16 bits of time */
            Xp = &X[(*Time) >> FP_DIGITS];	/* Ptr to current input sample is top 16 bits */
            x1 = *Xp++;
            x2 = *Xp;
            x1 *= FP_FACTOR - iconst;
            x2 *= iconst;
            v = x1 + x2;
            *Y++ = WordToHword(v, FP_DIGITS);	/* Deposit output */
            *Time += dt;
        }
        return (Y - Ystart);
    }
}