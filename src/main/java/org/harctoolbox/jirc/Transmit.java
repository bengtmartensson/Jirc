/*      $Id: transmit.c,v 5.31 2010/04/02 10:26:57 lirc Exp $      */
/**
 * **************************************************************************
 ** transmit.c **************************************************************
 ****************************************************************************
 *
 * functions that prepare IR codes for transmitting
 *
 * Copyright (C) 1999-2004 Christoph Bartelmus <lirc@bartelmus.de>
 *
 */
package org.harctoolbox.jirc;

import java.util.List;
import org.harctoolbox.ircore.ThisCannotHappenException;

/**
 * This class is a translation of transmit.c from Lirc 0.9.0, and renders the
 * signal. A Transmit is first constructed, then, getValid() and getData()
 * deliver the rendered signal as a sequence of durations.
 *
 */
final class Transmit {
    private static final int WBUF_SIZE = 1024;// was 256;
    private static final int LOG_ERR = 1;
    private static final int LOG_WARNING = 2;

    /**
     * if the gap is lower than this value, we will concatenate the signals and
     * send the signal chain at a single blow
     */
    private static final int LIRCD_EXACT_GAP_THRESHOLD = 10000;
    private static int debug = 0;

    public static void setDebug(int newDebug) {
        debug = newDebug;
    }

    private final IrRemote remote;
    private boolean valid = false;
    private final Sbuf send_buffer = new Sbuf();
    private final IrNCode code;

    Transmit(IrRemote remote, IrNCode code, boolean repeat) {
        this.remote = remote;
        this.code = code;
        IrRemote.setRepeat_remote(null);
        valid = init_send();
        if (repeat) {
            remote.repeat_countdown = 1;
            IrRemote.setRepeat_remote(remote);
            init_send();
        }
    }

    /**
     *
     * @return true if the rendering produced valid result.
     */
    public boolean getValid() {
        return valid;
    }

    /**
     * Returns the rendered durations as an array of ints. Guaranteed to end
     * with a gap, if not already present, add it of size from the argument.
     *
     * @param gap Ending duration to add, if necessary.
     * @see #getData()
     *
     * @return array of durations.
     */
    public int[] getData(int gap) {
        return valid ? send_buffer.getData(gap) : null;
    }

    /**
     * Returns the rendered durations as an array of ints. Due to Lirc's
     * idiosyncrasies, it may be of odd size, i.e. ending with a mark.
     *
     * @see #getData(int gap)
     *
     * @return array of durations.
     */
    public int[] getData() {
        return valid ? send_buffer.getData() : null;
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    private void logprintf(int level, String format, Object... args) {
        System.err.println(String.format(remote.getSource() + " (" + remote.getName() + "): " + format, args));
    }

    @SuppressWarnings("UseOfSystemOutOrSystemErr")
    private void LOGPRINTF(int level, String format, Object... args) {
        if (debug > 0)
            System.err.println(String.format(remote.getName() + ": " + format, args));
    }

    private void clear_send_buffer() {
        LOGPRINTF(3, "clearing transmit buffer");
        send_buffer.wptr = 0;
        send_buffer.too_long = false;
        send_buffer.is_biphase = false;
        send_buffer.pendingp = 0;
        send_buffer.pendings = 0;
        send_buffer.sum = 0;
    }

    private void add_send_buffer(int data) {
        if (send_buffer.wptr < WBUF_SIZE) {
            LOGPRINTF(3, "adding to transmit buffer: %d", data);
            send_buffer.sum += data;
            send_buffer._data[send_buffer.wptr] = data;
            send_buffer.wptr++;
        } else {
            send_buffer.too_long = true;
        }
    }

    private void send_pulse(int data) {
        if (send_buffer.pendingp > 0) {
            send_buffer.pendingp += data;
        } else {
            if (send_buffer.pendings > 0) {
                add_send_buffer(send_buffer.pendings);
                send_buffer.pendings = 0;
            }
            send_buffer.pendingp = data;
        }
    }

    private void send_space(int data) {
        if (send_buffer.wptr == 0 && send_buffer.pendingp == 0) {
            LOGPRINTF(1, "first signal is a space!");
            return;
        }
        if (send_buffer.pendings > 0) {
            send_buffer.pendings += data;
        } else {
            if (send_buffer.pendingp > 0) {
                add_send_buffer(send_buffer.pendingp);
                send_buffer.pendingp = 0;
            }
            send_buffer.pendings = data;
        }
    }

    private boolean bad_send_buffer() {
        return send_buffer.too_long || (send_buffer.wptr == WBUF_SIZE && send_buffer.pendingp > 0);
    }

    private boolean check_send_buffer() {
        int i;

        if (send_buffer.wptr == 0) {
            LOGPRINTF(1, "nothing to send");
            return false;
        }
        for (i = 0; i < send_buffer.wptr; i++) {
            if (send_buffer.data[i] == 0) {
                if (i % 2 != 0) {
                    LOGPRINTF(1, "invalid space: %d", i);
                } else {
                    LOGPRINTF(1, "invalid pulse: %d", i);
                }
                return false;
            }
        }

        return true;
    }

    private void flush_send_buffer() {
        if (send_buffer.pendingp > 0) {
            add_send_buffer(send_buffer.pendingp);
            send_buffer.pendingp = 0;
        }
        if (send_buffer.pendings > 0) {
            add_send_buffer(send_buffer.pendings);
            send_buffer.pendings = 0;
        }
    }

    private void sync_send_buffer() {
        if (send_buffer.pendingp > 0) {
            add_send_buffer(send_buffer.pendingp);
            send_buffer.pendingp = 0;
        }
        if (send_buffer.wptr > 0 && send_buffer.wptr % 2 == 0)
            send_buffer.wptr--;
    }

    private void send_header() {
        if (remote.has_header()) {
            send_pulse(remote.phead);
            send_space(remote.shead);
        }
    }

    private void send_foot() {
        if (remote.has_foot()) {
            send_space(remote.sfoot);
            send_pulse(remote.pfoot);
        }
    }

    private void send_lead() {
        if (remote.plead != 0) {
            send_pulse(remote.plead);
        }
    }

    private void send_trail() {
        if (remote.ptrail != 0) {
            send_pulse(remote.ptrail);
        }
    }

    @SuppressWarnings("AssignmentToMethodParameter")
    private void send_data(long data, int bits, int done) {
        int i;
        int all_bits = remote.bit_count();
        int toggle_bit_mask_bits = IrRemote.bits_set(remote.toggle_bit_mask);
        long mask;

        data = IrRemote.reverse(data, bits);
        if (remote.is_rcmm()) {
            mask = 1 << (all_bits - 1 - done);
            if (bits % 2 != 0 || done % 2 != 0) {
                logprintf(LOG_ERR, "invalid bit number.");
                return;
            }
            for (i = 0; i < bits; i += 2, mask >>= 2) {
                switch ((int) (data & 3L)) {
                    case 0:
                        send_pulse(remote.pzero);
                        send_space(remote.szero);
                        break;
                    //* 2 and 1 swapped due to reverse() */
                    case 2:
                        send_pulse(remote.pone);
                        send_space(remote.sone);
                        break;
                    case 1:
                        send_pulse(remote.ptwo);
                        send_space(remote.stwo);
                        break;
                    case 3:
                        send_pulse(remote.pthree);
                        send_space(remote.sthree);
                        break;
                    default:
                        throw new ThisCannotHappenException();
                }
                data >>= 2;
            }
            return;
        } else if (remote.is_xmp()) {
            if (bits % 4 != 0 || done % 4 != 0) {
                logprintf(LOG_ERR, "invalid bit number.");
                return;
            }
            for (i = 0; i < bits; i += 4) {
                long nibble;

                nibble = IrRemote.reverse(data & 0xf, 4);
                send_pulse(remote.pzero);
                send_space((int) (remote.szero + nibble * remote.sone));
                data >>= 4;
            }
            return;
        }

        mask = 1L << (all_bits - 1 - done);
        for (i = 0; i < bits; i++, mask >>= 1) {
            if (remote.has_toggle_bit_mask() && (mask & remote.toggle_bit_mask) != 0) {
                if (toggle_bit_mask_bits == 1) {
                    //* backwards compatibility */
                    data &= ~1L;
                    if ((remote.toggle_bit_mask_state & mask) != 0) {
                        data |= 1L;
                    }
                } else if ((remote.toggle_bit_mask_state & mask) != 0) {
                    data ^= 1L;
                }
            }
            if (remote.has_toggle_mask() && (mask & remote.toggle_mask) != 0 && (remote.toggle_mask_state % 2) != 0) {
                data ^= 1;
            }
            if ((data & 1) != 0) {
                if (remote.is_biphase()) {

                    if ((mask & remote.rc6_mask) != 0) {
                        send_space(2 * remote.sone);
                        send_pulse(2 * remote.pone);
                    } else {
                        send_space(remote.sone);
                        send_pulse(remote.pone);
                    }
                } else if (remote.is_space_first()) {
                    send_space(remote.sone);
                    send_pulse(remote.pone);
                } else {
                    send_pulse(remote.pone);
                    send_space(remote.sone);
                }
            } else if ((mask & remote.rc6_mask) != 0) {
                send_pulse(2 * remote.pzero);
                send_space(2 * remote.szero);
            } else if (remote.is_space_first()) {
                send_space(remote.szero);
                send_pulse(remote.pzero);
            } else {
                send_pulse(remote.pzero);
                send_space(remote.szero);
            }
            data >>= 1;
        }
    }

    private void send_pre() {
        if (remote.has_pre()) {
            send_data(remote.pre_data, remote.pre_data_bits, 0);
            if (remote.pre_p > 0 && remote.pre_s > 0) {
                send_pulse(remote.pre_p);
                send_space(remote.pre_s);
            }
        }
    }

    private void send_post() {
        if (remote.has_post()) {
            if (remote.post_p > 0 && remote.post_s > 0) {
                send_pulse(remote.post_p);
                send_space(remote.post_s);
            }
            send_data(remote.post_data, remote.post_data_bits, remote.pre_data_bits + remote.bits);
        }
    }

    private void send_repeat() {
        send_lead();
        send_pulse(remote.prepeat);
        send_space(remote.srepeat);
        send_trail();
    }

    private void send_code(long code, boolean isRepeat) {
        if (!isRepeat || (remote.flags & IrRemote.NO_HEAD_REP) == 0)
            send_header();
        send_lead();
        send_pre();
        send_data(code, remote.bits, remote.pre_data_bits);
        send_post();
        send_trail();
        if (!isRepeat || (remote.flags & IrRemote.NO_FOOT_REP) == 0)
            send_foot();

        if (!isRepeat
                && ((remote.flags & IrRemote.NO_HEAD_REP) != 0)
                && ((remote.flags & IrRemote.CONST_LENGTH) != 0)) {
            send_buffer.sum -= remote.phead + remote.shead;
        }
    }

    private void send_signals(int[] signals, int n) {
        int i;

        for (i = 0; i < n; i++) {
            add_send_buffer(signals[i]);
        }
    }

    private void send_signals(List<Integer> signals) {
        signals.forEach((s) -> {
            add_send_buffer(s);
        });
    }

    private boolean init_send() {
        return init_send_or_sim(0);
    }

    private boolean init_send_or_sim(int repeat_preset) {
        int repeat = repeat_preset;

        if (remote.is_grundig() || remote.is_goldstar() || remote.is_bo()) {
            logprintf(LOG_ERR, "sorry, can't send this protocol yet");
            return false;
        }
        clear_send_buffer();
        send_buffer.is_biphase = remote.is_biphase();

        if (IrRemote.getRepeat_remote() == null) {
            remote.repeat_countdown = remote.min_repeat;
        } else {
            repeat = 1;
        }
        boolean success = send_loop(repeat);
        if (success) {
            LOGPRINTF(3, "transmit buffer ready");
            return final_check();
        } else
            return false;
    }

    @SuppressWarnings("AssignmentToMethodParameter")
    private boolean send_loop(int repeat) {
        while (true) {
            if (repeat > 0 && remote.has_repeat()) {
                if ((remote.flags & IrRemote.REPEAT_HEADER) != 0 && remote.has_header()) {
                    send_header();
                }
                send_repeat();
            } else if (!remote.is_raw()) {
                long next_code;

                if (code.getTransmit_state() == null) {
                    next_code = code.getCode();
                } else {
                    next_code = code.getTransmit_state().getCode();
                }
                send_code(next_code, repeat != 0);
                if (remote.has_toggle_mask()) {
                    remote.toggle_mask_state++;
                    if (remote.toggle_mask_state == 4) {
                        remote.toggle_mask_state = 2;
                    }
                }
                send_buffer.data = send_buffer._data;
            } else {
                // is_raw
                if (code.getSignals() == null) {
                    logprintf(LOG_ERR, "no signals for raw send");
                    return false;
                }
                if (send_buffer.wptr > 0) {
                    send_signals(code.getSignals());
                } else {
                    send_buffer.data = new int[code.getSignals().size()];
                    //code.signals.toArray(new int[0]);
                    send_buffer.wptr = code.getSignals().size();//.length;
                    for (int i = 0; i < code.getSignals().size(); i++) {
                        send_buffer.data[i] = code.getSignals().get(i);
                        send_buffer.sum += code.getSignals().get(i);
                    }
                }
            }
            sync_send_buffer();
            if (bad_send_buffer()) {
                logprintf(LOG_ERR, "buffer too small");
                return false;
            }

            if (remote.has_repeat_gap() && repeat != 0 && remote.has_repeat()) {
                remote.min_remaining_gap = remote.repeat_gap;
                remote.max_remaining_gap = remote.repeat_gap;
            } else if (remote.is_const()) {
                if (remote.min_gap() > send_buffer.sum) {
                    remote.min_remaining_gap = remote.min_gap() - send_buffer.sum;
                    remote.max_remaining_gap = remote.max_gap() - send_buffer.sum;
                } else {
                    logprintf(LOG_ERR, "too short gap: %d", remote.gap);
                    remote.min_remaining_gap = remote.min_gap();
                    remote.max_remaining_gap = remote.max_gap();
                    return false;
                }
            } else {
                remote.min_remaining_gap = remote.min_gap();
                remote.max_remaining_gap = remote.max_gap();
            }
            /* update transmit state */
            if (code.getNext() != null) {
                if (code.getTransmit_state() == null) {
                    code.setTransmit_state(code.getNext());
                } else {
                    code.setTransmit_state(code.getTransmit_state().getNext());
                    if (remote.is_xmp() && code.getTransmit_state() == null) {
                        code.setTransmit_state(code.getNext());
                    }
                }
            }
            if ((remote.repeat_countdown > 0 || code.getTransmit_state() != null)
                    && remote.min_remaining_gap < LIRCD_EXACT_GAP_THRESHOLD) {
                if (send_buffer.data != send_buffer._data) {
                    //lirc_t *signals;
                    int n;
                    int[] signals;

                    LOGPRINTF(1, "unrolling raw signal optimisation");
                    signals = send_buffer.data;
                    n = send_buffer.wptr;
                    send_buffer.data = send_buffer._data;
                    send_buffer.wptr = 0;

                    send_signals(signals, n);
                }
                LOGPRINTF(1, "concatenating low gap signals");
                if (code.getNext() == null || code.getTransmit_state() == null) {
                    remote.repeat_countdown--;
                }
                send_space(remote.min_remaining_gap);
                flush_send_buffer();
                send_buffer.sum = 0;

                repeat = 1;
                //goto init_send_loop;
            } //if (code.getTransmit_state() != null)
            //    ; //goto init_send_loop;
            else
                return true;
        }
    }

    private boolean final_check() {
        if (!check_send_buffer()) {
            logprintf(LOG_ERR, "invalid send buffer");
            logprintf(LOG_ERR, "this remote configuration cannot be used to transmit");
            return false;
        }
        return true;
    }

    private static class Sbuf {
        int[] data; //int *data;
        int[] _data = new int[WBUF_SIZE]; // int _data[WBUF_SIZE];
        int wptr;
        boolean/*int*/ too_long;
        boolean/*int*/ is_biphase;
        int pendingp;
        int pendings;
        int sum;

        public int[] getData(int gap) {
            int[] array = new int[wptr + wptr % 2];
            System.arraycopy(data, 0, array, 0, wptr);
            if ((wptr & 1) == 1)
                array[array.length - 1] = gap;
            return array;
        }

        public int[] getData() {
            int[] array = new int[wptr];
            System.arraycopy(data, 0, array, 0, wptr);
            return array;
        }
    }
}
