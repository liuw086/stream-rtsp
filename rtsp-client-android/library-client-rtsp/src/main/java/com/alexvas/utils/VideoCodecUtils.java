package com.alexvas.utils;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class VideoCodecUtils {

    private static final String TAG = VideoCodecUtils.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final byte NAL_SLICE           = 1;
    public static final byte NAL_DPA             = 2;
    public static final byte NAL_DPB             = 3;
    public static final byte NAL_DPC             = 4;
    public static final byte NAL_IDR_SLICE       = 5;
    public static final byte NAL_SEI             = 6;
    public static final byte NAL_SPS             = 7;
    public static final byte NAL_PPS             = 8;
    public static final byte NAL_AUD             = 9;
    public static final byte NAL_END_SEQUENCE    = 10;
    public static final byte NAL_END_STREAM      = 11;
    public static final byte NAL_FILLER_DATA     = 12;
    public static final byte NAL_SPS_EXT         = 13;
    public static final byte NAL_AUXILIARY_SLICE = 19;
    public static final byte NAL_STAP_A          = 24; // https://tools.ietf.org/html/rfc3984 5.7.1
    public static final byte NAL_STAP_B          = 25; // 5.7.1
    public static final byte NAL_MTAP16          = 26; // 5.7.2
    public static final byte NAL_MTAP24          = 27; // 5.7.2
    public static final byte NAL_FU_A            = 28; // 5.8 fragmented unit
    public static final byte NAL_FU_B            = 29; // 5.8
//  public static final int NAL_FF_IGNORE       = 0xff0f001;

    // Table 7-3: NAL unit type codes
    public static final byte H265_NAL_TRAIL_N    = 0;
    public static final byte H265_NAL_TRAIL_R    = 1;
    public static final byte H265_NAL_TSA_N      = 2;
    public static final byte H265_NAL_TSA_R      = 3;
    public static final byte H265_NAL_STSA_N     = 4;
    public static final byte H265_NAL_STSA_R     = 5;
    public static final byte H265_NAL_RADL_N     = 6;
    public static final byte H265_NAL_RADL_R     = 7;
    public static final byte H265_NAL_RASL_N     = 8;
    public static final byte H265_NAL_RASL_R     = 9;
    public static final byte H265_NAL_BLA_W_LP   = 16;
    public static final byte H265_NAL_BLA_W_RADL = 17;
    public static final byte H265_NAL_BLA_N_LP   = 18;
    public static final byte H265_NAL_IDR_W_RADL = 19;
    public static final byte H265_NAL_IDR_N_LP   = 20;
    public static final byte H265_NAL_CRA_NUT    = 21;
    public static final byte H265_NAL_VPS        = 32;
    public static final byte H265_NAL_SPS        = 33;
    public static final byte H265_NAL_PPS        = 34;
    public static final byte H265_NAL_AUD        = 35;
    public static final byte H265_NAL_EOS_NUT    = 36;
    public static final byte H265_NAL_EOB_NUT    = 37;
    public static final byte H265_NAL_FD_NUT     = 38;
    public static final byte H265_NAL_SEI_PREFIX = 39;
    public static final byte H265_NAL_SEI_SUFFIX = 40;

    private static final byte[] NAL_PREFIX1 = { 0x00, 0x00, 0x00, 0x01 };
    private static final byte[] NAL_PREFIX2 = { 0x00, 0x00, 0x01 };

    public static boolean isValidH264NalUnit(@Nullable byte[] data, int offset, int length) {
        boolean ret = false;

        if (data == null || length <= NAL_PREFIX1.length)
            return false;

        if (data[offset] == 0) {
            // Check first 4 bytes maximum
            for (int cpos = 1; cpos < NAL_PREFIX1.length; cpos++) {
                if (data[cpos + offset] != 0) {
                    ret = data[cpos + offset] == 1;
                    break;
                }
            }
        }
        return ret;
    }

    public static byte getH264NalUnitType(@Nullable byte[] data, int offset, int length) {
        if (data == null || length <= NAL_PREFIX1.length)
            return (byte)-1;

        int nalUnitTypeOctetOffset = -1;
        if (data[offset + NAL_PREFIX2.length - 1] == 1)
            nalUnitTypeOctetOffset = offset + NAL_PREFIX2.length - 1;
        else if (data[offset + NAL_PREFIX1.length - 1] == 1)
            nalUnitTypeOctetOffset = offset + NAL_PREFIX1.length - 1;

        if (nalUnitTypeOctetOffset != -1) {
            byte nalUnitTypeOctet = data[nalUnitTypeOctetOffset + 1];
            return (byte) (nalUnitTypeOctet & 0x1f);
        } else {
            return (byte)-1;
        }
    }

    /**
     * Search for 00 00 01 or 00 00 00 01 in byte stream.
     * @return offset to the start of NAL unit if found, otherwise -1
     */
    public static int searchForH264NalUnitStart(
            @NonNull byte[] data,
            int offset,
            int length,
            @NonNull AtomicInteger prefixSize) {
        if (offset >= data.length - 3)
            return -1;
        for (int pos = 0; pos < length; pos++) {
            int prefix = getNalUnitStartCodePrefixSize(data, pos + offset, length);
            if (prefix >= 0) {
                prefixSize.set(prefix);
                return pos + offset;
            }
        }
        return -1;
    }

    public static class NalUnit {
        public final byte type;
        public final int offset;
        public final int length;
        private NalUnit(byte type, int offset, int length) {
            this.type = type;
            this.offset = offset;
            this.length = length;
        }
    }

   public static int getH264NalUnitsNumber(
           @NonNull byte[] data,
           int    dataOffset,
           int    length) {
       return getH264NalUnits(data, dataOffset, length, new ArrayList<NalUnit>());
   }

    public static int getH264NalUnits(
            @NonNull byte[] data,
            int    dataOffset,
            int    length,
            @NonNull ArrayList<NalUnit> foundNals) {

        foundNals.clear();

        int nalUnits = 0;
        int nextNalOffset = 0;
        AtomicInteger nalUnitPrefixSize = new AtomicInteger(-1);
        long timestamp = System.currentTimeMillis();

        int offset = dataOffset;
        boolean stopped = false;
        while (!stopped) {

            // Search for first NAL unit
            int nalUnitIndex = searchForH264NalUnitStart(
                    data,
                    offset + nextNalOffset,
                    length - nextNalOffset,
                    nalUnitPrefixSize);

            // NAL unit found
            if (nalUnitIndex >= 0) {
                nalUnits++;

                int nalUnitOffset = offset + nextNalOffset + nalUnitPrefixSize.get();
                byte nalUnitTypeOctet = data[nalUnitOffset];
                byte nalUnitType = (byte)(nalUnitTypeOctet & 0x1f);

                // Search for second NAL unit (optional)
                int nextNalUnitStartIndex = searchForH264NalUnitStart(
                        data,
                        nalUnitOffset,
                        length - nalUnitOffset,
                        nalUnitPrefixSize);

                // Second NAL unit not found. Use till the end.
                if (nextNalUnitStartIndex < 0) {
                    // Not found next NAL unit. Use till the end.
//                  nextNalUnitStartIndex = length - nextNalOffset + dataOffset;
                    nextNalUnitStartIndex = length + dataOffset;
                    stopped = true;
                }

                int l = nextNalUnitStartIndex - offset;
                if (DEBUG)
                    Log.d(TAG, "NAL unit type: " + getH264NalUnitTypeString(nalUnitType) +
                            " (" + nalUnitType + ") - " + l + " bytes, offset " + offset);
                foundNals.add(new NalUnit(nalUnitType, offset, l));
                offset = nextNalUnitStartIndex;

                // Check that we are not too long here
                if (System.currentTimeMillis() - timestamp > 100) {
                    Log.w(TAG, "Cannot process data within 100 msec in " + length + " bytes");
                    break;
                }
            } else {
                stopped = true;
            }
        }
        return nalUnits;
    }

   // TODO: Code has a BUG! Sometimes it goes to infinite loop!
   public static int searchForH264NalUnitByType(
           @NonNull byte[] data,
           int    offset,
           int    length,
           int    byUnitType) {

       AtomicInteger nalUnitPrefixSize = new AtomicInteger(-1);
       long timestamp = System.currentTimeMillis();

       while (true) {
           int nalUnitIndex = searchForH264NalUnitStart(data, offset, length, nalUnitPrefixSize);
           if (nalUnitIndex >= 0) {
               int nalUnitOffset = nalUnitIndex + nalUnitPrefixSize.get();
               byte nalUnitTypeOctet = data[nalUnitOffset];
               byte nalUnitType = (byte)(nalUnitTypeOctet & 0x1f);
               if (nalUnitType == byUnitType) {
                   return nalUnitIndex;
               }
               offset = nalUnitOffset;

               // Check that we are not too long here
               if (System.currentTimeMillis() - timestamp > 100) {
                   Log.w(TAG, "Cannot process data within 100 msec in " + length + " bytes");
                   break;
               }
           } else {
               break;
           }
       }
       return -1;
   }

    @NonNull
    public static String getH264NalUnitTypeString(byte nalUnitType) {
        switch (nalUnitType) {
            case NAL_SLICE:           return "NAL_SLICE";
            case NAL_DPA:             return "NAL_DPA";
            case NAL_DPB:             return "NAL_DPB";
            case NAL_DPC:             return "NAL_DPC";
            case NAL_IDR_SLICE:       return "NAL_IDR_SLICE";
            case NAL_SEI:             return "NAL_SEI";
            case NAL_SPS:             return "NAL_SPS";
            case NAL_PPS:             return "NAL_PPS";
            case NAL_AUD:             return "NAL_AUD";
            case NAL_END_SEQUENCE:    return "NAL_END_SEQUENCE";
            case NAL_END_STREAM:      return "NAL_END_STREAM";
            case NAL_FILLER_DATA:     return "NAL_FILLER_DATA";
            case NAL_SPS_EXT:         return "NAL_SPS_EXT";
            case NAL_AUXILIARY_SLICE: return "NAL_AUXILIARY_SLICE";
            case NAL_STAP_A:          return "NAL_STAP_A";
            case NAL_STAP_B:          return "NAL_STAP_B";
            case NAL_MTAP16:          return "NAL_MTAP16";
            case NAL_MTAP24:          return "NAL_MTAP24";
            case NAL_FU_A:            return "NAL_FU_A";
            case NAL_FU_B:            return "NAL_FU_B";
            default:                  return "unknown - " + nalUnitType;
        }
    }

//    @NonNull
//    public static String getH265NalUnitTypeString(byte nalUnitType) {
//        switch (nalUnitType) {
//            case H265_NAL_TRAIL_N:    return "NAL_TRAIL_N";
//            case H265_NAL_TRAIL_R:    return "NAL_TRAIL_R";
//            case H265_NAL_TSA_N:      return "NAL_TSA_N";
//            case H265_NAL_TSA_R:      return "NAL_TSA_R";
//            case H265_NAL_STSA_N:     return "NAL_STSA_N";
//            case H265_NAL_STSA_R:     return "NAL_STSA_R";
//            case H265_NAL_RADL_N:     return "NAL_RADL_N";
//            case H265_NAL_RADL_R:     return "NAL_RADL_R";
//            case H265_NAL_RASL_N:     return "NAL_RASL_N";
//            case H265_NAL_RASL_R:     return "NAL_RASL_R";
//            case H265_NAL_BLA_W_LP:   return "NAL_BLA_W_LP";
//            case H265_NAL_BLA_W_RADL: return "NAL_BLA_W_RADL";
//            case H265_NAL_BLA_N_LP:   return "NAL_BLA_N_LP";
//            case H265_NAL_IDR_W_RADL: return "NAL_IDR_W_RADL";
//            case H265_NAL_IDR_N_LP:   return "NAL_IDR_N_LP";
//            case H265_NAL_CRA_NUT:    return "NAL_CRA_NUT";
//            case H265_NAL_VPS:        return "NAL_VPS";
//            case H265_NAL_SPS:        return "NAL_SPS";
//            case H265_NAL_PPS:        return "NAL_PPS";
//            case H265_NAL_AUD:        return "NAL_AUD";
//            case H265_NAL_EOS_NUT:    return "NAL_EOS_NUT";
//            case H265_NAL_EOB_NUT:    return "NAL_EOB_NUT";
//            case H265_NAL_FD_NUT:     return "NAL_FD_NUT";
//            case H265_NAL_SEI_PREFIX: return "NAL_SEI_PREFIX";
//            case H265_NAL_SEI_SUFFIX: return "NAL_SEI_SUFFIX";
//            default:                  return "unknown - " + nalUnitType;
//        }
//    }
    private static int getNalUnitStartCodePrefixSize(@NonNull byte[] data, int offset, int length) {
        if (length < 4)
            return -1;

        if (ByteUtils.memcmp(data, offset, NAL_PREFIX1, 0, NAL_PREFIX1.length))
            return NAL_PREFIX1.length;
        else if (ByteUtils.memcmp(data, offset, NAL_PREFIX2, 0, NAL_PREFIX2.length))
            return NAL_PREFIX2.length;
        else
            return -1;
    }

}
